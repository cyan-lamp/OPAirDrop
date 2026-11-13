#!/system/bin/sh
# What the sender's TCP believes about this link, sampled while a transfer runs.
#
# Every number in the node's status line is about frames. The throughput question is
# about the connection: cwnd, RTT, and how often the sender gave up and retransmitted.
# A link that delivers 92% of frames at the MAC layer and still moves 750 B/s is a
# connection pinned at one segment per round trip, and ss is the only reading that
# says so directly.
DUR=${DUR:-150}
OUT=/data/local/tmp/tcpmon.log
: > "$OUT"

pick() {
    # The transfer is the only established connection on the tun's link-local peer,
    # so select it by the app's port rather than dumping every socket on the phone.
    ss -tin 2>/dev/null | grep -A1 -E ':8770|:22[0-9A-F]{2} ' | grep -v '^--$'
}

START=$(date +%s)
while :; do
    NOW=$(date +%s)
    [ $((NOW - START)) -ge "$DUR" ] && break
    echo "=== t=$((NOW - START)) ===" >> "$OUT"
    ss -tin 2>/dev/null | awk '/ESTAB/{h=$0; getline; if (h ~ /8770/ || $0 ~ /8770/) print h "\n" $0}' >> "$OUT"
    echo "-- l2 --" >> "$OUT"
    ip -s link show wondertap0 2>/dev/null | sed -n '/TX:/,+1p' >> "$OUT"
    sleep 1
done
echo "=== done ===" >> "$OUT"
