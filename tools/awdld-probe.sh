#!/system/bin/sh
# Runs `awdld` on the device for a while and reports what the radio did.
#
# The four counters are the whole point. `wondertap0` is where this process reads and
# writes: rx moving means frames are arriving from the air, tx moving means presence
# frames are leaving. `wonder0` is the mac80211 side, whose rx moving means the driver
# accepted our frames as belonging to the network rather than dropping them.
#
# Usage: awdld-probe.sh [seconds] [extra awdld args]

set -u
cd /data/local/tmp/opairdrop || exit 1

seconds=${1:-30}
shift 2>/dev/null || true

count() { cat "/sys/class/net/$1/statistics/$2" 2>/dev/null || echo "?"; }

echo "BEFORE tap_tx=$(count wondertap0 tx_packets) tap_rx=$(count wondertap0 rx_packets) wonder_rx=$(count wonder0 rx_packets) wonder_tx=$(count wonder0 tx_packets)"

./awdld "$@" > awdld.log 2>&1 &
pid=$!
sleep "$seconds"
kill "$pid" 2>/dev/null
wait "$pid" 2>/dev/null

echo "AFTER  tap_tx=$(count wondertap0 tx_packets) tap_rx=$(count wondertap0 rx_packets) wonder_rx=$(count wonder0 rx_packets) wonder_tx=$(count wonder0 tx_packets)"

echo "--- stations ---"
iw dev wonder0 station dump 2>&1 | head -30

echo "--- log ---"
tail -25 awdld.log
