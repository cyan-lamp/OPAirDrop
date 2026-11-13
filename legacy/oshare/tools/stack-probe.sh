#!/bin/sh
# Builds the M2 stack probe with javac + d8, pushes it and runs it on the device.
#
#   tools/stack-probe.sh [iface] [seconds]
#
# Runs the real AirDropLink (identity, mDNS, TLS) on the phone and transfers a file
# to itself over loopback, so Conscrypt, the inbox and the TXT record are all
# exercised before an APK exists. Bring the link up first:
#
#   module/bin/awdl-link.sh up
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SDK=${ANDROID_SDK:-D:/Downloads/project/tools/android-sdk}
ADB=${ADB:-D:/ROM/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
MIN_API=${MIN_API:-35}

iface=${1:-wonder0}
seconds=${2:-30}

# javac and d8 are Windows binaries and cannot open MSYS-style /d/... paths.
winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
root=$(winpath "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)")
# Under build/ rather than a temp dir: the sandbox refuses recursive deletes of
# absolute system temp paths, and build/ is already ignored by git.
work="$root/build/stack-probe"
mkdir -p "$work/classes" "$work/dex"

buildtools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
[ -f "$buildtools/d8.bat" ] || { echo "no d8 under $buildtools" >&2; exit 1; }
[ -f "$androidjar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

echo "stack-probe: compiling with $androidjar"
javac -encoding UTF-8 --release 11 -nowarn -classpath "$androidjar" -d "$work/classes" \
    "$root"/app/src/main/java/com/oshare/airdrop/core/*.java \
    "$root"/tools/StackProbe.java

echo "stack-probe: dexing for API $MIN_API"
"$buildtools/d8.bat" --min-api "$MIN_API" --lib "$androidjar" \
    --output "$work/dex" "$work/classes"/com/oshare/airdrop/core/*.class >/dev/null

jar --create --file "$work/probe.jar" -C "$work/dex" classes.dex

echo "stack-probe: installing"
"$ADB" -s "$SERIAL" push "$work/probe.jar" /data/local/tmp/stack-probe.jar >/dev/null

echo "stack-probe: running on $iface for ${seconds}s"
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/stack-probe.jar app_process /system/bin \
    com.oshare.airdrop.core.StackProbe $iface $seconds'"
