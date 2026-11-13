#!/bin/sh
# Asks a narrower question than awdl-ack.sh: can the peer hear this phone at all?
#
#   tools/awdl-null.sh [iface] [peer] [count] [period-ms]
#
# awdl-ack.sh sends a Master Indication Frame and reads the firmware's completion
# status; it comes back NO_ACK. That result cannot say whether the frame was never
# received or was received and only the management path is at fault, because the
# bridge sends an action frame through the firmware's management queue at 6 Mbps
# while a data frame takes the RAW path with an explicit preamble and MCS.
#
# A QoS Data frame settles it. It carries no payload worth parsing and no TLVs, so
# nothing above the MAC can be wrong, and an ACK is a MAC-level answer -- whatever
# can receive us has to send one. The bridge will only forward 42 bytes or more
# carrying the AWDL LLC/SNAP, which is why the frame is that size and not smaller.
#
# The verdict comes from the driver's own counters, not from dmesg: dmesg rate
# limits these lines to a handful per second. opairshare_tx_metrics counts data
# frame completions only, as status0 (acked) and status3 (no ack), so the delta
# across the run is exactly what the radio saw.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
PARAMS=/sys/module/qca_cld3_peach_v2/parameters

iface=${1:-wondertap0}
peer=${2:-}
count=${3:-32}
period=${4:-20}

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)

if [ -z "$peer" ]; then
    echo "awdl-null: usage: tools/awdl-null.sh [iface] [peer] [count] [period-ms]"
    echo "VERDICT no-peer"
    exit 2
fi

counter() {
    # The metrics line is one long key=value string; pull the one field asked for.
    "$ADB" -s "$SERIAL" shell "su -c 'cat $PARAMS/opairshare_tx_metrics'" 2>/dev/null \
        | tr ' ' '\n' | sed -n "s/^$1=//p" | head -1
}

"$here/awdl-build.sh"

before0=$(counter status0)
before3=$(counter status3)
: "${before0:=0}"
: "${before3:=0}"
echo "awdl-null: status0=$before0 status3=$before3 before"

"$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlNull $iface $peer $count $period'"

# The completion arrives after the send returns, so give the firmware a moment
# rather than reading the counters at the instant the last frame leaves.
sleep 2
after0=$(counter status0)
after3=$(counter status3)
: "${after0:=0}"
: "${after3:=0}"
ack=$((after0 - before0))
noack=$((after3 - before3))
echo "awdl-null: status0=$after0 status3=$after3 after"
echo "awdl-null: acked=$ack no-ack=$noack"

if [ "$ack" -gt 0 ]; then
    echo "VERDICT peer-heard"
elif [ "$noack" -gt 0 ]; then
    echo "VERDICT peer-silent"
else
    echo "VERDICT no-completion"
fi
