#!/bin/sh
# Runs the raw 802.11 probe on the device's AWDL monitor interface.
#
#   tools/awdl-probe.sh [iface] [seconds]
#
# The interface has to be created as a monitor device first, which is what Mosey does
# and what module/bin/awdl-monitor.sh now does too:
#
#   iw phy wonder interface add wonder0 type monitor
#   ip link set wonder0 up
#
# With that in place this prints every AWDL action frame on 5745 MHz, so the peer's PSF
# cadence, its master address and its channel sequence become visible from inside our
# own process rather than through tcpdump.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SDK=${ANDROID_SDK:-D:/Downloads/project/tools/android-sdk}
ADB=${ADB:-D:/ROM/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
MIN_API=${MIN_API:-35}

iface=${1:-wonder0}
seconds=${2:-20}

# javac and d8 are Windows binaries and cannot open MSYS-style /d/... paths.
winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
root=$(winpath "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)")
work="$root/build/awdl-probe"
mkdir -p "$work/classes" "$work/dex"

buildtools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
[ -f "$buildtools/d8.bat" ] || { echo "no d8 under $buildtools" >&2; exit 1; }
[ -f "$androidjar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

# --release would hide android.jar behind the Java SE signature files, and this probe
# is built out of android.system.Os, so the platform jar has to stay on the classpath
# while source/target pin the class file version d8 accepts.
#
# AwdlFrame rides along because the slot decoder sizes its array from
# AwdlFrame.SLOTS -- the same constant AwdlTx builds its sequence with, so the two
# cannot drift apart the way a copied literal would.
echo "awdl-probe: compiling with $androidjar"
if ! javac -J-Duser.language=en -encoding UTF-8 -source 11 -target 11 -nowarn \
        -classpath "$androidjar" -d "$work/classes" \
        "$root"/app/src/main/java/com/oshare/airdrop/core/AwdlFrame.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/AwdlProbe.java; then
    echo "awdl-probe: compilation failed" >&2
    exit 1
fi

echo "awdl-probe: dexing for API $MIN_API"
# d8 is a Windows binary and does not expand a glob itself, so hand it relative class
# names from inside the tree: the shell expands them and the paths stay short.
( cd "$work/classes" && "$buildtools/d8.bat" --min-api "$MIN_API" --lib "$androidjar" \
    --output "$work/dex" com/oshare/airdrop/core/*.class ) >/dev/null

jar --create --file "$work/probe.jar" -C "$work/dex" classes.dex

echo "awdl-probe: installing"
"$ADB" -s "$SERIAL" push "$work/probe.jar" /data/local/tmp/awdl-probe.jar >/dev/null

echo "awdl-probe: listening on $iface for ${seconds}s"
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/awdl-probe.jar app_process /system/bin \
    com.oshare.airdrop.core.AwdlProbe $iface $seconds'"
