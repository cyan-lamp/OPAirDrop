#!/system/bin/sh
# Does the connection carry data, and does the phone answer as an AirDrop receiver?
#
# The handshake test settled that a TCP connection completes across the air. It also
# showed the connection dying one packet later, because nc's stdin was /dev/null: EOF
# at connect time means the kernel sends ACK FIN before anything is written, so the
# run measured a connection that was never used. This script uses the connection.
#
# The request is POST /Discover with an empty dictionary -- the smallest thing that is
# still a real AirDrop message. It is chosen over a file offer on purpose: /Discover
# costs 42 bytes of body and the answer says everything at once.
#
#   * a 200 with a plist holding StatusCode 100 means the phone listed us as an
#     AirDrop destination, in Everyone mode. That is discovery working end to end,
#     over AWDL, with no mDNS and no multicast in the path.
#   * a 4xx/5xx means the HTTP layer and the plist parser both ran and rejected the
#     request on its merits -- still proof the link carries data, and the status says
#     what to fix.
#   * zero bytes back means bytes went out and nothing came back, which is a
#     different problem from a handshake that never completed.
#
# The reply is kept as a file per attempt rather than summarised here. The bytes are
# a binary plist; judging it on the device would mean writing a plist parser into
# shell, and every earlier conclusion drawn from a summary line has been wrong at
# least once. The files are pulled afterwards and decoded where plistlib exists.
DUR=${DUR:-150}
TUN=${TUN:-awdlrt0}
PORT=${PORT:-8770}
N=${N:-4}
# Seconds stdin stays open after the request is written, so the connection is not
# closed out from under the answer. WAIT has to outlast HOLD plus the read tail, or
# the timeout fires while the request is still legitimately outstanding.
HOLD=${HOLD:-6}
WAIT=${WAIT:-16}
QUIT=${QUIT:-3}
REQ=/data/local/tmp/discover.bin
LOG=/data/local/tmp/discover.log
DELTA=/data/local/tmp/discover.delta

if [ ! -s "$REQ" ]; then
    echo "discover: $REQ is missing or empty -- push it before running"
    exit 1
fi

# Unlinked before truncating, because a file that predates whatever mounted over
# /data/local/tmp sits in a read-only lower layer: open for truncation returns EACCES
# while unlink succeeds, and a run that never starts reads as a link that never works.
rm -f "$LOG" "$DELTA" /data/local/tmp/dresp.*.bin
: > "$LOG"; : > "$DELTA"

# The driver holds every receiver it has been told to talk to in a table of eight
# slots and frees one only when the vif is stopped. Rebuilding the link also
# assigns a new MAC, so every earlier run of this script left one more slot
# behind -- six runs meant six slots, and a frame aimed at an address with no
# peer behind it is still transmitted, still completes status3, and reads from
# the log exactly like a phone that has stopped answering. This script never did
# the reset that awdl-data.sh always did, which is the one environmental
# difference between the runs that answered and the runs that did not.
#
# `ensure` reads the table first and only blinks the link when there is
# something to clear, so a clean link pays nothing; RESET=0 skips it when the
# link must not drop under the run.
if [ "${RESET:-1}" = 1 ]; then
    if [ -f /data/local/tmp/awdl-link.sh ]; then
        echo "discover: $(sh /data/local/tmp/awdl-link.sh ensure 2>&1 | tr '\n' ' ')"
    else
        echo "discover: no /data/local/tmp/awdl-link.sh; table left as is"
    fi
fi
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    "$PORT",61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
P=$!
echo "discover: pid=$P dur=${DUR}s port=$PORT tun=$TUN n=$N hold=${HOLD}s req=$(wc -c < "$REQ")B"

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
        echo "discover: launch $tries hit EACCES on the raw socket, relaunching"
    else
        echo "discover: launch $tries heard no peer in 40s"
    fi
    sleep 2
    rm -f "$LOG"; : > "$LOG"
    CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
        "$PORT",61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
    P=$!
done
if [ -z "$mac" ]; then
    echo "discover: no peer after $tries launches"
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
echo "discover: peer $mac -> $LL"

# The relay needs a moment to anchor the slot before a frame can be aimed at it, but not
# three seconds. Measured: the peer's port opened 1.19 s into this process and shut again
# at 2.57 s, while a three-second pause here put the kernel's first SYN on the air at
# 3.49 s -- half a second after the only window in the whole run had closed. The anchor
# costs about half a second, so that is what this waits.
sleep "${KNOCK_DELAY:-0.5}"

ok_n=0
body_n=0
try_n=0

