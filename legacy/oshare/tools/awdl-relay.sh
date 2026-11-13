#!/system/bin/sh
# Does the kernel reach the peer when the packets travel through a tun and the radio
# frames are built in userspace?
#
# This is the shape the rest of these tools settled on, and the reason is measurable
# rather than stylistic. wonder0 already carries the address the phone answers to, but the
# driver delivers almost nothing back into it: over a 35 second run 9295 frames came in
# over the radio and wonder0's rx counter moved by 8, while wondertap0's moved with every
# one of them. So the receive side of an ethernet vif is a dead end regardless of how
# correct the transmit side is.
#
# Everything therefore moves to a tun, where the packets are plain IPv6 and nothing
# depends on the 802.11 address fields -- which arrive shifted by six bytes (our own
# MAC inserted after the second address byte) and parse as somebody else's MAC. The
# receiver a frame is sent to is derived from the packet's own destination under
# RFC 4291, the same rule the peer applies to us, so neither end reads an address off
# the air.
#
# The test is deliberately the kernel's own tools: if ping6 answers and a plain TCP
# connect to 8770 completes, then IPv6, ICMPv6 and TCP all work over AWDL and every
# hand-written protocol layer in this tree can be deleted.
DUR=${DUR:-45}
TUN=${TUN:-awdlrt0}
LOG=/data/local/tmp/relay.log
: > "$LOG"
ip link set wondertap0 up 2>/dev/null

CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData wondertap0 "$DUR" 33:33:00:00:00:fb auto \
    8770,61504,61505 _airdrop._tcp.local "$TUN" > "$LOG" 2>&1 &
P=$!
echo "relay: pid=$P dur=${DUR}s tun=$TUN"

# The tun only exists once the JVM has opened it, so nothing below is worth running
# before the address is there.
i=0
while [ $i -lt 100 ]; do
    if ip -6 addr show dev "$TUN" 2>/dev/null | grep -q fe80; then break; fi
    if ! kill -0 $P 2>/dev/null; then echo "relay: died before the tun came up"; break; fi
    i=$((i + 1))
    sleep 0.1
done
echo "relay: link   $(ip -d link show "$TUN" 2>&1 | head -1)"
echo "relay: addr   $(ip -6 addr show dev "$TUN" 2>/dev/null | grep inet6 | tr -s ' ')"
echo "relay: route  $(ip -6 route show table 1087 | grep -E "fe80|$TUN" | tr '\n' ' ')"

