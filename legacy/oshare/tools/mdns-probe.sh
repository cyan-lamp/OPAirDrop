#!/bin/sh
# Builds the M1 mDNS probe with javac + d8, pushes it and runs it on the device.
#
#   tools/mdns-probe.sh [iface] [seconds] [instance]
#
# Needs: JDK 21 on PATH, an Android SDK with build-tools (d8) and a connected
# device with root. The interface has to exist already: run
# `module/bin/awdl-link.sh up` first, otherwise the probe reports no link-local.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SDK=${ANDROID_SDK:-D:/Downloads/project/tools/android-sdk}
ADB=${ADB:-D:/ROM/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
MIN_API=${MIN_API:-35}

iface=${1:-wonder0}
seconds=${2:-45}
instance=${3:-OPAirDrop}

# javac and d8 are Windows binaries and cannot open MSYS-style /d/... paths.
winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
root=$(winpath "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)")
# Kept under build/ rather than a temp dir: the sandbox refuses recursive deletes
# of absolute system temp paths, and build/ is already ignored by git.
work="$root/build/mdns-probe"
mkdir -p "$work/classes" "$work/dex"

buildtools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
[ -f "$buildtools/d8.bat" ] || { echo "no d8 under $buildtools" >&2; exit 1; }
[ -f "$androidjar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

echo "mdns-probe: compiling with $androidjar"
javac -encoding UTF-8 --release 11 -nowarn -classpath "$androidjar" -d "$work/classes" \
    "$root"/app/src/main/java/com/oshare/airdrop/core/*.java \
    "$root"/tools/MdnsProbe.java

echo "mdns-probe: dexing for API $MIN_API"
"$buildtools/d8.bat" --min-api "$MIN_API" --lib "$androidjar" \
    --output "$work/dex" "$work/classes"/com/oshare/airdrop/core/*.class >/dev/null

jar --create --file "$work/probe.jar" -C "$work/dex" classes.dex

echo "mdns-probe: installing"
"$ADB" -s "$SERIAL" push "$work/probe.jar" /data/local/tmp/mdns-probe.jar >/dev/null

echo "mdns-probe: running $instance on $iface for ${seconds}s"
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/mdns-probe.jar app_process /system/bin \
    com.oshare.airdrop.core.MdnsProbe $iface $seconds $instance'"
