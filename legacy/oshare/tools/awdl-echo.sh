#!/system/bin/sh
# One echo, waited on for twenty seconds, printed raw.
#
# The timeline run answered ping six times in seventy seconds and called every one of
# them lost, while the relay's own counter said two replies had come in from the air.
# Both cannot be true unless the reply is arriving after the sender stopped waiting, so
# the thing to measure is not whether an answer exists but when. -W 20 is forty times
# the round trip the first exchange ever showed (15ms), which makes "late" a number
# instead of a guess.
#
# The relay's own lines are printed with the ping's, because the answer to "when" is
# only useful next to which copy of the request it answers.
DUR=${DUR:-70}
TUN=${TUN:-awdlrt0}
SIZE=${SIZE:-16}
COUNT=${COUNT:-3}
LOG=/data/local/tmp/echo1.log
: > "$LOG"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
P=$!
echo "echo1: pid=$P dur=${DUR}s size=$SIZE count=$COUNT tun=$TUN"

i=0
while [ $i -lt 400 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    if ! kill -0 $P 2>/dev/null; then echo "echo1: relay died"; exit 1; fi
    i=$((i + 1))
    sleep 0.1
done
if [ -z "$mac" ]; then
    echo "echo1: no peer heard in 40s"
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
echo "echo1: peer $mac -> $LL"

# Three attempts, three seconds apart, one long wait each: two of them land on
# different eight-millisecond windows of the peer's cycle, and the third exists so a
# single unlucky phase is not read as a dead link.
echo "echo1: --- ping $COUNT x ${SIZE}B, -W 20 ---"
ping6 -c "$COUNT" -i 3 -W 20 -s "$SIZE" "$LL%$TUN" 2>&1
echo "echo1: ping exit=$?"

echo "echo1: --- what the relay saw (out then in, in order) ---"
grep -E "relay #|relay in #|relay drop" "$LOG" | sed 's/^  //'
echo "echo1: --- five-second summary ---"
grep -E "s left\]" "$LOG"

kill $P 2>/dev/null
wait $P 2>/dev/null
echo "echo1: done"
