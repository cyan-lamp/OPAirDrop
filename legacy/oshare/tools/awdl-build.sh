#!/bin/sh
# Builds the native transmit shim and every Java frame tool, then installs them.
#
#   tools/awdl-build.sh
#
# Split out of awdl-tx.sh because there is now more than one thing to send: the MIF
# experiment (AwdlTx) and the acknowledgement probe (AwdlNull) ship in the same jar,
# and paying for a rebuild inside each runner hid which step failed.
#
# Nothing here is device specific beyond the paths below.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SDK=${ANDROID_SDK:-D:/Downloads/project/tools/android-sdk}
ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
MIN_API=${MIN_API:-35}

# javac and d8 are Windows binaries and cannot open MSYS-style /d/... paths.
winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
root=$(winpath "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)")
work="$root/build/awdl-tx"
# The output tree is emptied first. javac overwrites the classes it recompiles and
# leaves the ones whose source has moved or gone, and d8 is handed a *glob* of
# com/oshare/airdrop/core/*.class -- so a file deleted from the tree kept its class in
# the jar, and the probe went on running code that no longer had a source. That is the
# same failure as an up-to-date gradle task, approached from the other side.
rm -rf "$work/classes" "$work/dex"
mkdir -p "$work/classes" "$work/dex"
started=$(date +%s)

buildtools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
androidjar=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
[ -f "$buildtools/d8.bat" ] || { echo "awdl-build: no d8 under $buildtools" >&2; exit 1; }
[ -f "$androidjar" ] || { echo "awdl-build: no android.jar under $SDK/platforms" >&2; exit 1; }

# --- native shim -------------------------------------------------------------------
# Rebuilt from source when an NDK is around, otherwise the previously built .so is
# reused. aarch64-linux-android35 is picked over 34 because it links against the same
# bionic the device runs; either would work for three syscalls.
ndk=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
so="$work/libawdl_inject.so"
if [ -n "${ndk:-}" ]; then
    cc=$(ls "$ndk"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android"$MIN_API"-clang 2>/dev/null | head -1 || true)
    if [ -z "$cc" ]; then
        cc=$(ls "$ndk"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android*-clang 2>/dev/null | sort -V | tail -1 || true)
    fi
    if [ -n "$cc" ]; then
        echo "awdl-build: building $so"
        # The two unused-parameter warnings per function are JNI's signature, not a bug.
        #
        # The page-size flags are not optional on 16 KB devices. Without them the linker
        # defaults to the aarch64 convention of 4 KB, every PT_LOAD comes out with
        # p_align 0x1000, and the package installer refuses to load the library -- the
        # dialog it puts on screen says the native library is incompatible with the
        # 16 KB page size, which is exactly what happened. Asserting the alignment here
        # rather than in the app build keeps the one place that compiles this file from
        # silently reintroducing a 4 KB ELF.
        "$cc" -shared -fPIC -O2 -Wall \
            -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
            -o "$so" "$root/tools/awdl_inject.c"

        # Fail loudly instead of shipping a stale-but-buildable 4 KB object.
        readelf=$(ls "$ndk"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)
        if [ -n "$readelf" ]; then
            bad=$("$readelf" -l "$so" | awk '/^  LOAD/ { for (i = 1; i <= NF; i++) if ($i ~ /^0x[0-9a-f]+$/) a = $i; if (a != "0x4000") print a }')
            if [ -n "$bad" ]; then
                echo "awdl-build: $so has 4 KB LOAD alignment ($bad); 16 KB devices will reject it" >&2
                exit 1
            fi
        fi
    fi
fi
[ -f "$so" ] || { echo "awdl-build: no libawdl_inject.so and no NDK to build one" >&2; exit 1; }

