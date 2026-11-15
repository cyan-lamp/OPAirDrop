#!/system/bin/sh
# Starts a capture on the device and returns immediately.
#
# The interface is named rather than guessed: `-i any` collects nothing on this build of
# tcpdump, and the tunnel that carries 8770 changes name between runs (wonder0 while the app
# owns it, awdltun0 while the bring-up script does). A capture on the wrong name produces an
# empty file, which reads as "nothing happened" instead of as an error, so the first packets
# are also checked before this script reports success.
set -e
iface=${1:-wonder0}
out=${2:-/data/local/tmp/next.txt}
pkill -f 'tcpdump -i' 2>/dev/null || true
rm -f "$out" "$out.err"
nohup tcpdump -i "$iface" -s 96 -tt 'port 8770 or port 5353' > "$out" 2> "$out.err" &
sleep 2
# Success is spelled out in stderr, not implied by its emptiness: tcpdump always writes
# "listening on <iface>, link-type ..." there, so a non-empty .err proves nothing and an
# empty one proves even less. Anything else in there ("No such device", "Permission
# denied") is the failure we actually care about.
if grep -q '^listening on' "$out.err" 2>/dev/null; then
    echo "started on $iface -> $out ($(grep -m1 '^listening on' "$out.err" | tr -d '\r'))"
else
    echo "tcpdump failed: $(cat "$out.err" 2>/dev/null | tr '\n' ' ')"
    exit 1
fi
