#!/bin/sh
# Builds the client-only probe and runs it against the installed app.
#
#   tools/link-client.sh [iface]
#
# Unlike stack-probe.sh this one starts no server: it asks the mDNS responder that
# com.oshare.airdrop already left running, pins TLS to the hash that answer carried,
# and walks Discover/Ask/Upload. Everything it prints came from the app's process,
# so it is the closest we get to an iPhone without one present. Requires the app to
# have been opened once (its service is what answers) and the link to be up:
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

# javac and d8 are Windows binaries and cannot open MSYS-style /d/... paths.
winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
root=$(winpath "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)")
work="$root/build/link-client"
mkdir -p "$work/classes" "$work/dex"

buildtools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
[ -f "$buildtools/d8.bat" ] || { echo "no d8 under $buildtools" >&2; exit 1; }
[ -f "$androidjar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

echo "link-client: compiling with $androidjar"
javac -encoding UTF-8 --release 11 -nowarn -classpath "$androidjar" -d "$work/classes" \
    "$root"/app/src/main/java/com/oshare/airdrop/core/*.java \
    "$root"/tools/LinkClientProbe.java

echo "link-client: dexing for API $MIN_API"
"$buildtools/d8.bat" --min-api "$MIN_API" --lib "$androidjar" \
    --output "$work/dex" "$work/classes"/com/oshare/airdrop/core/*.class >/dev/null

jar --create --file "$work/probe.jar" -C "$work/dex" classes.dex

echo "link-client: installing"
"$ADB" -s "$SERIAL" push "$work/probe.jar" /data/local/tmp/link-client.jar >/dev/null

echo "link-client: probing the app on $iface"
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/link-client.jar app_process /system/bin \
    com.oshare.airdrop.core.LinkClientProbe $iface'"
