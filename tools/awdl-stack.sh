#!/system/bin/sh
# Brings the patched Wi-Fi stack up on this phone, and leaves a usable AWDL link.
#
# This is a subset of `legacy/module/bin/control.sh` — the KernelSU module that
# used to do this — with everything belonging to the old product removed: no
# `mosey_server`, no QuickShare/provider plumbing, no supervisor, no callback
# bootstrap. What is left is the part this app actually needs:
#
#   1. stop Wi-Fi                          (the driver cannot be replaced live)
#   2. rmmod qca -> provider -> patched qca -> patched mac80211 -> wonder
#   3. create wonder0 as an IBSS and join Apple's AWDL network — always on the
#      social channel, 149, because that is the only frequency the driver will
#      initialise a tap on (see AWDL_FREQ below)
#   4. put Wi-Fi back if this run was asked to share
#
# Steps 2 and 3 both happen with the vendor HAL stopped; see "When the HAL has to
# be stopped" below for why, and why step 4 is last rather than second.
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
# Usage: awdl-stack.sh start | boot [name] | stop | status | link | share | exclusive
#        | watch | guard
#   start      full sequence; idempotent while the boot id matches
#   boot       the same sequence, for the app to run on itself (no `am
#              force-stop`, daemon started at the end, receipt written to
#              the app's own directory as `stack-state`); `name` is passed
#              to the daemon as `--name`, default its hostname
#   stop       unload and restore the factory drivers
#   status     what is loaded, what the link looks like
#   link       step 4 only (the drivers are already in place)
#   share      give the radio back to Wi-Fi without touching the link
#   exclusive  take it away again (the default state after `start`)
#   watch      start the freeze guard if it is not running (idempotent, returns
#              at once); this is what the app calls when listening is turned on
#   guard      the guard loop itself; started by `watch`/`boot`, not typed
#
# `start` and `boot` differ in exactly two places, and both come from who the
# caller is. `start` is typed by an operator with a shell: it begins with
# `am force-stop com.oshare.airdrop`, because a receiver left running holds a
# socket on an interface that is about to be recreated. `boot` is that app asking
# for itself, where the same line would kill the caller mid-sentence -- so it
# takes down what is holding the interfaces (`awdld`) and leaves the process
# alone. And `boot` ends by starting the daemon and writing a receipt, because
# its caller cannot see stdout.
#
# When the HAL has to be stopped, and when it does not (measured 2026-11-14).
#
# `legacy/module/bin/control.sh` stopped `vendor.wifi_hal_legacy` before the whole
# swap. Half of that turned out to be unnecessary and half turned out to be load
# bearing, and the two halves fail in ways that look alike, so both are recorded
# here with the numbers.
#
# It is *not* needed for the modules that carry Wi-Fi. With Wi-Fi off and the HAL
# `running` the whole time, the cycle below completed rc=0 at every step and the
# phone associated afterwards:
#
#   rmmod qca_cld3_peach_v2   rc=0
#   insmod provider/qca/mac80211   rc=0 0 0
#   svc wifi enable           station associated, channel 149 (5745)
#
# It *is* needed before `insmod wonder.ko`. With the HAL running, `wonder_probe`
# binds to the provider and then fails to register its mac80211 hardware:
#
#   [wonder][mac80211] Set MAC addrs from wlan0: ...
#   [wonder][mac80211] Failed to register mac80211 hardware. Ret: -12
#
# `insmod` still exits 0 and the module still appears in /proc/modules, so the
# only symptom is that neither netdev exists -- and `link_up` then reports
# "wonder wiphy not present (did wonder.ko load?)" about a module that is loaded.
# It is deterministic, not a race: reproduced on two consecutive loads with the
# HAL up (t=35360 and t=35446), and the same load with the HAL `stopped` binds:
#
#   platform qcom,cnss-vendor-wlan-wonder: vendor wonder bound to master device wonder
#   wonder wonder: wonder_master_bind(): Connected to wlan ver 5 (cur: wonder ver 7)!
#
# So the HAL is stopped in one place only -- immediately before `insmod wonder` --
# and resumed by `cmd_start`/`cmd_boot` once the link is up. The `rmmod` gate below
# still probes the HAL as a fallback, because a refusal there and a refusal before
# `wonder` have the same cause and the same cure.
#
# The stop does *not* cover the first bring-up of `wonder0`, which is worth saying
# because it looks like it does. `ip link set wonder0 up` is the first thing that
# runs `wondertap_init`, and the very first attempt after a swap is refused:
#
#   RTNETLINK answers: Operation not supported on transport endpoint
#
# Measured 2026-11-14 on cold `boot` runs, and the attribution went wrong twice
# before the log settled it. With the HAL up, the error arrives after
# `HAL_RUNNING` and `wondertap0` never appears, so the daemon has no tunnel
# (`awdld: cannot read wondertap0's address`) -- and that reads as "the HAL is
# holding the tap". It is not: the same failure happens with the HAL `stopped`
# the whole time, which is the configuration `cmd_boot` actually runs in. What
# distinguishes the two cases is dmesg -- on failure there is not a single wonder
# line, not even `HW started.`, so the request never reached `wonder_start`;
# mac80211/cfg80211 turned it away. On the attempt that succeeds, the log runs
#
#   OPAIRSHAREAUXREADY: rx=opairsharerx0 vdev=1 target=wondertap0 freq=5745
#   Vendor init successful. State set to UP.
#
# i.e. the provider had registered its vendor ops by then. There is no readable
# flag for that (`wonder_master_bind(): Connected to wlan ver 5` is log-only), so
# `link_up` retries six times three seconds apart and treats the retry as the
# test. Every cold `boot` since has needed exactly one retry. The same lesson is
# in `legacy/module/bin/control.sh`: its `awdl_link_up` allows 20 attempts because
# "the first failure after the swap is normal".
#
# Order was also ruled out as the cause. A `HAL_FIRST` build that resumed the HAL
# before `link_up` failed identically at the first attempt -- and then never got
# to the comparison at all, because `insmod wonder` started returning -12. That
# is a second finding, and it is not about the HAL either: the -12 came with
# `warn_alloc` / `__alloc_pages_slowpath` next to `ieee80211_txq_setup_flows+0x1e8`
# in the trace, so `ieee80211_register_hw` can fail for want of memory even with
# the HAL down. Four retries at three seconds were not enough on that run; the
# loop in `load_stack` now allows six with a backoff and reports free memory.

BASE=${BASE:-/data/local/tmp/opairdrop}
STATE=$BASE/state
AWDL_IF=wonder0

# PHY class for the AWDL data frames.
#
# `passthru_awdl_phy` is not a diagnostic knob: it becomes
# `tx_param.preamble_type = BIT(phy)` on every data frame. It used to be pinned
# to 2 (HT) for every profile, including the 80 MHz one, on the belief that 2
# meant VHT. It does not; see below. The value now follows the bandwidth,
# because an HT preamble on an 80 MHz vdev is not a legal rate class.
#
# 2026-11-22: that caveat IS the wide profile's default now, not a fallback.
# `passthru_awdl_phy` is a WMI_RATE_PREAMBLE index -- 0 OFDM, 1 CCK, 2 HT,
# 3 VHT, 4 HE -- NOT the wondertap enum (0 LEGACY, 1 HT, 2 VHT, 3 HE, 4 EHT).
# The two scales coincide only at 2 and 3, which is why `2` looked like VHT and
# shipped as HT for so long. HT caps at 40 MHz and at a 4095-octet MPDU, so an
# 80 MHz vdev transmitting HT preambles cannot carry the 7935-octet A-MSDUs
# `amsdu_bytes` asks for: the oversize ones are exactly the frames that show up
# as `followers_dropped`. VHT is the first preamble class that is both legal on
# 80 MHz and allowed to carry an MPDU that large.
#
# EHT is still not reachable from here. `preamble_type` is a 5-bit field
# (WMI_TX_SEND_PARAM_PREAMBLE_SET(dword1, 15, 5)) and EHT needs BIT(5), which
# masks to zero -- "firmware picks", reported as success. The only EHT channel
# is wmi_vdev_param_fixed_rate (preamble at bit 8), see
# wlan_hdd_wondertap.c::opairshare_wt_set_fixed_rate().
#
# The four knobs below are one decision, not four. VHT on an HT20 vdev is not a
# legal rate class any more than HT is on an 80 MHz one, and 11454 octets is the
# *VHT* A-MSDU ceiling -- an HT vdev cannot carry it. So they follow the same
# `$STATE/wide` switch the join and `passthru_ht` follow, and the narrow profile
# stays byte-identical to the 3.24 MB/s baseline it was measured on.
if [ -f "$STATE/wide" ]; then
    # Wide: 80 MHz join, VHT preamble, VHT A-MSDU ceiling.
    AWDL_PHY_DEF=3
    AWDL_MCS_DEF=9
    # Overridable because the two numbers are a *build* property, not a profile
    # one: `passthru_awdl_amsdu_bytes` is range-checked by the module itself and
    # its ceiling is `OPAIRSHARE_AMSDU_BYTES_MAX`. The overlay source raised that
    # to 11454, but the shipped bytes in `kernel/*.ko.gz.bak` still carry
    # the 7935 HT ceiling, and passing 11454 to them fails `insmod` outright
    # (`failed to load …: Invalid argument`, rc=12 at `driver-swap`).
    # So a wide-join test on shipped bytes must pin these two to the values the
    # module accepts: `AMSDU_MAX=5 AMSDU_BYTES=7935 … awdl-stack.sh boot`.
    AMSDU_MAX=${AMSDU_MAX:-8}
    AMSDU_BYTES=${AMSDU_BYTES:-11454}
    # The vdev's FIXED TX RATE stays HT20 / 1 SS / MCS7 in the wide profile too,
    # and that is not an oversight -- it is the only shape a peer in its
    # non-transfer state can decode.
    #
    # 2026-11-22 05:30 measured the alternative. With this line at 2/2/1/2/9
    # (VHT80 / 2 SS / MCS9) the two phones stopped seeing each other entirely:
    # the daemon logged rx=0p/0B while `psf`, `peer_map` and `other` kept
    # climbing, i.e. the receive path still heard the iPhone's presence frames
    # but the iPhone could not decode a single one of ours. Its slot map at that
    # moment was 8 slots on 149 and 1 on 6 -- the non-transfer shape -- and a
    # receiver holding 20 MHz cannot demodulate an 80 MHz PPDU.
    #
    # This also explains why the 4.25 MB/s baseline ever worked: `wonder-ven feed`
    # hardcoded 1/0/1/1/7 and nothing overrode it, so `wmi_vdev_param_chwidth`
    # quietly clamped the "80 MHz" vdev back to 20 MHz at start-up. That bug was
    # load bearing. Fixing it without this line is what made us disappear.
    #
    # So the width has to be re-introduced the way AWDL itself does it: stay at
    # 20 MHz while discovering, widen once the peer enters its transfer state
    # (slot map ~15x149 + 1x6). Until that two-phase switch exists in the driver,
    # the wide profile buys only the A-MSDU ceiling, not the radio width.
    #
    # Override per-run to retest a wider rate: `VEN_PRE=2 VEN_BW=2 VEN_NSS=2
    # VEN_MCS=9 tools/awdl-stack.sh boot`.
    VEN_PRE_DEF=1; VEN_BW_DEF=0; VEN_GI_DEF=1; VEN_NSS_DEF=1; VEN_MCS_DEF=7
else
    # Narrow: HT20 join, HT preamble, HT A-MSDU ceiling.
    AWDL_PHY_DEF=2
    AWDL_MCS_DEF=7
    AMSDU_MAX=${AMSDU_MAX:-5}
    AMSDU_BYTES=${AMSDU_BYTES:-7935}
    VEN_PRE_DEF=1; VEN_BW_DEF=0; VEN_GI_DEF=1; VEN_NSS_DEF=1; VEN_MCS_DEF=7
fi

# A/B knob for the data-frame rate class, same shape as VEN_* below.
#
# This exists because the wide profile's VHT request is the only untested step
# between the known-good shape and a firmware hang (2026-11-22 10:53,
# `Received firmware hang event`, mhi `cause=7003`, 0.9 s after the IBSS came
# up). Everything else in the wide profile -- EHT80 vdev, 2 SS, bw_mask=80 MHz
# -- was already in place on 2026-11-20 and survived, so the preamble class is
# the variable. `AWDL_PHY=2 AWDL_MCS=7 tools/awdl-stack.sh boot` puts the rate
# class back to HT while keeping the wide join and A-MSDU ceiling, which
# separates "VHT preamble crashes firmware" from "80 MHz join crashes firmware"
# without a rebuild.
AWDL_PHY=${AWDL_PHY:-$AWDL_PHY_DEF}
AWDL_MCS=${AWDL_MCS:-$AWDL_MCS_DEF}

# The vdev's fixed TX rate, in `wonder-ven rate` terms. This is a SECOND enum
# family from AWDL_PHY above and must not be confused with it:
#
#   wonder-ven rate : preamble LEGACY=0 HT=1 VHT=2 HE=3 EHT=4
#   passthru_awdl_phy (WMI_RATE_PREAMBLE): OFDM=0 CCK=1 HT=2 VHT=3 HE=4 EHT=5
#
# They only coincide at 2 and 3, which is exactly why `phy=2` spent so long
# looking like VHT when it was HT. The narrow defaults below are not a guess:
# they are what `wonder-ven feed` already hardcodes, so leaving them in place
# keeps the narrow profile byte-identical to the measured 3.24 MB/s baseline.
#
# Overridable from the environment so a slower knob can be tried without editing
# the file: `VEN_PRE=4 tools/awdl-stack.sh boot`. EHT is deliberately not the
# default -- see the long note at the call site, the vdev is created in chanmode
# VHT80 and a preamble above the vdev's own mode has no rate table to draw from.
VEN_PRE=${VEN_PRE:-$VEN_PRE_DEF}
VEN_BW=${VEN_BW:-$VEN_BW_DEF}
VEN_GI=${VEN_GI:-$VEN_GI_DEF}
VEN_NSS=${VEN_NSS:-$VEN_NSS_DEF}
VEN_MCS=${VEN_MCS:-$VEN_MCS_DEF}

