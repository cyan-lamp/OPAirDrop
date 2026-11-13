#!/bin/sh
# Runs the tun relay on the device and reports what the driver did around it.
#
#   tools/awdl-relay-run.sh [seconds]
#
# The device side lives in awdl-relay.sh; this half only gets the jar onto the phone,
# wakes the peer over BLE, and brackets the run with counter snapshots. The bracketing
# is the same discipline as awdl-run.sh and for the same reason: every driver counter
# is cumulative, so only the diff describes this run.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
PKG=${PKG:-com.oshare.airdrop}
seconds=${1:-45}
tun=${TUN:-awdlrt0}

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# adb is a Windows binary and MSYS path conversion is off, so it has to be handed a
# drive-letter path or the push fails before the run starts.
herew=$(cd "$here" && pwd -W)
stamp=$(date +%m%d-%H%M%S)
out="$here/../build/cnt/relay-$stamp"
mkdir -p "$here/../build/cnt"

if [ "${SKIP_BUILD:-0}" != 1 ]; then
    "$here/awdl-build.sh" >/dev/null
fi

wake() {
    "$ADB" -s "$SERIAL" shell "am broadcast -n $PKG/.BleWakeReceiver \
        -a com.oshare.airdrop.BLE_WAKE --es op $1" 2>&1 | tr -d '\r' | grep -E 'Broadcast|data=' || true
}

echo "=== 1. install the device script ==="
# Before anything is measured: a target table left full by earlier runs sends every
# frame of this one to an address the firmware has no peer for, which reads as a peer
# that stopped answering -- and cost several runs of tuning timeouts against a link
# that had never been asked. `ensure` resets only when there is something to clear.
"$here/awdl-link.sh" ensure | sed 's/^/  /'
"$ADB" -s "$SERIAL" push "$herew/awdl-relay.sh" /data/local/tmp/awdl-relay.sh >/dev/null
"$ADB" -s "$SERIAL" shell "chmod 755 /data/local/tmp/awdl-relay.sh"
"$ADB" -s "$SERIAL" shell "su -c 'dmesg -C'" >/dev/null 2>&1 || true
"$here/awdl-counters.sh" snap "$out.before.txt"

echo
echo "=== 2. wake the phone over BLE ==="
wake start
"$ADB" -s "$SERIAL" shell "am broadcast -n $PKG/.BleWakeReceiver \
    -a com.oshare.airdrop.BLE_WAKE --es op status" 2>&1 | tr -d '\r' | grep -E 'data=' || true

echo
echo "=== 3. relay: $seconds s on $tun ==="
# The device script reads DUR and TUN from its own environment, so they travel inside
# the su command rather than being baked into the file.
"$ADB" -s "$SERIAL" shell \
    "su -c 'DUR=$seconds TUN=$tun sh /data/local/tmp/awdl-relay.sh'" 2>&1 \
    | tr -d '\r' | tee "$out.log"

echo
echo "=== 4. after snapshot ==="
"$here/awdl-counters.sh" snap "$out.after.txt"
"$here/awdl-counters.sh" diff "$out.before.txt" "$out.after.txt"

echo
echo "=== 5. driver events ==="
"$ADB" -s "$SERIAL" shell "su -c 'dmesg | grep -E \"OPAIRSHARE\"'" 2>/dev/null | tr -d '\r' \
    | sed 's/^\[[^]]*\] //' | sort | uniq -c | sort -rn | head -30 || true

echo
echo "awdl-relay-run: $out.{before,after}.txt $out.log"
