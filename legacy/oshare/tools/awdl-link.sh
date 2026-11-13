#!/bin/sh
# Runs module/bin/awdl-link.sh on the phone, refreshing the device copy first.
#
#   tools/awdl-link.sh probe | up | down | reset | ensure | status | table
#
# The on-device script is the one that knows what the link needs; this wrapper
# exists so no run executes a stale copy of it. Until now the deployment lived at
# /data/local/tmp/awdl-link.sh and was pushed by hand, which is the same trap
# awdl-build.sh was written to close for the jar: a stale script and a fresh one
# look identical from the output of a measurement.
#
# `ensure` is the action a run harness wants. `up` is idempotent and only
# refreshes addressing, so it leaves the driver's eight-slot target table exactly
# as the previous runs left it -- and once those eight slots are spent, every data
# frame is transmitted to a receiver the firmware has no peer for. Nothing
# complains: the frame is accepted, the completion is status3, and the run reads
# as a peer that will not answer. `ensure` inspects the table and resets the link
# only when there is something to clear, so a clean link pays nothing.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
REMOTE=/data/local/tmp/awdl-link.sh

case ${1:-} in
    probe | up | down | reset | ensure | status | table) ;;
    *)
        echo 'Usage: awdl-link.sh probe|up|down|reset|ensure|status|table [ifname]' >&2
        exit 2
        ;;
esac

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# adb is a Windows binary and cannot open an MSYS-style /d/... path, and
# MSYS_NO_PATHCONV above turned off the translation that would have done it.
# pwd -W asks the shell that launched this for the native form.
src=$(cd "$here/../../module/bin" && pwd -W 2>/dev/null || printf '%s' "$here/../../module/bin")
"$ADB" -s "$SERIAL" push "$src/awdl-link.sh" "$REMOTE" >/dev/null

# `exec` so a caller sees the device script's exit status, which is how `reset`
# reports a link it could not rebuild.
exec "$ADB" -s "$SERIAL" shell "su -c 'sh $REMOTE $*'"
