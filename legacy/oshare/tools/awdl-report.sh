#!/bin/sh
# Turns the node's log into the four numbers a tuning change is answered by.
#
#   tools/awdl-report.sh            pull the log and report on it
#   tools/awdl-report.sh FILE       report on an already pulled file
#
# The node writes one statistics line every five seconds and everything else it writes
# is per frame, so the log of a real transfer is a few thousand lines where the answer
# is in maybe twenty of them. Reading it by eye is how a run gets judged on whichever
# line scrolled past last.
#
# What is reported, and what each one settles:
#
#   1. throughput    the tx rate of every interval, and the peak. This is the number
#                    the user is looking at on the progress bar.
#   2. window        every TCP window the peer advertised, counted. A peer that stays at
#                    2041 is a peer whose receive buffer is four segments of the size
#                    we names in the handshake, and that makes MSS the lever. A peer
#                    that climbs is a peer that was never window limited.
#   3. burst         how many frames each availability window actually carried. The
#                    histogram is reset every interval, so it describes the last five
#                    seconds rather than everything before them.
#   4. polls         how many iterations found nothing to send. A transfer that stalls
#                    with polls not moving is a transfer whose loop is asleep; a
#                    transfer that stalls with polls climbing is waiting on the peer.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
LOG=/data/local/tmp/oshare-awdl.log

here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)

if [ $# -ge 1 ]; then
    src=$1
else
    src="$here/../build/cnt/awdl-$(date +%m%d-%H%M%S).log"
    mkdir -p "$here/../build/cnt"
    "$ADB" -s "$SERIAL" shell "su -c 'cat $LOG'" >"$src" 2>/dev/null || true
fi

if [ ! -s "$src" ]; then
    printf 'awdl-report: %s is empty -- was the node running?\n' "$src" >&2
    exit 1
fi

printf 'awdl-report: %s (%s lines)\n\n' "$src" "$(wc -l <"$src" | tr -d ' ')"

# The burst histogram is a comma list of nine counts indexed by how many frames the
# window carried, so index zero is always empty and the interesting ones are 1..8.
# Printed as "1:3 2:5 3:2" -- one window carried three frames, five carried two -- so a
# row reads without counting commas.
fmt_hist() {
    printf '%s' "$1" | tr ',' '\n' | awk 'NF{ if ($1+0 > 0) printf "%d:%d ", NR-1, $1 }'
}

# --- 1. throughput ------------------------------------------------------------------
printf '=== throughput, one row per five-second interval ===\n'
printf '%s\n' '   left   tx total   tx rate   rx total   rx rate   polls   frames/window'
grep -a ' left\]' "$src" | while IFS= read -r line; do
    # The app starts the node with seconds=0, meaning run until killed, and the node
    # turns that into a deadline at Long.MAX_VALUE. Printing the subtraction would put
    # 9223348591 on every row, so anything past a day is reported as inf.
    left=$(printf '%s' "$line" | grep -o '\[[0-9]*s left\]' | tr -dc '0-9')
    if [ -n "$left" ] && [ "$left" -gt 86400 ] 2>/dev/null; then left=inf; fi
    txs=$(printf '%s' "$line" | grep -o 'tx=[0-9]*K' | head -1 | tr -dc '0-9')
    txr=$(printf '%s' "$line" | grep -o 'tx=[0-9]*K/[0-9]*KBs' | head -2 | tail -1 | sed 's|.*/||; s|KBs||')
    rxs=$(printf '%s' "$line" | grep -o 'rx=[0-9]*K' | head -1 | tr -dc '0-9')
    rxr=$(printf '%s' "$line" | grep -o 'rx=[0-9]*K/[0-9]*KBs' | head -2 | tail -1 | sed 's|.*/||; s|KBs||')
    poll=$(printf '%s' "$line" | grep -o 'poll=[0-9]*' | tr -dc '0-9')
    hist=$(printf '%s' "$line" | grep -o 'win=[0-9,]*' | head -1 | sed 's|win=||')
    printf '%7s %8sK %8sKB/s %8sK %8sKB/s %7s   %s\n' \
        "$left" "${txs:-0}" "${txr:-0}" "${rxs:-0}" "${rxr:-0}" "${poll:--}" \
        "$(fmt_hist "${hist:-}")"
done

peak=$(grep -a ' left\]' "$src" | grep -o 'tx=[0-9]*K/[0-9]*KBs' | sed 's|.*/||; s|KBs||' | sort -n | tail -1)
printf '\n  peak tx rate: %s KB/s\n' "${peak:-0}"

# --- 2. the peer's receive window ---------------------------------------------------
printf '\n=== TCP window the peer advertised (win=N, counted) ===\n'
grep -a 'win=[0-9]' "$src" | grep -o 'win=[0-9]*' | sort | uniq -c | sort -rn | sed 's/^/  /'
printf '  a constant small value is a peer limited by our MSS; a rising one is not\n'

printf '\n=== MSS the peer advertised, ignoring our own 1360 (mss=N) ===\n'
peermss=$(grep -a 'mss=' "$src" | grep -o 'mss=[0-9]*' | grep -v 'mss=1360' | sort | uniq -c | sort -rn || true)
if [ -n "$peermss" ]; then
    printf '%s\n' "$peermss" | sed 's/^/  /'
else
    printf '  none -- no frame in this log carried a peer MSS option\n'
    printf '  (the handshake lines print win and hlen; hlen=24 is 20 bytes of header\n'
    printf '   plus one four-byte option, which is the MSS, and it is rewritten in place)\n'
fi

# --- 3. the tuning in force ----------------------------------------------------------
printf '\n=== tuning ===\n'
grep -a 'tuning ' "$src" | sed 's/^/  /' | tail -8
if ! grep -qa 'tuning ' "$src"; then
    printf '  none -- the node never read %s, so it ran on build defaults\n' \
        '/data/local/tmp/awdl-tuning.conf'
fi

# --- 4. how it ended -----------------------------------------------------------------
printf '\n=== verdict ===\n'
grep -aE '^VERDICT|^awdl-data: relay packets' "$src" | sed 's/^/  /' || true
if ! grep -qaE '^VERDICT' "$src"; then
    printf '  no VERDICT line -- the node was killed or is still running\n'
fi
