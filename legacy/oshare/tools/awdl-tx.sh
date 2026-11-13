#!/bin/sh
# Builds, then runs the AWDL node (AwdlTx).
#
#   tools/awdl-tx.sh [iface] [seconds] [--no-tx] [name]
#
# AwdlTx is no longer a frame injector with a period; it is a node with a schedule,
# so there is no dst and no period to pass. What it sends is fixed by node.rs: a PSF
# every 110 TU and a MIF in the middle of every slot, both broadcast, because an
# AWDL action frame is always broadcast -- only unicast data frames are acked, and
# that probe lives elsewhere.
#
# --no-tx runs the receive side only: it still parses peers, still runs the election
# and still reports the phase error against a peer's clock, it just does not
# transmit. That is the first thing to run on a device, because it separates
# "cannot hear the phone" from "cannot join its schedule", which look identical
# from the transmit side.
#
# The interface must be the passthru vdev, not the monitor one: the driver decides
# which vdev a frame belongs to from the netdev the skb arrived on, and only the
# passthru netdev routes management frames into opairshare_pas_mgmt_xmit(). So:
#
#   wondertap0   transmit (this script)
#   wonder0      receive  (tools/awdl-probe.sh, or tcpdump)
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SDK=${ANDROID_SDK:-D:/Downloads/project/tools/android-sdk}
ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}

iface=${1:-wondertap0}
seconds=${2:-30}
shift 2 2>/dev/null || true

mode=""
name=""
for arg in "$@"; do
    case $arg in
        --no-tx) mode=--no-tx ;;
        *) if [ -z "$name" ]; then name=$arg; fi ;;
    esac
done

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
"$here/awdl-build.sh"

echo "awdl-tx: $iface for ${seconds}s as ${name:-<auto>} tx=${mode:-psf+mif}"
# Every argument is quoted inside the device shell: an empty name would otherwise
# vanish and shift the rest left, which is how the literal string "20" once ended up
# in the ARPA TLV.
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlTx \"$iface\" \"$seconds\" \"$mode\" \"$name\"'"
