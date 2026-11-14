#!/system/bin/sh
# Brings the patched Wi-Fi stack up on this phone, and leaves a usable AWDL link.
#
# This is a subset of `legacy/module/bin/control.sh` — the KernelSU module that
# used to do this — with everything belonging to the old product removed: no
# `mosey_server`, no QuickShare/provider plumbing, no supervisor, no callback
# bootstrap. What is left is the part this app actually needs:
#
#   1. stop Wi-Fi, stop its HAL            (the driver cannot be replaced live)
#   2. rmmod qca -> provider -> patched qca -> patched mac80211 -> wonder
#   3. start the HAL, restore Wi-Fi
#   4. create wonder0 as an IBSS on 5745 MHz and join Apple's AWDL network
#
# Step 4 is `legacy/module/bin/awdl-link.sh`, inlined. Three facts from that
# script are load bearing and are repeated at the code that depends on them:
#
#   * the vif must be named `wonder0` — wonder.ko resolves its netdev by name
#     in wonder_start(), before add_interface() has run, so any other name dies
#     with ENODEV;
#   * it must be IFF_ADHOC — wonder_rx_handler() only forwards to mac80211 for
#     ADHOC, everything else falls through to RX_HANDLER_PASS and never reaches
#     wonder0 (the radio hears the frames, the interface counts zero);
#   * the IBSS must be joined with Apple's fixed BSSID — mac80211 otherwise
#     elects a random one and then drops every Apple frame at the addr3 check.
#
# Failure handling follows the same rule as the old script, for the same reason:
# once the driver swap has finished, a later failure must NOT roll the stack
# back. The swapped drivers are valid; rolling back there costs Wi-Fi for a
# problem that lives above them.
#
# Usage: awdl-stack.sh start | stop | status | link
#   start   full sequence; idempotent while the boot id matches
#   stop    unload and restore the factory drivers
#   status  what is loaded, what the link looks like
#   link    step 4 only (the drivers are already in place)

BASE=${BASE:-/data/local/tmp/opairdrop}
STATE=$BASE/state
AWDL_IF=wonder0
# wonder.ko pins the radio to 5745 in wondertap_init(); the IBSS join has to
# name the same frequency or mac80211 refuses the channel.
AWDL_FREQ=5745
AWDL_SSID=awdl0
AWDL_BSSID=00:25:00:ff:94:73
HAL=vendor.wifi_hal_legacy
PCI=/sys/class/net

boot_id() { cat /proc/sys/kernel/random/boot_id; }
loaded() { grep -q "^$1 " /proc/modules; }
same_boot() { [ -r "$1" ] && [ "$(cat "$1" 2>/dev/null)" = "$(boot_id)" ]; }
mark() { boot_id > "$STATE/$1"; }
say() { echo "$*"; }
die() { echo "ERROR: $*"; exit 1; }

mkdir -p "$STATE" && chmod 700 "$STATE"

# `cmd wifi status` prints "Wifi is enabled" / "Wifi is disabled". Both of these
# strings appear in the *other* one's prefix only in the case of "enabled" vs
# "disabled", which do not overlap — but the disabled test has to come first
# anyway, because "Wifi is disabled" is not a substring of "Wifi is enabled"
# and the reverse is also true. Order kept as a habit, not a requirement.
wifi_state() {
    text=$(cmd wifi status 2>/dev/null) || return 3
    case "$text" in
        *disabled*) echo 0 ;;
        *enabled*) echo 1 ;;
        *) return 3 ;;
    esac
}

wifi_wait() {
    want=$1
    n=0
    while [ "$n" -lt 20 ]; do
        now=$(wifi_state) || now=unknown
        [ "$now" = "$want" ] && return 0
        sleep 1
        n=$((n + 1))
    done
    say "WIFI_STATE_TIMEOUT want=$want now=$now"
    return 3
}

hal_state() { getprop init.svc.$HAL; }

