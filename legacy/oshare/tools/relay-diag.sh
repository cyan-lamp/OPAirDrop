#!/system/bin/sh
# One diagnostic: with the tun configured and the route in table 1087, what does the
# kernel say when we try to reach the peer's link-local through it?
# Separated from relay-test.sh so a single failure mode prints its own error text
# instead of disappearing into a silent rc=1.
DUR=${DUR:-90}
PORT=${PORT:-8770}
UIDN=${UIDN:-10411}
TUN=awdlrt0
LOG=/data/local/tmp/relay-diag.log
: > "$LOG"
ip link set wondertap0 up 2>/dev/null
CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    "$PORT",61504,61505 _airdrop._tcp.local "$TUN" none "$UIDN" >> "$LOG" 2>&1 &
P=$!
i=0; mac=""
while [ $i -lt 300 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    kill -0 $P 2>/dev/null || { echo "died"; tail -8 "$LOG"; exit 1; }
    i=$((i+1)); sleep 0.1
done
[ -n "$mac" ] || { echo "no peer"; kill $P; exit 1; }
grep -a "awdl-data: tun" "$LOG"
m1=${mac%%:*}; r=${mac#*:}
m2=${r%%:*};  r=${r#*:}
m3=${r%%:*};  r=${r#*:}
m4=${r%%:*};  r=${r#*:}
m5=${r%%:*};  m6=${r#*:}
LL="fe80::$(printf '%02x' $((0x$m1 ^ 2)))${m2}:${m3}ff:fe${m4}:${m5}${m6}"
ip -6 neigh replace "$LL" lladdr "$mac" dev $TUN nud permanent 2>&1
ip -6 route replace "$LL/128" dev $TUN table 1087 2>&1
sleep 2
echo "=== addr ==="; ip -6 -o addr show dev $TUN
echo "=== rule fe80 ==="; ip -6 rule show | grep -a 1087
echo "=== table 1087 ==="; ip -6 route show table 1087 | head
echo "=== route get ==="; ip -6 route get "$LL" 2>&1
echo "=== nc verbose (root) ==="
nc -6 -v -w 5 "$LL%$TUN" "$PORT" < /dev/null 2>&1 | head -6
echo "rc=$?"
echo "=== nc as app uid $UIDN ==="
SUID=$(stat -c %u /data/local/tmp/big.bin 2>/dev/null)
echo "shell uid=$(id -u) file uid=$SUID"
kill $P 2>/dev/null
echo done
