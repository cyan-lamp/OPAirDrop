#!/usr/bin/env bash
# The JVM round-trip: a JVM calls into the library and asserts what came back.
#
# This is not `cargo test`. A test target links the rlib, so `cargo test` never builds the
# cdylib the JVM would load — the one artefact whose symbol names, unwind behaviour and
# calling convention only a real JVM can check. "It compiles" is exactly the claim this
# script exists to replace.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../../.." && pwd)

# A login shell usually has this; a non-interactive one started by a tool often does not.
if ! command -v cargo >/dev/null 2>&1; then
  if [ -f "$HOME/.cargo/env" ]; then
    # shellcheck disable=SC1091
    . "$HOME/.cargo/env"
  elif [ -x "$HOME/.cargo/bin/cargo" ]; then
    PATH=$HOME/.cargo/bin:$PATH
  fi
fi

if ! command -v cargo >/dev/null 2>&1; then
  echo "cargo is not on PATH" >&2
  exit 1
fi

for tool in javac java; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is not on PATH; the JVM round-trip needs a JDK 11 or newer" >&2
    exit 1
  fi
done

# Windows programs cannot open an MSYS `/tmp/...`, so every path handed to one is converted.
native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# `pwd` and not `mktemp`'s own output: some builds answer with a Windows path, which the
# cleanup trap then cannot delete.
work=$(cd "$(mktemp -d)" && pwd)
trap 'rm -rf "$work"' EXIT
archive=$(native "$work/archive.cpio")
inbox=$(native "$work/inbox")
names=$(native "$work/names.txt")
classes=$(native "$work/classes")

echo "building the library"
(cd "$root" && cargo build -q -p opairdrop-ffi)

target=${CARGO_TARGET_DIR:-$root/target}/debug
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) library=$target/opairdrop_ffi.dll ;;
  Darwin) library=$target/libopairdrop_ffi.dylib ;;
  *) library=$target/libopairdrop_ffi.so ;;
esac
if [ ! -f "$library" ]; then
  echo "$library was not produced; is crate-type still [\"cdylib\", \"rlib\"]?" >&2
  exit 1
fi

echo "building the fixture archive"
(cd "$root" && cargo run -q -p opairdrop-ffi --example fixture -- "$archive") >"$work/names.txt"

echo "compiling the Java side"
mkdir -p "$work/classes"
# `-encoding` and not the platform default: these sources are UTF-8 and half of them have
# Chinese in them. A JDK 17 on a GBK host (this one) reads them as mojibake and refuses to
# compile, which looks like a code error and is not one. The `run_java` below already says
# `-Dfile.encoding=UTF-8` for the same reason.
javac -encoding UTF-8 -d "$classes" \
  "$here"/com/oshare/airdrop/Native.java \
  "$here"/com/oshare/airdrop/Report.java \
  "$here"/com/oshare/airdrop/RoundTrip.java \
  "$here"/com/oshare/airdrop/RadioShare.java \
  "$here"/com/oshare/airdrop/RadioCheck.java \
  "$here"/com/oshare/airdrop/StackState.java \
  "$here"/com/oshare/airdrop/StackCheck.java \
  "$here"/com/oshare/airdrop/SettingsScreen.java \
  "$here"/com/oshare/airdrop/SettingsCheck.java \
  "$here"/com/oshare/airdrop/Der.java \
  "$here"/com/oshare/airdrop/Identity.java \
  "$here"/com/oshare/airdrop/IdentityStore.java \
  "$here"/com/oshare/airdrop/IdentityCheck.java

# The two entry points take different arguments, so they cannot share a command line: the
# round-trip is handed the fixture it was built for, the settings page only a scratch
# directory to write its own file into.
run_java() {
  java -Xcheck:jni -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -Djava.library.path="$(native "$target")" \
    -cp "$classes" "$@"
}

# HotSpot answers a JNI call made while an exception is pending with a warning; Android's
# CheckJNI aborts the process over the same call, and did — a peer that reset mid-request left
# `SocketException: Connection reset` pending from the reader and `NewByteArray` took the whole
# app down, in `adb logcat -b crash`. The two JVMs disagree about the penalty, not about the
# finding, so the finding is promoted to the penalty here: a build that warns on this desktop
# kills that phone, and this is the only place the warning can be seen before it ships.
#
# `-Xcheck:jni` is what makes the check possible at all. Without it HotSpot does not look, and
# every case below passes on a library that would crash on the device.
run_checked() {
  local out="$work/jvm.out" log="$work/jvm.err"
  if ! run_java "$@" >"$out" 2>"$log"; then
    cat "$out" "$log" >&2
    echo "the JVM exited non-zero" >&2
    exit 1
  fi
  cat "$out"
  cat "$log" >&2
  # Both streams: HotSpot writes this particular warning to stdout, where it would otherwise
  # be read as part of the checks' own output.
  if grep -q 'JNI call made with exception pending' "$out" "$log"; then
    echo "a JNI call was made while an exception was pending; Android aborts the process for" >&2
    echo "this, so the warning above is treated as a failure here" >&2
    exit 1
  fi
}

echo "running the round-trip"
mkdir -p "$work/scratch"
run_checked com.oshare.airdrop.RoundTrip "$archive" "$inbox" "$names" "$(native "$work/scratch")"

echo "running the settings page"
mkdir -p "$work/settings"
run_checked com.oshare.airdrop.SettingsCheck "$(native "$work/settings")"

# The identity is the one thing a peer pins by value: publish a hash and serve another and
# every receiver sees a device that does not match its advertisement. Its DER is built by hand,
# so `verify()` — which recomputes the signature over our TBS — is the only check that would
# notice a wrong length or a field out of order. The directory is fresh per run, because the
# interesting cases are the ones a second run would not reach: what a truncated file leaves
# behind, and whether the identity that replaces it is still coherent.
echo "running the identity"
mkdir -p "$work/identity"
run_java com.oshare.airdrop.IdentityCheck "$(native "$work/identity")"

# The radio switch is two files and a sentence, and it is driven with a directory of its own
# rather than the settings one: the daemon watches that directory on the phone, and a check
# that wrote a request into a fixture would be describing a phone that is really being asked.
echo "running the radio switch"
mkdir -p "$work/radio"
run_java com.oshare.airdrop.RadioCheck "$(native "$work/radio")"

# The bring-up receipt is the only thing the app is allowed to say about the link, so its
# shapes are driven here too: `stack up`, `stack partial`, `stack failed`, and no file at all.
echo "running the bring-up receipt"
mkdir -p "$work/stack"
run_java com.oshare.airdrop.StackCheck "$(native "$work/stack")"
