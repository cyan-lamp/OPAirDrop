#!/bin/sh
# Prints what a peer actually advertises, then greets it with an unpinned handshake.
#
#   tools/peer-probe.sh [iface] [seconds]
#
# The other probes report what our parser made of the answer. This one is for the
# questions that are about Apple's records rather than about our code: which TXT keys
# exist, what `fl` holds, whether a cert hash is published, which TLS versions get
# negotiated. So it dumps every record verbatim and then connects with no pin at all to
# see what certificate comes back and whether /Discover is answered.
#
# It never sends /Ask: on a real Apple device that puts a dialog in front of a person.
# Needs the link up (`module/bin/awdl-link.sh up`) and the iPhone to be showing AirDrop.
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
work="$root/build/peer-probe"
mkdir -p "$work/classes" "$work/dex"

buildtools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
[ -f "$buildtools/d8.bat" ] || { echo "no d8 under $buildtools" >&2; exit 1; }
[ -f "$androidjar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

echo "peer-probe: compiling with $androidjar"
javac -encoding UTF-8 --release 11 -nowarn -classpath "$androidjar" -d "$work/classes" \
    "$root"/app/src/main/java/com/oshare/airdrop/core/*.java \
    "$root"/tools/PeerProbe.java

echo "peer-probe: dexing for API $MIN_API"
"$buildtools/d8.bat" --min-api "$MIN_API" --lib "$androidjar" \
    --output "$work/dex" "$work/classes"/com/oshare/airdrop/core/*.class >/dev/null

jar --create --file "$work/probe.jar" -C "$work/dex" classes.dex

echo "peer-probe: installing"
"$ADB" -s "$SERIAL" push "$work/probe.jar" /data/local/tmp/peer-probe.jar >/dev/null

echo "peer-probe: watching $iface for ${seconds}s"
exec "$ADB" -s "$SERIAL" shell \
    "su -c 'CLASSPATH=/data/local/tmp/peer-probe.jar app_process /system/bin \
    com.oshare.airdrop.core.PeerProbe $iface $seconds'"
