#!/system/bin/sh
# AWDL link control for the Mosey-free stack (v2).
#
# Creates the AWDL data interface on the `wonder` wiphy without Mosey, brings it
# up with an IPv6 link-local address and joins the mDNS group. Every action is
# idempotent: `up` on an existing link only refreshes addressing.
#
# Usage: awdl-link.sh probe | up [ifname] | down [ifname] | reset [ifname]
#                      ensure [ifname] | status [ifname] | table [ifname]
#
# Two findings from the on-device bring-up drive the shape of this script:
#
# 1. The interface must be named `wonder0`. wonder.ko resolves its virtual
#    netdev by name in wonder_start(), which mac80211 runs *before*
#    add_interface(); wonder->vdev is therefore still NULL and the only way to
#    find the netdev is dev_get_by_name(virtual_name) with the default
#    virtual_name=wonder0. Any other name makes `ip link set up` fail with
#    ENODEV ("Failed to get virtual device wonder0" in dmesg).
#
# 2. The interface must be ADHOC (IBSS), not NAN. wonder_rx_handler() only
#    hands received frames to mac80211 for NL80211_IFTYPE_ADHOC; NAN and
#    STATION fall through to RX_HANDLER_PASS, which leaves the 802.11 frame on
#    the physical netdev's stack and never reaches wonder0. IBSS is advertised
#    in interface_modes and wonder_tx() even has an ADHOC-only fixup for probe
#    requests.
#
# IBSS also gives us carrier: a bare `interface add` stays NO-CARRIER, so `up`
# joins a throwaway IBSS on the fixed channel to get LOWER_UP.

set -eu

AWDL_IF=${2:-wonder0}
# wonder.ko pins the radio to 5745 MHz (channel 149) in wondertap_init(); the
# IBSS join has to name the same frequency or mac80211 refuses the channel.
AWDL_FREQ=${AWDL_FREQ:-5745}
AWDL_SSID=${AWDL_SSID:-awdl0}
# Apple's AWDL is an IBSS whose BSSID is fixed, not randomly elected: every
# AWDL frame on the air carries 00:25:00:ff:94:73 in addr3, and the vendor's
# wondertap_init() passes the same value down as its bssid. Joining without it
# makes mac80211 elect our own random BSSID (46:0c:de:...), after which the
# IBSS receive path drops every Apple frame at the addr3 check -- the radio
# hears them (op13_wonder_rx climbs, aux monitor shows them at -33 dBm) while
# wonder0 stays at rx_packets=0.
AWDL_BSSID=${AWDL_BSSID:-00:25:00:ff:94:73}
SYS_NET=/sys/class/net
SYS_PHY=/sys/class/ieee80211
LOG_TAG=awdl-link

# The driver keeps the receivers it has been told to talk to in a table of exactly
# eight slots and never evicts one -- opairshare_target_reserve() says so in as
# many words ("No eviction: live peers and their RX BA state survive nearby
# discovery"). The table is emptied in exactly one place, opairshare_target_stop(),
# which __wlan_hdd_stop_wondertap_intf() calls when the vif is stopped. A vif left
# up across several runs therefore carries the address every one of those runs ever
# aimed at. Once all eight slots are spent, reserve() returns without a word,
# ensure() answers 0 so the frame is transmitted with no peer behind it, and every
# completion is status3 while the bridge still counts the frame as accepted: it
# reads as a peer that left, and it never recovers.
#
# Measured on this phone, both directions. Table at peers=8: 84% of data frames
# status3 (7667/9168), 96% of ADDBA requests -ENOENT (1489/1552), and not one reply
# to an mDNS query, an ICMPv6 echo or a SYN. Table cleared, one run: peers=1 -- the
# phone's current address -- status0 +67 / status3 +18, an mDNS reply, an ICMPv6
# echo reply and a TCP RST from the AirDrop port.
#
# So `reset` (down, then up) is the supported way to start an experiment, and
# `table` prints what the driver currently holds.
TARGET_MODULE=${TARGET_MODULE:-qca_cld3_peach_v2}
TARGET_STATE=/sys/module/$TARGET_MODULE/parameters/opairshare_awdl_target_state

# wonder-ven sits beside this script in the module's bin/ directory. It is the only
# way to reach the four settings wondertap_init() wants: the vendor contract is a
# cfg80211 vendor command, and `iw vendor send` cannot deliver one on this device --
# every subcommand, valid or not, comes back -EINVAL before the driver is reached, so
# the binary speaks netlink to nl80211 directly instead.
self_dir=$(CDPATH= cd -- "$(dirname "$0")" 2>/dev/null && pwd)
VEN=${WONDER_VEN:-$self_dir/wonder-ven}

log() { echo "$LOG_TAG: $*"; }

