#!/system/bin/sh
# Is the peer still reachable at all after the first connection is answered?
#
# Round four answered exactly one SYN -- the first -- and then ignored every later
# attempt for two and a half minutes while its own action frames kept arriving at
# about thirteen a second. That already rules out "the phone left", but it cannot
# tell apart the two remaining explanations, which call for completely different
# work:
#
#   (a) the phone is on the air and its IPv6 stack still answers, but the AirDrop
#       listener on 8770 was closed by that first connection (or by the garbage
#       that went into it), or
#   (b) nothing we transmit reaches it after the first exchange, on any port.
#
# A SYN to a port nobody listens on separates them. A stack that receives it must
# answer: RST, or at worst an ICMP port-unreachable. Silence there means our frames
# never arrive and the port question never arises. Port 9 is used because nothing
# in iOS listens on it and nothing can plausibly start to.
#
# The matrix runs twice -- once as found, once after a fresh BLE wake -- because
# "closed by the connection" and "open only inside a wake window" are also
# indistinguishable from a single pass, and they too imply different products.
#
# Timing: -w 4 gives each connect four seconds, which is eight times the 530 ms the
# round-four handshake took. A rung that needs longer than that is not slow, it is
# unanswered.
DUR=${DUR:-130}
TUN=${TUN:-awdlrt0}
WAKE=${WAKE:-com.oshare.airdrop}
LOG=/data/local/tmp/alive.log
DELTA=/data/local/tmp/alive.delta
REPLY=/data/local/tmp/alive.reply
PKG=$WAKE

: > "$LOG"; : > "$DELTA"; : > "$REPLY"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local "$TUN" >> "$LOG" 2>&1 &
P=$!
echo "alive: pid=$P dur=${DUR}s tun=$TUN"

i=0
while [ $i -lt 400 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    if ! kill -0 $P 2>/dev/null; then echo "alive: relay died"; exit 1; fi
    i=$((i + 1))
    sleep 0.1
done
if [ -z "$mac" ]; then
    echo "alive: no peer heard in 40s"
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
echo "alive: peer $mac -> $LL"
sleep 3

wake() {
    am broadcast -n "$PKG/.BleWakeReceiver" -a com.oshare.airdrop.BLE_WAKE --es op "$1" \
        2>&1 | grep -o 'data="[^"]*"' || true
}

# One question to one port, then the frames that question produced and the frames
# it brought back. Sliced by line offset rather than counted, for the same reason
# tcpsize slices: a number says something happened, the line says what.
attempt() {
    name=$1
    port=$2
    size=$3
    mark=$(wc -l < "$LOG")
    head -c "$size" /dev/urandom > /data/local/tmp/alive.bin
    : > "$REPLY"
    t0=$(date +%s)
    nc -6 -w 4 -q 1 "$LL%$TUN" "$port" < /data/local/tmp/alive.bin > "$REPLY" 2>/dev/null
    rc=$?
    ms=$(( ($(date +%s) - t0) * 1000 ))
    sed -n "$((mark + 1)),\$p" "$LOG" > "$DELTA"
    inn=$(grep -c 'relay in #' "$DELTA")
    outn=$(grep -c 'relay #' "$DELTA")
    bytes=$(wc -c < "$REPLY")
    printf 'alive: %-14s :%-5s rc=%s %sms out=%s in=%s bytes=%s\n' \
        "$name" "$port" "$rc" "$ms" "$outn" "$inn" "$bytes"
    grep 'relay in #' "$DELTA" | sed 's/^[^[]*//; s/^/alive:        <- /' | head -4
}

# Echo is the cheapest question that has nothing to do with TCP. If these come back
# while the SYNs do not, the loss is specific to TCP and not to our transmit path.
pinging() {
    n=$1
    k=0
    while [ $k -lt "$n" ]; do
        o=$(ping6 -c 1 -W 2 -I "$TUN" "$LL%$TUN" 2>&1 | tr '\n' ' ')
        case "$o" in
            *"bytes from"*) echo "alive: ping      ok   ${o#*bytes from}" ;;
            *)              echo "alive: ping      none ${o}" ;;
        esac
        k=$((k + 1))
        sleep 1
    done
}

echo "alive: === pass 1, as found ==="
attempt "first-8770"   8770 16
pinging 3
attempt "closed-port"  9    16
attempt "again-8770"   8770 16
attempt "syn-only"     9    0

echo "alive: === re-wake ==="
wake stop
sleep 1
wake start
sleep 3
wake status

echo "alive: === pass 2, after a fresh wake ==="
attempt "woke-8770"    8770 16
attempt "woke-9"       9    16
attempt "second-8770"  8770 16
pinging 3

echo "alive: === five-second summary (tail) ==="
grep -E "s left\]" "$LOG" | tail -6
echo "alive: === inbound frames seen in total ==="
grep -c 'relay in #' "$LOG"

kill $P 2>/dev/null
wait $P 2>/dev/null
echo "alive: done"
