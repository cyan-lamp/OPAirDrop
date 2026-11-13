#!/system/bin/sh
# How large a packet can cross this link before it stops arriving?
#
# The run before this one measured a TCP connect at 266ms and a TLS handshake at
# 34.7s on the same connection, which cannot both be about latency. The relay log says
# what the difference is: a 130-byte SYN and its 106-byte answer cross in fifteen
# milliseconds, a 238-byte DNS answer arrives, and a 355-byte frame carrying the
# ClientHello needs a dozen copies over thirty seconds. So the link has a size
# threshold, and everything above it is slow not because the radio is slow but because
# delivery fails and something has to retry.
#
# The threshold decides whether the tun route can carry a transfer at all, so it is
# worth one run to measure instead of guessing. ICMP echo is the right instrument: it
# rides the same relayed unicast data frame as everything else, the kernel quotes the
# round trip itself, and the payload size is chosen per run with no stack in between to
# renegotiate it.
#
# Frame overhead beyond the ICMP payload is fixed: 40 bytes of IPv6, 8 of ICMPv6 and
# about 34 of AWDL and 802.11 headers, so a ping of size N puts roughly N+82 bytes on
# the air. The table below prints both numbers for that reason.
DUR=${DUR:-150}
TUN=${TUN:-awdlrt0}
LOG=/data/local/tmp/mtu.log
: > "$LOG"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local "$TUN" > "$LOG" 2>&1 &
P=$!
echo "mtu: pid=$P dur=${DUR}s tun=$TUN"

i=0
while [ $i -lt 100 ]; do
    if ip -6 addr show dev "$TUN" 2>/dev/null | grep -q fe80; then break; fi
    if ! kill -0 $P 2>/dev/null; then echo "mtu: died before the tun came up"; break; fi
    i=$((i + 1))
    sleep 0.1
done

# The peer's MAC only appears once the relay has heard a frame from it, and the address
# is derived from that MAC. Pinging before this loop finishes is pinging nobody.
mac=""
i=0
while [ $i -lt 400 ]; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    [ -n "$mac" ] && break
    i=$((i + 1))
    sleep 0.1
done
if [ -z "$mac" ]; then
    echo "mtu: no peer heard in 40s"
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
echo "mtu: peer $mac -> $LL"

# Ascending, and never reusing a size: each ping is one observation of one frame size.
# -c 2 rather than -c 1 because a single loss says the frame was unlucky, and two
# consecutive ones say it was too large. -W 6 is one order of magnitude above the 412ms
# round trip, which is enough that a timeout means the frame never came back.
echo "mtu: payload  frame  result"
for N in 16 100 150 200 250 300 350 400 500 600 800 1000 1200 1400; do
    FRAME=$((N + 82))
    OUT=$(ping6 -c 2 -i 1 -W 6 -s "$N" "$LL%$TUN" 2>&1)
    GOT=$(printf '%s\n' "$OUT" | sed -n 's/.*received, \([0-9]*\)% packet loss.*/\1/p')
    RTT=$(printf '%s\n' "$OUT" | sed -n 's/^rtt min\/avg\/max\/mdev = \([0-9.]*\).*/\1/p')
    case "$GOT" in
        0)   echo "mtu: $N  $FRAME  PASS  rtt=${RTT:-?}ms" ;;
        50)  echo "mtu: $N  $FRAME  HALF" ;;
        *)   echo "mtu: $N  $FRAME  FAIL" ;;
    esac
    # Nothing above a failing size is going to pass either, and each failure costs
    # twelve seconds of waiting; the run only has so long.
    if [ "$GOT" = "100" ] && [ "$N" -ge 300 ]; then
        echo "mtu: stopping at $N, nothing larger is worth the wait"
        break
    fi
done

echo "mtu: --- relay summary ---"
grep -E "^awdl-data: relay packets|^awdl-data: ctrl=" "$LOG" | tail -2
kill $P 2>/dev/null
wait $P 2>/dev/null
echo "mtu: done"