# Does our ADDBA Response advertise A-MSDU Supported?  (`BIT(0)` of the BA
# Parameter Set, which upstream writes as a hard 0.)
#
# This is the receive direction's only lever and it is not bandwidth-dependent,
# so it is not inside the wide/narrow branch. A peer will not put more than one
# MSDU in an MPDU towards a receiver that said it cannot take them -- that is
# the whole 3.42 MB/s -> 13.00 MB/s step in the reference driver, and it is why
# receive sits still no matter how wide the channel is.
#
# Claims like this are safe to test because the REO deaggregates in hardware:
# dp_rx sees an nbuf chain with one MSDU per buffer, not an A-MSDU. Override
# with `BA_AMSDU=0 tools/awdl-stack.sh boot` to get the old byte-for-byte path.
BA_AMSDU=${BA_AMSDU:-1}
# The frequency to feed the tap and join the IBSS on. It is not a variable: it is
# always the social channel, and `AWDL_FREQ` can only produce a complaint.
#
# Earlier versions of this file spent a lot of words on "follow the STA" and
# treated 5745 as one choice among many. It is not a choice. The driver refuses
# every other frequency: `opairshare_pas_rx_fixed_channel()` in
# `legacy/kernel/driver/wma_opairshare_pas_mgmt.h` returns -EOPNOTSUPP unless the
# requested frequency is exactly 5745, and the comment above that check ends the
# discussion --
#
#     /* Fixed149 profile: this does not establish general hopping support. */
#
# It refuses inside `wonder_start`, i.e. *after* `wonder-ven feed` has already
# answered ok, so a bad frequency looks like a successful feed followed by an
# inexplicable `ip link set wonder0 up` failure ("Operation not supported on
# transport endpoint"). dmesg has the rest: `OPAIRSHARERXAUTO: unsupported
# request freq=5220 ... bandwidth_enum=0` then `Vendor init failed: -95`.
#
# The old "follow the STA" story came from three ping runs -- STA and tap both on
# 5745: 10% loss; STA roamed to 5220 with the tap left on 5745: 72% loss; Wi-Fi
# off, radio exclusive: 0% -- and it read the middle row as "followed, but
# lossy". Nothing was followed. Measured on this phone 2026-11-14, with `awdld`
# live, three ways of moving a link to 5220 all failed the same way: retuning in
# place, `iw dev del` + `interface add` on the new frequency, and `rmmod wonder`
# followed by a fresh `insmod wonder.ko physical_name=wondertap0
# virtual_name=wonder0`. Each one answered -EOPNOTSUPP, and the first two also
# took `wondertap0` and `awdld` down with them and brought the link back on a new
# MAC. So the 72% was the price of the STA going away, not the price of chasing
# it, and there was never a code path that could have chased it.
#
# What is left is honest and smaller: the tap sits on 149, the daemon announces
# 149, and the only thing that varies is whether wlan0 is also on the radio. That
# is `KEEP_WIFI` below, and it costs no reconfiguration at all -- see the numbers
# there.
#
# The `:-` is load bearing: a bare `AWDL_FREQ=` reads as "empty is the default" and
# silently wipes the environment. Found by trying it -- the override this file
# used to document, `AWDL_FREQ=5220 awdl-stack.sh link`, printed
# `link_freq=5745 source=no-sta` and the radio never moved. The override is now
# inert by design, but the assignment is still guarded so that passing one gets a
# message instead of a shrug.
AWDL_FREQ=${AWDL_FREQ:-}
SOCIAL_FREQ=5745

# Whether `start` puts Wi-Fi back the way it found it before bringing the link up.
#
# It used to, unconditionally, which made the tap share the radio with the STA.
# Three runs of this binary against the same peer, 40 pings each at 0.2 s:
#
#   STA on 5745, tap on 5745 (same channel)        10% loss, min RTT   7 ms
#   STA roams to 5220 mid-run, tap still on 5745   72% loss, phy=44! in STAT
#   Wi-Fi off, radio exclusive to the tap           0% loss, min RTT  24 ms
#
# No row here can be blamed on the tap failing to follow, because it cannot
# follow; see the AWDL_FREQ block above. What the rows actually measure is how
# much of the radio the STA is taking. Same AP, so same channel: 10%. Different
# APs on one SSID, phone picking by signal: 72%.
#
# Which leaves the question this variable answers -- is the phone online or not
# -- and the good news is that it is a hot switch: measured on 2026-11-14 with
# one `awdld` and one peer, `ping6 -c 20 -i 0.2`, in three consecutive phases:
#
#   exclusive (Wi-Fi off)                     20/20,  0% loss, rx=96p
#   `svc wifi enable`, STA landed on 5220     11/20, 45% loss, rx=133p
#   `svc wifi disable`                        20/20,  0% loss, rx=181p
#
# Nothing was rebuilt across those phases: tap ifindex stayed 141, `awdld` stayed
# up, the tunnel stayed up. So sharing does not cost a teardown, a new MAC, or a
# dropped daemon -- it costs coexistence, which is 45% here because 5220 is two
# bands of separation away from 149, and would be near 10% against an AP on 149.
#
# Left empty rather than defaulted to 0 so that "the caller said nothing" and
# "the caller said 0" stay different statements: the first follows whatever the
# app's switch was last left at, the second means exclusive regardless of it.
KEEP_WIFI=${KEEP_WIFI:-}

AWDL_SSID=awdl0
AWDL_BSSID=00:25:00:ff:94:73
HAL=vendor.wifi_hal_legacy
PCI=/sys/class/net

boot_id() { cat /proc/sys/kernel/random/boot_id; }
# The first group of the UUID, plus a fallback word. The receipt carries this so
# the app can tell "the stack is up" from "the stack was up last time the phone
# was on": `files/stack-state` survives a reboot, so without a boot name a cold
# phone shows the previous run's `daemon=` and calls itself ready.
boot_short() { boot_id 2>/dev/null | cut -d- -f1 | grep . || echo unknown; }
loaded() { grep -q "^$1 " /proc/modules; }
same_boot() { [ -r "$1" ] && [ "$(cat "$1" 2>/dev/null)" = "$(boot_id)" ]; }
mark() { boot_id > "$STATE/$1"; }
say() { echo "$*"; }
die() { echo "ERROR: $*"; exit 1; }

mkdir -p "$STATE" && chmod 700 "$STATE"

# The app that owns the tunnel is also the app that owns the radio switch. Its
# private directory is `drwx------` and one uid wide, so the kernel answers "who
# may ask for the radio" without this script or the daemon inventing a policy:
# uid 10415 and root, nothing else.
#
# Fixed rather than configurable for the same reason `--uid` defaults to it --
# the IPv6 rule for the tunnel is keyed on that number, and a script pointed at
# a different package would quietly ask for a different phone's radio.
APP=com.oshare.airdrop
app_dir() { echo "/data/data/$APP/files"; }
app_uid() { stat -c %u "/data/data/$APP" 2>/dev/null; }
ctl_req() { echo "$(app_dir)/radio-share"; }
ctl_ack() { echo "$(app_dir)/radio-state"; }

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

# ---------------------------------------------------------------------------
# Keeping the receiving hook schedulable.
#
# The half of the bridge that turns an incoming offer into a dialog lives inside
# `com.coloros.oshare`. That process has no visible component of its own: we
# bring it up with `am startservice`, the service returns within a second, and
# the process falls straight to `oom_score_adj=905` with `cpuset=/background`.
# This ROM freezes anything at or above `freezer_cutoff_adj` (900) into
# `cgroup.freeze`, and a frozen process is not scheduled even when a socket it
# owns has data waiting -- `recvfrom` never returns, the bytes sit in
# `rx_queue`, and the app hears nothing until the sender gives up 30s later.
#
# Measured on this phone: with the hook frozen, every published event was
# written, acknowledged by the kernel, and then delivered minutes later or not
# at all; the three probe runs all ended `503` at 30.0s. `cmd deviceidle
# whitelist +com.coloros.oshare` did not prevent it (whitelisted, still frozen).
# With `cached_apps_freezer disabled`, the same event arrived in the same
# millisecond it was written and the dialog came up, and the probes turned into
# `503` at 8.9s (refused by the user) and `200 OK` at 8.8s (accepted).
#
# So this is not tuning, it is a prerequisite of the same kind as `svc wifi
# disable`: a system-wide resource the share session takes for as long as it
# lasts, and hands back when it ends.
FREEZER_KEY=cached_apps_freezer

freezer_state() {
    settings get global "$FREEZER_KEY" 2>/dev/null || echo null
}

freezer_off() {
    prev=$(freezer_state)
    # First writer wins. A second `boot` in the same session must not overwrite
    # the user's original value with the `disabled` we put there ourselves.
    [ -e "$STATE/freezer-before" ] || echo "$prev" > "$STATE/freezer-before"
    [ "$prev" = disabled ] && return 0
    if settings put global "$FREEZER_KEY" disabled 2>/dev/null; then
        say 'FREEZER_OFF'
        return 0
    fi
    say 'freezer could not be disabled; incoming offers may not reach the hook'
    return 1
}

freezer_restore() {
    prev=$(cat "$STATE/freezer-before" 2>/dev/null)
    rm -f "$STATE/freezer-before"
    case "$prev" in
        enabled|disabled|default) settings put global "$FREEZER_KEY" "$prev" 2>/dev/null ;;
        null|'') settings delete global "$FREEZER_KEY" 2>/dev/null ;;
    esac
    say 'FREEZER_RESTORED'
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

# Free memory in kB, for the `insmod wonder` retry: `-12` has two causes and the
# number separates them. Silent on failure -- it is a diagnostic, not a gate.
mem_avail_kb() {
    sed -n 's/^MemAvailable: *\([0-9][0-9]*\) kB/\1/p' /proc/meminfo 2>/dev/null
}

# Largest free block in the Normal zone as a buddy order (0 = 4 KB or nothing).
#
# This, not `MemAvailable`, is the number that decides whether `insmod wonder`
# can register: see `defrag_memory`.
mem_max_order() {
    awk '/zone +Normal/ { for (i = NF; i > 4; i--) if ($i != 0) { print i - 5; exit } }' \
        /proc/buddyinfo 2>/dev/null
}

# Makes contiguous memory available before `insmod wonder`.
#
# `ieee80211_txq_setup_flows()` allocates `fq->flows` as a single block (8192
# flows, ~1 MB, i.e. order 8 on this 4K-page kernel) and returns -ENOMEM when
# the buddy lists hold nothing that large. Measured 2026-11-17 on the Normal
# zone, with 6 GB MemAvailable and 4.7 GB of it page cache:
#
#     129329 63105 7962 930 21 4 1 0 0 0 0      <- orders 0..10, order 6+ empty
#
# Six `insmod` attempts, six seconds of backoff and a stopped HAL later, every
# one still failed with `ieee80211_txq_setup_flows+0x1e8` under
# `ieee80211_register_hw` -- so the retry was treating a fragmentation problem
# as a transient one. Dropping the clean page cache and asking for compaction
# took the same zone to
#
#     180383 117747 51894 19149 8695 3081 708 151 86 61 78
#
# (86 blocks at order 8, 78 at order 10) and the next `insmod` registered its
# wiphy on the first attempt. Hence this runs before the first attempt instead
# of being hoped for between attempts.
#
# Both writes are global but harmless: drop_caches drops only clean cache,
# compact_memory migrates only movable pages. Best effort -- `insmod` decides.
defrag_memory() {
    sync
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
    echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
    # The sysfs write runs compaction synchronously; the pause is what the
    # measurement above was taken with, and costs one second of a cold path.
    sleep 1
}