pause_hal() {
    case "$(hal_state)" in
        running) mark hal-paused ;;
        stopped) return 0 ;;
        *) say "HAL in unexpected state '$(hal_state)'"; return 4 ;;
    esac
    stop $HAL
    n=0
    while [ "$(hal_state)" != stopped ] && [ "$n" -lt 10 ]; do sleep 1; n=$((n + 1)); done
    [ "$(hal_state)" = stopped ] || { say 'HAL did not stop'; return 4; }
    say 'HAL_STOPPED'
}

resume_hal() {
    same_boot "$STATE/hal-paused" || return 0
    start $HAL
    n=0
    while [ "$(hal_state)" != running ] && [ "$n" -lt 10 ]; do sleep 1; n=$((n + 1)); done
    [ "$(hal_state)" = running ] || { say 'HAL did not start'; return 4; }
    rm -f "$STATE/hal-paused"
    say 'HAL_RUNNING'
}

# Put the factory build back. Only called while the swap is half done or from
# `stop`; never after a successful swap in the same run.
restore_drivers() {
    if loaded wonder; then rmmod wonder; fi
    if same_boot "$STATE/qca-swapped"; then
        if loaded qca_cld3_peach_v2; then rmmod qca_cld3_peach_v2 || return 11; fi
        if loaded opairshare_wonder_provider; then rmmod opairshare_wonder_provider || return 12; fi
        if ! loaded qca_cld3_peach_v2; then
            insmod /vendor/lib/modules/qca_cld3_peach_v2.ko || return 13
        fi
        rm -f "$STATE/qca-swapped"
    elif same_boot "$STATE/provider-added"; then
        if loaded opairshare_wonder_provider; then rmmod opairshare_wonder_provider || return 12; fi
        rm -f "$STATE/provider-added"
    fi
    if same_boot "$STATE/mac-swapped"; then
        if loaded mac80211; then rmmod mac80211 || return 14; fi
        if ! loaded mac80211; then insmod /vendor/lib/modules/mac80211.ko || return 15; fi
        rm -f "$STATE/mac-swapped"
    fi
    resume_hal || return 4
    desired=$(cat "$STATE/wifi-before" 2>/dev/null || echo 1)
    case "$desired" in 0|1) svc wifi "$([ "$desired" = 1 ] && echo enable || echo disable)" ;; esac
    say 'STOCK_DRIVERS_RESTORED'
}

# The qca passthru arguments. Copied verbatim from control.sh (the values were
# tuned on this phone; every one of them is read by the patched driver).
load_stack() {
    rmmod qca_cld3_peach_v2 || { say 'cannot unload the factory qca driver'; return 10; }
    mark qca-swapped
    insmod "$BASE/opairshare_wonder_provider.ko" || return 11
    mark provider-added
    # turbo default: phy=2 (HT), mcs=7, A-MSDU 4 x 6000. `turbo-fast` in the old
    # module used mcs=4; measured 20.8% retransmits at mcs=8 vs 0.9% at mcs=4
    # when the STA shares the channel, so if throughput disappoints, this is the
    # knob — not the app.
    insmod "$BASE/qca_cld3_peach_v2.ko" \
        passthru_awdl_addba=1 passthru_awdl_native_rx=1 passthru_ht=1 passthru_fwtype=7 \
        passthru_mon_rings=0 passthru_dry_run=0 passthru_mon_chan_sync=1 passthru_ndi_peer=1 \
        passthru_ndi_no_up=1 passthru_mgmt_bridge=1 passthru_aux_monitor=1 passthru_tx_audit=1 \
        passthru_ndi_fw_authorize=0 passthru_awdl_wmi_raw=1 passthru_awdl_target_mac="" \
        passthru_awdl_target_auto=1 passthru_awdl_target_enable=1 passthru_awdl_target_auth=2 \
        passthru_native_data_rx=1 passthru_awdl_backpressure=1 passthru_awdl_raw_rx=1 \
        passthru_awdl_rate_mbps=54 passthru_awdl_host_seq=1 passthru_awdl_rx_dedup=1 \
        passthru_awdl_attempts=3 passthru_awdl_rx_auto=1 passthru_awdl_rx_rearm=1 \
        passthru_sta_coexist=3 passthru_sta_regular_rx=1 passthru_sta_action_rx=1 \
        passthru_pas_discovery_actions=1 \
        passthru_awdl_amsdu=2 passthru_awdl_amsdu_max=4 passthru_awdl_amsdu_bytes=6000 \
        passthru_awdl_phy=2 passthru_awdl_mcs=7 \
        || return 12
    rmmod mac80211 || return 13
    mark mac-swapped
    insmod "$BASE/mac80211.ko" || return 14
    insmod "$BASE/wonder.ko" physical_name=wondertap0 virtual_name=wonder0 || return 15
    say 'DRIVER_SWAP_DONE'
}

