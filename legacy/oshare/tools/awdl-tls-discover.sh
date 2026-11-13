#!/system/bin/sh
# Relay the peer's link, wake it, and ask /Discover inside TLS.
#
# The run before this one got a complete answer from the peer at the TCP layer and read
# it as a refusal. What it had sent into that connection was 178 bytes of cleartext
# `POST /Discover HTTP/1.1`, and the trace says what a TLS server does with that:
#
#   [25.550] relay #29  tcp 49496->8770 ACK PSH seq=2704555366 ack=1106866000 data=178
#   [25.558] relay in #43 tcp 8770->49496 ACK seq=1106866000 ack=2704555544
#   [32.362] relay in #58 tcp 8770->49510 ACK FIN seq=1268304292 ack=1780109800
#
# The acknowledgement covers every byte (2704555366 + 178 = 2704555544) and the peer's
# own sequence number never moves past its initial SYN (1268304292 = ISN + 1), so it read
# the request, produced nothing, and closed. Its first byte was 'P' where a TLS record
# starts with 0x16, and TLS-1 says every AirDrop request travels inside TLS. Reflexively
# calling that "the phone rejected HTTP" would have been the fourth broken instrument in
# this investigation; the request is byte-identical to the minimal one, and the minimal one
# is sent through TlsStream, not through a socket.
#
# So the difference between this script and awdl-discover.sh is one thing: the request
# goes through javax.net.ssl instead of nc. AwdlTcp already does that, and it is the same
# code the product will use, so a session it cannot establish is one the product could not
# have established either.
#
#   awdl-tls-discover.sh [advert-seconds] [relay-seconds] [knock-delay] [knocks]
#
# One knock is `mode` or `mode/shape`, where mode is `discover` (default), `handshake`,
# `get`, `ask`, `nope` or a literal `/Path`, and shape is a `+`-joined set of switches: `real`
# (a body naming the sender, which is what a receiver needs to have something to show a
# person), `host`, `close`, `chunked`, `plistct`, or `plain` for the empty dict.
# `ask` is the other path and the other half of the protocol: it offers one file and is the
# only request whose answer means a person agreed. It holds its answer while the receiver's
# prompt is up -- ASK-2 gives that prompt fifteen seconds and ASK-2a has senders wait about
# forty-five -- so it needs READ_MS well above the default and a relay that outlives it.
# `send` is `ask` followed by the `/Upload` on the same connection: the mode that actually
# moves a file, and the only one whose result is checked on the other device.
# `handshake` is the control that stops after TLS: a completed handshake proves the radio
# carried a multi-round-trip session and leaves only the HTTP layer in question. `get` is
# the control that asks for a path with no body at all -- HTTP-5 has a receiver answer GET
# with 200. `nope` is `POST /Nope`, which HTTP-5 has a receiver answer with `400` and an
# empty body: a 400 there and a silence on /Discover would be two facts about one
# receiver rather than one fact about the session.
#
# The default is `discover/real` because it is the only shape that has ever been answered:
# an empty dict goes unanswered on this receiver, whatever §6.3 says about it being valid.
#
# The knocks run in sequence inside one relay, each preceded by its own short burst,
# because the peer's port has been measured open for about a second and a half after an
# advert and the first variant's answer took twelve hundred milliseconds to arrive. Two
# variants in one window would mean the second one measured the window, not the request.
secs=${1:-6}
dur=${2:-70}
delay=${3:-0.4}
knocks=${4:-discover/real}

TUN=${TUN:-awdlrt0}
PORT=${PORT:-8770}
CONNECT_MS=${CONNECT_MS:-10000}
# An answer that comes at all comes in about a second on this link, so the read budget is
# a ceiling on silence rather than an expectation. Twenty-five seconds here was a budget
# sized for a route that was not yet known to work.
READ_MS=${READ_MS:-8000}
# One try per knock: each variant gets its own burst, so a retry would only spend the
# window the burst bought on the same bytes twice.
TRIES=${TRIES:-1}
LOG=/data/local/tmp/tlsdisc.log
# The tun's route lives in a table Android reaches only through a policy rule, and a rule
# has to name a uid. The node takes that uid from its ninth argument because it runs as
# root and cannot ask; without it the rule is skipped and every connect() below dies in
# ENETUNREACH on rmnet_data3 -- a route that looks absent but is really a rule that was
# never written. Read from the app's own data directory rather than hard-coded, since the
# uid is assigned at install time.
APP_UID=${APP_UID:-$(stat -c %u /data/data/com.oshare.airdrop 2>/dev/null)}

# The target table holds eight slots and frees one only when the vif stops, so a table
# with anything in it means a frame aimed at a new address is still transmitted, still
# completes, and reads exactly like a peer that has gone quiet. Cleared here, before the
# burst, because after the burst the clearing would be paid out of the only window the
# burst bought.
if [ -f /data/local/tmp/awdl-link.sh ]; then
    echo "tls: $(sh /data/local/tmp/awdl-link.sh ensure 2>&1 | tr '\n' ' ')"
fi

: > "$LOG"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$dur" 33:33:00:00:00:fb auto \
    "$PORT",61504,61505 _airdrop._tcp.local "$TUN" "" "$APP_UID" > "$LOG" 2>&1 &
P=$!
echo "tls: pid=$P relay=${dur}s tun=$TUN port=$PORT knocks=$knocks uid=$APP_UID"

