#!/system/bin/sh
# Does this link die after a second or two, or does it refuse large payloads?
#
# The previous probe climbed a size ladder and every rung failed except the first,
# which read as "there is a size threshold just above sixteen bytes". That reading
# is only available if the link itself is constant across the ladder, and it is not:
# the one exchange that worked happened at 0.896s, and the summary line that prints
# every five seconds showed offch climbing 27 -> 59 -> 93 -- frames going out on a
# slot whose channel is not the one the frame was built for.
#
# So the ladder confounded size with time. This one holds the payload at one size and
# moves only the clock: one 16-byte echo every second for the whole run. If the
# answers stop at some second and never come back, the variable is time, and the size
# ladder was measuring the wrong thing. If they keep coming for a minute, size is back
# on the table and the ladder is worth re-running with the parse fixed.
DUR=${DUR:-70}
TUN=${TUN:-awdlrt0}
SIZE=${SIZE:-16}
LOG=/data/local/tmp/timeline.log
: > "$LOG"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
P=$!
echo "timeline: pid=$P dur=${DUR}s size=$SIZE tun=$TUN"

i=0
while [ $i -lt 400 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    if ! kill -0 $P 2>/dev/null; then echo "timeline: relay died"; exit 1; fi
    i=$((i + 1))
    sleep 0.1
done
if [ -z "$mac" ]; then
    echo "timeline: no peer heard in 40s"
    kill $P 2>/dev/null
    exit 1
fi

m1=${mac%%:*}; r=${mac#*:}
m2=${r%%:*};  r=${r#*:}
m3=${r%%:*};  r=${r#*:}
m4=${r%%:*};  r=${r#*:}
m5=${r%%:*};  m6=${r#*:}
b1=$(printf '%02x' $((0x$m1 ^ 2)))
LL="fe80::${b1}${m2}:${m3}ff:fe${m4}:${m5}${m6}"
echo "timeline: peer $mac -> $LL"

# One packet per line, with the second it was answered, so the transition is a line
# number rather than an inference. -c 1 because a retry would blur exactly the
# boundary this is looking for.
t0=$(date +%s)
n=0
while kill -0 $P 2>/dev/null; do
    out=$(ping6 -c 1 -W 2 -s "$SIZE" "$LL%$TUN" 2>&1)
    if printf '%s' "$out" | grep -q ' 0% packet loss'; then
        rtt=$(printf '%s\n' "$out" | sed -n 's/.*time=\([0-9.]*\).*/\1/p' | head -1)
        echo "t=$(( $(date +%s) - t0 ))s  ok    rtt=${rtt:-?}ms"
    else
        echo "t=$(( $(date +%s) - t0 ))s  lost"
    fi
    n=$((n + 1))
    sleep 1
done
echo "timeline: $n probes sent"

echo "timeline: --- the relay's own five-second summary ---"
grep -E "s left\]" "$LOG"
echo "timeline: --- every successful reply the relay relayed ---"
grep -c "relay in #" "$LOG" 2>/dev/null
echo "timeline: done"
