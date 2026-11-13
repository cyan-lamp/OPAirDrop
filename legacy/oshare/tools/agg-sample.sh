#!/system/bin/sh
# Samples the AWDL aggregation counters into a flat log the host script can slice.
#
#   agg-sample.sh [duration_s] [interval_s] [outfile]
#
# One line per sample: "<epoch> <agg_bytes> <agg_aggregates> <agg_msdus>
# <collect_us> <bridge_submitted> <bridge_completed> <bridge_pending>".
# The host appends "MARK <label>" lines to the same file when it changes a
# parameter, so sections can be split by wall clock on one clock source only.
#
# Everything is read from sysfs with one cat per counter: reading these
# parameters enters the driver, so the interval is kept above 100 ms to stay
# well clear of perturbing what it is measuring.
P=/sys/module/qca_cld3_peach_v2/parameters
DUR=${1:-60}
IV=${2:-0.12}
OUT=${3:-/data/local/tmp/agg-sample.log}

field() {   # field <key> <line>  -> value of key=.. or 0
    echo "$2" | tr ' ' '\n' | sed -n "s/^$1=//p" | head -1
}

: > "$OUT"
END=$(( $(date +%s) + DUR ))
while :; do
    NOW=$(date +%s)
    [ "$NOW" -ge "$END" ] && break
    TS=$(date +%s.%N)
    A=$(cat $P/opairshare_agg_stats)
    B=$(cat $P/opairshare_bridge_submitted 2>/dev/null)
    C=$(cat $P/opairshare_bridge_completed 2>/dev/null)
    D=$(cat $P/opairshare_bridge_pending 2>/dev/null)
    K=$(cat $P/passthru_awdl_collect_us 2>/dev/null)
    echo "$TS $(field bytes "$A") $(field aggregates "$A") $(field msdus "$A")" \
         "${K:-0} ${B:-0} ${C:-0} ${D:-0}" >> "$OUT"
    sleep "$IV"
done
echo "DONE" >> "$OUT"
