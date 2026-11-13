#!/system/bin/sh
# What fraction of what we transmit actually arrives?
#
# The port-9 control answered this the hard way: a SYN to a port nobody listens on
# came back as a RST, so the phone's IPv6 stack is up, it receives our frames, and it
# replies to them. It also came back for only one of four attempts in the same minute,
# which turns "can we reach the phone" into a rate question -- and a rate is the one
# thing a single observation can never report.
#
# So: one repeated question, twenty-four times, with the answer counted rather than
# interpreted. A closed port is the right question because nothing above the kernel
# is involved -- no AirDrop listener, no TLS, no window that a previous connection
# can close. Every RST is one frame that crossed the air and came back. Every
# silence is one that did not.
#
# The two blocks exist for the same reason the wake did in awdl-alive: if a fresh
# BLE wake lifts the rate, the wake is not an availability switch but a nudge, and
# the product has to nudge before every attempt instead of once per session.
DUR=${DUR:-150}
TUN=${TUN:-awdlrt0}
WAKE=${WAKE:-com.oshare.airdrop}
PORT=${PORT:-9}
N=${N:-12}
GAP=${GAP:-1.2}
LOG=/data/local/tmp/synrate.log
DELTA=/data/local/tmp/synrate.delta

# Removed before being recreated, because a file that predates whatever mounted over
# /data/local/tmp sits in a read-only lower layer: open for truncation returns EACCES
# while unlink succeeds, and a run that never starts reads as a link that never works.
rm -f "$LOG" "$DELTA"
: > "$LOG"; : > "$DELTA"
ip link set wondertap0 up 2>/dev/null

# Starting the relay is not reliable and the failure is not the interesting kind.
# Opening an AF_PACKET/SOCK_RAW socket sometimes returns EACCES even though the
# caller is root, and the process exits before a single frame is built -- which
# reads from outside as a link that cannot transmit. It is a launch condition, not
# a finding, so it is retried here rather than reported: nothing else in the run
# can distinguish it from a real silence.
# A relaunch must start from an empty log, or the "following" line left by the
# attempt that died is read as the new one's success and the peer address comes
# from a process that is gone.
truncate_log() {
    rm -f "$LOG" "$DELTA"
    : > "$LOG"; : > "$DELTA"
}

launch() {
    CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
        8770,61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
    P=$!
}

P=
mac=
tries=0
while [ $tries -lt 6 ]; do
    tries=$((tries + 1))
    truncate_log
    launch
    i=0
    while [ $i -lt 400 ]; do
        mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
        [ -n "$mac" ] && break
        if ! kill -0 $P 2>/dev/null; then break; fi
        i=$((i + 1))
        sleep 0.1
    done
    [ -n "$mac" ] && break
    if grep -q 'inject open failed' "$LOG"; then
        echo "synrate: launch $tries hit EACCES on the raw socket, relaunching"
    else
        echo "synrate: launch $tries heard no peer in 40s"
    fi
    kill $P 2>/dev/null
    wait $P 2>/dev/null
    sleep 2
done
if [ -z "$mac" ]; then
    echo "synrate: no peer after $tries launches"
    exit 1
fi
echo "synrate: pid=$P dur=${DUR}s port=$PORT n=$N launch=$tries"

m1=${mac%%:*}; r=${mac#*:}
m2=${r%%:*};  r=${r#*:}
m3=${r%%:*};  r=${r#*:}
m4=${r%%:*};  r=${r#*:}
m5=${r%%:*};  m6=${r#*:}
b1=$(printf '%02x' $((0x$m1 ^ 2)))
LL="fe80::${b1}${m2}:${m3}ff:fe${m4}:${m5}${m6}"
echo "synrate: peer $mac -> $LL"
sleep 3

wake() {
    am broadcast -n "$WAKE/.BleWakeReceiver" -a com.oshare.airdrop.BLE_WAKE --es op "$1" \
        2>&1 | grep -o 'data="[^"]*"' || true
}

hit=0
try_n=0
# One attempt: did a RST come back inside the four seconds? Reported with the time it
# took, because a slow yes and a fast yes are different findings about the window.
attempt() {
    tag=$1
    mark=$(wc -l < "$LOG")
    t0=$(date +%s)
    nc -6 -w 4 -q 1 "$LL%$TUN" "$PORT" < /dev/null > /dev/null 2>&1
    rc=$?
    ms=$(( ($(date +%s) - t0) * 1000 ))
    sed -n "$((mark + 1)),\$p" "$LOG" > "$DELTA"
    # Two shapes of yes. "relay in #" is the kernel's own connection being answered --
    # that is nc's RST coming back. "spoof hit" is the phone answering a SYN this tool
    # built, which no longer reaches the tun as a relay line because the kernel has no
    # socket and could only answer it with a RST. Counting only the first shape made
    # every fabricated-SYN hit look like silence.
    rst=$(grep -c -E 'relay in #|spoof hit' "$DELTA")
    try_n=$((try_n + 1))
    if [ "$rst" -gt 0 ]; then
        hit=$((hit + 1))
        printf 'synrate: %s #%02d HIT  %sms  %s\n' "$tag" "$try_n" "$ms" \
            "$(grep -E 'relay in #|spoof hit' "$DELTA" | head -1 | sed 's/.*\(plen=.*\|flags=.*\|tcp .*\)/\1/')"
    else
        printf 'synrate: %s #%02d miss %sms\n' "$tag" "$try_n" "$ms"
    fi
}

i=0
while [ $i -lt "$N" ]; do
    attempt before
    i=$((i + 1))
    sleep "$GAP"
done
echo "synrate: block 1 done, $hit/$try_n"

echo "synrate: === re-wake ==="
wake stop
sleep 1
wake start
sleep 4
wake status

i=0
while [ $i -lt "$N" ]; do
    attempt after
    i=$((i + 1))
    sleep "$GAP"
done
echo "synrate: block 2 done, total $hit/$try_n"

echo "synrate: === summary lines, to line a hit up against its phase ==="
grep -E "s left\]" "$LOG" | sed 's/^ *//'

# The 5s lines are counters, not a record. A previous run concluded "the channel
# sequence is never parsed" from a file that held only these lines, and the sequence
# was there the whole time in the detail this block now returns. Anything used to
# judge the run has to travel with it.
echo "synrate: === detail: what the phone said, and what we did about it ==="
grep -E "spoof hit|relay in #|relay drop|rst held back|syn #|syn retry|syn burst|odd data|following |aim |VERDICT|relay packets=|tun .*fd=" \
    "$LOG" | sed 's/^ *//' | tail -80
echo "synrate: === what we relayed out (first 16 of $(grep -c 'relay #' "$LOG")) ==="
grep -E "relay #" "$LOG" | sed 's/^ *//' | head -16
echo "synrate: === adv: the phone's channel sequence and services ==="
grep -E "adv MIF" "$LOG" | sed 's/^ *//' | head -3

kill $P 2>/dev/null
wait $P 2>/dev/null
echo "synrate: done"