# The order of the next three things is the whole design, and it was wrong in every
# earlier script in this directory.
#
# The peer is found by hearing it, and a sleeping phone transmits nothing to hear, so the
# relay cannot anchor until something wakes the phone -- which is what the advert burst
# is for. But the burst is also what opens the peer's receive window, and that window has
# been measured at roughly a second and a half. Bursting first and then waiting for the
# anchor spends the window on the wait: one run read "launch 1 heard no peer in 40 s"
# three times over, four minutes of it, and then knocked on a port that had already gone
# back to answering RST.
#
# So the relay is started first, the burst runs while it is anchoring, and the knock
# follows the burst within a fraction of a second of the link being ready. Nothing here
# waits for the peer to become audible on its own; the burst is what makes it audible.
#

# A raw socket open sometimes returns EACCES even as root, and the process then exits
# before it has built a frame -- which from outside is a link that cannot transmit rather
# than a launch that did not happen. Retried, not reported.
mac=
tries=0
while [ $tries -lt 3 ]; do
    tries=$((tries + 1))
    if [ "$secs" -gt 0 ] 2>/dev/null; then
        CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
            com.oshare.airdrop.core.BleAdvertProbe "$secs" > /data/local/tmp/ble.log 2>&1
        echo "tls: launch $tries burst -- $(grep -c onStartSuccess /data/local/tmp/ble.log) advert(s) confirmed"
    fi
    i=0
    while [ $i -lt 250 ]; do
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
        echo "tls: launch $tries hit EACCES on the raw socket, relaunching"
    else
        echo "tls: launch $tries heard no peer in 25s"
    fi
    sleep 2
    : > "$LOG"
    CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlData wondertap0 "$dur" 33:33:00:00:00:fb auto \
        "$PORT",61504,61505 _airdrop._tcp.local "$TUN" "" "$APP_UID" > "$LOG" 2>&1 &
    P=$!
done
if [ -z "$mac" ]; then
    echo "tls: no peer after $tries launches"
    kill $P 2>/dev/null
    exit 1
fi

# RFC 4291 modified EUI-64 from the transmitter address, universal/local bit flipped.
m1=${mac%%:*}; r=${mac#*:}
m2=${r%%:*};  r=${r#*:}
m3=${r%%:*};  r=${r#*:}
m4=${r%%:*};  r=${r#*:}
m5=${r%%:*};  m6=${r#*:}
b1=$(printf '%02x' $((0x$m1 ^ 2)))
LL="fe80::${b1}${m2}:${m3}ff:fe${m4}:${m5}${m6}"
echo "tls: peer $mac -> $LL"

# An earlier experiment left a /128 for this peer pointing at wonder0, and a /128 beats
# the /64 the tunnel installs. With it there the route lookup names the dead vif and every
# reading below is about the wrong interface.
ip -6 route del "$LL/128" table 1087 2>/dev/null
ip -6 neigh del "$LL" dev wonder0 2>/dev/null

# The node installs its policy rule for the app's uid, but both halves of this script run
# as root, so on this side of the run nothing names table 1087 and the connect above dies
# on rmnet_data3. A /128 rule keeps the exception as narrow as the one address in use
# instead of pulling every fe80:: destination into the tunnel, and it is taken out again
# at the end so root's other link-local traffic is left as it was found.
ip -6 route replace "$LL/128" dev "$TUN" table 1087 2>/dev/null
ip -6 rule add to "$LL/128" uidrange 0-0 lookup 1087 pref 100 2>/dev/null
echo "tls: 1087 $(ip -6 route show table 1087 2>/dev/null | grep "$LL" | tr '\n' ' ')"

# The peer's port has been measured open from 1.19 s into this process to 2.57 s, so the
# pause here is the walk to the door, not a wait for the house to be built. Half a second
# is what the slot anchor costs; three seconds was inside the window and outside it at the
# same time, which is how a whole run got spent on housekeeping.
sleep "$delay"

echo "tls: route  $(ip -6 route get "$LL" 2>&1 | tr '\n' ' ')"

# Each knock is its own connection, because HTTP-7 lets a receiver accept /Discover once
# per connection: a second request on the same socket is refused by rule, and would read
# exactly like the failure this run is trying to explain.
first=1
for k in $knocks; do
    m=${k%%/*}
    s=${k#*/}
    [ "$s" = "$k" ] && s=plain

    if [ "$first" = 1 ]; then
        first=0
    else
        # The window closes again, and another burst is what opens it. No re-anchoring:
        # the relay already knows which transmitter to follow, so this is a wake, not a
        # search. Without it the second variant would knock on a port that had gone back
        # to answering RST and the run would report the window as the answer.
        if [ "$secs" -gt 0 ] 2>/dev/null; then
            CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
                com.oshare.airdrop.core.BleAdvertProbe "$secs" > /data/local/tmp/ble.log 2>&1
            echo "tls: re-burst -- $(grep -c onStartSuccess /data/local/tmp/ble.log) advert(s) confirmed"
        fi
        sleep "$delay"
    fi

    before=$(wc -l < "$LOG" 2>/dev/null)
    [ -z "$before" ] && before=0

    echo "--- tls $m/$s $PORT (connect ${CONNECT_MS}ms read ${READ_MS}ms tries $TRIES) ---"
    CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlTcp "$TUN" "$LL" "$PORT" "$CONNECT_MS" "$READ_MS" \
        "$TRIES" "$m" "$s" 2>&1
    echo "tls: --- wire for $m/$s ---"
    sed -n "$((before + 1)),\$p" "$LOG" 2>/dev/null \
        | grep -E "relay #|relay in #|spoof hit|VERDICT" | sed 's/^ *//' | tail -30
done

sleep 1
kill $P 2>/dev/null
wait $P 2>/dev/null

echo "tls: --- whole run ---"
grep -E "relay packets=|VERDICT|peer frame not relayed" "$LOG" | sed 's/^ *//' | tail -8
ip -6 rule del to "$LL/128" uidrange 0-0 lookup 1087 pref 100 2>/dev/null
ip -6 route del "$LL/128" table 1087 2>/dev/null
echo "tls: done"
