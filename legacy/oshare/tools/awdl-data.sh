#!/bin/sh
# Sends IPv6 packets inside AWDL data frames, and reports what comes back.
#
#   tools/awdl-data.sh [iface] [seconds] [dst-mac] [follow-mac|auto|none] [ports] [qname]
#
# This is the first thing in this tree that speaks AWDL's *data* plane rather than
# its control plane. Up to now every frame that left the antenna was an ordinary
# 802.11 data frame carrying a SNAP header, which the phone accepts at L2 and then
# drops one layer above, because AWDL-13 puts a sixteen-byte header between the SNAP
# pair and the packet. See AwdlFrame.dataFrame.
#
# The default question is a PTR query for `_airdrop._tcp.local` -- the exact question an
# AirDrop panel answers -- so a response proves three things at once: the header is
# right, the timing put us inside the phone's availability window, and the phone's mDNS
# responder accepted us as a peer on its AWDL network. It also does *not* answer, so the
# frames that matter in practice are the three probes sent alongside it: a TCP SYN
# (which proves the data plane both ways -- RST means the kernel is there and the port
# is not), an ICMPv6 echo (which makes the phone learn our neighbor entry), and the
# unicast PTR query.
#
# ports is a comma list, tried in rotation: 8770 is the AirDrop convention, and the
# phone publishes its own port in the Service Response TLV it broadcasts, so a SYN that
# draws a RST from one and nothing from another is how the live port is found.
#
# The interface must be wondertap0. The driver decides which vdev a frame belongs to
# from the netdev the skb arrived on, and only the passthru netdev feeds
# opairshare_pas_mgmt_xmit() / the AWDL data path.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SDK=${ANDROID_SDK:-D:/Downloads/project/tools/android-sdk}
ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}

iface=${1:-wondertap0}
seconds=${2:-20}
dst=${3:-33:33:00:00:00:fb}
follow=${4:-auto}
ports=${5:-8770}
qname=${6:-_airdrop._tcp.local}

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
"$here/awdl-build.sh"

# The driver holds the receivers it has been told to talk to in eight slots and
# never frees one until the vif is stopped, so a table left full by earlier runs
# sends every frame of this one to an address the firmware has no peer for: no
# ack, no error, status3, and it looks exactly like a phone that stopped
# answering. One line here is the difference between a measurement and a rerun of
# the last failure. RESET=0 skips it for the case where the link must not blink.
if [ "${RESET:-1}" = 1 ]; then
    "$here/awdl-link.sh" ensure | sed 's/^/awdl-data: /'
fi

echo "awdl-data: $iface for ${seconds}s, dst=$dst, follow=$follow, ports=$ports, qname=$qname"
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/awdl-tx.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlData \"$iface\" \"$seconds\" \"$dst\" \"$follow\" \
    \"$ports\" \"$qname\"'"
