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
javac -d "$classes" \
  "$here"/com/oshare/airdrop/Native.java \
  "$here"/com/oshare/airdrop/Report.java \
  "$here"/com/oshare/airdrop/RoundTrip.java \
  "$here"/com/oshare/airdrop/SettingsScreen.java \
  "$here"/com/oshare/airdrop/SettingsCheck.java

# The two entry points take different arguments, so they cannot share a command line: the
# round-trip is handed the fixture it was built for, the settings page only a scratch
# directory to write its own file into.
run_java() {
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -Djava.library.path="$(native "$target")" \
    -cp "$classes" "$@"
}

echo "running the round-trip"
mkdir -p "$work/scratch"
run_java com.oshare.airdrop.RoundTrip "$archive" "$inbox" "$names" "$(native "$work/scratch")"

echo "running the settings page"
mkdir -p "$work/settings"
run_java com.oshare.airdrop.SettingsCheck "$(native "$work/settings")"