last=""
round=0
start=$(date +%s)
while kill -0 $P 2>/dev/null; do
    mac=$(sed -n 's/^awdl-data: following \([0-9a-f:]*\).*$/\1/p' "$LOG" | tail -1)
    # Two rounds, not one. The first frame to a peer is spent creating the bridge entry
    # and the first seconds are spent finding the phone's slot, so a single probe at t=1
    # measures the setup rather than the link. The second round is the reading.
    #
    # The round is placed well after the first one can possibly be finished, because the
    # first one now contains a TLS session whose round trips are measured in tens of
    # seconds. Firing it early meant it started while round one was still running, and by
    # then the JVM was gone and the tun with it -- every line of that round said
    # ENETUNREACH and none of it was about the link.
    el=$(( $(date +%s) - start ))
    go=0
    if [ -n "$mac" ]; then
        case $round in
            0) go=1 ;;
            1) if [ $el -ge 170 ]; then go=1; fi ;;
        esac
    fi
    # The tun dies with the JVM that opened it, and a round started after that measures
    # the absence of an interface. Checking is cheaper than explaining the result away.
    if [ $go -eq 1 ] && ! ip -6 addr show dev "$TUN" 2>/dev/null | grep -q fe80; then
        echo "relay: tun $TUN gone after ${el}s, no more rounds"
        break
    fi
    if [ $go -eq 1 ]; then
        # Same derivation as everywhere else in this tree: RFC 4291 modified EUI-64,
        # universal/local bit flipped, ff:fe in the middle.
        m1=${mac%%:*}; r=${mac#*:}
        m2=${r%%:*};  r=${r#*:}
        m3=${r%%:*};  r=${r#*:}
        m4=${r%%:*};  r=${r#*:}
        m5=${r%%:*};  m6=${r#*:}
        b1=$(printf '%02x' $((0x$m1 ^ 2)))
        LL="fe80::${b1}${m2}:${m3}ff:fe${m4}:${m5}${m6}"
        # An earlier experiment left a /128 for this peer pointing at wonder0, and a /128
        # beats the /64 the tunnel installs. Deleting it is not tidying: with it in place
        # the route lookup names the dead vif and every reading below would be about the
        # wrong interface.
        ip -6 route del "$LL/128" table 1087 2>/dev/null
        ip -6 neigh del "$LL" dev wonder0 2>/dev/null
        echo "relay: peer $mac -> $LL (round $((round + 1)))"
        echo "relay: get    $(ip -6 route get "$LL" 2>&1 | tr '\n' ' ')"
        echo "--- ping6 ---"
        ping6 -c 1 -W 2 "$LL%$TUN" 2>&1
        # TLS, because TLS-1 says every AirDrop request is inside TLS 1.2 or 1.3 and
        # HTTP-2 says every request is a POST. The earlier plaintext
        # "GET /Discover HTTP/1.1" could not be answered by a TLS server: silence was the
        # correct response to it, and reading that silence as a broken link cost a run.
        #
        # Two numbers per probe now, because connect and read measure different things.
        # Connect is one round trip and was measured at 26.6s and 27.5s on consecutive
        # attempts, so 60s is a little over two times the measurement. Reading is a
        # different budget entirely: the handshake alone took 28.2s, the request then
        # needs its own one-way trip of about thirty seconds, and the answer needs to
        # come back. A single 60s number covering all of that is what produced the last
        # "NO REPLY" -- the 771-byte server flight had already arrived on the relay while
        # the probe was giving up on the handshake.
        #
        # Round one asks only whether the handshake completes. That is the question the
        # whole tun experiment exists to answer -- if TLS finishes, the kernel's TCP has
        # crossed the radio in both directions several times over. Round two adds the
        # request, which is the product's question rather than the link's: T-DISC-1 says
        # an Everyone-mode receiver answers an empty dictionary with StatusCode 100 and
        # its computer name, and both are now decoded with the product's own Bplist.
        if [ $round -eq 0 ]; then
            echo "--- tls handshake 8770 (connect 60s, read 120s) ---"
            CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
                com.oshare.airdrop.core.AwdlTcp "$TUN" "$LL" 8770 60000 120000 1 handshake 2>&1
        else
            echo "--- tls /Discover 8770 (connect 60s, read 120s) ---"
            CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
                com.oshare.airdrop.core.AwdlTcp "$TUN" "$LL" 8770 60000 120000 1 discover 2>&1
        fi
        echo "--- neigh ---"
        ip -6 neigh show dev "$TUN" 2>&1 | head -4
        # The control that separates "our SYN never arrives" from "the phone is not
        # listening". 61504 refused a handshake with a RST in every earlier run, so a
        # relayed SYN that reaches the same stack must be refused the same way -- and the
        # refusal now lands at TCP connect, before TLS is even attempted, which is what
        # makes it a control rather than a second experiment.
        echo "--- tcp 61504 control ---"
        CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
            com.oshare.airdrop.core.AwdlTcp "$TUN" "$LL" 61504 20000 20000 1 discover 2>&1
        echo "relay: ---"
        last=$mac
        round=$((round + 1))
    fi
    sleep 3
done
wait $P

echo "relay: --- what the relay did ---"
# The whole relay trace, not its tail. The first six packets of a run are exactly the
# ones that were cut off by the earlier limit -- and they are the handshake.
grep -E "\[ *[0-9]+\.[0-9]+\]|^awdl-data:" "$LOG" | head -1500
echo "relay: --- final counters ---"
grep -E "^awdl-data:" "$LOG" | tail -3
echo "relay: done"
