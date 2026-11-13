#!/system/bin/sh
# Does a real TCP handshake ever complete over this link?
#
# Every previous measurement stopped one step short of this question. A SYN went out,
# a SYN-ACK came back, and the run was recorded as a success -- but a SYN-ACK only
# proves the phone answered. It does not prove the phone will carry a connection, and
# it says nothing about the ACK that has to follow, which is the packet that either
# completes the handshake or leaves it hanging.
#
# The ACK is the whole test. And it is the kernel's ACK, not ours: nc owns a socket on
# the tun, the SYN-ACK is written back into that tun, the kernel matches it to nc's
# socket and emits the ACK itself. If that ACK appears in the relay's outbound log, the
# handshake completed on both ends and there is a real TCP connection across the AWDL
# air. If the SYN-ACK arrives and no ACK follows, the kernel refused the connection and
# the reason is on our side, not the phone's.
#
# The destination is a multicast group on purpose. That leaves aim == null, so the relay
# never fabricates a SYN of its own and the only TCP on the air is the kernel's. One
# source of frames, one question.
#
# What closed the earlier attempts is now suppressed: a fabricated SYN used to be aimed
# at this same port from the same address, the kernel answered the phone's SYN-ACK with
# a RST, and the relay sent that RST back three times. Whether a handshake could ever
# have completed behind that noise is exactly what this script measures.
DUR=${DUR:-120}
TUN=${TUN:-awdlrt0}
PORT=${PORT:-8770}
N=${N:-10}
WAIT=${WAIT:-6}
LOG=/data/local/tmp/handshake.log
DELTA=/data/local/tmp/handshake.delta

# Unlinked before truncating, because a file that predates whatever mounted over
# /data/local/tmp sits in a read-only lower layer: open for truncation returns EACCES
# while unlink succeeds, and a run that never starts reads as a link that never works.
rm -f "$LOG" "$DELTA"
: > "$LOG"; : > "$DELTA"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    "$PORT",61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
P=$!
echo "handshake: pid=$P dur=${DUR}s port=$PORT tun=$TUN n=$N wait=${WAIT}s"

# Opening the raw socket sometimes returns EACCES even as root and the process exits
# before building a frame, which reads from outside as a link that cannot transmit.
# That is a launch condition rather than a finding, so it is retried instead of
# reported -- and the log is emptied each time, or the "following" line left by the
# attempt that died is read as the new one's success.
mac=
tries=0
while [ $tries -lt 6 ]; do
    tries=$((tries + 1))
    i=0
    while [ $i -lt 400 ]; do
        mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
        [ -n "$mac" ] && break
        if ! kill -0 $P 2>/dev/null; then break; fi
        i=$((i + 1))
        sleep 0.1
    done
    [ -n "$mac" ] && break
    kill $P 2>/dev/null
    wait $P 2>/dev/null
    if grep -q 'inject open failed' "$LOG"; then
        echo "handshake: launch $tries hit EACCES on the raw socket, relaunching"
    else
        echo "handshake: launch $tries heard no peer in 40s"
    fi
    sleep 2
    rm -f "$LOG"; : > "$LOG"
    CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
        "$PORT",61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
    P=$!
done
if [ -z "$mac" ]; then
    echo "handshake: no peer after $tries launches"
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
echo "handshake: peer $mac -> $LL"

# The relay needs a few seconds to anchor the slot before a frame can be aimed at it.
sleep 3

open_n=0
half_n=0
try_n=0

# One attempt, counted by what came back rather than by what nc reported: nc exiting
# zero proves nothing, and nc exiting non-zero is what a working handshake followed by
# silence at the application layer looks like too.
attempt() {
    mark=$(wc -l < "$LOG")
    t0=$(date +%s)
    nc -6 -w "$WAIT" -q 1 "$LL%$TUN" "$PORT" < /dev/null > /dev/null 2>&1
    ms=$(( ($(date +%s) - t0) * 1000 ))
    sed -n "$((mark + 1)),\$p" "$LOG" > "$DELTA"
    try_n=$((try_n + 1))
    # Outbound SYN from the kernel: the attempt actually reached the air.
    syn=$(grep -cE "relay #.*tcp [0-9]+->$PORT .*SYN" "$DELTA")
    # The phone answering it. Either shape counts: a relay line when the kernel has no
    # socket on that port, nothing when it does.
    synack=$(grep -cE "tcp $PORT->[0-9]+ .*SYN ACK" "$DELTA")
    # The kernel's ACK. This is the packet that completes the handshake: outbound to
    # this port, carrying ACK, and not a SYN of its own.
    ack=$(grep -E "relay #.*tcp [0-9]+->$PORT " "$DELTA" | grep 'ACK' | grep -vc 'SYN')
    if [ "$ack" -gt 0 ]; then
        open_n=$((open_n + 1))
        printf 'handshake: #%02d OPEN   %sms syn=%s synack=%s ack=%s\n' \
            "$try_n" "$ms" "$syn" "$synack" "$ack"
    elif [ "$synack" -gt 0 ]; then
        half_n=$((half_n + 1))
        printf 'handshake: #%02d HALF   %sms syn=%s synack=%s\n' \
            "$try_n" "$ms" "$syn" "$synack"
        grep -E "relay in #|spoof hit|SYN ACK" "$DELTA" | head -2 | sed 's/^/handshake:   /'
    else
        printf 'handshake: #%02d silent %sms syn=%s\n' "$try_n" "$ms" "$syn"
    fi
    if [ "$syn" -eq 0 ]; then
        # Nothing left from the kernel at all: nc never got a route, or the tun was not
        # the interface the kernel chose. Shown because it is a different failure from a
        # frame that left and was not heard.
        grep -E "relay drop|relay in failed|no route" "$DELTA" | head -2 \
            | sed 's/^/handshake:   /'
    fi
}

i=0
while [ $i -lt "$N" ]; do
    attempt
    i=$((i + 1))
    sleep 1
done

echo "handshake: === $open_n open, $half_n half, $try_n attempts ==="
echo "handshake: --- five-second summary ---"
grep -E "s left\]" "$LOG" | sed 's/^ *//' | tail -6
echo "handshake: --- what crossed the air, in order ---"
grep -E "relay #|relay in #|spoof hit|rst held back|VERDICT|relay packets=" "$LOG" \
    | sed 's/^ *//' | tail -40
echo "handshake: --- the phone's channel sequence ---"
grep -E "adv MIF" "$LOG" | sed 's/^ *//' | head -1 \
    | sed 's/ tlvs=.*//'

kill $P 2>/dev/null
wait $P 2>/dev/null
echo "handshake: done"
