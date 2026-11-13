#!/bin/sh
# Runs the real send path on the device, then reads the result back out of the inbox.
#
#   tools/send-probe.sh [iface] [inbox]
#
# Where link-client.sh speaks the protocol by hand, this one runs the shipping client:
# AirDropClient + HttpCall + Outgoing + streamed cpio. It discovers whatever answers on
# the link (normally the app's own receiver on this very phone), sends three files
# through it, and compares the bytes that land in the receiver's directory with what it
# sent. Needs the app to have been opened once and the link to be up:
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
inbox=${2:-/storage/emulated/0/Android/data/com.oshare.airdrop/files/incoming}

# javac and d8 are Windows binaries and cannot open MSYS-style /d/... paths.
winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
root=$(winpath "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)")
work="$root/build/send-probe"
mkdir -p "$work/classes" "$work/dex"

buildtools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
[ -f "$buildtools/d8.bat" ] || { echo "no d8 under $buildtools" >&2; exit 1; }
[ -f "$androidjar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

echo "send-probe: compiling with $androidjar"
# The v2 package is compiled whole rather than file by file, so anything it references
# from the platform -- Log, Looper, the BLE advertiser -- has to resolve at compile time
# too; d8 already gets android.jar as its library, and javac needs the same one.
javac -encoding UTF-8 --release 11 -nowarn -classpath "$androidjar" -d "$work/classes" \
    "$root"/app/src/main/java/com/oshare/airdrop/core/*.java \
    "$root"/tools/SendProbe.java

echo "send-probe: dexing for API $MIN_API"
# The directory, not the glob. d8 takes a folder of class files as one input, and the
# glob is what broke this: the core is now about forty sources, javac emits a class per
# inner class on top of that, and the resulting command line passed cmd.exe's 8191
# character limit -- which arrives as the localised "文件名或扩展名太长", a message about
# filenames for a problem that is about their number.
"$buildtools/d8.bat" --min-api "$MIN_API" --lib "$androidjar" \
    --output "$work/dex" "$work/classes/com/oshare/airdrop/core" >/dev/null

jar --create --file "$work/probe.jar" -C "$work/dex" classes.dex

echo "send-probe: installing"
"$ADB" -s "$SERIAL" push "$work/probe.jar" /data/local/tmp/send-probe.jar >/dev/null

echo "send-probe: sending through $iface, reading back from $inbox"
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/send-probe.jar app_process /system/bin \
    com.oshare.airdrop.core.SendProbe $iface $inbox'"