# Stops the vendor Wi-Fi HAL and waits for it to be stopped.
#
# Called for two different reasons, which is why it is not named after one of
# them: it is a deliberate step before `insmod wonder` (see the header -- the
# module will not register its mac80211 hardware while the HAL is up), and it is
# a best-effort cure inside the `rmmod` gate, where the caller ignores the answer
# and lets the second `rmmod` decide.
#
# The state is read rather than assumed. An empty answer means `getprop` could
# not see the property -- the shell context cannot, root can -- and that is
# *unreadable*, not *absent*; stopping a service init does not know is a message
# from `stop` and a wasted second. Returning failure there lets the deliberate
# caller try anyway (`insmod` is the real judge) and the best-effort one shrug.
stop_hal() {
    case "$(hal_state)" in
        stopped) say 'HAL already stopped'; mark hal-paused; return 0 ;;
        running) ;;
        *) say "HAL state unreadable ('$(hal_state)'); not stopping it"; return 1 ;;
    esac
    stop $HAL
    n=0
    while [ "$(hal_state)" != stopped ] && [ "$n" -lt 10 ]; do sleep 1; n=$((n + 1)); done
    [ "$(hal_state)" = stopped ] || { say 'HAL did not stop'; return 1; }
    mark hal-paused
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
        if loaded qca_cld3_peach_v2; then
            # Same gate as `load_stack`, in the other direction: refused once
            # means something is holding it, and the HAL is the only plausible
            # something. `stop` used to stop the HAL unconditionally up front,
            # which made this the one branch that could never be reached here.
            rmmod qca_cld3_peach_v2 || {
                stop_hal || true
                rmmod qca_cld3_peach_v2 || return 11
            }
        fi
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
    if ! rmmod qca_cld3_peach_v2; then
        # The gate. Everything the swap needs is "the factory module is gone", and
        # that is asked directly instead of being arranged for ahead of time --
        # the header has the measurement: a refusal here is the HAL, and a stop
        # plus one retry is the whole cure.
        say 'qca is still held; stopping the HAL and retrying once'
        stop_hal || true
        rmmod qca_cld3_peach_v2 || { say 'cannot unload the factory qca driver'; return 10; }
    fi
    mark qca-swapped
    insmod "$BASE/opairshare_wonder_provider.ko" || return 11
    mark provider-added
    # --- the throughput tuning set, 2026-11-18 -----------------------------------
    #
    # This block is the measured best, not a guess. Same 24.7 MB video, same phone,
    # same receiver, 1-second granularity on `awdltun0/tx_bytes`:
    #
    #   2.08 MB/s   A-MSDU 4x6000, nss=1, capacity=32
    #   2.25 MB/s   A-MSDU 5x7935, nss=2, capacity=32
    #   2.59 MB/s   A-MSDU 5x7935, nss=2, capacity=256   <- this file
    #   3.24 MB/s   the same run, steady state (last 6 s); peak 3.48
    #
    # The single change between the last two rows was capacity 32 -> 256 /
    # lowmark 16 -> 128, and it bought +15% average / +30% steady. It looks
    # backwards -- a deeper queue on a 17 ms link -- but the queue was not
    # buffering, it was throttling: `wma_opairshare_pas_mgmt.h:1403` stops the
    # flow once `opairshare_bridge_pending >= passthru_awdl_capacity`, and at 32
    # that put the TCP congestion window at ~34 segments (2.2 MB/s x 20.6 ms /
    # 1386 B). Window headroom was 89 KB, i.e. 5.0 MB/s. The queue, not the air,
    # was the ceiling.
    #
    # nss=2 is what the 3.2 MB/s snapshot actually ran (`opairshare_tx_pack_stats`
    # printed `nss=2`); this file had never set it, so every measurement before
    # 2026-11-18 was single-stream and half the airtime. `phy=2` (HT) is what makes
    # the second stream legal, and `iw ... ibss join ... HT20` below is what makes
    # phy=2 reachable -- a NOHT vdev silently pins the rate to the highest legacy
    # mandatory rate, 24 Mb/s.
    #
    # A-MSDU 5 x 7935: 7935 is the largest value the driver accepts, and the 5th
    # slot only fits because the PPDU is HT (`:477` — a non-HT PPDU may not carry
    # an MPDU above 2346 octets). Before the HT20 join `p5` was 0; after it, 5878
    # packets. Bigger is *not* automatically better: 4x6000 gives 4150 B per
    # aggregate at 27.8 ms, 5x7935 gives 5641 B at 41.5 ms on a NOHT link, which is
    # why the pair only pays off together with HT20 (now 17.2 ms at 5x7935).
    #
    # collect_us turns the aggregation timer on. At 0
    # `opairshare_collect_delay_locked()` returns on its first line, so an A-MSDU
    # ships only when the ring happens to fill: 3.6 msdu/agg, `deferrals` 0. At
    # 1500 the timer defers and the average went to 4.54 msdu/agg.
    #
    # It is now 2000 because the driver clamps it there anyway
    # (`if (wait_us > 2000) wait_us = 2000;` in opairshare_collect_remaining),
    # so anything above 2000 is the same number wearing a different label.
    # Note what the timer cannot do: the goal is `amsdu_max` frames, and the
    # wait only continues while more frames are actually queued. A queue that
    # holds one frame per 2 ms produces one-frame aggregates no matter what
    # this value says -- measured 2026-11-21, 58% of transmissions were single
    # frames at 1500 us.
    #
    # retry=0 and tid=-1 are negative results, kept as defaults because both were
    # measured to hurt:
    #   * `passthru_awdl_retry=1` (link-layer NO_ACK retransmit) maps 1:1 onto the
    #     drop counter (+590 retransmits / +590 `status3`, `retry_gaveup=0`): it
    #     resends the same frame inside the same 28 ms slot, so it cannot recover a
    #     loss and still burns the slot.
    #   * `passthru_awdl_tid=100` (QoS byte rotating 0..7) splits the BlockAck
    #     session per TID; the iPhone then reorders per TID and the sender's
    #     `ssthresh` collapsed 1400 -> 28, 2.08 -> 0.93 MB/s.
    #
    # These are module parameters, so insmod is the durable way to set them: they
    # are correct from the first frame, before any traffic exists. (`apply_tuning`
    # below re-asserts them at runtime for the paths that skip the reload.)
    TUNE_INSMOD="
        passthru_awdl_amsdu=2 passthru_awdl_amsdu_max=$AMSDU_MAX passthru_awdl_amsdu_bytes=$AMSDU_BYTES
        passthru_awdl_collect_us=1500
        passthru_awdl_phy=$AWDL_PHY passthru_awdl_mcs=$AWDL_MCS passthru_awdl_rate_mbps=54 passthru_awdl_nss=2
        passthru_awdl_capacity=256 passthru_awdl_lowmark=128 passthru_awdl_backpressure=1
        passthru_awdl_attempts=3 passthru_awdl_retry=0 passthru_awdl_retry_max=8
        passthru_awdl_retry_budget_ms=500
        passthru_awdl_ba_window=256 passthru_awdl_addba=1 passthru_awdl_ack_policy=0
        passthru_awdl_ba_amsdu=$BA_AMSDU
        passthru_awdl_ackfilter=0 passthru_awdl_tid=-1 passthru_awdl_host_seq=1
        passthru_awdl_rx_order_ms=0"
    # Both `passthru_ht` and `passthru_chan_11ac` are module parameters, so the
    # wide profile has to be decided before insmod, not at retune time.
    #
    # `passthru_ht=0` is what actually gets a wide vdev: with ht=1 the start
    # command goes out as `chanmode 5 (11NG_HT20)` and the computed 80 MHz
    # centre is dropped; with ht=0 the same request lands as
    # `chanmode 26 (11BE_EHT80), center_chan 5775, Tx SS 2`. Measured 2026-11-20,
    # 126.8 MB transferred at 1.28 MB/s with `bad=0` on that shape -- the width
    # was reserved and never used, because the TX path still asked for 20 MHz.
    #
    # `passthru_chan_11ac=1` is still what lets an 80 MHz request past the
    # concurrency gate; it no longer forces a VHT phy mode (see
    # wlan_hdd_wondertap.c), so the bring-up above stays 11be.
    WIDE_CHAN_PARAM=0
    HT_PARAM=1
    if [ -f "$STATE/wide" ]; then
        WIDE_CHAN_PARAM=1
        HT_PARAM=0
    fi
    # shellcheck disable=SC2086  # word splitting is the point: one insmod argument per word
    insmod "$BASE/qca_cld3_peach_v2.ko" \
        passthru_ht=$HT_PARAM passthru_chan_11ac=$WIDE_CHAN_PARAM \
        passthru_awdl_addba=1 passthru_awdl_native_rx=1 passthru_fwtype=7 \
        passthru_mon_rings=0 passthru_dry_run=0 passthru_mon_chan_sync=1 passthru_ndi_peer=1 \
        passthru_ndi_no_up=1 passthru_mgmt_bridge=1 passthru_aux_monitor=${AUX_MON:-1} \
        passthru_aux_wide=${AUX_WIDE:-1} passthru_tx_audit=1 \
        passthru_ndi_fw_authorize=0 passthru_awdl_wmi_raw=1 passthru_awdl_target_mac="" \
        passthru_awdl_target_auto=1 passthru_awdl_target_enable=1 passthru_awdl_target_auth=2 \
        passthru_native_data_rx=1 passthru_awdl_raw_rx=1 passthru_awdl_rx_dedup=1 \
        passthru_awdl_rx_auto=1 passthru_awdl_rx_rearm=1 \
        passthru_sta_coexist=2 passthru_sta_regular_rx=1 passthru_sta_action_rx=1 \
        passthru_pas_discovery_actions=1 \
        $TUNE_INSMOD \
        || return 12
    rmmod mac80211 || return 13
    mark mac-swapped
    insmod "$BASE/mac80211.ko" || return 14
    # The one step that needs the HAL gone. `wonder_probe` binds to the provider
    # and then fails to register its mac80211 hardware (`Ret: -12`) while the HAL
    # holds the master device -- and `insmod` still exits 0, so the failure would
    # surface two steps later as "wonder wiphy not present" about a module that
    # is loaded. See the header for both measurements.
    #
    # Not fatal if it refuses: `insmod` is the real judge, and a HAL that cannot
    # be stopped is worth trying past rather than dying on.
    #
    # Retried because `init.svc` saying `stopped` is the *process* leaving, and
    # the master device is released a moment later. Measured: the same insmod
    # that fails with `Ret: -12` immediately after `stop` succeeds after a few
    # seconds of quiet, so the retry is the difference between the two and not
    # superstition. A retry means removing the failed instance first -- the
    # module stays in /proc/modules after -12, and `insmod` onto it would only
    # report EEXIST.
    #
    # There is a second source of the same -12, and it is not the HAL: measured
    # 2026-11-14 with the HAL `stopped` throughout, four loads in a row failed
    # while `warn_alloc` / `__alloc_pages_slowpath` sat next to
    # `ieee80211_txq_setup_flows+0x1e8` in the trace, i.e. `ieee80211_register_hw`
    # ran out of memory. Four attempts at three seconds did not outlast it, so
    # this went to six with a backoff. Measured again 2026-11-17: six attempts
    # did not outlast it either, because the shortage is contiguous memory and
    # no amount of waiting creates it. `defrag_memory` is now called before the
    # first attempt and between attempts, and the retry prints the largest free
    # buddy order -- that is the number that separates the two causes: a leaking
    # HAL shows up as plenty of order-10 blocks with the insmod still failing,
    # fragmentation shows up as order 6+ empty with MemAvailable in the GB.
    defrag_memory
    stop_hal || say 'HAL would not stop; wonder.ko may fail to register'
    n=0
    delay=3
    while :; do
        insmod "$BASE/wonder.ko" physical_name=wondertap0 virtual_name=wonder0 || return 15
        find_wonder_phy >/dev/null && break
        rmmod wonder 2>/dev/null
        n=$((n + 1))
        if [ "$n" -ge 6 ]; then
            say 'wonder.ko loaded but registered no wiphy; see dmesg for wonder_probe'
            return 16
        fi
        say "wonder registered no wiphy (mem=$(mem_avail_kb)k max_order=$(mem_max_order)); retry $n in ${delay}s"
        defrag_memory
        sleep "$delay"
        delay=$((delay + 2))
    done
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

# Where the STA is, in MHz, or nothing when it is not associated.
#
# `iw dev wlan0 info` prints `channel 44 (5220 MHz), width: 20 MHz, ...`; only
# the parenthesised number is stable across iw versions, so that is what is
# matched.
# The frequency an interface is sitting on, in MHz. Echoes nothing and returns
# non-zero when the interface has no channel, so callers can tell "no answer"
# from "the answer is empty".
iface_freq() {
    mhz=$(iw dev "$1" info 2>/dev/null \
        | sed -n 's/.*channel [0-9][0-9]* (\([0-9][0-9]*\) MHz).*/\1/p' \
        | head -1)
    [ -n "$mhz" ] || return 1
    echo "$mhz"
}

sta_freq() {
    iface_freq wlan0
}

# Echo `<freq> <source>` for the link to use. The frequency is always the social
# channel; `<source>` says what the STA was doing, because that is the only part
# that varies and it is what decides whether the link can hear the peer at all.
#
# This used to follow the STA, on the theory that tap and STA had to share a
# channel and the tap could be retuned onto it. The second half is false on this
# driver: `opairshare_pas_rx_fixed_channel()` in wma_opairshare_pas_mgmt.h refuses
# every frequency but 5745 with -EOPNOTSUPP -- its own comment calls it the
# "Fixed149 profile: this does not establish general hopping support" -- and it
# refuses at `wonder_start`, after `wonder-ven feed` has already answered ok.
# Measured on this phone 2026-11-14: retuning a live link to 5220 failed at
# `ip link set wonder0 up`, took `wondertap0` and `awdld` with it, and came back
# with a new MAC; deleting and re-adding the vif failed the same way, and so did
# reloading wonder.ko. So the earlier 72% figure was never "followed but lossy" --
# the tap sat on 149 while the STA roamed, and there was no code path that could
# have moved it. What is left for this function is to say which world the STA is
# in, so `status` and the daemon's `phy=` line can be read against each other.
pick_freq() {
    if [ -n "$AWDL_FREQ" ] && [ "$AWDL_FREQ" != "$SOCIAL_FREQ" ]; then
        # Left as a one-line escape hatch for a driver that does support hopping,
        # but on today's driver obeying it can only produce a link that will not
        # come up, so it is reported and then ignored.
        say "AWDL_FREQ=$AWDL_FREQ ignored: fixed149 profile refuses any other frequency"
    fi
    mhz=$(sta_freq)
    if [ -z "$mhz" ]; then echo "$SOCIAL_FREQ no-sta"; return 0; fi
    if [ "$mhz" = "$SOCIAL_FREQ" ]; then echo "$SOCIAL_FREQ sta"; return 0; fi
    echo "$SOCIAL_FREQ sta-on-$mhz"
}

