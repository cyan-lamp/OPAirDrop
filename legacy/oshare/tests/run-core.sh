#!/bin/sh
# Codec and transport tests for the AirDrop core. Plain JVM, no device, no SDK.
set -eu
# The Windows JDK cannot open MSYS-style /d/... paths, so normalize when possible.
# On Linux and macOS `pwd -W` is unsupported and the input is returned unchanged.
winpath() {
    _w=$(cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || _w=
    if [ -n "$_w" ]; then printf '%s' "$_w"; else printf '%s' "$1"; fi
}
root=$(winpath "$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)")
work=$(winpath "$(mktemp -d)")
# javac needs the Windows form; deletion needs the POSIX one -- see tools/unixpath.sh.
. "$root/tools/unixpath.sh"
work_rm=$(unixpath "$work")
trap 'rm -rf "$work_rm"' EXIT
cd "$root"
# Every core class except the ones that import android.* -- BLE advertising, AWDL frame
# injection and the multicast hop limit all reach for platform APIs a desktop JDK does
# not have. The glob this replaces pulled them in and the whole suite stopped compiling
# with dozens of errors that all named Android classes -- a runner failure disguised as
# a code failure, which is worse than either.
#
# The exclusion list has to be kept honest: this file used to name only BleWake, and
# MulticastHop was added later, after which nothing here compiled at all and the suite
# silently stopped running. Anything skipped has a stand-in under tests/stubs if a
# tested class still refers to it.
coresrc=$(ls "$root"/app/src/main/java/com/oshare/airdrop/core/*.java \
    | grep -vE '/(BleWake|MulticastHop|AwdlData|AwdlProbe|AwdlTx)\.java$' | tr '\n' ' ')
javac -encoding UTF-8 -d "$work" \
    $coresrc \
    "$root/tests/stubs/MulticastHop.java" \
    "$root/tests/MdnsTest.java" \
    "$root/tests/PlistTest.java" \
    "$root/tests/CpioTest.java" \
    "$root/tests/DvZipTest.java" \
    "$root/tests/HttpTest.java" \
    "$root/tests/ProtocolTest.java" \
    "$root/tests/IdentityTest.java" \
    "$root/tests/InboxTest.java" \
    "$root/tests/ServerTest.java" \
    "$root/tests/MdnsResponderTest.java" \
    "$root/tests/WiringTest.java" \
    "$root/tests/BrowserTest.java" \
    "$root/tests/SharedNamesTest.java" \
    "$root/tests/SendTest.java"
java -cp "$work" com.oshare.airdrop.core.MdnsTest
java -cp "$work" com.oshare.airdrop.core.PlistTest
java -cp "$work" com.oshare.airdrop.core.CpioTest
java -cp "$work" com.oshare.airdrop.core.DvZipTest
java -cp "$work" com.oshare.airdrop.core.HttpTest
java -cp "$work" com.oshare.airdrop.core.ProtocolTest
java -cp "$work" com.oshare.airdrop.core.IdentityTest
java -cp "$work" com.oshare.airdrop.core.InboxTest
java -cp "$work" com.oshare.airdrop.core.ServerTest
java -cp "$work" com.oshare.airdrop.core.MdnsResponderTest
java -cp "$work" com.oshare.airdrop.core.WiringTest
java -cp "$work" com.oshare.airdrop.core.BrowserTest
java -cp "$work" com.oshare.airdrop.core.SharedNamesTest
java -cp "$work" com.oshare.airdrop.core.SendTest