die() {
    echo "$LOG_TAG: $*" >&2
    exit 1
}

iw_bin() {
    for candidate in /system/bin/iw /vendor/bin/iw /system/xbin/iw; do
        [ -x "$candidate" ] && { echo "$candidate"; return 0; }
    done
    return 1
}

# The wonder wiphy owns both the physical netdev (wondertap0, created by vendor
# init) and the virtual one (wonder0). Prefer the virtual netdev, fall back to
# the physical one, and finally to the wiphy name itself -- on a cold start
# neither netdev exists yet, but /sys/class/ieee80211/wonder always does.
find_wonder_phy() {
    for anchor in wonder0 wondertap0; do
        link="$SYS_NET/$anchor/phy80211"
        if [ -e "$link" ]; then
            basename "$(readlink -f "$link")"
            return 0
        fi
    done
    for phy in "$SYS_PHY"/*; do
        [ -e "$phy" ] || continue
        if [ "$(cat "$phy/name" 2>/dev/null)" = wonder ]; then
            basename "$phy"
            return 0
        fi
    done
    return 1
}

if_index() {
    cat "$SYS_NET/$1/ifindex" 2>/dev/null
}

link_exists() {
    [ -e "$SYS_NET/$1" ]
}

# Existence is not enough: `ip link set wonder0 down` leaves the netdev behind
# while taking the vendor's wondertap0 with it, so a link can exist, be IBSS, and
# still be unable to carry a frame.
link_up() {
    [ "$(cat "$SYS_NET/$1/operstate" 2>/dev/null)" = up ]
}

# How many target slots the driver is holding, or empty when the parameter is not
# there -- the module name differs per build, and a caller that cannot read the
# table should get silence rather than a bogus zero.
target_peers() {
    [ -r "$TARGET_STATE" ] || return 1
    head -1 "$TARGET_STATE" | sed -n 's/.*peers=\([0-9][0-9]*\).*/\1/p'
}

# Stable link-local address from the interface MAC using EUI-64: insert ff:fe
# and flip the universal/local bit of the first byte.
link_local_for() {
    mac=$(cat "$SYS_NET/$1/address" 2>/dev/null) || return 1
    [ -n "$mac" ] || return 1
    b0=$(echo "$mac" | cut -d: -f1)
    b1=$(echo "$mac" | cut -d: -f2)
    b2=$(echo "$mac" | cut -d: -f3)
    b3=$(echo "$mac" | cut -d: -f4)
    b4=$(echo "$mac" | cut -d: -f5)
    b5=$(echo "$mac" | cut -d: -f6)
    first=$(printf '%02x' $((0x$b0 ^ 2)))
    printf 'fe80::%s%s:%sff:fe%s:%s%s\n' "$first" "$b1" "$b2" "$b3" "$b4" "$b5"
}

