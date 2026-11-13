#!/system/bin/sh
# Relay-mode throughput: the KERNEL owns TCP, this process only moves L2 frames.
# If the kernel's own TCP also crawls, the bottleneck sits below the app's TCP code
# and no amount of app-side retuning can reach the author's 2.57 MB/s.
DUR=${DUR:-120}
PORT=${PORT:-8770}
UIDN=${UIDN:-10411}
TUN=awdlrt0
LOG=/data/local/tmp/relay-test.log
: > "$LOG"
ip link set wondertap0 up 2>/dev/null
CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    "$PORT",61504,61505 _airdrop._tcp.local "$TUN" none "$UIDN" >> "$LOG" 2>&1 &
P=$!
echo "relay: pid=$P dur=${DUR}s"
i=0; mac=""
while [ $i -lt 400 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    if ! kill -0 $P 2>/dev/null; then echo "relay: died"; tail -5 "$LOG"; exit 1; fi
    i=$((i+1)); sleep 0.1
done
[ -n "$mac" ] || { echo "relay: no peer"; kill $P; exit 1; }
echo "relay: peer $mac"
grep -a "awdl-data: tun" "$LOG"
echo "--- tun addr/route ---"
ip -6 addr show dev $TUN 2>&1 | grep -a inet6
ip -6 route show table 1087 2>&1 | head -5
m1=${mac%%:*}; r=${mac#*:}
m2=${r%%:*};  r=${r#*:}
m3=${r%%:*};  r=${r#*:}
m4=${r%%:*};  r=${r#*:}
m5=${r%%:*};  m6=${r#*:}
LL="fe80::$(printf '%02x' $((0x$m1 ^ 2)))${m2}:${m3}ff:fe${m4}:${m5}${m6}"
echo "relay: peer LL=$LL"
ip -6 neigh replace "$LL" lladdr "$mac" dev $TUN nud permanent 2>&1
ip -6 route replace "$LL/128" dev $TUN table 1087 2>&1
ip -6 route show table 1087 2>&1 | head -3
sleep 2
echo "--- 256KB through kernel TCP ---"
head -c 262144 /dev/urandom > /data/local/tmp/big.bin
S=$(date +%s%N)
nc -6 -w 6 "$LL%$TUN" "$PORT" < /data/local/tmp/big.bin > /dev/null 2>&1
RC=$?
E=$(date +%s%N)
echo "relay: rc=$RC elapsed=$(( (E-S)/1000000 ))ms"
echo "--- session counters ---"
grep -aE "data=|retx=" "$LOG" | tail -3
kill $P 2>/dev/null
echo done
