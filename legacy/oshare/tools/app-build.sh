#!/bin/sh
# Builds the debug APK, then proves the APK is the one this run produced.
#
#   tools/app-build.sh            build and verify
#   tools/app-build.sh install    build, verify, install on the attached device
#
# Gradle's summary is not evidence. "36 actionable tasks: 4 executed, 32 up-to-date"
# is printed for a build that changed nothing and for a build that changed one file
# alike, and the up-to-date verdict is the thing that has been wrong here repeatedly:
# the source edits fine, the task is skipped, the APK that gets installed is the
# previous one, and the change is then judged on a device that never received it.
# Nothing in this script reads that line.
#
# What it checks instead, all of it against the artifact rather than the log:
#
#   1. the APK is newer than the instant the build started -- this run wrote it;
#   2. no source file is newer than the APK -- nothing was edited after it;
#   3. the dex contains the strings below and not the ones retired -- the code that
#      was edited is the code inside it.
#
# The string lists are deliberately literal. They cost one line when a constant is
# renamed and in exchange a rename that fails to reach the artifact fails the build
# instead of quietly shipping.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
JAVA_HOME=${JAVA_HOME:-D:/Downloads/project/tools/jdk-17.0.20.1+1}
ANDROID_HOME=${ANDROID_HOME:-D:/Downloads/project/tools/android-sdk}
GRADLE=${GRADLE:-D:/Downloads/project/tools/gradle-8.13/bin/gradle.bat}
export JAVA_HOME ANDROID_HOME

# Why each name is here, because an unexplained list is a list somebody deletes.
#
#   AWDL_WINDOW_FRAMES     the burst width: how many frames one availability window
#                          may carry. Without it a transfer is one frame per slot.
#   AWDL_WINDOW_LAST_TU    the last TU of a slot a burst frame may start in.
#   AWDL_WINDOW_GAP_MS     the gap between two frames of one burst.
#   AWDL_MSS               the segment size the handshake is allowed to name. It decides
#                          the peer's receive buffer, and the peer's receive buffer is
#                          the transfer rate: a measured run advertised 2041 bytes, which
#                          is four segments of the 512 this used to clamp to.
#   AWDL_IDLE_MS           how often the loop looks at the kernel when it had nothing to
#                          send. It used to be the probe interval, a quarter of a second,
#                          and every windowful of data was followed by that silence.
#   awdl-tuning.conf       the path of the runtime tuning file. If this string is in the
#                          artifact then the parameters can be swept without a rebuild,
#                          which is the only way the numbers above get answered.
#   seq_own master_take    the two duty-cycle keys, and the two methods behind them. The
#   self_metric            failure they exist to test returns no error, no log line and no
#   sequenceSummary        observable difference from the state before the edit -- a run
#   takeMaster ownSequence with the key absent measures the baseline and looks exactly
#                          like a run where the experiment failed. Their names are
#                          therefore asserted here rather than trusted to the build log.
#   mssClamp, windowFrames these are field names, not descriptions, and a field name is
#                          in the dex only if the constant really became a field.
#   edgeOpen               the count of frames that left without waiting for a slot
#                          boundary because the window was still open. It is not a
#                          constant, it is a statistics label, and it is the only
#                          evidence in the artifact that the aim itself changed rather
#                          than only the burst width: the earlier round fixed the width
#                          and still paced every frame by the slot clock.
#   timeline:             the discovery clock in SendActivity: process start, files
#                          resolved, endpoint up, first peer on screen, and a round that
#                          failed with the message that says why. "Finding a device is
#                          slow" cannot be answered without it, and a build that quietly
#                          lost the lines would look identical to one that kept them until
#                          the next measurement. Only the prefix is asserted: the token
#                          list is split on spaces, so anything longer with a space in it
#                          would silently become two weak tokens instead of one strong one.
#   publishInbox           the receive path's filing step. Without it a finished transfer
#                          stayed in Android/data where no gallery indexes it -- received,
#                          and findable only by somebody who knows the path. A method name
#                          is in the dex only if the method really shipped, and an APK
#                          missing it looks on the device exactly like the one from before
#                          it existed, because nothing here ever sends us a file.
#   airdrop-publish        the thread that does that filing. It is asserted by name because
#                          doing it on the /Upload thread instead would leave the sender's
#                          progress bar at 100% for the length of a second copy of the file,
#                          which is the one place this change could make a transfer worse.
REQUIRE=${REQUIRE:-"AWDL_WINDOW_FRAMES AWDL_WINDOW_LAST_TU AWDL_WINDOW_GAP_MS AWDL_MSS AWDL_IDLE_MS awdl-tuning.conf mssClamp windowFrames idleGapNs edgeOpen rate_mbps setTxRateMbps radiotapHeader timeline: publishInbox airdrop-publish ctlKind ctl_log tcpWindowRaw synWscale atu= QuickAck armQuickAck seq_own master_take self_metric sequenceSummary takeMaster ownSequence outranks offerSequence sequenceVerdict"}
# RELAY_REPLY_NS was a twenty-millisecond reply interval that became the transfer
# rate. Its absence is the point of the change, so it is asserted absent.
# MSS_CLAMP is the compile-time constant the MSS lever used to be. The lever is still
# there and still named, but as a field, so a build that still says MSS_CLAMP is a build
# where the file was not actually recompiled after the constant was replaced.
FORBID=${FORBID:-"RELAY_REPLY_NS MSS_CLAMP"}