find_wonder_phy() {
    for anchor in "$AWDL_IF" wondertap0; do
        link="$PCI/$anchor/phy80211"
        [ -e "$link" ] && { basename "$(readlink -f "$link")"; return 0; }
    done
    for phy in /sys/class/ieee80211/*; do
        [ -e "$phy" ] || continue
        [ "$(cat "$phy/name" 2>/dev/null)" = wonder ] && { basename "$phy"; return 0; }
    done
    return 1
}

link_local_for() {
    mac=$(cat "$PCI/$1/address" 2>/dev/null) || return 1
    [ -n "$mac" ] || return 1
    set -- $(echo "$mac" | tr ':' ' ')
    first=$(printf '%02x' $((0x$1 ^ 2)))
    printf 'fe80::%s%s:%sff:fe%s:%s%s\n' "$first" "$2" "$3" "$4" "$5" "$6"
}

wait_carrier() {
    n=0
    while [ "$n" -lt 100 ]; do
        grep -q '^1$' "$PCI/$1/carrier" 2>/dev/null && return 0
        sleep 0.1
        n=$((n + 1))
    done
    return 1
}

link_up() {
    phy=$(find_wonder_phy) || { say 'wonder wiphy not present (did wonder.ko load?)'; return 1; }
    say "wonder_phy=$phy"
    if [ ! -e "$PCI/$AWDL_IF" ]; then
        iw phy "$phy" interface add "$AWDL_IF" type ibss 2>/dev/null \
            || iw phy "$phy" interface add "$AWDL_IF" type managed 2>/dev/null \
            || { say 'interface add failed'; return 1; }
        say "created $AWDL_IF"
    fi
    # DAD would keep the link-local tentative for ~1s and leave a server that
    # binds right after `up` without an address.
    echo 0 > "/proc/sys/net/ipv6/conf/$AWDL_IF/accept_dad" 2>/dev/null

    # The four vendor caches have to be filled while the link is still down:
    # after `up` the setters take the radio path against a tap that failed to
    # initialise, and wondertap0 never leaves DOWN.
    "$BASE/wonder-ven" "$AWDL_IF" feed "$AWDL_FREQ" "$AWDL_BSSID" \
        || { say 'wonder-ven feed failed; wondertap0 will not come up'; return 1; }

    ip link set "$AWDL_IF" up || { say 'link up failed (see dmesg for wonder_start)'; return 1; }
    say 'WONDER0_UP'

    if grep -q '^1$' "$PCI/$AWDL_IF/carrier" 2>/dev/null; then
        say 'ibss already joined'
    else
        iw dev "$AWDL_IF" ibss leave 2>/dev/null
        # fixed-freq is load bearing: without it mac80211 keeps rescanning to
        # merge with other IBSS networks of the same SSID, taking the radio off
        # 5745 every 30s.
        iw dev "$AWDL_IF" ibss join "$AWDL_SSID" "$AWDL_FREQ" fixed-freq "$AWDL_BSSID" 2>/dev/null \
            || say 'ibss join failed'
        wait_carrier "$AWDL_IF" || say 'carrier still down after 10s'
    fi

    ll=$(link_local_for "$AWDL_IF" 2>/dev/null)
    if [ -n "$ll" ]; then
        ip -6 addr show dev "$AWDL_IF" 2>/dev/null | grep -qF "inet6 $ll/" \
            || ip -6 addr add "$ll/64" dev "$AWDL_IF" 2>/dev/null
    fi
    ip -6 maddr show dev "$AWDL_IF" 2>/dev/null | grep -q 'inet6 ff02::fb' \
        || ip -6 maddr add ff02::fb dev "$AWDL_IF" 2>/dev/null
    say "AWDL_LINK_UP iface=$AWDL_IF freq=$AWDL_FREQ ll=${ll:-none}"
}

cmd_status() {
    say "kernel=$(uname -r) boot=$(boot_id)"
    say "wifi=$(wifi_state || echo unknown) hal=$(hal_state)"
    for m in opairshare_wonder_provider qca_cld3_peach_v2 mac80211 wonder; do
        if loaded "$m"; then say "module $m=loaded"; else say "module $m=absent"; fi
    done
    if [ -e "$PCI/$AWDL_IF" ]; then
        say "iface $AWDL_IF mac=$(cat "$PCI/$AWDL_IF/address") operstate=$(cat "$PCI/$AWDL_IF/operstate" 2>/dev/null) carrier=$(cat "$PCI/$AWDL_IF/carrier" 2>/dev/null)"
        iw dev "$AWDL_IF" info 2>/dev/null | grep -E 'ssid|type|channel'
        ip -6 addr show dev "$AWDL_IF" 2>/dev/null | grep inet6
    else
        say "iface $AWDL_IF=absent"
    fi
}

cmd_start() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    for m in wonder opairshare_wonder_provider; do
        loaded "$m" && die "unexpected live module $m"
    done

    if same_boot "$STATE/stack-boot"; then
        # The patched stack is already live in this boot. Any failure from here
        # on is recoverable and must not touch the drivers.
        say 'stack already swapped this boot'
        link_up || true
        cmd_status
        return 0
    fi

    wifi_before=$(wifi_state) || die 'cannot read Wi-Fi state'
    echo "$wifi_before" > "$STATE/wifi-before"
    mark stack-boot
    say "WIFI_BEFORE=$wifi_before"

    # Our own receiver probes the link; leaving it running across a HAL restart
    # leaves it holding a socket on an interface that is about to disappear.
    am force-stop com.oshare.airdrop
    svc wifi disable
    wifi_wait 0 || { resume_hal; rm -f "$STATE/stack-boot"; die 'Wi-Fi did not disable'; }
    pause_hal || { rm -f "$STATE/stack-boot"; die 'HAL would not stop'; }

    if ! load_stack; then
        rc=$?
        say "DRIVER_SWAP_FAILED rc=$rc"
        restore_drivers || true
        rm -f "$STATE/stack-boot"
        return "$rc"
    fi

    # From here the drivers are valid; a failure below is not a driver problem.
    resume_hal || true
    case "$wifi_before" in 1) svc wifi enable ;; esac
    wifi_wait "$wifi_before" || say 'Wi-Fi did not come back to the previous state'

    link_up || say 'link did not come up; the app will report no-vif'
    cmd_status
}

cmd_stop() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    if [ -e "$PCI/$AWDL_IF" ]; then
        ip link set "$AWDL_IF" down 2>/dev/null
        iw dev "$AWDL_IF" del 2>/dev/null || ip link del "$AWDL_IF" 2>/dev/null
    fi
    svc wifi disable
    wifi_wait 0 || true
    pause_hal || true
    restore_drivers || say "restore reported rc=$?"
    rm -f "$STATE/stack-boot"
    cmd_status
}

case "${1:-status}" in
    start) cmd_start ;;
    stop) cmd_stop ;;
    link) link_up; ;;
    status) cmd_status ;;
    *) echo 'Usage: awdl-stack.sh start|stop|link|status'; exit 2 ;;
esac