cmd_probe() {
    iw=$(iw_bin) || die "no iw binary"
    log "iw=$iw"
    phy=$(find_wonder_phy) || die "wonder wiphy not present (module not started?)"
    log "wonder_phy=$phy"
    log "interface_modes=$("$iw" phy "$phy" info 2>/dev/null | sed -n '/Supported interface modes/,/^Band/p' | grep -oE '\* [A-Za-z_]+' | sed 's/\* //' | tr '\n' ' ')"

    log "netdevs:"
    for dev in "$SYS_NET"/*; do
        [ -e "$dev" ] || continue
        name=$(basename "$dev")
        case "$name" in
            lo|dummy*|ifb*|tunl*|gre*|gretap*|erspan*|ip6*|sit*|rmnet*|ovnet*|vgate*) continue ;;
        esac
        idx=$(cat "$dev/ifindex" 2>/dev/null || echo '?')
        addr=$(cat "$dev/address" 2>/dev/null || echo '?')
        phy_link=$(readlink -f "$dev/phy80211" 2>/dev/null || true)
        log "  $name ifindex=$idx mac=$addr phy=${phy_link:-none}"
    done

    if link_exists "$AWDL_IF"; then
        log "$AWDL_IF=present"
        cmd_status
    else
        log "$AWDL_IF=absent"
    fi
}

carrier_up() {
    grep -q '^1$' "$SYS_NET/$1/carrier" 2>/dev/null
}

wait_carrier() {
    # mac80211 runs two sw-scan rounds ("Trigger new scan to find an IBSS to
    # join") before it creates the network, which is why the budget here is ten
    # seconds rather than one. Measured on this phone: a down/up cycle of an
    # already-warm wiphy brings carrier back in 0.28 s, so the loop normally
    # leaves after two or three ticks; the long tail only shows up on a cold
    # wiphy, where the scan rounds really are seconds.
    i=0
    while [ "$i" -lt 100 ]; do
        if grep -q '^1$' "$SYS_NET/$1/carrier" 2>/dev/null; then
            return 0
        fi
        sleep 0.1
        i=$((i + 1))
    done
    return 1
}

cmd_up() {
    phy=$(find_wonder_phy) || die "wonder wiphy not present"
    iw=$(iw_bin) || die "no iw binary"

    if ! link_exists "$AWDL_IF"; then
        log "creating $AWDL_IF on $phy (type ibss)"
        if ! "$iw" phy "$phy" interface add "$AWDL_IF" type ibss 2>/dev/null; then
            # A second non-STATION vif is rejected while another one is active;
            # managed is the only type that still yields a usable netdev.
            log "ibss vif refused, trying managed"
            "$iw" phy "$phy" interface add "$AWDL_IF" type managed 2>/dev/null \
                || die "interface add failed"
        fi
    fi

    # Duplicate address detection would keep the link-local tentative for ~1s
    # and leave any server that binds straight after `up` without an address.
    # Nobody else answers NS on our own IBSS, so switch DAD off for this link.
    echo 0 > "/proc/sys/net/ipv6/conf/$AWDL_IF/accept_dad" 2>/dev/null || true

    # The four caches have to be filled while the link is still down. Doing it after
    # `up` is not the same thing: the setters take the cache-only path and return 0
    # when the tap is down, but once it is up they try to touch a radio that already
    # failed to initialise. wondertap_init() answers "Init failed: Missing cached
    # settings. flags=0x0" and the tap never leaves DOWN.
    if [ -x "$VEN" ]; then
        if ! "$VEN" "$AWDL_IF" feed "$AWDL_FREQ" "$AWDL_BSSID"; then
            log "wonder-ven feed failed; wondertap0 will not come up"
        fi
    else
        log "wonder-ven missing at $VEN; wondertap0 will not come up"
    fi

    # Bringing the link up is what triggers wonder_start(): the vendor creates
    # wondertap0, the RX handler gets registered and the channel is pinned.
    ip link set "$AWDL_IF" up || die "link up failed (see dmesg for wonder_start)"

    # IBSS carrier: a fresh vif is NO-CARRIER until it belongs to a network.
    # `fixed-freq` is load bearing -- without it mac80211 keeps re-scanning to
    # merge with other IBSS networks sharing the SSID ("No active IBSS STAs -
    # trying to scan for other IBSS networks with same SSID"), which takes the
    # radio off 5745 MHz every 30s.
    if carrier_up "$AWDL_IF"; then
        log "ibss already joined"
    else
        "$iw" dev "$AWDL_IF" ibss leave 2>/dev/null || true
        # fixed-freq keeps the radio on 5745; the BSSID argument joins Apple's
        # AWDL network instead of creating a private one with the same SSID.
        "$iw" dev "$AWDL_IF" ibss join "$AWDL_SSID" "$AWDL_FREQ" fixed-freq "$AWDL_BSSID" 2>/dev/null \
            || log "ibss join failed"
        wait_carrier "$AWDL_IF" || log "carrier still down after 10s"
    fi

    ll=$(link_local_for "$AWDL_IF" 2>/dev/null || true)
    if [ -n "$ll" ]; then
        # addrgenmode is eui64, so the kernel normally derives exactly this
        # address on its own; only add it when it is missing, otherwise the
        # replace would restart DAD and mark the address tentative again.
        if ! ip -6 addr show dev "$AWDL_IF" 2>/dev/null | grep -qF "inet6 $ll/"; then
            ip -6 addr add "$ll/64" dev "$AWDL_IF" 2>/dev/null \
                || log "link-local $ll not applied"
        fi
    fi
    # ff02::fb is joined automatically when IPv6 comes up on a multicast link;
    # only reach for it explicitly when it is missing, since a redundant add
    # fails with EEXIST.
    if ! ip -6 maddr show dev "$AWDL_IF" 2>/dev/null | grep -q 'inet6 ff02::fb'; then
        ip -6 maddr add ff02::fb dev "$AWDL_IF" 2>/dev/null \
            || log "mDNS group join failed"
    fi

    log "up ifindex=$(if_index "$AWDL_IF" || echo '?') linklocal=${ll:-none} ssid=$AWDL_SSID freq=$AWDL_FREQ"

    # `up` on an existing link only refreshes addressing, so the table it finds is
    # the one the previous runs left behind. Say so here rather than let a run
    # collect silent status3 and be read as a peer that will not answer.
    held=$(target_peers 2>/dev/null || true)
    if [ -n "${held:-}" ] && [ "$held" != 0 ]; then
        log "warning: driver already holds $held target slot(s); run 'awdl-link.sh reset' for a clean run"
    fi
}

cmd_down() {
    if link_exists "$AWDL_IF"; then
        ip link set "$AWDL_IF" down 2>/dev/null || true
        # `ip link del` is refused for mac80211 vifs on this kernel; the
        # nl80211 path behind `iw dev ... del` is the one that works.
        if iw=$(iw_bin 2>/dev/null); then
            "$iw" dev "$AWDL_IF" del 2>/dev/null || ip link del "$AWDL_IF" 2>/dev/null || true
        else
            ip link del "$AWDL_IF" 2>/dev/null || true
        fi
        link_exists "$AWDL_IF" && die "interface delete failed"
        log "deleted $AWDL_IF"
    else
        log "$AWDL_IF already absent"
    fi
}

cmd_status() {
    link_exists "$AWDL_IF" || { log "$AWDL_IF absent"; return 0; }
    log "ifindex=$(if_index "$AWDL_IF" || echo '?') mac=$(cat "$SYS_NET/$AWDL_IF/address" 2>/dev/null)"
    log "operstate=$(cat "$SYS_NET/$AWDL_IF/operstate" 2>/dev/null) carrier=$(cat "$SYS_NET/$AWDL_IF/carrier" 2>/dev/null)"
    iw=$(iw_bin 2>/dev/null || true)
    if [ -n "$iw" ]; then
        # toybox sed has no BRE alternation, so filter with grep -E.
        "$iw" dev "$AWDL_IF" info 2>/dev/null | grep -E 'ssid|type|channel' | sed 's/^[[:space:]]*/  /'
    fi
    ip -6 addr show dev "$AWDL_IF" 2>/dev/null | sed 's/^/  /'
}

