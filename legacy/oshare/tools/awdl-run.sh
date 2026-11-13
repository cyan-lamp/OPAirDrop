#!/bin/sh
# One measured AWDL data run: wake the phone over BLE, ask, and diff the counters.
#
#   tools/awdl-run.sh [seconds] [ports] [qname]
#
# The point is not the run, it is the bookkeeping around it. Every driver counter is
# cumulative since the module loaded, so a number read after a run describes every
# run that ever happened. Taking a snapshot immediately before and immediately after,
# with nothing else touching the radio in between, is the only reading that answers
# "what did *this* do" -- and the diff of those two is what awdl-run prints.
#
# dmesg is cleared first for the same reason. It cannot give counts (the driver rate
# limits them), but the discrete events -- a new target slot, an RX filter rearm, a
# channel fault -- appear exactly once each, and clearing first makes every line that
# survives belong to this run.
#
# BLE wake goes first and stays on. The phone only opens its AirDrop service for a
# while after hearing an Apple-format advertisement, so a run that starts with the
# service already closed measures silence and reports it as absence.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
PKG=${PKG:-com.oshare.airdrop}
IFACE=${IFACE:-wondertap0}

seconds=${1:-45}
ports=${2:-8770,61504,61505}
qname=${3:-_airdrop._tcp.local}
dst=${DST:-33:33:00:00:00:fb}
follow=${FOLLOW:-auto}
# A tun name switches the run from probe to relay: the kernel owns IPv6 and TCP, and
# this process only moves packets between the tun and the radio. Empty keeps the pure
# probe, which is what every measurement so far was taken with.
tun=${TUN:-}

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
stamp=$(date +%m%d-%H%M%S)
out="$here/../build/cnt/run-$stamp"
mkdir -p "$here/../build/cnt"

if [ "${SKIP_BUILD:-0}" != 1 ]; then
    "$here/awdl-build.sh" >/dev/null
fi

wake() {
    "$ADB" -s "$SERIAL" shell "am broadcast -n $PKG/.BleWakeReceiver \
        -a com.oshare.airdrop.BLE_WAKE --es op $1" 2>&1 | tr -d '\r' | grep -E 'Broadcast|data=' || true
}

echo "=== 0. make sure the driver has a target slot free for this run ==="
# Before the snapshot, because a reset belongs to neither column of the diff. The
# link is usually already clean -- `up` only refreshes addressing -- but a table
# left full by earlier runs turns every frame of this one into status3 without a
# single counter saying why, and the run would be read as the phone refusing.
"$here/awdl-link.sh" ensure | sed 's/^/  /'

echo
echo "=== 1. quiet the log and take the before snapshot ==="
"$ADB" -s "$SERIAL" shell "su -c 'dmesg -C'" >/dev/null 2>&1 || true
"$here/awdl-counters.sh" snap "$out.before.txt"

echo
echo "=== 2. wake the phone over BLE ==="
wake start
# The advertisement is registered asynchronously on the app's main looper, so the
# op=start reply can only say "asked". This is the call that reports the outcome.
"$ADB" -s "$SERIAL" shell "am broadcast -n $PKG/.BleWakeReceiver \
    -a com.oshare.airdrop.BLE_WAKE --es op status" 2>&1 | tr -d '\r' | grep -E 'data=' || true

echo
echo "=== 3. run: $seconds s, ports $ports, qname $qname${tun:+, tun $tun} ==="
# The seventh argument is only passed when asked for: app_process takes it as a plain
# class argument, and an empty string would name a tun that cannot exist.
if [ -n "$tun" ]; then
    "$ADB" -s "$SERIAL" shell \
        "su -c 'CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlData \"$IFACE\" \"$seconds\" \"$dst\" \"$follow\" \
        \"$ports\" \"$qname\" \"$tun\"'" 2>&1 | tr -d '\r' | tee "$out.log"
else
    "$ADB" -s "$SERIAL" shell \
        "su -c 'CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
        com.oshare.airdrop.core.AwdlData \"$IFACE\" \"$seconds\" \"$dst\" \"$follow\" \
        \"$ports\" \"$qname\"'" 2>&1 | tr -d '\r' | tee "$out.log"
fi

echo
echo "=== 4. take the after snapshot ==="
"$here/awdl-counters.sh" snap "$out.after.txt"

echo
echo "=== 5. what that run did to the driver ==="
"$here/awdl-counters.sh" diff "$out.before.txt" "$out.after.txt"

echo
echo "=== 6. driver events during the run (counts are rate limited; events are not) ==="
"$ADB" -s "$SERIAL" shell "su -c 'dmesg | grep -E \"OPAIRSHARE\"'" 2>/dev/null | tr -d '\r' \
    | sed 's/^\[[^]]*\] //' | sort | uniq -c | sort -rn | head -40 || true

echo
echo "=== 7. is the advertisement still up ==="
wake status

echo
echo "awdl-run: $out.{before,after}.txt $out.log"
