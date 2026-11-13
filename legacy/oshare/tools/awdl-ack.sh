#!/bin/sh
# Asks the radio one question: does the peer hear our frames?
#
#   tools/awdl-ack.sh [iface] [seconds] [name]
#
# A broadcast frame is never acked, so its completion status says nothing about
# whether anyone was listening: 0 means "no ack was expected", not "delivered".
# A unicast frame is acked by the receiver's hardware, and the vendor bridge
# reports that as the management transmit status:
#
#   0  OPAIRSHARE_MGMT_TX_COMP_OK      the peer acked
#   3  OPAIRSHARE_MGMT_TX_COMP_NO_ACK  nothing answered
#
# So this sends the same MIF we normally broadcast, addressed to the peer, and
# reads the status. A run against an address nobody holds is the control: it
# must come back 3, or the status isn't measuring what it claims to.
#
# Before sending it captures a few seconds to learn the peer's address, because
# a node randomises it per session; after sending it captures again, because a
# peer that has accepted us may start addressing frames to us.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
PYTHON=${PYTHON:-python}

iface=${1:-wondertap0}
seconds=${2:-8}
name=${3:-awdl-ack}

# The capture has to run on the receive interface; the frames go out the other one.
rx=${RX_IFACE:-wonder0}

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
root=$(CDPATH= cd -- "$here/.." && pwd)

winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
work=$(winpath "$root/build/awdl-ack")
# The helpers are python, and this script turns path conversion off so adb sees
# /data/local/tmp unchanged. A native interpreter handed the resulting /d/...
# opens D:\d\Downloads\... and dies, so every path it is given has to be a
# Windows one.
herew=$(winpath "$here")
mkdir -p "$work"

dev=/data/local/tmp
capture() {
    # 200 frames or 15 s, whichever comes first. `-c` alone would wait forever
    # for a silent -i away peer, and a bare timeout would cut an active one short.
    "$ADB" -s "$SERIAL" shell \
        "su -c 'timeout 15 /system/bin/tcpdump -i $rx -s 0 -c 200 -w $dev/$1.pcap'" \
        >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" pull "$dev/$1.pcap" "$work/$1.pcap" >/dev/null 2>&1 || true
}

echo "== 1/4 listening for the peer on $rx"
capture pre
peer=$("$PYTHON" "$herew/peer-mac.py" "$work/pre.pcap" 2>"$work/peer-mac.err" || true)
if [ -z "$peer" ]; then
    # Show why rather than assuming the air was quiet: a broken interpreter or
    # path looks exactly like an absent peer once stderr is thrown away.
    [ -s "$work/peer-mac.err" ] && sed 's/^/  /' "$work/peer-mac.err"
    echo "awdl-ack: no AWDL frame in the capture"
    echo "VERDICT no-peer-awdl"
    exit 2
fi
echo "awdl-ack: peer $peer"

echo "== 2/4 clearing dmesg and unicasting a MIF to it for ${seconds}s"
"$ADB" -s "$SERIAL" shell su -c "dmesg -C" >/dev/null 2>&1 || true
"$here/awdl-tx.sh" "$iface" "$seconds" "$name" "$peer"

echo "== 3/4 reading the transmit completion status"
"$ADB" -s "$SERIAL" shell su -c "dmesg" > "$work/dmesg.txt" 2>&1 || true
ack=$(grep -o "OPAIRSHAREBRIDGECOMP: status=0" "$work/dmesg.txt" | wc -l | tr -d ' ')
noack=$(grep -o "OPAIRSHAREBRIDGECOMP: status=3" "$work/dmesg.txt" | wc -l | tr -d ' ')
other=$(grep -o "OPAIRSHAREBRIDGECOMP: status=[0-9]*" "$work/dmesg.txt" \
    | grep -vc "status=[03]$" || true)
echo "awdl-ack: acked=$ack no-ack=$noack other=${other:-0} (status lines are rate limited)"

echo "== 4/4 listening again for what the peer does next"
capture post
"$PYTHON" "$herew/analyze-pcap.py" "$work/post.pcap" 2>/dev/null | sed 's/^/  /' || true

sent=$(grep -c "OPAIRSHAREBRIDGESEND" "$work/dmesg.txt" || true)
echo "awdl-ack: frames submitted=$sent"
if [ "${ack:-0}" -gt 0 ]; then
    echo "VERDICT peer-heard"
elif [ "${noack:-0}" -gt 0 ]; then
    echo "VERDICT peer-silent"
else
    echo "VERDICT no-completion"
fi
