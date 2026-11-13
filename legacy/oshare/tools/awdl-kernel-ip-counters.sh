#!/system/bin/sh
# Did the kernel actually hand a packet to wonder0?
#
# The error text and the bridge log both lie in different ways: sendmsg can fail after
# the frame was queued, and dmesg is rate limited so a count of bridge sends cannot be
# attributed to one command. The interface counters cannot lie about this -- tx_packets
# is incremented by the driver's xmit path and nowhere else.
#
# rx_packets matters just as much. If replies arrive on wonder0 the stack should see
# them; if rx stays flat while wondertap0 rises, the driver is feeding the raw vif and
# starving the stack, and the userspace relay is the only way in.
W=wonder0
T=wondertap0
st() { for i in $W $T; do
    echo "  $i tx=$(cat /sys/class/net/$i/statistics/tx_packets) rx=$(cat /sys/class/net/$i/statistics/rx_packets) txd=$(cat /sys/class/net/$i/statistics/tx_dropped) rxd=$(cat /sys/class/net/$i/statistics/rx_dropped)"
done; }

LL=$1
echo "== before =="; st
echo "-- ping6 $LL%wonder0 (sent to peer, expects an echo reply) --"
ping6 -c 2 -W 2 "$LL%wonder0" 2>&1
sleep 2
echo "== after ping =="; st
echo "-- icmp6 counters --"
for f in Icmp6OutEchos Icmp6InEchoReplies Icmp6OutErrors; do
    printf "  %-20s %s\n" "$f" "$(grep "^$f" /proc/net/snmp6 | awk '{print $2}')"
done
echo "-- send to a link-local nobody owns, to separate 'emit' from 'reply' --"
ping6 -c 2 -W 2 "fe80::1%wonder0" 2>&1 | tail -2
sleep 2
echo "== after ping fe80::1 =="; st
echo done