# Wait for the STA to have a channel, up to `n` seconds.
#
# A fresh association takes seconds: the HAL has just restarted, so the supplicant
# has to scan and authenticate, and `iw` reports no channel at all until it is done.
# Resolving before that reads "no STA" and pins the link to the social channel —
# which is the behaviour this replacement exists to stop having, arrived at by
# accident instead of by a constant.
wait_sta() {
    n=0
    while [ "$n" -lt "$1" ]; do
        [ -n "$(sta_freq)" ] && return 0
        sleep 1
        n=$((n + 1))
    done
    return 1
}

# The tuning set, re-asserted at runtime.
#
# `load_stack` passes every one of these to insmod, and on a cold boot that is the
# only place they need to exist -- the values are right before the first frame is
# built. This exists for the paths that do not reload: `start`/`boot` when the
# stack is already swapped for this boot id, a bare `link`, and the case where an
# older build's script left a module loaded with the previous numbers. A stale
# `capacity` is not cosmetic -- 32 instead of 256 is 2.25 vs 2.59 MB/s -- so
# "already loaded" is worth one read-back rather than an assumption.
#
# A refused write is reported, not retried: `backpressure` is compiled in (0444)
# and everything else is writable. A value that reads back different from what was
# written means the driver clamped it, which is the one thing worth knowing.
TUNE_RUNTIME="amsdu=2 amsdu_max=$AMSDU_MAX amsdu_bytes=$AMSDU_BYTES collect_us=1500 phy=$AWDL_PHY mcs=$AWDL_MCS
    rate_mbps=54 nss=2 capacity=256 lowmark=128 attempts=3 retry=0 retry_max=8
    retry_budget_ms=500 ba_window=256 ack_policy=0 ackfilter=0 tid=-1 host_seq=1
    ba_amsdu=$BA_AMSDU rx_order_ms=0"

passthru_dir() { echo /sys/module/qca_cld3_peach_v2/parameters; }

apply_tuning() {
    P=$(passthru_dir)
    [ -d "$P" ] || return 0
    bad=
    for kv in $TUNE_RUNTIME; do
        k=${kv%%=*}
        v=${kv#*=}
        f="$P/passthru_awdl_$k"
        [ -w "$f" ] || continue
        printf '%s' "$v" > "$f" 2>/dev/null || { bad="$bad $k=refused"; continue; }
        got=$(cat "$f" 2>/dev/null)
        # bools read back as Y/N, so accept either spelling of the same intent.
        case "$v" in
            1) { [ "$got" = 1 ] || [ "$got" = Y ]; } || bad="$bad $k=$got" ;;
            0) { [ "$got" = 0 ] || [ "$got" = N ]; } || bad="$bad $k=$got" ;;
            *) [ "$got" = "$v" ] || bad="$bad $k=$got" ;;
        esac
    done
    if [ -n "$bad" ]; then
        say "TUNING_MISMATCH${bad}"
    else
        say 'TUNING ok'
    fi

    # Backlog. A transfer stopped mid-flight leaves frames owned by the bridge
    # worker, and the next `send` queues behind them. `opairshare_bridge_pending`
    # is read-only (0444), so this cannot clear the count -- what it can do is
    # wait for the worker to drain it, which is what actually happens once the
    # flow is resumed, and say so when it does not. A number that stays put is
    # the difference between "slow first transfer" and "the link is wedged".
    first=$(cat "$P/opairshare_bridge_pending" 2>/dev/null)
    case "$first" in
        ''|0) ;;
        *)
            n=0
            while [ "$n" -lt 10 ]; do
                [ "$(cat "$P/opairshare_bridge_pending" 2>/dev/null)" = 0 ] && break
                sleep 0.3
                n=$((n + 1))
            done
            now=$(cat "$P/opairshare_bridge_pending" 2>/dev/null)
            if [ "$now" = 0 ]; then
                say "BRIDGE_BACKLOG drained ($first -> 0)"
            else
                say "BRIDGE_BACKLOG stuck=${now:-?} (was $first); a stopped flow is holding it"
            fi
            ;;
    esac
}

# ---------------------------------------------------------------------------
# TCP tuning: the congestion control, and the queue it paces from
#
# The driver set above decides how much air one frame gets. This decides how many
# frames TCP keeps in flight, and on this link that was the second half of the
# gap. Measured 2026-11-20, same phone, same receiver, 20 x 14 MiB of `/dev/urandom`
# (incompressible, so wire bytes and disk bytes are the same number), rate taken
# from the app's own per-decile progress lines:
#
#   2.75 MB/s   cubic + pfifo_fast   per-decile 2.75 2.81 2.83 2.86 2.76 2.54 ...
#   3.14 MB/s   bbr   + fq           per-decile 2.93 3.25 3.10 3.12 3.13 3.14 ...
#   2.97 MB/s   bbr   + fq  (rerun)
#
# The dip is the whole story. `ss -tin` read mid-transfer showed `retrans 0/1120`
# against 114244 segments out -- 0.98% -- and cubic halves its window on every
# one of those, then spends the next segments climbing back. That sawtooth is the
# 2.54 entry, and it is why the average sits 0.4 MB/s under where the curve
# otherwise runs. BBR does not look at loss, so the sawtooth is gone and the +11%
# with it. `fq` is not decoration: BBR paces, and fq is the clock it paces
# against. `pfifo_fast` has no pacing clock at all, so `cc=bbr` alone on this
# phone is half a change.
#
# Both runs land at 2.97-3.14, i.e. the change is real and small. The comment is
# here mainly to stop the ceiling being re-read into it: this does not raise the
# ceiling. Mid-transfer the same session sat at `cwnd 46-56` segments (~77 KB)
# against a peer that advertised `wscale:9` (32 MB), with `minrtt 3.413 ms` and a
# live `rtt 17.9 ms` -- ~45 KB, ~14 ms of queue sitting between `awdltun0` and
# the passthru bridge. That queue is the cap, at ~26 Mbps, and two parallel
# streams measured the same day did not add: 140 KB in flight between them, the
# same ~3.2 MB/s, and `rtt` pushed to 19-23 ms. What this buys is a constant
# factor on a link that was leaving 11% on the floor; what it cannot buy is the
# ~20% the queue itself is holding.
#
# Runtime rather than insmod parameters, because that is what these are: a sysctl
# and a qdisc. Neither survives a reboot, and the qdisc does not even survive a
# daemon restart (the tunnel is recreated), which is why this is re-asserted at
# every daemon start instead of written once.
TUNE_CC=bbr
TUNE_QDISC=fq

# The tunnel belongs to `awdld`, so its name is not ours to assume. `awdltun0` is
# what this build creates; the glob covers a daemon that was handed another index
# after an earlier run left the first name taken by a dying process.
tun_dev() {
    for d in awdltun0 awdltun1; do
        [ -e "/sys/class/net/$d" ] && { echo "$d"; return 0; }
    done
    for p in /sys/class/net/awdltun*; do
        [ -e "$p" ] || continue          # an unmatched glob stays literal
        echo "${p##*/}"
        return 0
    done
    return 1
}

tune_tcp() {
    # The sysctl first and unconditionally: `boot` can be asked for the stack
    # while the daemon is still coming up, and the congestion control is worth
    # setting whether or not there is a tunnel to put it on yet.
    cc=$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)
    avail=$(cat /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null)
    case " $avail " in
        *" $TUNE_CC "*)
            if [ "$cc" != "$TUNE_CC" ]; then
                # The first value this boot saw, so `stop` can hand it back. Not
                # overwritten on a second `boot`: that would record `bbr` and
                # leave `stop` restoring what it just set.
                [ -e "$STATE/cc-before" ] || printf '%s\n' "$cc" > "$STATE/cc-before"
                printf '%s' "$TUNE_CC" > /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null
                cc=$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)
            fi
            ;;
        *)
            say "TCP_TUNING cc=$TUNE_CC unavailable (have:${avail:-none}), leaving $cc"
            ;;
    esac

    command -v tc >/dev/null 2>&1 || { say "TCP_TUNING cc=$cc qdisc=no-tc"; return 0; }
    dev=$(tun_dev) || { say "TCP_TUNING cc=$cc qdisc=no-tun"; return 0; }
    have=$(tc qdisc show dev "$dev" 2>/dev/null | head -n 1)
    case "$have" in
        "qdisc $TUNE_QDISC "*) ;;
        *)
            # `replace`, not `add`: a tunnel that came back keeps nothing, and a
            # tunnel that never went away already has a root qdisc. Both are the
            # same command.
            tc qdisc replace dev "$dev" root "$TUNE_QDISC" 2>/dev/null
            have=$(tc qdisc show dev "$dev" 2>/dev/null | head -n 1)
            ;;
    esac
    say "TCP_TUNING cc=$cc qdisc=$(printf '%s' "$have" | cut -d' ' -f2) dev=$dev"
}

# Hand the congestion control back.
#
# `stop` means the phone goes back to the user, and this is a global sysctl: it
# was raised for a 17 ms AWDL link, and leaving a machine-wide knob changed for a
# link that no longer exists is the same mistake as leaving Wi-Fi off. The qdisc
# needs no equivalent -- it lives on the tunnel, and the tunnel goes with the
# daemon.
restore_cc() {
    was=$(cat "$STATE/cc-before" 2>/dev/null)
    [ -n "$was" ] || return 0
    cur=$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)
    if [ "$cur" != "$was" ]; then
        printf '%s' "$was" > /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null
        cur=$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)
    fi
    say "TCP_RESTORED cc=$cur"
    rm -f "$STATE/cc-before"
}