cmd_table() {
    if [ -r "$TARGET_STATE" ]; then
        cat "$TARGET_STATE"
    else
        log "no target table at $TARGET_STATE (module name wrong, or not rooted?)"
    fi
}

# Down and up in one step, so the caller cannot pay for half of it. `down` alone
# is enough to clear the table -- stopping the vif runs opairshare_target_stop --
# but leaving the link down is never what an experiment wants, and `ip link set
# down` on its own also destroys the vendor's wondertap0 netdev that the data
# probes send through. Both are recreated by `up`.
#
# The cost is a new MAC: wondertap_init() calls eth_random_addr() every time, so
# the phone sees a new neighbour rather than a returning one. That is the price of
# a clean table, and it is why this is not done automatically on every `up` -- the
# data path has to have room for the address the phone hands out next.
cmd_reset() {
    before=$(target_peers 2>/dev/null || true)
    cmd_down
    cmd_up
    after=$(target_peers 2>/dev/null || true)
    log "target slots ${before:-?} -> ${after:-?}; mac now $(cat "$SYS_NET/$AWDL_IF/address" 2>/dev/null)"
}

# The action a measurement harness should use: leave the link in a state where a
# frame sent now can be acked. Two ways it is not, and they look the same from the
# outside -- the probe reports silence.
#
#   No interface. `ip link set wonder0 down` also takes the vendor's wondertap0
#   netdev with it, and the data probes send through that one, so a link left down
#   by an earlier experiment fails the next run before a frame is built.
#
#   No free slot. `up` is idempotent on an existing link, so it keeps whatever the
#   previous runs left in the driver's eight-slot table. A full table means every
#   frame goes out addressed to a receiver the firmware has no peer for.
#
# Resetting costs a new MAC and a down/up of the vif -- measured 0.5 s on this
# phone (0.22 s down, 0.28 s back to carrier), not the eight seconds this comment
# used to claim from a cold-boot measurement. Still not free: every reset spends
# the address the other phone knows us by, so the table is read first and the
# reset only happens when there is something to clear.
cmd_ensure() {
    if ! link_up "$AWDL_IF"; then
        state=$(cat "$SYS_NET/$AWDL_IF/operstate" 2>/dev/null || echo absent)
        log "$AWDL_IF is not carrying traffic (operstate=$state); bringing the link up"
        cmd_up
        return 0
    fi
    held=$(target_peers 2>/dev/null || true)
    if [ -z "${held:-}" ]; then
        log "no target table to inspect at $TARGET_STATE; leaving the link alone"
        return 0
    fi
    if [ "$held" = 0 ]; then
        log "target table empty; link left as is"
        return 0
    fi
    log "target table holds $held slot(s) -- frames to any other address would be transmitted to nobody; resetting"
    cmd_reset
}

case ${1:-} in
    probe) cmd_probe ;;
    up) cmd_up ;;
    down) cmd_down ;;
    reset) cmd_reset ;;
    ensure) cmd_ensure ;;
    status) cmd_status ;;
    table) cmd_table ;;
    *)
        echo 'Usage: awdl-link.sh probe|up|down|reset|ensure|status|table [ifname]'
        exit 2
        ;;
esac
