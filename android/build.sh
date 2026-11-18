#!/usr/bin/env bash
# Builds the APK from source: the Rust library first, then the app that loads it.
#
# Two steps in one script because they are one artifact. Gradle cannot produce the `.so` and
# cargo cannot produce the APK, so a build that runs only one of them produces something that
# looks finished and fails on the device — an APK without a library, or a library no APK
# carries.
#
# Everything is named outright rather than looked up in PATH: this machine keeps the JDK, the
# SDK and Gradle under `tools/`, and `~/.cargo/env` does not exist here. Override any of them
# with the environment variable beside it.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

cargo_bin=${CARGO_BIN:-$HOME/.cargo/bin/cargo}
java_home=${JAVA_HOME:-/d/Downloads/project/tools/jdk-17.0.20.1+1}
gradle_bin=${GRADLE_BIN:-/d/Downloads/project/tools/gradle-9.6.0/bin/gradle}
task=${1:-assembleDebug}

echo "== rust: aarch64-linux-android =="
# `cd` and not only --manifest-path: cargo finds `.cargo/config.toml` by walking up from the
# working directory, so running this script from somewhere else would build without the
# linker, the archiver and the page-size flag declared there — a build that succeeds and
# produces a library the device refuses.
#
# Two packages, because the APK carries both: the library the JVM loads and the daemon the
# bring-up script starts. The daemon is staged into the assets by `stageKernelAssets`, which
# stops the build with the command to build it if it is missing.
(cd "$root" && "$cargo_bin" build --release --target aarch64-linux-android -p opairdrop-ffi -p opairdrop-awdl)

echo "== app: $task =="
JAVA_HOME=$java_home "$gradle_bin" -p "$here" --no-daemon --console=plain "$task"

apk=$(find "$here/app/build/outputs/apk" -name '*.apk' -newer "$root/target/aarch64-linux-android/release/libopairdrop_ffi.so" | head -1)
if [ -n "$apk" ]; then
    echo "== built =="
    echo "$apk"
    # Says whether the library actually made it in. An APK of the right size that is missing
    # one architecture is the failure mode this line exists to catch before an install.
    unzip -l "$apk" | grep -E 'lib/.*/libopairdrop_ffi\.so' || echo "WARNING: the APK has no libopairdrop_ffi.so"
fi