link_up() {
    phy=$(find_wonder_phy) || { say 'wonder wiphy not present (did wonder.ko load?)'; return 1; }
    say "wonder_phy=$phy"

    # Only worth waiting for when the STA is supposed to exist *and* something is
    # actually up to associate. With the radio exclusive it was taken from Wi-Fi
    # before this ran, so "no STA" is the expected state; and on the cold path of
    # `start`/`boot` the HAL is deliberately still stopped here (the link has to
    # be built before `resume_hal`), so there is nothing to wait for either --
    # 15 s of it on every cold start for an answer the order already knows.
    #
    # Resolved here as well as in `start` because `link` is an entry point of its
    # own with no start around it, and a `link` that skipped this would read
    # source=no-sta on a station that is about to associate.
    [ -n "$RADIO_WANT" ] || RADIO_WANT=$(radio_want)
    if [ "$RADIO_WANT" = share ] && [ "$(hal_state)" = running ]; then
        wait_sta 15 || say 'wlan0 has no channel after 15s; the link will not hear anything'
    fi

    # Resolved here rather than at the top of the file, because on the `link`
    # entry point the STA may have associated since the caller last looked. The
    # frequency no longer depends on it -- only the `source=` tag does, which is
    # what makes `status` readable next to STAT. On the cold path of
    # `start`/`boot` the HAL is still stopped, so this reads `no-sta` and that is
    # the truth for that moment; `choose_radio` reports the radio state after.
    pair=$(pick_freq)
    freq=${pair% *}
    freq_source=${pair#* }
    say "link_freq=$freq source=$freq_source"

    if [ ! -e "$PCI/$AWDL_IF" ]; then
        iw phy "$phy" interface add "$AWDL_IF" type ibss 2>/dev/null \
            || iw phy "$phy" interface add "$AWDL_IF" type managed 2>/dev/null \
            || { say 'interface add failed'; return 1; }
        say "created $AWDL_IF"
    else
        # A vif that already exists is left exactly where it is. It has to be:
        # an interface that is up cannot be retuned (the vendor command answers
        # -95 EOPNOTSUPP, or -22 if it races the join), and taking it down to
        # retune is worse than leaving it -- measured, that took `wondertap0` and
        # `awdld` down with it and the link came back with a new MAC. A live link
        # on the "wrong" channel still carries frames; a dead one carries none
        # and takes the app's tunnel with it.
        #
        # `freq` is 5745 unconditionally now, so the only way to land in the
        # first branch is a link left on something else by an older build of this
        # script. Keeping it then is still the right answer: it is the only link
        # there is, and it is still the one the peer knows about.
        here=$(iface_freq "$AWDL_IF" 2>/dev/null)
        if [ -n "$here" ] && [ "$here" != "$freq" ]; then
            say "refuse retune $here -> $freq: a loaded tap cannot be moved; keeping $here"
            freq=$here
        elif [ -z "$here" ] && [ "$freq" != "$SOCIAL_FREQ" ]; then
            say "refuse $freq: the tap only initialises on $SOCIAL_FREQ; using $SOCIAL_FREQ"
            freq=$SOCIAL_FREQ
        fi
    fi
    # DAD would keep the link-local tentative for ~1s and leave a server that
    # binds right after `up` without an address.
    echo 0 > "/proc/sys/net/ipv6/conf/$AWDL_IF/accept_dad" 2>/dev/null

    # The four vendor caches have to be filled while the link is still down:
    # after `up` the setters take the radio path against a tap that failed to
    # initialise, and wondertap0 never leaves DOWN.
    if ! "$BASE/wonder-ven" "$AWDL_IF" feed "$freq" "$AWDL_BSSID"; then
        # A vif that was up is down and retuned but may still be holding the
        # radio; a vif that never existed never has this problem. One retry
        # from scratch, because the difference between the two states is not
        # something the caller can see.
        say 'feed refused; recreating the interface and retrying once'
        iw dev "$AWDL_IF" del 2>/dev/null
        iw phy "$phy" interface add "$AWDL_IF" type ibss 2>/dev/null \
            || { say 'interface add failed on retry'; return 1; }
        echo 0 > "/proc/sys/net/ipv6/conf/$AWDL_IF/accept_dad" 2>/dev/null
        "$BASE/wonder-ven" "$AWDL_IF" feed "$freq" "$AWDL_BSSID" \
            || { say 'wonder-ven feed failed; wondertap0 will not come up'; return 1; }
    fi

    # Wide profile, off unless `$STATE/wide` exists.
    #
    # wonder.ko only accepts SET_FREQUENCY while the tap is inactive and caches
    # it for the start_ap that `up` triggers, so the 80 MHz request has to be
    # written here -- after the caches, before the interface is ever opened.
    # A down/up cycle cannot be used instead: it loses the IBSS join and
    # wonder0 sits at NO-CARRIER even at 20 MHz (measured 2026-11-20).
    #
    # What this buys, measured on the same date: the vdev starts as
    # `chanmode 10 (VHT80), center_chan 5775, Tx SS 2` instead of
    # `chanmode 5 (HT20)` with the wide centre silently dropped.
    if [ -f "$STATE/wide" ]; then
        "$BASE/wonder-ven" "$AWDL_IF" freq "$freq" 2 >/dev/null \
            || { say 'wide: freq 80 refused'; return 1; }
        say 'wide: cached 80 MHz before first up'
    fi

    # The vdev's fixed TX rate, cached before the same first `up` and for the
    # same reason as the frequency: wonder.ko pushes cached_tx_rate into the vdev
    # inside `wondertap_init` and only caches it afterwards, so a value written
    # after `up` never reaches firmware except through the provider's probe hook,
    # which only prints.
    #
    # This was the missing line. `wonder-ven feed` fills four caches and its rate
    # default is 1/0/1/1/7 -- HT, 20 MHz, 1 SS, MCS7 -- and dmesg has been saying
    # so all along:
    #
    #   Set fixed TX rate: preamble=1, bw=0, gi=1, nss=1, mcs=7
    #
    # `__wlan_hdd_wondertap_set_fixed_tx_rate()` then feeds that `bw` straight
    # into `wmi_vdev_param_chwidth`, so the vdev that `freq 80` had just widened
    # is narrowed back to 20 MHz one call later, and `wmi_vdev_param_fixed_rate`
    # pins every frame on the vdev to a single HT20 rate. That is the observed
    # "TX is still 20 MHz on an 80 MHz link", and it also explains a geometry
    # mismatch that nothing else did: the peer descriptor a few lines further
    # down takes its bandwidth from `params->channel.bandwidth` (80), while the
    # vdev runs at `params->tx_rate.bw` (20).
    #
    # EHT is not the answer at this layer even though the driver maps it
    # (`wlan_hdd_convert_wonder_preamble_to_wmi()` has an EHT case and
    # ASSEMBLE_RATECODE_V1 puts preamble at bit 8, so it is not masked off like
    # the 5-bit `preamble_type` field is). The vdev is created in chanmode VHT80;
    # a fixed rate above the vdev's own mode has no rate table behind it. Making
    # EHT real means raising the vdev mode, not this number.
    #
    # Failure here is not fatal -- worse rates than we have now is the worst case
    # and the defaults are still in the cache from `feed`, so complain and go on.
    if ! "$BASE/wonder-ven" "$AWDL_IF" rate "$VEN_PRE" "$VEN_BW" "$VEN_GI" \
            "$VEN_NSS" "$VEN_MCS"; then
        say "fixed rate $VEN_PRE/$VEN_BW/$VEN_GI/$VEN_NSS/$VEN_MCS refused;" \
            'the vdev keeps the feed defaults (HT20 / 1 SS / MCS7)'
    else
        say "fixed rate cached: pre=$VEN_PRE bw=$VEN_BW gi=$VEN_GI" \
            "nss=$VEN_NSS mcs=$VEN_MCS"
    fi

    # `ip link set up` is the first thing that runs the vendor tap's
    # initialisation, and it is refused while the provider is still settling:
    # measured 2026-11-14, the first attempt after a cold `insmod wonder` returns
    #
    #   RTNETLINK answers: Operation not supported on transport endpoint
    #
    # with nothing in dmesg at all, and the same command seconds later returns 0
    # and logs `Vendor init successful. State set to UP.` (which itself takes ~5 s
    # inside `wondertap_init`, waiting for the vendor's aux rx interface). The
    # retry is the judge: there is no readable flag for "the provider has
    # registered its vendor ops", and `wonder_master_bind()` completing is only
    # visible in the log. Same lesson as the 20-attempt `awdl_link_up` in
    # `legacy/module/bin/control.sh`: a first failure here is normal.
    n=0
    while :; do
        err=$(ip link set "$AWDL_IF" up 2>&1)
        rc=$?
        [ "$rc" = 0 ] && break
        n=$((n + 1))
        if [ "$n" -ge 6 ]; then
            say "link up failed after $n tries: $err"
            say 'see dmesg for wonder_start'
            return 1
        fi
        say "link up refused (${err:-rc=$rc}); retry $n in 3s"
        sleep 3
    done
    say 'WONDER0_UP'

    if grep -q '^1$' "$PCI/$AWDL_IF/carrier" 2>/dev/null; then
        say 'ibss already joined'
    else
        iw dev "$AWDL_IF" ibss leave 2>/dev/null
        # HT20 is load bearing for throughput, not decoration. Without it the
        # join lands on NL80211_CHAN_WIDTH_20_NOHT (`iw dev wonder0 info` says
        # `width: 20 MHz (no HT)`), and every HT rate handed down afterwards is
        # ignored by firmware: `wonder-ven rate 1 0 1 2 7` still logs
        # `nss:2 mcs:7` at the vdev, yet the measured air rate stays at the
        # highest *mandatory* legacy rate, 24 Mbps (band_config.c keeps only
        # 6/12/24 as mandatory in 5 GHz). That is the whole 2 MB/s ceiling:
        # 4200 B / 24 Mbps + 0.6 ms = 2.0 ms per aggregate. Asking for HT20 at
        # join is the only way to make the vdev HT -- the tx_param and
        # fixed-rate paths cannot promote a non-HT vdev on their own.
        # fixed-freq is load bearing: without it mac80211 keeps rescanning to
        # merge with other IBSS networks of the same SSID, taking the radio off
        # this channel every 30s.
        #
        # The width here is mac80211's view, and it is what the peer reads out
        # of the beacons' HT/VHT IEs. HT20 was the only option while the vdev
        # was HT; in the wide profile the join asks for 80 MHz so the two agree.
        JOIN_WIDTH=HT20
        [ -f "$STATE/wide" ] && JOIN_WIDTH=80MHz
        iw dev "$AWDL_IF" ibss join "$AWDL_SSID" "$freq" "$JOIN_WIDTH" fixed-freq "$AWDL_BSSID" 2>/dev/null \
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
    # The daemon announces this number in every frame, so it is written only
    # once the tap has actually taken it. Writing it before the feed is how a
    # failed retune turned into a note claiming 5220 while the radio sat on
    # 5745 -- the same announcement/radio split this file exists to remove,
    # reintroduced by ordering instead of by a constant. A failure above leaves
    # the previous note alone, which still describes the radio as it stands.
    echo "$freq" > "$BASE/link-freq" 2>/dev/null
    # Last, not first: the parameters describe how to build a frame on a link that
    # exists. Running this after the join means the read-back also proves the
    # values survived the vdev coming up, which is where HT20 and nss=2 get their
    # teeth.
    apply_tuning
    say "AWDL_LINK_UP iface=$AWDL_IF freq=$freq source=$freq_source ll=${ll:-none}"
}

# Which radio state this run ends in, resolved once and applied here.
#
# Leaving Wi-Fi off is what makes the radio exclusive; see KEEP_WIFI at the top
# for the measurement that shaped this. "Asked for" has two possible authors --
# `KEEP_WIFI` when an operator set it for this run, and otherwise the app's
# switch, whose file the daemon goes on reading after this process exits -- so it
# is answered in exactly one place. Two places is how `link_up` and the `svc wifi`
# below end up describing different radios.
choose_radio() {
    wifi_before=$1
    if [ -n "$KEEP_WIFI" ]; then
        case "$KEEP_WIFI" in
            1) RADIO_WANT=share ;;
            *) RADIO_WANT=exclusive ;;
        esac
        say "RADIO_WANT=$RADIO_WANT source=KEEP_WIFI"
    else
        RADIO_WANT=$(radio_want)
        say "RADIO_WANT=$RADIO_WANT source=app note=KEEP_WIFI overrides this"
    fi

    if [ "$RADIO_WANT" = share ]; then
        case "$wifi_before" in 1) svc wifi enable ;; esac
        wifi_wait "$wifi_before" || say 'Wi-Fi did not come back to the previous state'
    else
        say 'RADIO_WANT=exclusive the radio stays exclusive; the phone is offline until share or stop'
    fi
}

cmd_status() {
    say "kernel=$(uname -r) boot=$(boot_id)"
    # The daemon's own words rather than a re-derivation. `cmd wifi status` can
    # say the radio is on; it cannot say who turned it on, or whether the link
    # was meant to survive without it -- which is the whole question here.
    say "wifi=$(wifi_state || echo unknown) hal=$(hal_state) radio=$(radio_state_line)"
    for m in opairshare_wonder_provider qca_cld3_peach_v2 mac80211 wonder; do
        if loaded "$m"; then say "module $m=loaded"; else say "module $m=absent"; fi
    done
    if [ -e "$PCI/$AWDL_IF" ]; then
        say "iface $AWDL_IF mac=$(cat "$PCI/$AWDL_IF/address") operstate=$(cat "$PCI/$AWDL_IF/operstate" 2>/dev/null) carrier=$(cat "$PCI/$AWDL_IF/carrier" 2>/dev/null)"
        iw dev "$AWDL_IF" info 2>/dev/null | grep -E 'ssid|type|channel'
        # What the daemon will announce, and where the STA is: the two lines that
        # have to agree, printed together so a disagreement is visible here rather
        # than inferred from a ping.
        say "link_freq=$(cat "$BASE/link-freq" 2>/dev/null || echo '?') sta_freq=$(sta_freq || echo none)"
        ip -6 addr show dev "$AWDL_IF" 2>/dev/null | grep inet6
    else
        say "iface $AWDL_IF=absent"
    fi
    # Read back rather than re-derived from the config above: this is the one line
    # that answers "is the tuning actually in the phone", and a receipt that
    # repeats what the script meant to do is not a receipt.
    tun=$(tun_dev)
    if [ -n "$tun" ]; then
        say "tcp cc=$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null || echo '?') dev=$tun qdisc=$(tc qdisc show dev "$tun" 2>/dev/null | head -n 1 | cut -d' ' -f2)"
    else
        say "tcp cc=$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null || echo '?') dev=absent"
    fi
}

cmd_start() {
    [ "$(id -u)" = 0 ] || die 'must run as root'

    # Order matters here, and it was wrong. This branch used to sit below the
    # module guard, so the second `start` in a boot died with "unexpected live
    # module wonder" -- even though the modules being live is precisely what a
    # second start means. It was hit in the field twice; both times the way out
    # was `awdl-stack.sh link`, which skips the Wi-Fi bookkeeping this function
    # exists for and so quietly skipped half the recovery.
    if same_boot "$STATE/stack-boot"; then
        # The patched stack is already live in this boot. Any failure from here
        # on is recoverable and must not touch the drivers.
        say 'stack already swapped this boot'
        freezer_off || true
        link_up || true
        cmd_status
        return 0
    fi

    # Cold path only. Modules loaded without a matching boot id means a stack
    # swapped by something else, or a `stack-boot` marker lost with $STATE;
    # `load_stack` cannot work on top of either, and guessing which one it is
    # would be worse than saying so.
    for m in wonder opairshare_wonder_provider; do
        loaded "$m" && die "unexpected live module $m (a stack is already up; use link, or stop first)"
    done

    wifi_before=$(wifi_state) || die 'cannot read Wi-Fi state'
    echo "$wifi_before" > "$STATE/wifi-before"
    mark stack-boot
    say "WIFI_BEFORE=$wifi_before"
    freezer_off || true

    # Our own receiver probes the link; leaving it running across a HAL restart
    # leaves it holding a socket on an interface that is about to disappear.
    am force-stop com.oshare.airdrop
    svc wifi disable
    wifi_wait 0 || { rm -f "$STATE/stack-boot"; die 'Wi-Fi did not disable'; }

    # Not `if ! load_stack; then rc=$?`: the `!` inverts the status, so `$?` is
    # always 0 there and the failure is reported as `DRIVER_SWAP_FAILED rc=0` --
    # which is how a real -16 from `insmod` got logged as a success code once.
    load_stack
    rc=$?
    if [ "$rc" != 0 ]; then
        say "DRIVER_SWAP_FAILED rc=$rc"
        restore_drivers || true
        rm -f "$STATE/stack-boot"
        return "$rc"
    fi

    # Link first, HAL second. Both orders hit the same one-off `ip link up`
    # refusal, so the retry -- not the order -- is what fixed the cold path; the
    # order was kept as it is because that is the one measured: four cold `boot`
    # runs in a row ended `stack up`, and the `HAL_FIRST` comparison never got
    # past `insmod wonder` (see the -12 note on `load_stack`).
    link_up || say 'link did not come up; the app will report no-vif'

    # From here the drivers are valid; a failure below is not a driver problem.
    resume_hal || true

    # Wi-Fi comes back only if asked for. `choose_radio` runs after `link_up`
    # because a station that associates can still be reported (`source=sta-on-*`)
    # but it no longer decides the frequency -- see `pick_freq`.
    choose_radio "$wifi_before"
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
    # No HAL stop here any more. It used to be `pause_hal || true` before
    # `restore_drivers`, i.e. a service restart on every teardown to arrange for
    # an `rmmod` that usually does not need it. `restore_drivers` now asks the
    # module directly and stops the HAL only if the answer is no.
    restore_drivers || say "restore reported rc=$?"

    # `start` may have taken the radio for the duration, so the state recorded
    # there is what the phone had before any of this. Restoring it here is the
    # other half of that trade: without it, every run ends with the phone offline
    # and the user reaching for the quick-settings tile.
    was=$(cat "$STATE/wifi-before" 2>/dev/null)
    case "$was" in
        1)
            svc wifi enable
            wifi_wait 1 || say 'Wi-Fi did not come back'
            ;;
    esac

    rm -f "$STATE/stack-boot"
    # After the radio is back, so the phone is never left both offline and
    # un-throttled. `freezer_restore` is a no-op when `boot` never had to set
    # it, which is the case for a `start`/`stop` pair that never shared a link.
    freezer_restore || true
    # And the one sysctl this script changes on purpose. Same principle, same
    # place: `stop` means the phone goes back to the user, and `bbr` was raised
    # for a 17 ms link that no longer exists.
    restore_cc || true
    # Same principle, one layer up: `stop` means the phone goes back to the
    # user, and a root loop that writes `cgroup.procs` every 2 s is not part of
    # "back to the user". The app keeps whatever freezer policy the ROM wants
    # for it -- which is the whole reason this guard exists, and also the whole
    # reason it must not outlive the link.
    stop_guard || true
    # The app's request file is deliberately left alone. It is the user's
    # preference and not state of this run, so `stop` restoring Wi-Fi above is
    # the end of the teardown, not a silent edit of what the switch will be next
    # time -- and the next `start` reads it back to decide the same question
    # again. The pre-daemon copy under $STATE is removed by hand: nothing reads
    # it now, and a stale file that looks authoritative is worse than none.
    cmd_status
}

