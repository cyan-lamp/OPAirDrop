#!/system/bin/sh
# Why does ping6 over wonder0 fail with EINVAL when the route is in place?
#
# The route rules out "unreachable": ip -6 rule sends every fe80:: destination to table
# 1087, the /128 is there, and the neighbour is PERMANENT. What is left is the socket
# layer refusing the destination, and the two candidate causes look identical from the
# outside -- a scope id the kernel will not accept, or the netdev not being the kind of
# device IPv6 can build a link header for.
#
# The decisive measurement is not the error text, it is whether a frame reaches the
# bridge. dmesg is cleared between each attempt so a bridge send can only belong to the
# attempt that follows it. Only fc=0088 (QoS data) is counted: fc=00d0 is the action
# traffic the session produces by itself and would swamp the comparison.
DUR=${DUR:-70}
LOG=/data/local/tmp/kernel-ip.log
: > "$LOG"
ip link set wonder0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local > "$LOG" 2>&1 &
P=$!
echo "session pid=$P dur=${DUR}s"

# The peer only appears once it hears us, and its address changes between sessions, so
# the first "following" line is the only trustworthy source for it.
MAC=""
n=0
while [ -z "$MAC" ] && [ $n -lt 40 ]; do
    sleep 1; n=$((n+1))
    MAC=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
done
[ -n "$MAC" ] || { echo "no peer after ${n}s"; kill $P 2>/dev/null; tail -5 "$LOG"; exit 1; }

m1=${MAC%%:*}; r=${MAC#*:}
m2=${r%%:*};  r=${r#*:}
m3=${r%%:*};  r=${r#*:}
m4=${r%%:*};  r=${r#*:}
m5=${r%%:*};  m6=${r#*:}
LL="fe80::$(printf '%02x' $((0x$m1 ^ 2)))${m2}:${m3}ff:fe${m4}:${m5}${m6}"

ip -6 neigh replace "$LL" lladdr "$MAC" dev wonder0 nud permanent
ip -6 route replace "$LL/128" dev wonder0 table 1087
ip -6 route replace ff02::/16 dev wonder0 table 1087
SRC=$(ip -6 -o addr show dev wonder0 | awk '{print $4}' | cut -d/ -f1)

echo "peer $MAC -> $LL   src $SRC"
echo "== device =="
ip -d link show wonder0 | head -2
echo "type=$(cat /sys/class/net/wonder0/type) addr_len=$(cat /sys/class/net/wonder0/addr_len)"
echo "== route get =="
ip -6 route get "$LL" 2>&1

sends() { dmesg 2>/dev/null | grep 'BRIDGESEND' | grep -c 'fc=0088'; }
run() {
    label=$1; shift
    dmesg -C 2>/dev/null
    echo "-- $label --"
    "$@" 2>&1
    sleep 1
    echo "   fc=0088 bridged: $(sends)"
}

run "idle 3s"                   sleep 3
run "ping6 -I wonder0"          ping6 -c 1 -W 2 -I wonder0 "$LL"
run "ping6 <ll>%wonder0"        ping6 -c 1 -W 2 "$LL%wonder0"
run "ping6 -I wonder0 -S src"   ping6 -c 1 -W 2 -I wonder0 -S "$SRC" "$LL"
run "nc 8770"                   nc -w 4 "$LL%wonder0" 8770

echo
echo "== frames the driver bridged during the last attempt =="
dmesg 2>/dev/null | grep 'BRIDGESEND' | sed 's/^\[[^]]*\] //' | sort | uniq -c | sort -rn | head -10
echo "== session tail =="
grep -E "replies=|  data " "$LOG" | tail -6
kill $P 2>/dev/null
echo done
