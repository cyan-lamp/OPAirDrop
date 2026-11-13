#!/system/bin/sh
# Where in the slot should we be aiming?
#
# The phase histograms answered the first half of this question in one window: our
# frames leave at exactly three TU past our slot boundary -- one bucket, zero spread,
# ten frames out of ten -- and the peer's frames arrive between ten and forty-seven
# TU past that same boundary, peaking at twenty-four to thirty-nine. Both numbers are
# readings of the same slot, taken on the same clock, and they disagree by about half
# of it. Three TU is AWDL-16's unicast guard and it is being honoured exactly; it is
# simply not where this peer is listening. A receiver awake from thirty-two to
# forty-eight is not awake at three, and a frame sent at three is heard only when the
# sequence happens to carry the window elsewhere -- one frame in four, which is the
# rate twenty single-shot attempts measured.
#
# So this sweeps the offset and counts, with port 9 as the question again: nothing
# above the kernel answers, so every RST is one frame that crossed and every silence
# is one that did not.
#
# The sweep runs inside one process, not one process per offset. Restarting would
# measure the offset and the peer's rediscovery together, and the second of those
# moved the answer further than the first -- seven hits out of twenty on one run, one
# out of twelve on the next, with a peer address that had drifted in between. Here
# the process reads /data/local/tmp/awdl-phase four times a second and takes whatever
# was aimed the moment the file changed, so the only thing varying is the offset.
#
# Offsets are interleaved rather than run in blocks: the peer's availability is not
# stationary over minutes, and a block design would charge any drift to whichever
# offset happened to be aimed at the time. Each round visits every offset once, in a
# fixed order rotated by one per round, so no offset is always first or always last.
DUR=${DUR:-420}
OFFS=${OFFS:-"0 8 16 24 32 40 48"}
ROUNDS=${ROUNDS:-6}
TUN=${TUN:-awdlrt0}
WAKE=${WAKE:-com.oshare.airdrop}
PORT=${PORT:-9}
SETTLE=${SETTLE:-1.2}
GAP=${GAP:-0.4}
LOG=/data/local/tmp/phasescan.log
DELTA=/data/local/tmp/phasescan.delta
TALLY=/data/local/tmp/phasescan.tally
PHASE=/data/local/tmp/awdl-phase

rm -f "$LOG" "$DELTA" "$TALLY"
: > "$DELTA"; : > "$TALLY"
printf '0\n' > "$PHASE"
ip link set wondertap0 up 2>/dev/null

# Starting the relay is not reliable in a way that has to be handled rather than
# hoped away: the first attempt at this sweep opened its raw socket and got EACCES,
# and the identical command run a minute later got a descriptor. Nothing about the
# command changed -- the link was mid-rebuild -- but a relay that never starts looks
# exactly like an offset that never works, so it is checked and restarted instead of
# being allowed to fill the table with misses.
start_relay() {
    : > "$LOG"
    CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
        8770,61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
    P=$!
    i=0
    while [ $i -lt 400 ]; do
        mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
        [ -n "$mac" ] && return 0
        if grep -q 'VERDICT no-tx-socket' "$LOG" 2>/dev/null; then
            echo "phasescan: relay could not open the inject socket" >&2
            kill $P 2>/dev/null
            wait $P 2>/dev/null
            return 1
        fi
        if ! kill -0 $P 2>/dev/null; then
            echo "phasescan: relay exited before hearing a peer" >&2
            return 1
        fi
        i=$((i + 1))
        sleep 0.1
    done
    echo "phasescan: no peer heard in 40s" >&2
    return 1
}

tries=0
while :; do
    tries=$((tries + 1))
    if start_relay; then break; fi
    if [ $tries -ge 3 ]; then
        echo "phasescan: giving up after $tries attempts"
        kill $P 2>/dev/null
        exit 1
    fi
    echo "phasescan: retrying after a failed start"
    sleep 3
done
echo "phasescan: pid=$P dur=${DUR}s port=$PORT rounds=$ROUNDS offsets=$OFFS"

# AWDL addresses are the link-local interface identifier with the universal/local bit
# flipped, which is the same derivation the earlier probes used.
m1=${mac%%:*}; r=${mac#*:}
m2=${r%%:*};  r=${r#*:}
m3=${r%%:*};  r=${r#*:}
m4=${r%%:*};  r=${r#*:}
m5=${r%%:*};  m6=${r#*:}
b1=$(printf '%02x' $((0x$m1 ^ 2)))
LL="fe80::${b1}${m2}:${m3}ff:fe${m4}:${m5}${m6}"
echo "phasescan: peer $mac -> $LL"

# A wake before the sweep, because synrate showed the wake is a nudge: the second
# block of that run answered four in ten against the first block's three.
am broadcast -n "$WAKE/.BleWakeReceiver" -a com.oshare.airdrop.BLE_WAKE --es op start \
    2>&1 | grep -o 'data="[^"]*"' || true
sleep 4

attempt() {
    # A relay that has exited turns every remaining attempt into a miss, and a table
    # full of misses reads like a wrong offset rather than a missing process.
    if ! kill -0 $P 2>/dev/null; then
        echo ""
        echo "phasescan: relay exited, aborting sweep" >&2
        return 1
    fi
    printf '%s\n' "$1" > "$PHASE"
    # The file is polled every fifth of a second, so this is four chances for the new
    # aim to be picked up before the frame that answers this attempt is built.
    sleep "$SETTLE"
    mark=$(wc -l < "$LOG")
    nc -6 -w 4 -q 1 "$LL%$TUN" "$PORT" < /dev/null > /dev/null 2>&1
    sed -n "$((mark + 1)),\$p" "$LOG" > "$DELTA"
    if [ "$(grep -c 'relay in #' "$DELTA")" -gt 0 ]; then
        echo "hit $1" >> "$TALLY"
        printf '+'
    else
        echo "miss $1" >> "$TALLY"
        printf '.'
    fi
}

# The order is rotated one place per round by moving the first offset to the end,
# which needs no shuffle and is reproducible: a run that looks anomalous can be
# repeated and compared position by position.
ORDER=$OFFS
r=0
while [ $r -lt "$ROUNDS" ] && kill -0 $P 2>/dev/null; do
    printf 'phasescan: round %d ' "$((r + 1))"
    for o in $ORDER; do
        attempt "$o" || break
        sleep "$GAP"
    done
    echo ""
    ORDER="$(echo "$ORDER" | sed 's/^[^ ]* *//') $(echo "$ORDER" | sed 's/ .*//')"
    r=$((r + 1))
done

echo "phasescan: === tally, hits per offset ==="
for o in $OFFS; do
    h=$(grep -c "^hit $o\$" "$TALLY")
    m=$(grep -c "^miss $o\$" "$TALLY")
    printf 'phasescan: poff=%3s  %d/%d\n' "$o" "$h" "$((h + m))"
done

echo "phasescan: === aim as logged, per window (poff= should follow the sweep) ==="
grep -E "s left\]" "$LOG" | sed 's/^ *//' | grep -o 'poff=[0-9-]* ph=[^ ]* ph8=[^ ]*' | sed 's/^/phasescan: /'

echo "phasescan: === offset changes the relay actually saw ==="
grep -c "phase offset" "$LOG"

kill $P 2>/dev/null
wait $P 2>/dev/null
echo "phasescan: done"