# ---------------------------------------------------------------------------
# `boot`: the app's entry point
#
# Everything below exists because the caller is a GUI with no shell. It cannot
# read stdout, it cannot be force-stopped by the command it just ran, and it has
# to put a sentence on a screen when the answer is no.

# Stop the daemon, and wait for it to actually be gone.
#
# `pkill -x` matches the process name exactly, which is the whole point of this
# file having a `boot` at all: the app's package name and the daemon's name are
# different strings, so one cannot take the other down by accident.
#
# The wait is not politeness. `rmmod` on a module whose netdev is still open
# fails, and a daemon on its way out still holds the tun, the nl80211 socket and
# `wondertap0` -- measured: skipping the wait turns the swap into rc=10 with the
# module still loaded and no explanation.
stop_daemon() {
    pid=$(pidof awdld 2>/dev/null)
    [ -n "$pid" ] || return 0
    say "stopping awdld pid=$pid"
    pkill -x awdld 2>/dev/null || kill "$pid" 2>/dev/null
    n=0
    while [ "$n" -lt 10 ]; do
        pidof awdld >/dev/null 2>&1 || { say 'awdld gone'; return 0; }
        sleep 1
        n=$((n + 1))
    done
    say 'awdld ignored TERM; killing it'
    pkill -9 -x awdld 2>/dev/null
    n=0
    while [ "$n" -lt 5 ]; do
        pidof awdld >/dev/null 2>&1 || { say 'awdld killed'; return 0; }
        sleep 1
        n=$((n + 1))
    done
    say 'awdld will not die; the swap will probably fail'
    return 1
}

# Start the daemon that owns the tunnel, unless one is already watching the
# control directory.
#
# `--uid` and `--ctl` are spelled out rather than left to the daemon's defaults,
# even though they match them on this phone: the app is now the caller, and a
# default that drifts is a radio switch that quietly stops answering.
#
# It is started inside a subshell that `exec`s it, so the pid `pidof` reports is
# the daemon and not a shell wrapping it. `setsid` is used when the ROM has it,
# because the parent is `su -c` and a process that dies with the su session would
# make boot look like it worked right up until the screen was redrawn.
start_daemon() {
    BOOT_NAME_ARG=$1
    if DAEMON_PID=$(ctl_daemon); then
        DAEMON_TUN=$(grep -q TUN_READY "$STATE/awdld.log" 2>/dev/null && echo up || echo unknown)
        say "DAEMON_ALREADY pid=$DAEMON_PID"
        # Re-asserted even though nothing was started: the sysctl survived the
        # previous run, the qdisc did not, because the tunnel it hangs on is
        # recreated by every daemon start. One `tc` on a path that already forks.
        tune_tcp
        return 0
    fi
    [ -x "$BASE/awdld" ] || { say "no $BASE/awdld to start"; DAEMON_TUN=absent; return 1; }
    uid=$(app_uid)
    [ -n "$uid" ] || { say "no /data/data/$APP on this phone; a daemon there would have no tunnel"; DAEMON_TUN=absent; return 1; }

    rm -f "$STATE/awdld.log"
    launcher=
    command -v setsid >/dev/null 2>&1 && launcher=setsid
    (
        if [ -n "$BOOT_NAME_ARG" ]; then set -- --name "$BOOT_NAME_ARG"; else set --; fi
        exec $launcher "$BASE/awdld" --uid "$uid" "$@" --burst 4 --ctl "$(app_dir)"
    ) > "$STATE/awdld.log" 2>&1 &

    DAEMON_PID=
    DAEMON_TUN=absent
    n=0
    while [ "$n" -lt 10 ]; do
        sleep 1
        n=$((n + 1))
        DAEMON_PID=$(ctl_daemon) && break
    done
    if [ -z "$DAEMON_PID" ]; then
        # Either it never ran or it ran and died. The log's last line is the only
        # account of which, and it goes into the receipt rather than into a guess.
        DAEMON_WHY=$(tail -n 1 "$STATE/awdld.log" 2>/dev/null)
        say "awdld did not stay up: ${DAEMON_WHY:-no output}"
        return 1
    fi
    say "DAEMON_STARTED pid=$DAEMON_PID"

    # `TUN_READY` is the daemon's own word for "the tunnel has an address", which
    # is a stronger statement than "the process is running" -- the same reason
    # `radio-state` is written by the actor and not re-derived here.
    DAEMON_TUN=pending
    n=0
    while [ "$n" -lt 20 ]; do
        if grep -q TUN_READY "$STATE/awdld.log" 2>/dev/null; then
            DAEMON_TUN=up
            say "DAEMON_TUN $(grep -m 1 TUN_READY "$STATE/awdld.log")"
            # The only moment the tunnel is known to be new. `TUN_READY` is the
            # daemon's word for "it has an address", which is also the first
            # moment a root qdisc can be attached to it.
            tune_tcp
            return 0
        fi
        pidof awdld >/dev/null 2>&1 || { DAEMON_TUN=gone; break; }
        sleep 1
        n=$((n + 1))
    done
    [ "$DAEMON_TUN" = gone ] && say "awdld exited before the tunnel came up: $(tail -n 1 "$STATE/awdld.log" 2>/dev/null)"
    [ "$DAEMON_TUN" = pending ] && say 'awdld is up but has not announced TUN_READY yet'
    return 1
}

# ---------------------------------------------------------------------------
# Keeping the app schedulable
#
# The freezer above is AOSP's, and on this ROM it is not the one that decides.
# ColorOS ships a second one -- HANS, `oplus_sys_hans` driven over netlink by
# the `oplus.hans` userspace daemon -- and it parks every third-party app that
# is not on screen. Measured on this phone 2026-11-18 17:57:
#
#   17:57:45.598  unfreeze uid: 10456 reason: W_AsyncBinder
#   17:57:45.599  F exit(), F stay=83
#   17:57:48.602  F enter(), M stay=3
#   17:57:48.603  freeze uid: 10456 scene: LcdOn
#
# Three seconds of running per external event, and `F stay=3` is the whole of
# it. The app read utime/stime 47/18 from /proc/<pid>/stat and was still 47/18
# twelve seconds later, while `awdld` on the same phone went 94/196 -> 97/205 in
# the same window. An app parked like that cannot announce -- the mDNS ticker is
# a timer, so nothing external ever wakes it -- and cannot answer on 8770. A
# cold `boot` measured 289 s wall against the 25 s this script needs, because
# the work had finished long before the app was given a slice to read it.
#
# It is a cgroup, and only the app is inside it: the app sits at
# `/apps/uid_10456/pid_N`, this script and `awdld` both sit at `0::/` because
# `su` starts them there, and `cgroup.freeze` lives on the app's subtree. So the
# fix is not to negotiate with the vendor -- the deviceidle whitelist, the
# standby bucket, `cached_apps_freezer` and a foreground service with an ongoing
# notification are all already in place and none of them touches HANS -- it is
# to move the app into a subtree nobody freezes. `/sys/fs/cgroup/system` is a
# leaf of the same hierarchy, so memory and io accounting stay in the unified
# tree, and it carries no freeze of its own.
#
# Measured after the move: 51/23 -> 54/28 over 24 s, and not one HANS line for
# the uid in the following five minutes.
#
# A loop rather than a one-shot because the app is replaced (systemui rebinds
# the tile, `am force-stop`, an update) and because AMS re-files a process whose
# importance changes. Re-asserting costs one `cat` of /proc/<pid>/cgroup per pid
# every two seconds, and nothing at all when the app is already where it should
# be.
FREEZE_ROOT=/sys/fs/cgroup
UNFROZEN_GROUP=$FREEZE_ROOT/system
GUARD_PID=$STATE/guard.pid
GUARD_LOG=$STATE/guard.log

# The two things the guard reads to decide whether it still has a job.
#
# `receive.manual` is the person's intent, and the app keeps it in its own
# SharedPreferences file -- root reads the XML directly rather than asking the app,
# because the app may be exactly the frozen thing this loop exists for, and a
# question put to a parked process is not answered. The foreground marker is a file
# `MainActivity` creates in `onResume` and deletes in `onPause`: of everything in
# this system, existence is the cheapest thing that cannot lie, and it is what tells
# the loop it can skip a tick without guessing.
PREF_FILE=/data/data/$APP/shared_prefs/opairdrop.xml
FRONT_FLAG=/data/data/$APP/files/foreground

# Is the person still asking to receive?
#
# Read with the shell's own `read` rather than `grep`: at twenty seconds a tick the
# fork would cost more than the answer, and the file is four lines of XML.
listening() {
    [ -r "$PREF_FILE" ] || return 1
    while IFS= read -r line; do
        case "$line" in
            *'name="receive.manual" value="true"'*) return 0 ;;
        esac
    done < "$PREF_FILE"
    return 1
}

