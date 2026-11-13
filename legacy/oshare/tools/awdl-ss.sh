#!/system/bin/sh
# Records the kernel's view of the AirDrop sockets while a transfer runs.
#
# The question this exists to answer is one the wire cannot: whether the two
# segments in flight are two because the peer's receive window is full or
# because our own congestion window will not grow. Those two look identical in
# a frame trace -- in both cases one frame leaves, one ACK comes back, and
# nothing else is available -- but they need opposite fixes, and `ss` prints
# cwnd, the negotiated window scale, the retransmission count and the RTT on
# the same line.
#
# Usage: awdl-ss.sh [samples] [interval]   (defaults: 4500 samples, 0.2 s,
#        i.e. fifteen minutes of coverage)
# Output: /data/local/tmp/tcpstat.txt, blocks introduced by "@@@ <uptime>".
# /proc/uptime rather than date, because it has subsecond resolution on every
# Android version and does not jump when the clock is adjusted.

OUT=/data/local/tmp/tcpstat.txt
LIMIT=${1:-4500}
STEP=${2:-0.2}

: > "$OUT"

# Only sockets whose far end is the AirDrop service are interesting; the device
# has dozens of others and this runs a few thousand times.
sampler() {
    awk '
        /:8770/ { print; want = 1; next }
        want && /^[ \t]/ { print; want = 0; next }
        { want = 0 }
    '
}

i=0
while [ "$i" -lt "$LIMIT" ]; do
    read -r up _ < /proc/uptime
    echo "@@@ $up" >> "$OUT"
    ss -tin 2>/dev/null | sampler >> "$OUT"
    i=$((i + 1))
    sleep "$STEP"
done
echo "@@@ done $i samples" >> "$OUT"
