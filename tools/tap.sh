#!/system/bin/sh
# A second capture, on the raw 802.11 tap, next to the one on the tunnel.
#
# The tunnel capture says what the kernel received; this one says what the radio heard. The
# difference between the two is the only way to tell "the peer never sent it" from "we lost it
# on the way in", and those two have nothing in common except the symptom.
set -e
out=${1:-/data/local/tmp/tap.txt}
rm -f "$out" "$out.err"
nohup tcpdump -i wondertap0 -s 128 -tt 'ip6' > "$out" 2> "$out.err" &
sleep 2
# "listening on" is the only proof of success; stderr is never empty either way.
if grep -q '^listening on' "$out.err" 2>/dev/null; then
    echo "started on wondertap0 -> $out ($(grep -m1 '^listening on' "$out.err" | tr -d '\r'))"
else
    echo "tcpdump failed: $(cat "$out.err" 2>/dev/null | tr '\n' ' ')"
    exit 1
fi