# nc is left to own the socket. Two things matter here and the first run got both wrong.
#
# The request goes in through a pipe that stays open for HOLD seconds. Reading the file
# straight into nc sends the request and then EOF, and EOF is what the kernel turns into
# an ACK FIN half a second later -- measured, 10.607 to 11.139. Half a second is not
# long enough for anything to answer, and a client that closes while the server is still
# parsing is a fair reason to be ignored. Holding stdin open turns the run into a normal
# request that waits, which is the thing being tested.
#
# -q then covers the tail: it makes nc keep reading for QUIT seconds after stdin finally
# ends, instead of exiting the instant the write side closes. That is exactly the shape
# HTTP needs -- request, half-close, answer on the other direction.
attempt() {
    mark=$(wc -l < "$LOG")
    resp=/data/local/tmp/dresp.$try_n.bin
    t0=$(date +%s)
    { cat "$REQ"; sleep "$HOLD"; } \
        | nc -6 -w "$WAIT" -q "$QUIT" "$LL%$TUN" "$PORT" > "$resp" 2>/dev/null
    ms=$(( ($(date +%s) - t0) * 1000 ))
    sed -n "$((mark + 1)),\$p" "$LOG" > "$DELTA"
    try_n=$((try_n + 1))

    got=0
    [ -f "$resp" ] && got=$(wc -c < "$resp")
    syn=$(grep -cE "relay #.*tcp [0-9]+->$PORT .*SYN" "$DELTA")
    # plen on a relay line is the IPv6 payload length, which for TCP is the header plus
    # the payload. Measured shapes on this link: a SYN is 40 (twenty bytes of options),
    # a bare ACK and an ACK FIN are 32, and the phone's SYN-ACK is 44. So a frame above
    # 40 bytes of TCP is carrying something. Counting by flags instead would have meant
    # matching 0x18 PSH ACK and guessing what an option-heavy ACK looks like, and the
    # number is the fact while the flag name is my reading of it.
    psh=$(grep -E "relay #.*tcp [0-9]+->$PORT " "$DELTA" \
        | grep -oE 'plen=[0-9]+' | cut -d= -f2 | awk '$1 > 40' | wc -l)
    # Only the relay line counts. A frame the phone sends appears twice in the log -- once
    # as "data" from the probe, once as "relay in" when it is written back into the tun --
    # and counting both made one SYN-ACK read as two. The relay line is the one that
    # matters, because it is the one the kernel saw.
    synack=$(grep -cE "^  \[[0-9.]+\] relay in #[0-9]+ .*tcp $PORT->[0-9]+ .*SYN ACK" "$DELTA")
    # Anything at all from the phone, whatever it says. Three of these in a sixty-second
    # run is the fact that made the earlier numbers look like a radio problem: the peer is
    # close to silent, so a missing answer cannot be told from a question never heard.
    phone=$(grep -cE "^  \[[0-9.]+\] data [0-9a-f:]+ ->" "$DELTA")
    # The phone's answer. Same rule from the other direction, with 44 as the floor so
    # its SYN-ACK does not count as content.
    back=$(grep -E "relay in #[0-9]+ .* tcp $PORT->[0-9]+ " "$DELTA" \
        | grep -oE 'plen=[0-9]+' | cut -d= -f2 | awk '$1 > 44' | wc -l)
    # Whether the phone closed on us, and whether it ACKed our body at all. A second PSH
    # is a retransmission, and a retransmission means no ACK came back for the first --
    # so psh=1 is evidence the request landed, and psh>1 is evidence it did not.
    infin=$(grep -cE "relay in #[0-9]+ .* tcp $PORT->[0-9]+ .*(FIN|RST)" "$DELTA")
    status=$(head -c 40 "$resp" 2>/dev/null | tr -c '[:print:]' '.')

    if [ "$got" -gt 0 ]; then
        body_n=$((body_n + 1))
        printf 'discover: #%02d REPLY %sB  %sms syn=%s psh=%s synack=%s back=%s infin=%s phone=%s\n' \
            "$try_n" "$got" "$ms" "$syn" "$psh" "$synack" "$back" "$infin" "$phone"
        printf 'discover:     %s\n' "$status"
        ok_n=$((ok_n + 1))
    elif [ "$psh" -gt 0 ]; then
        printf 'discover: #%02d SENT  %sms syn=%s psh=%s synack=%s back=%s infin=%s phone=%s quiet\n' \
            "$try_n" "$ms" "$syn" "$psh" "$synack" "$back" "$infin" "$phone"
    else
        printf 'discover: #%02d dead  %sms syn=%s phone=%s\n' \
            "$try_n" "$ms" "$syn" "$phone"
        grep -E "relay drop|relay in failed|no route" "$DELTA" | head -2 \
            | sed 's/^/discover:   /'
    fi
}

i=0
while [ $i -lt "$N" ]; do
    attempt
    i=$((i + 1))
    sleep 1
done

echo "discover: === $ok_n answered, $body_n with bytes, $try_n attempts ==="
echo "discover: --- five-second summary ---"
grep -E "s left\]" "$LOG" | sed 's/^ *//' | tail -6
echo "discover: --- what crossed the air, in order ---"
grep -E "relay #|relay in #|spoof hit|rst held back|VERDICT|relay packets=" "$LOG" \
    | sed 's/^ *//' | tail -40
echo "discover: --- replies kept ---"
ls -l /data/local/tmp/dresp.*.bin 2>/dev/null | sed 's/^/discover: /'

kill $P 2>/dev/null
wait $P 2>/dev/null
echo "discover: done"