here=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
apk="$here/app/build/outputs/apk/debug/app-debug.apk"

say() { printf '%s\n' "$*"; }
die() { printf 'app-build: %s\n' "$*" >&2; exit 1; }

started=$(date +%s)
say "app-build: building"
( cd "$here" && "$GRADLE" :app:assembleDebug --offline ) || die "gradle failed"

[ -f "$apk" ] || die "no APK at $apk"

# --- 1. this run wrote it, or there was nothing to write -----------------------------
# Gradle skipping the package is only a problem when something actually changed, and
# check 2 is the one that knows: it fails whenever a source file is newer than the APK,
# whatever gradle claimed. Treating "not rewritten" as fatal here made every second run
# fail with nothing edited -- a false alarm loud enough to train a reader past the line,
# and a gate people scroll past is worse than no gate. So this reports what it saw.
written=$(stat -c %Y "$apk")
if [ "$written" -lt "$started" ]; then
    say "app-build: [1/3] gradle rewrote nothing: APK from $(date -d @"$written" '+%H:%M:%S'), run started $(date -d @"$started" '+%H:%M:%S') -- its freshness is check 2's job"
else
    say "app-build: [1/3] APK written by this run ($(date -d @"$written" '+%H:%M:%S'))"
fi

# --- 2. nothing was edited after it -------------------------------------------------
# build.gradle.kts is in the list because a dependency or a minifier change is a
# change to the artifact even though no source file moved.
newest=$(find "$here/app/src" "$here/app/build.gradle.kts" -type f \
    \( -name '*.java' -o -name '*.kt' -o -name '*.xml' -o -name '*.kts' \) \
    -printf '%T@ %p\n' | sort -n | tail -1)
newest_at=${newest%% *}
newest_at=${newest_at%.*}
if [ "$newest_at" -gt "$written" ]; then
    die "edited after the build: ${newest#* } (newer than the APK) -- build again"
fi
say "app-build: [2/3] no source newer than the APK"

# --- 3. the code inside it is this tree's code --------------------------------------
tmp=$(mktemp -d)
# Deleted through its POSIX form: mktemp can hand back C:\Users\...\Temp/tmp.x, which no
# rm can unlink (see tools/unixpath.sh for what that looked like).
. "$here/tools/unixpath.sh"
tmp_rm=$(unixpath "$tmp")
trap 'rm -rf "$tmp_rm"' EXIT HUP INT TERM
unzip -o -q "$apk" 'classes*.dex' -d "$tmp" || die "cannot read dex from $apk"
[ -f "$tmp/classes.dex" ] || die "no classes.dex in $apk"

for want in $REQUIRE; do
    found=
    for f in "$tmp"/classes*.dex; do
        if grep -aq "$want" "$f"; then found=$f; break; fi
    done
    [ -n "$found" ] || die "dex is missing '$want' -- the APK does not contain this source tree"
done
for gone in $FORBID; do
    for f in "$tmp"/classes*.dex; do
        if grep -aq "$gone" "$f"; then
            die "dex still contains '$gone' -- the APK is from before its removal"
        fi
    done
done
say "app-build: [3/3] dex carries $(echo "$REQUIRE" | wc -w) required, none of $(echo "$FORBID" | wc -w) retired"

[ "${1:-}" = "install" ] || { say "app-build: ok"; exit 0; }
# adb.exe is a Windows binary and cannot stat an MSYS path: handed "/d/Downloads/..."
# it answers "failed to stat ...: No such file or directory" while the file is right
# there. Every other place in this repository that passes a path to a Windows program
# converts it first; this one was written before the install branch ever ran under Git
# Bash and the failure reads exactly like a missing APK.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) apk_arg=$(cygpath -w "$apk") ;;
    *)                    apk_arg=$apk ;;
esac
"$ADB" -s "$SERIAL" install -r "$apk_arg" >/dev/null || die "install failed"
say "app-build: installed"
