#!/system/bin/sh
# Capture everything needed to settle "is the peer's queue empty or full" in one shot.
#
# Three signals, one clock:
#   1. wondertap0, `-e` text  -> the radiotap decode line, which is the only place the
#      real per-frame rate appears (station dump lies: see tools-probe/air/rate.sh).
#   2. awdltun0, `-S -v` text -> absolute TCP sequence numbers plus the window we
#      advertise. The seq delta between two segments of one burst is a direct readout of
#      the sender's congestion window; no inference from counters involved.
#   3. rx_bytes / rx_packets of awdltun0 -> wall-clock throughput, the only judge that
#      Task.md 6.5 accepts.
#
# Absolute epoch timestamps on both tcpdumps (`-tt`) so the air side and the TCP side can
# be lined up after the fact; the counter CSV uses uptime ms because `date +%s%N` overflows
# in this shell's $(( )) (documented in rate.sh).
#
# `timeout` not `nohup &`: an orphaned tcpdump keeps stealing frames from the virtual
# monitor and quietly changes what the next run measures.

secs=${1:-180}
tag=${2:-run}
dir=/data/local/tmp
AIR=$dir/$tag-air.txt
TUN=$dir/$tag-tun.txt
CNT=$dir/$tag-cnt.csv

: > "$AIR"
: > "$TUN"
printf 't_ms\trx_bytes\trx_packets\ttx_bytes\n' > "$CNT"

timeout "$secs" tcpdump -i wondertap0 -e -n -tt -s 96 -l > "$AIR" 2>/dev/null &
p1=$!
timeout "$secs" tcpdump -i awdltun0 -n -S -tt -v -s 128 -l > "$TUN" 2>/dev/null &
p2=$!

t0=$(cut -d. -f1 /proc/uptime)
end=$(( t0 + secs ))
while [ "$(cut -d. -f1 /proc/uptime)" -lt "$end" ]; do
    read rb < /sys/class/net/awdltun0/statistics/rx_bytes
    read rp < /sys/class/net/awdltun0/statistics/rx_packets
    read tb < /sys/class/net/awdltun0/statistics/tx_bytes
    now=$(cut -d. -f1 /proc/uptime)
    printf '%s\t%s\t%s\t%s\n' "$(( (now - t0) * 1000 ))" "$rb" "$rp" "$tb" >> "$CNT"
    sleep 0.2
done

wait $p1 $p2 2>/dev/null
echo "collect done: $(wc -c < "$AIR") air / $(wc -c < "$TUN") tun / $(wc -l < "$CNT") samples"