# --- java --------------------------------------------------------------------------
# --release would hide android.jar behind the Java SE signature files and these classes
# only need plain java.*, but source/target keep the class file version d8 accepts.
#
# AwdlProbe is on the list because AwdlTx now runs the receive side of the stack and
# parses frames through AwdlProbe.Frame instead of keeping a second parser that would
# drift from the one the capture tool was debugged against. AwdlData needs both for the
# same reason: it builds its schedule with AwdlFrame and anchors to a peer through
# AwdlProbe.Frame, so it can only be right if it is wrong in the same place they are.
#
# BleAdvert is in the app tree rather than tools/ because it is product code -- the
# runtime will send the same bytes -- and compiling the one file here keeps a single
# implementation instead of a copy in the probe that could drift. Bplist is on the list
# for the same reason and with more force: AwdlTcp decodes the /Discover answer with the
# parser the product will use, so a reply it cannot read is one the product could not
# have read either. DvZip rides along with Cpio for the same reason -- Cpio.decompress
# sniffs for it, so the archive reader the probes exercise is only the product's if both
# halves of it are here.
#
# AwdlFrame, AwdlProbe, AwdlTx and AwdlData moved to the app tree for a harder reason
# than tidiness: they were copies, and a copy drifts. AwdlData was thirty-two hundred
# lines in the app and twenty-eight hundred here, which means every probe built from
# this jar was measuring a scheduler the product had already replaced -- the burst work
# that made a transfer faster than one frame per slot existed only on the app side, so
# a probe run would have reported the old pace and been believed. AwdlInj and AwdlTun
# stay in tools/ and are not duplicates: they declare the two-argument shim entry points
# the scripts call and load the deployed library from a static initialiser, and both of
# those are wrong for a node that is handed its library path on a command line.
echo "awdl-build: compiling with $androidjar"
if ! javac -J-Duser.language=en -encoding UTF-8 -source 11 -target 11 -nowarn \
        -classpath "$androidjar" -d "$work/classes" \
        "$root"/tools/AwdlInj.java "$root"/tools/AwdlNull.java \
        "$root"/tools/AwdlTun.java "$root"/tools/AwdlTcp.java \
        "$root"/tools/BleAdvertProbe.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/AwdlData.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/AwdlFrame.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/AwdlProbe.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/AwdlTx.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/BleAdvert.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/Bplist.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/DvZip.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/Cpio.java \
        "$root"/app/src/main/java/com/oshare/airdrop/core/Trace.java; then
    echo "awdl-build: compilation failed" >&2
    exit 1
fi

echo "awdl-build: dexing for API $MIN_API"
# d8 is a Windows binary and does not expand a glob itself, so hand it relative class
# names from inside the tree: the shell expands them and the paths stay short.
( cd "$work/classes" && "$buildtools/d8.bat" --min-api "$MIN_API" --lib "$androidjar" \
    --output "$work/dex" com/oshare/airdrop/core/*.class ) >/dev/null

jar --create --file "$work/awdl-tx.jar" -C "$work/dex" classes.dex

# Same rule as the app build: the artifact has to be newer than the run that made it,
# otherwise what gets pushed is the jar from last time and the probe result describes
# code that is no longer in the tree.
written=$(stat -c %Y "$work/awdl-tx.jar")
if [ "$written" -lt "$started" ]; then
    echo "awdl-build: awdl-tx.jar predates this run; refusing to install a stale jar" >&2
    exit 1
fi
# And the jar has to be built from the current source, which is a different claim: a
# javac that found nothing to recompile still leaves the directory in place.
for want in "$root/tools/AwdlTcp.java" "$root/app/src/main/java/com/oshare/airdrop/core/AwdlData.java"; do
    if [ "$want" -nt "$work/awdl-tx.jar" ]; then
        echo "awdl-build: $want is newer than awdl-tx.jar" >&2
        exit 1
    fi
done

echo "awdl-build: installing"
"$ADB" -s "$SERIAL" push "$work/awdl-tx.jar" /data/local/tmp/awdl-tx.jar >/dev/null
"$ADB" -s "$SERIAL" push "$so" /data/local/tmp/libawdl_inject.so >/dev/null
