#!/system/bin/sh
# The one question that separates the layers: can this data plane put ONE 1400-byte
# packet on the air, with no TCP anywhere in the picture?
#
# Every byte of every measurement so far averaged ~109 B per frame while MSS was 1398.
# That is either the app choosing small payloads, or the framer refusing large ones.
# ICMP6 with an explicit size decides it: the probe sends nothing but what the kernel
# hands the tun, so a 1400-byte echo that gets answered proves the framer accepts
# full-size frames and the small-frame habit is the app's; a silent one that still
# draws a driver completion proves the frame went out but was dropped above the radio.
#
# Driver counters are read per size, because status0 (acked) vs status3 (no ack) is
# the only reading that says whether the radio carried it or the peer ignored it.
DUR=${DUR:-130}
UIDN=${UIDN:-10411}
TUN=awdlrt0
LOG=/data/local/tmp/relay-mtu.log
PARAMS=/sys/module/qca_cld3_peach_v2/parameters
: > "$LOG"
ip link set wondertap0 up 2>/dev/null

counter() { cat $PARAMS/opairshare_tx_metrics 2>/dev/null | tr ' ' '\n' | sed -n "s/^$1=//p" | head -1; }

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local "$TUN" none "$UIDN" >> "$LOG" 2>&1 &
P=$!
i=0; mac=""
while [ $i -lt 300 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    kill -0 $P 2>/dev/null || { echo "relay: died"; tail -8 "$LOG"; exit 1; }
    i=$((i+1)); sleep 0.1
done
[ -n "$mac" ] || { echo "relay: no peer"; kill $P; exit 1; }
grep -a "awdl-data: tun" "$LOG"
m1=${mac%%:*}; r=${mac#*:}
m2=${r%%:*}; r=${r#*:}
m3=${r%%:*}; r=${r#*:}
m4=${r%%:*}; r=${r#*:}
m5=${r%%:*}; m6=${r#*:}
LL="fe80::$(printf '%02x' $((0x$m1 ^ 2)))${m2}:${m3}ff:fe${m4}:${m5}${m6}"
ip -6 neigh replace "$LL" lladdr "$mac" dev $TUN nud permanent 2>&1
ip -6 route replace "$LL/128" dev $TUN table 1087 2>&1
echo "relay: peer $mac -> $LL"
sleep 3

# Ascending, one size at a time, so the first silent rung is the answer.
for SZ in 16 200 600 1000 1400 1450; do
    b0=$(counter status0); b3=$(counter status3)
    out=$(ping6 -6 -I $TUN -c 4 -i 0.4 -W 3 -s $SZ "$LL" 2>&1 | tail -3)
    sleep 1
    a0=$(counter status0); a3=$(counter status3)
    echo "--- size $SZ ---"
    echo "$out" | sed 's/^/    /'
    echo "    driver acked=$((a0-b0)) noack=$((a3-b3))"
done

echo "=== session tail ==="
grep -aE "data=|retx=|frm=" "$LOG" | tail -4
kill $P 2>/dev/null
echo done