# Move every pid of the app out of the frozen subtree, and say how many moved.
keep_unfrozen() {
    moved=0
    for p in $(pidof "$APP" 2>/dev/null); do
        where=$(sed -n 's/^0:://p' "/proc/$p/cgroup" 2>/dev/null)
        case "$where" in
            /apps/*)
                echo "$p" > "$UNFROZEN_GROUP/cgroup.procs" 2>/dev/null && moved=$((moved + 1))
                ;;
        esac
    done
    [ "$moved" -gt 0 ] && echo "UNFROZEN pids=$moved at $(date +%H:%M:%S)"
    return 0
}

stop_guard() {
    guard_alive || { rm -f "$GUARD_PID"; return 0; }
    kill "$(cat "$GUARD_PID")" 2>/dev/null
    say 'GUARD_STOPPED'
    rm -f "$GUARD_PID"
}

# Is the pid in the file still our loop?
#
# `$STATE` lives in /data/local/tmp and survives a reboot, a pid does not: after
# one, the number belongs to whatever the kernel handed it to next, and both
# `kill` and "already running" would act on a stranger. The cmdline is the only
# thing that distinguishes a reused pid from the process we left behind.
guard_alive() {
    pid=$(cat "$GUARD_PID" 2>/dev/null)
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'awdl-stack\.sh guard'
}

# Idempotent: a second `boot` in the same session keeps the guard it has and
# only re-asserts the one position that may have changed since.
start_guard() {
    if guard_alive; then
        say "GUARD_ALREADY pid=$(cat "$GUARD_PID")"
        keep_unfrozen
        return 0
    fi
    launcher=
    command -v setsid >/dev/null 2>&1 && launcher=setsid
    # Re-exec this file rather than inline the loop: `setsid` needs a program to
    # start, and a guard that dies with the `su -c` that launched it would stop
    # watching exactly when the app stops being talked to.
    #
    # The pid is *not* taken from `$!`. With `setsid` in front, `$!` is the
    # parent that may fork and exit at once, and a pid file pointing at a
    # wrapper is a pid file that cannot be stopped -- `cmd_guard` writes its own
    # `$$` instead, which is true whatever the launcher does.
    rm -f "$GUARD_LOG" "$GUARD_PID"
    $launcher sh "$0" guard > "$GUARD_LOG" 2>&1 &
    n=0
    while [ "$n" -lt 20 ]; do
        guard_alive && break
        sleep 0.1
        n=$((n + 1))
    done
    if guard_alive; then
        say "GUARD_STARTED pid=$(cat "$GUARD_PID") $(tail -n 1 "$GUARD_LOG" 2>/dev/null)"
        return 0
    fi
    say 'the guard did not stay up; the app will be frozen between events'
    return 1
}

# `awdld` has exactly one fatal failure, and nothing above it notices.
#
# An error out of the packet socket is not a transient the read loop retries:
# `awdld.rs` treats it as the end of the run and exits 1. The case that produces
# it is the one the bring-up causes itself -- `wonder-ven freq` answers -EBUSY on
# a tap that is already up, `link_up` recreates the interface to get round that,
# and the netdev the AF_PACKET socket is pinned to is destroyed underneath it
# (`awdld: reading the radio failed: Network is down (os error 100)`, measured
# with `ifindex` going 286 -> 303). After that the daemon is gone for the rest of
# the boot: the tap is up, the app looks like it is listening, and the phone is
# invisible to every peer at once.
#
# This loop is the only thing still running at that moment, so it is where the
# second chance has to live. Bounded on purpose: a daemon that dies because there
# is nothing to bind to will die again, and a guard that respawns it every two
# seconds turns one broken boot into a fork bomb with a log. The budget is five
# per boot, not five in a row -- it is deliberately not reset by a successful
# revive, because a counter that resets itself cannot tell "the link hiccuped
# once" from "the interface is being recreated in a loop".
DAEMON_REVIVE_MAX=5
revive_daemon() {
    pidof awdld >/dev/null 2>&1 && return 0
    # Nothing to revive against: `stop` ran, the phone rebooted, or the stack was
    # never built. The interface is the test, not the pid file -- `stop` removes
    # it first, so a link that is already down cannot be resurrected into one.
    [ -e "$PCI/wondertap0" ] || return 0
    same_boot "$STATE/stack-boot" || return 0
    if [ "$revive_n" -ge "$DAEMON_REVIVE_MAX" ]; then
        # Said once. Repeating it every tick would bury the line that matters --
        # and the daemon's own reason is still on the last line of its log.
        if [ "$revive_n" -eq "$DAEMON_REVIVE_MAX" ]; then
            say "DAEMON_REVIVE_GAVEUP tries=$revive_n why=$(tail -n 1 "$STATE/awdld.log" 2>/dev/null)"
        fi
        revive_n=$((revive_n + 1))
        return 0
    fi
    revive_n=$((revive_n + 1))
    say "DAEMON_REVIVE try=$revive_n the link is up and nothing is reading it"
    if start_daemon ''; then
        say "DAEMON_REVIVED pid=$DAEMON_PID tun=$DAEMON_TUN"
        # The receipt in `files/stack-state` names the pid it was published for,
        # and the app reads it to decide whether the stack is up. Left alone it
        # goes on naming the daemon that died, which is the one thing the app
        # cannot check for itself -- it has no business calling `pidof`.
        boot_receipt 0
    else
        say "DAEMON_REVIVE failed: $(tail -n 1 "$STATE/awdld.log" 2>/dev/null)"
    fi
}

# The loop itself.
#
# Its lifetime is the person's intent, not the stack's. While `receive.manual` is
# true the app has to stay schedulable -- that is the whole function -- and when it
# goes false there is nothing left worth a wake-up, so the loop ends and the phone
# gets its idle back. The three minutes of patience before it ends are deliberate:
# the tile is a one-second switch, and the documented way to use this is to turn it
# off, look at the iPhone, turn it back on. A guard that left on the first `false`
# would reopen the hole it was written to close, and would do it silently.
#
# The cadence is two seconds for the first minute, twenty after. Two is not a
# re-assertion rate: the move is sticky (measured -- one `echo <pid> >
# cgroup.procs` and then five minutes with not a single HANS line for the uid,
# utime advancing all the while), so what the loop actually watches for is the app
# being *replaced* by a new pid: systemui rebinding the tile, an update, a
# `force-stop` and relaunch. Twenty seconds is still well inside the time it takes
# a person to walk to the other phone and conclude this one is missing.
cmd_guard() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    echo $$ > "$GUARD_PID"
    born=$(date +%s)
    quiet=
    revive_n=0
    while :; do
        now=$(date +%s)
        if listening; then
            quiet=
            # A foreground app is out of the freezer's reach anyway; skipping here
            # saves a `cat` of /proc per pid for an answer already known. Skipping
            # only this tick, never the loop: "in front now" says nothing about the
            # next tick, and whoever presses home does not come back to warn us.
            [ -e "$FRONT_FLAG" ] || keep_unfrozen
            # After the unfreeze, because the revive blocks for as long as it
            # takes to get a pid and a tunnel -- up to 30 s in the bad case, which
            # is 30 s of the app being freezable if this ran the other way round.
            revive_daemon
        else
            [ -n "$quiet" ] || quiet=$now
            if [ $((now - quiet)) -ge 180 ]; then
                say "GUARD_EXIT receive.manual has been off for $((now - quiet)) s"
                break
            fi
        fi
        if [ $((now - born)) -lt 60 ]; then sleep 2; else sleep 20; fi
    done
    # Only if the file still names us: a newer guard may have claimed it while this
    # one was on its way out, and deleting its pid would leave an orphan nobody can
    # stop.
    [ "$(cat "$GUARD_PID" 2>/dev/null)" = "$$" ] && rm -f "$GUARD_PID"
    return 0
}

# `guard`, as the app asks for it: start the loop if it is not already there.
#
# A second verb because the loop and the request for it are different things. The
# app calls this the moment the person turns listening on, and `boot` is the wrong
# trigger for that: building the stack and wanting to receive are two separate
# presses, in either order. The common one is boot first, tile second -- and a
# guard started by `boot` would read `receive.manual` false, wait out its three
# minutes and leave before the person ever reached the tile.
cmd_watch() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    start_guard
}

# Where the answer goes: the app's own directory, next to `radio-share` and
# `radio-state`, because that is the one place this process and the app already
# agree on.
#
# Ownership is what makes it readable, not the path. Root writing into the app's
# directory leaves root as the owner with 0600, and a receipt the caller cannot
# open is indistinguishable from one that was never written -- the same failure
# the daemon's request file had before it was chowned, measured twice.
#
# Replaced atomically for the reader's sake: the app polls this file, and half a
# line is worse than the previous answer.
publish() {
    PUBLISH_LINE=$1
    dir=$(app_dir)
    [ -d "$dir" ] || { say "PUBLISH $PUBLISH_LINE (no $dir to write into)"; return 1; }
    printf '%s\n' "$PUBLISH_LINE" > "$dir/stack-state.new" \
        || { say "PUBLISH $PUBLISH_LINE (cannot write $dir/stack-state.new)"; return 1; }
    uid=$(app_uid)
    [ -n "$uid" ] && chown "$uid" "$dir/stack-state.new" 2>/dev/null
    chmod 0644 "$dir/stack-state.new" 2>/dev/null
    mv -f "$dir/stack-state.new" "$dir/stack-state" \
        || { say "PUBLISH $PUBLISH_LINE (cannot rename into place)"; return 1; }
    say "PUBLISHED $dir/stack-state <- $PUBLISH_LINE"
}

# The receipt itself.
#
# The first word is derived, never asserted: `stack up` requires the vif to exist
# *and* the daemon to have said `TUN_READY`. Drivers in place with no link is
# `stack partial`, which is a different sentence and a different next step than
# both `stack up` and `stack failed` -- collapsing it into either one is how a
# screen ends up saying "done" over a phone that cannot be seen.
#
# The second word is the boot the receipt belongs to, and it is there because the
# receipt outlives the thing it describes. `files/stack-state` is an ordinary file
# in the app's data directory; nothing clears it at shutdown, so after a reboot it
# still names the previous run's `daemon=` pid and the app has no way to tell that
# pid is a corpse. The app compares this word against its own boot id and demotes
# the line to "last boot" instead of saying ready.
boot_receipt() {
    rc=$1
    stage=${2:-unknown}
    detail=${3:-}
    boot=$(boot_short)
    if [ "$rc" != 0 ]; then
        line="stack failed boot=$boot rc=$rc stage=$stage${detail:+ detail=$detail}"
    else
        if [ -e "$PCI/$AWDL_IF" ]; then iface=$AWDL_IF; else iface=absent; fi
        ll=$(link_local_for "$AWDL_IF" 2>/dev/null)
        if [ "$iface" = "$AWDL_IF" ] && [ "$DAEMON_TUN" = up ]; then
            word='stack up'
        else
            word='stack partial'
        fi
        line="$word boot=$boot iface=$iface freq=$(cat "$BASE/link-freq" 2>/dev/null || echo '?') ll=${ll:-none} wifi=$(wifi_state || echo unknown) daemon=${DAEMON_PID:-none} tun=${DAEMON_TUN:-absent}"
    fi
    say "BOOT_RESULT $line"
    publish "$line"
    # Only on a real link: `ratewatch` moves a fixed rate that only exists once
    # the tap is up, and starting it over a failed bring-up would just log
    # switch failures against a vif that is not there.
    if [ "$rc" = 0 ] && [ "$word" = 'stack up' ]; then
        pkill -f "$0 ratewatch" 2>/dev/null
        ( nohup "$0" ratewatch >/dev/null 2>&1 & )
    fi
}

# `start`, for the app to run on itself.
#
# The two differences from `start` are in the header. What is added here is the
# third thing a caller without a shell needs: an answer on disk, at every exit,
# including the ones that only produce a protocol number.
cmd_boot() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    BOOT_NAME=${2:-}
    # First thing, before the branch and before any driver work.
    #
    # The caller is the app, and on this ROM the caller is parked. Measured: a
    # cold `boot` took 289 s of wall time for 25 s of work, because the script
    # finished and then the app sat frozen, unable to read a pipe that already
    # had EOF on it, until something unrelated gave it a HANS window. Starting
    # the guard at the end would fix the *next* boot and leave every cold one --
    # the exact case this is for -- as slow as it was. It goes here so the
    # caller is schedulable for the whole call, not once it is over.
    start_guard || true

    if same_boot "$STATE/stack-boot"; then
        # Already swapped in this boot. Same rule as `start`: from here on a
        # failure is above the drivers and must not touch them.
        say 'stack already swapped this boot'
        # Idempotent and deliberately before the daemon: the flag lives in
        # `settings`, so it survives a reboot, but a phone that was rebooted
        # between two `boot` calls can also have had it reset by the OTA-safe
        # path in `cmd_stop`. Re-asserting it costs one `settings get`.
        freezer_off || true
        link_up || true
        DAEMON_PID=
        DAEMON_TUN=absent
        start_daemon "$BOOT_NAME" || true
        boot_receipt 0
        return 0
    fi

    # Cold path. Modules live without a marker for this boot means a stack was
    # swapped by something else, or the marker went with $STATE; `load_stack`
    # cannot work on top of either, and guessing which is worse than saying so.
    for m in wonder opairshare_wonder_provider; do
        if loaded "$m"; then
            say "unexpected live module $m; a stack is up without a marker for this boot"
            boot_receipt 3 precondition "module $m is live and there is no marker for this boot"
            return 3
        fi
    done

    wifi_before=$(wifi_state) || {
        say 'cannot read Wi-Fi state'
        boot_receipt 2 wifi 'cannot read the Wi-Fi state'
        return 2
    }
    echo "$wifi_before" > "$STATE/wifi-before"
    mark stack-boot
    say "WIFI_BEFORE=$wifi_before"

    # Before the radio goes away, because this is the only step that depends on
    # a process outside this script staying schedulable -- see `freezer_off`.
    # A failure here is reported but not fatal: the drivers can still come up,
    # and the app should get its receipt rather than a silent no-op.
    freezer_off || true

    # Not `am force-stop com.oshare.airdrop`: that is the caller. `awdld` is what
    # holds the interfaces, and it is exactly what the swap needs gone.
    stop_daemon || true
    svc wifi disable
    wifi_wait 0 || {
        rm -f "$STATE/stack-boot"
        # No share session is starting, so the phone gets its freezer policy
        # back the same way it gets its Wi-Fi back.
        freezer_restore || true
        say 'Wi-Fi did not disable'
        boot_receipt 4 wifi 'Wi-Fi did not disable'
        return 4
    }

    # Same reason as in `cmd_start`: `if ! load_stack` would report rc=0.
    load_stack
    rc=$?
    if [ "$rc" != 0 ]; then
        say "DRIVER_SWAP_FAILED rc=$rc"
        restore_drivers || true
        rm -f "$STATE/stack-boot"
        freezer_restore || true
        boot_receipt "$rc" driver-swap "the driver swap failed at rc=$rc"
        return "$rc"
    fi

    # Link first, HAL second; see the note in `cmd_start` for why the order is
    # not what fixes this and the retry inside `link_up` is.
    link_up || say 'link did not come up; the receipt will say iface=absent'
    resume_hal || true
    choose_radio "$wifi_before"
    start_daemon "$BOOT_NAME" || say 'the daemon did not start; the receipt says what it said'
    boot_receipt 0
}

# Runtime radio sharing -- the two states the switch on the app's feature page
# toggles between.
#
# These are deliberately not `start`/`stop`. `start` may swap drivers and its
# first act is `am force-stop com.oshare.airdrop`, which from inside that app
# would be suicide; toggling sharing has to be safe while `awdld` is live and the
# tunnel is carrying frames. That is exactly what was measured on 2026-11-14,
# one daemon and one peer, `ping6 -c 20 -i 0.2 fe80::...%awdltun0`:
#
#   exclusive, Wi-Fi off                   20/20,  0% loss, min 38 ms, rx=96p
#   share, `svc wifi enable`, STA on 5220  11/20, 45% loss, min 27 ms, rx=133p
#   exclusive again                        20/20,  0% loss, min 6.4 ms, rx=181p
#
# Nothing was rebuilt across those three: tap ifindex stayed 141, `awdld` stayed
# on the same pid, the tunnel never went down, and STAT never printed a `!`. So
# sharing costs coexistence and nothing else.
#
# The 45% is not a property of sharing, it is a property of one channel pair: the
# STA sat on 5220 while AWDL was on 149. Which channel it sits on is decided by
# the AP and it is sticky -- the same test run again later landed on 149 ten times
# out of ten (`sta=5745`), and there the loss was around 10%. That is why nothing
# in the UI quotes a percentage: the receipt prints `sta=` in MHz instead, which
# is the one number that predicts how bad it will be, and the feature page's
# sentence says only that some packets are lost, not how many.
#
# ---------------------------------------------------------------------------
# Where the request lives, and why it moved
#
# It used to be `$STATE/radio-share`, under root's private tree. That was fine
# while the script was the only reader, and became a bug the moment `awdld` grew
# a control channel: the app writes `/data/data/$APP/files/radio-share` and the
# daemon polls *that* file, with an owner check. Two files, two writers, one
# radio -- and the app's switch would have been the one silently losing.
#
# So there is now exactly one file, in the app's directory, and three parties
# around it:
#
#   app    writes it (uid 10415)          the switch on the feature page
#   script writes it, then chowns it      this file, for ops and for testing
#   awdld  reads it, runs `svc wifi`,     the only process that acts on it
#          writes `radio-state`
#
# The ownership check on the daemon's side is what makes the `chown` below load
# bearing rather than tidy. Root writing into the app's directory leaves root as
# the owner, and a request the daemon is right to ignore looks exactly like a
# request nobody answered: no error, no log line, just a switch that does not
# move. Measured, twice, before the chown was added.
#
# What that buys is a receipt. `radio-state` is written by the process that did
# the work, after the work is visible in the world -- `share confirmed sta=5745`
# is printed only once the station has a channel, not when `svc wifi enable`
# returned. That is a stronger statement than the old `wifi_wait`, which declared
# success 1.1 s before the association existed.
radio_want() {
    # The same file the daemon reads, asked before the daemon exists. `start`
    # needs the answer to know whether to wait for an association, and it runs
    # while the app is force-stopped.
    want=$(cat "$(ctl_req)" 2>/dev/null)
    case "$want" in
        share|exclusive) echo "$want" ;;
        *) echo exclusive ;;
    esac
}

radio_state_line() {
    # The server's own words, or a statement that there is no server. Never
    # `unknown`: a phone with no receipt still has a preference on disk, and that
    # is the one thing the operator can still act on.
    ack=$(cat "$(ctl_ack)" 2>/dev/null)
    [ -n "$ack" ] || ack="$(radio_want) unconfirmed"
    printf '%s' "$ack"
}

# The pid of an `awdld` that is watching the control directory, if there is one.
#
# Read from `cmdline` rather than inferred from the name. An `awdld` started
# without `--ctl` is precisely the process that will not read the request, and
# treating it as the driver would produce a switch that writes a file and waits
# 25 s for an answer that was never coming.
ctl_daemon() {
    for pid in $(pidof awdld 2>/dev/null); do
        if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q -- '--ctl'; then
            echo "$pid"
            return 0
        fi
    done
    return 1
}

# Ask for a radio state, and let whoever is actually driving the radio reach it.
#
# Two processes can be driving it. Normally `awdld --ctl` is: it polls this file
# four times a second, runs `svc wifi`, and writes `radio-state` when the state
# it asked for really exists. Before the first `start` there is no daemon at all,
# and a request that only wrote a file would be a switch that reports moving and
# does not -- the one failure this page must not have.
#
# So: write the file, then find out who is listening. If it is the daemon, wait
# for its verdict instead of judging the radio here -- it is the process that
# knows whether the station ever associated. If nobody is listening, do what it
# would have done. The two paths cannot fight, because both ask for the same
# state and `svc wifi enable` twice is `svc wifi enable` once.
radio_request() {
    # `ASK` and not `want`, because `wifi_wait` assigns `want` and shell functions
    # have no locals: the first version of this printed `RADIO_REQUEST 0
    # result=confirmed`, having handed `exclusive` to `wifi_wait 0` and got the
    # target back in its own variable. The file's habit -- uppercase for what
    # crosses a call, lowercase for what stays inside one -- is load bearing.
    ASK=$1
    case "$ASK" in
        share|exclusive) ;;
        *) say "radio_request: '$ASK' is not a radio state"; return 2 ;;
    esac

    req=$(ctl_req)
    ack=$(ctl_ack)
    uid=$(app_uid)

    if [ -z "$uid" ]; then
        # No app means no control file and nobody polling one. Recorded rather
        # than fatal: the radio can still be switched, just not on behalf of a
        # switch that does not exist yet.
        say "no /data/data/$APP on this phone; the radio will be switched here"
    else
        printf '%s\n' "$ASK" > "$req" || { say "cannot write $req"; return 4; }
        chown "$uid" "$req" || { say "cannot give $req to uid $uid"; return 4; }
        chmod 0600 "$req" 2>/dev/null
    fi

    pid=
    [ -n "$uid" ] && pid=$(ctl_daemon)

    if [ -n "$pid" ]; then
        # `pending` is a phase, not an answer, so keep reading until it ends. The
        # daemon's own limits are 5 s to go off and 20 s to associate; this waits
        # past both and then reports whatever it last said, because a timeout
        # here is the daemon's problem to explain, not this script's to paper
        # over with a second guess.
        settled=$(cat "$ack" 2>/dev/null)
        n=0
        while [ "$n" -lt 25 ]; do
            case "$settled" in
                "$ASK confirmed"*|"$ASK failed"*) break ;;
            esac
            sleep 1
            settled=$(cat "$ack" 2>/dev/null)
            n=$((n + 1))
        done
        case "$settled" in
            "$ASK confirmed"*)
                say "RADIO_REQUEST $ASK result=confirmed source=daemon pid=$pid word='$settled'"
                ;;
            "$ASK failed"*)
                say "RADIO_REQUEST $ASK result=failed source=daemon pid=$pid word='$settled'"
                return 1
                ;;
            *)
                say "RADIO_REQUEST $ASK result=timeout source=daemon pid=$pid word='${settled:-no-receipt}'"
                return 1
                ;;
        esac
        # Said once per switch because it is repeatedly mistaken for a fault:
        # the loss that comes with `share` is coexistence on one radio, not a
        # broken link, and nothing in the log will ever show a teardown.
        [ "$ASK" = share ] && say 'note: the loss while sharing is coexistence, not a link failure'
        return 0
    fi

    # Nobody is watching. The file above is still written, so the next `awdld`
    # starts on this preference rather than undoing it a minute later.
    #
    # The sentences here are the daemon's sentences, word for word, because the
    # app reads this file and cannot tell which process wrote it. In particular
    # `share confirmed` means "the station has a channel" and not "the radio is
    # on": on is not associated, the difference is the 45% measured in the header
    # of this file, and `wifi_wait` only answers the first question. So the
    # channel is looked for before the word is used.
    case "$ASK" in
        exclusive)
            svc wifi disable
            if wifi_wait 0; then
                verdict='exclusive confirmed'
                rc=0
            else
                verdict='exclusive failed wi-fi still on'
                rc=1
            fi
            ;;
        share)
            svc wifi enable
            if ! wifi_wait 1; then
                verdict='share failed wi-fi did not turn on'
                rc=1
            else
                # Twenty seconds, the daemon's own limit. A station that is slow
                # to associate is not a failure; one that never does is the case
                # that has to be reported rather than papered over.
                mhz=
                n=0
                while [ "$n" -lt 20 ]; do
                    mhz=$(sta_freq 2>/dev/null)
                    [ -n "$mhz" ] && break
                    sleep 1
                    n=$((n + 1))
                done
                if [ -n "$mhz" ]; then
                    verdict="share confirmed sta=$mhz"
                    rc=0
                else
                    verdict='share failed wi-fi on, no association'
                    rc=1
                fi
            fi
            ;;
    esac
    say "RADIO_REQUEST $ASK result=${verdict#* } source=script"

    # And write the record, because this path is the actor. The daemon's last
    # line is still sitting there -- it was killed a second ago, and it says
    # whatever it said about a radio that no longer exists. Measured: `exclusive`
    # with no daemon printed `wifi=0 ... radio=share confirmed sta=5220` straight
    # from a dead process's receipt, which is the same lie as a switch that does
    # not move, only told about the past.
    if [ -n "$uid" ]; then
        printf '%s\n' "$verdict" > "$ack" 2>/dev/null && chmod 0644 "$ack" 2>/dev/null
    fi
    return "$rc"
}

cmd_share() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    # Not refused any more. With no stack up there is no link to share the radio
    # with, so this only turns Wi-Fi on -- worth saying, not worth blocking, and
    # the preference is exactly the one to keep for the next `start`.
    same_boot "$STATE/stack-boot" \
        || say 'no patched stack this boot: no link to share with; this only turns Wi-Fi on'
    radio_request share
    rc=$?
    cmd_status
    return $rc
}

cmd_exclusive() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    radio_request exclusive
    rc=$?
    cmd_status
    return $rc
}

# Two-phase data rate -- the one lever this whole day found that pays.
#
# Discovery has to stay at HT20 / 1 SS / MCS7. A peer in its non-transfer state
# holds a 20 MHz receiver: an 80 MHz PPDU is undecodable to it, and on
# 2026-11-22 05:30 fixing the vdev width without keeping the rate narrow is what
# made the two phones disappear from each other entirely.
#
# Its slot map is the tell. 8x149 + 1x6 (or sparser) is "not transferring";
# 15x149 + 1x6 is "transferring". So watch that one field and move the tap's
# fixed rate only while it is full:
#
#   full  -> wonder-ven rate 2 2 1 2 9   VHT / 80 MHz / SGI / 2 SS / MCS9
#   empty -> wonder-ven rate 1 0 1 1 7   back to the feed default
#
# Measured 2026-11-22 22:25 on the shipped `152b93ad` bytes, same iPhone, same
# ko, only this switch moving: 0.46 MB/s -> 2.09 MB/s average over the transfer
# with a 3.68 MB/s peak. The reverting half is not politeness -- if the wide
# rate turns out to be undecodable the map goes sparse by itself and this drops
# back within a few seconds instead of stranding the link.
#
# It reads the daemon's own STAT line rather than asking the driver, because
# that line is the only place the peer map is decoded, and it costs one grep.
RATE_WIDE='2 2 1 2 9'
RATE_NARROW='1 0 1 1 7'

cmd_ratewatch() {
    [ "$(id -u)" = 0 ] || die 'must run as root'
    log=$STATE/rate2phase.txt
    cur=narrow
    low=0
    say "RATEWATCH_START cur=$cur wide='$RATE_WIDE' narrow='$RATE_NARROW'"
    while [ -e "$PCI/$AWDL_IF" ] || [ -e "$STATE/stack-boot" ]; do
        # `|| true` on both: this file runs under `set -eo pipefail`, and `grep`
        # exits 1 when the map has no 149 at all -- which is exactly the
        # discovery state this loop is supposed to wait through. Without it the
        # watcher dies on its first sample instead of idling.
        map=$(grep -o 'peer_map=[0-9,]*' "$STATE/awdld.log" 2>/dev/null | tail -1 | cut -d= -f2) || true
        n=$(printf '%s\n' "$map" | tr ',' '\n' | grep -c '^149$') || true
        if [ "$cur" = narrow ] && [ "$n" -ge 14 ]; then
            # Retry once: `rc=2` was seen when the tap is mid-rebuild.
            "$BASE/wonder-ven" "$AWDL_IF" rate $RATE_WIDE >/dev/null 2>&1 || \
                { sleep 1; "$BASE/wonder-ven" "$AWDL_IF" rate $RATE_WIDE >/dev/null 2>&1; }
            rc=$?
            if [ "$rc" = 0 ]; then cur=wide; low=0; fi
            printf '%s SWITCH_WIDE rc=%s slots=%s pm=%s\n' "$(date +%s)" "$rc" "$n" "$map" >> "$log"
        elif [ "$cur" = wide ]; then
            if [ "$n" -le 8 ]; then low=$((low + 1)); else low=0; fi
            if [ "$low" -ge 6 ]; then
                "$BASE/wonder-ven" "$AWDL_IF" rate $RATE_NARROW >/dev/null 2>&1
                rc=$?
                if [ "$rc" = 0 ]; then cur=narrow; low=0; fi
                printf '%s SWITCH_NARROW rc=%s slots=%s pm=%s\n' "$(date +%s)" "$rc" "$n" "$map" >> "$log"
            fi
        fi
        sleep 1
    done
    say 'RATEWATCH_STOP link gone'
}

case "${1:-status}" in
    start) cmd_start ;;
    # The arguments are passed on: `cmd_boot` reads `$2` as the name the app wants
    # announced, and a call with no arguments leaves the function's `$2` empty --
    # which is how `boot 'Air'` used to start a daemon called `OPAirDrop`.
    boot) cmd_boot "$@" ;;
    stop) cmd_stop ;;
    # `tune_tcp` after the link rather than inside `link_up`: `link` is the entry
    # point that runs with a daemon already up, and it is the one path that never
    # passes through `start_daemon` -- so it is also the one path where the qdisc
    # would otherwise never be attached.
    link) link_up && tune_tcp ;;
    share) cmd_share ;;
    exclusive) cmd_exclusive ;;
    status) cmd_status ;;
    guard) cmd_guard ;;
    ratewatch) cmd_ratewatch ;;
    watch) cmd_watch ;;
    *)
        echo 'Usage: awdl-stack.sh start|boot|stop|link|share|exclusive|status|guard|watch'
        exit 2
        ;;
esac
