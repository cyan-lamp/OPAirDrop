#!/system/bin/sh
# Does the kernel's own IPv6 stack reach the peer over the AWDL bridge?
#
# Every frame we have sent so far was hand-built in userspace and injected through the
# vendor node. That is a lot of code to carry -- an IPv6 header, an ICMPv6 checksum, a
# TCP handshake, an HTTP request -- for something the kernel already does better.
#
# wonder0 is an ethernet-type vif and already owns fe80::f4ef:63ff:feb6:48ba, which is
# exactly the address the phone answered our echo with. So the question is not whether
# the peer will talk to the kernel, it is whether the bridge will carry what the kernel
# emits. If it does, the userspace TCP goes away entirely.
#
# Routing is the part that does not work the obvious way. Android sends every fe80::
# destination to table 1087 (ip -6 rule: "15000: from all to fe80::/64 lookup 1087")
# and never consults main for IPv6 at all, so a route added with plain "ip route" is
# written into a table nothing reads and the ping dies with "Network is unreachable"
# while the link is perfectly alive. The route has to go where the rule is looking.
#
# The peer's MAC is derived from its link-local address the same way in both directions
# (RFC 4291 modified EUI-64), which is also how the neighbour entry is filled in: the
# address fields on received frames are unreliable, but the IPv6 header is not.
DUR=${DUR:-75}
LOG=/data/local/tmp/kernel-ip.log
: > "$LOG"
ip link set wonder0 up 2>/dev/null
ip link set wondertap0 up 2>/dev/null
echo "probe: table 1087 before:"; ip -6 route show table 1087

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local > "$LOG" 2>&1 &
P=$!
echo "probe: awdl-data pid=$P dur=${DUR}s"

last=""
while kill -0 $P 2>/dev/null; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    if [ -n "$mac" ] && [ "$mac" != "$last" ]; then
        m1=${mac%%:*}; r=${mac#*:}
        m2=${r%%:*};  r=${r#*:}
        m3=${r%%:*};  r=${r#*:}
        m4=${r%%:*};  r=${r#*:}
        m5=${r%%:*};  m6=${r#*:}
        b1=$(printf '%02x' $((0x$m1 ^ 2)))
        LL="fe80::${b1}${m2}:${m3}ff:fe${m4}:${m5}${m6}"
        ip -6 neigh replace "$LL" lladdr "$mac" dev wonder0 nud permanent 2>&1
        ip -6 route replace "$LL/128" dev wonder0 table 1087 2>&1
        echo "probe: peer $mac -> $LL"
        echo "probe: neigh  $(ip -6 neigh show dev wonder0 | grep -i "$mac")"
        echo "probe: route  $(ip -6 route show table 1087 | grep -i "$LL")"
        echo "probe: link   $(ip -s link show wonder0 | tr -s ' ' | grep -E 'RX:|TX:' -A1 | tr '\n' ' ')"
        echo "--- ping6 ---"
        ping6 -c 2 -W 2 -I wonder0 "$LL" 2>&1
        echo "--- tcp 8770 ---"
        echo | nc -w 3 "$LL%wonder0" 8770 2>&1 | head -4
        echo "--- tcp 61504 ---"
        echo | nc -w 3 "$LL%wonder0" 61504 2>&1 | head -4
        echo "probe: ---"
        last=$mac
    fi
    sleep 3
done
wait $P
echo "probe: --- awdl-data said ---"
grep -E "replies=|  data |odd data" "$LOG" | tail -12
echo "probe: --- driver saw kernel frames? ---"
dmesg | grep -E "OPAIRSHARE" | sed 's/^\[[^]]*\] //' | sort | uniq -c | sort -rn | head -12
echo "probe: done"
