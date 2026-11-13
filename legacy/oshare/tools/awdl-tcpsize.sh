#!/system/bin/sh
# Where on the TCP path does payload stop arriving?
#
# Every instrument so far measured the wrong layer. ICMPv6 echo is not a fair test of
# this link at all: the driver only learns a target peer from a unicast IPv6/TCP frame
# (wma_opairshare_awdl_target.h, opairshare_target_frame_is_tcp checks b[22] == 6), so
# an echo request is transmitted untargeted and dropped above the radio while still
# being acked -- which is exactly the "status0 climbs, nothing answers" signature the
# counters showed.
#
# TCP is the path that works: a hand-built SYN gets its SYN-ACK back in about half a
# second, and the SYN-ACK came from the phone's own 8770, which is AirDrop's AWDL data
# port. So this connects to that listener for real and asks the only question left:
# how much can be put on the wire before the answer stops coming back.
#
# The record is the relay's own log, sliced by line offset per attempt rather than
# counted, because a count tells you that something happened and a slice tells you what.
DUR=${DUR:-150}
TUN=${TUN:-awdlrt0}
PORT=${PORT:-8770}
LOG=/data/local/tmp/tcpsize.log
DELTA=/data/local/tmp/tcpsize.delta
REPLY=/data/local/tmp/tcpsize.reply
: > "$LOG"
: > "$DELTA"
: > "$REPLY"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    "$PORT",61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
P=$!
echo "tcpsize: pid=$P dur=${DUR}s port=$PORT tun=$TUN"

i=0
while [ $i -lt 400 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    if ! kill -0 $P 2>/dev/null; then echo "tcpsize: relay died"; exit 1; fi
    i=$((i + 1))
    sleep 0.1
done
if [ -z "$mac" ]; then
    echo "tcpsize: no peer heard in 40s"
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
echo "tcpsize: peer $mac -> $LL"

# The relay needs a few seconds to get its own handshake out and anchor the slot;
# measuring during that window would just rediscover that the link is born slowly.
sleep 3

# Only the payload varies. Ascending, because a size that fails is expected to keep
# failing and the run has a fixed length.
printf 'tcpsize: %-6s %-4s %-6s %-6s %-6s %s\n' size rc tx in out verdict
for N in 16 64 128 256 384 512 768 1024 1400; do
    mark=$(wc -l < "$LOG")
    head -c "$N" /dev/urandom > /data/local/tmp/payload.bin
    : > "$REPLY"
    nc -6 -w 4 -q 1 "$LL%$TUN" "$PORT" < /data/local/tmp/payload.bin > "$REPLY" 2>/dev/null
    rc=$?
    bytes=$(wc -c < "$REPLY")
    sed -n "$((mark + 1)),\$p" "$LOG" > "$DELTA"
    inn=$(grep -c 'relay in #' "$DELTA")
    outn=$(grep -c 'relay #' "$DELTA")
    if [ "$bytes" -gt 0 ]; then
        verdict="ANSWERED"
    elif [ "$inn" -gt 0 ]; then
        verdict="HALF"
    else
        verdict="silent"
    fi
    printf 'tcpsize: %-6s %-4s %-6s %-6s %-6s %s\n' "$N" "$rc" "$outn" "$inn" "$bytes" "$verdict"
    # A silent rung is the interesting one, so show what the air did while it was silent.
    if [ "$verdict" != "ANSWERED" ]; then
        grep -E "relay #|relay in #|syn" "$DELTA" | tail -6 | sed 's/^/tcpsize:   /'
    fi
done

echo "tcpsize: --- five-second summary ---"
grep -E "s left\]" "$LOG" | tail -4
echo "tcpsize: --- inbound frames seen in total ---"
grep -c "relay in #" "$LOG"

kill $P 2>/dev/null
wait $P 2>/dev/null
echo "tcpsize: done"
