#!/bin/sh
# Rewrites /data/local/tmp/awdl-tuning.conf, which a running node re-reads once a second.
#
#   tools/awdl-tune.sh                     show the file and what the node last applied
#   tools/awdl-tune.sh mss=1200            change one parameter
#   tools/awdl-tune.sh mss=1200 gap=2      change several at once
#   tools/awdl-tune.sh ch=1                let frames out in slots the mask leaves empty
#   tools/awdl-tune.sh reset               back to the built-in defaults
#
# Why this exists: every parameter left in AwdlData is a guess about a link that answers
# once per transfer, and the loop that was answering them was edit, build, install, ask
# the user for another twenty-four-megabyte video. The node already runs as root, so the
# answer is a file it reads. A change here takes effect inside one second, on the same
# install, with the peer already discovered -- which is the only way a sweep measures the
# parameter instead of the peer's warm-up.
#
# The node prints one line when it applies a change:
#   awdl-data: tuning mss=1360 frames=8 lastTu=14 gap=0ms idle=3ms rate=36Mbps ignoreCh=false
# If that line does not appear after a change, the node is not running or the file is not
# readable to it, and nothing else here is worth believing.
set -eu
export MSYS_NO_PATHCONV=1

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
CONF=/data/local/tmp/awdl-tuning.conf
LOG=/data/local/tmp/oshare-awdl.log
# Kept in step with the field initialisers in AwdlData. A key missing from the file is
# not reset by the node, so this table is also what "reset" writes out.
DEFAULTS='mss=1360
window_frames=16
window_last_tu=56
window_gap_ms=0
idle_ms=3
ignore_channel=0
rate_mbps=36
mcs=-1
bw=20
vht=0
sgi=0
agg_depth=5
queue_pkts=256
collect_us=-1'

say() { printf '%s\n' "$*"; }
die() { printf 'awdl-tune: %s\n' "$*" >&2; exit 1; }

# The file is written through stdin rather than pushed, because adb.exe cannot stat a
# path this shell creates in /tmp and the alternative -- a Windows-shaped temporary path
# assembled by hand -- is a second thing to get wrong. adb translates the newlines to
# CRLF on the way through; the node's reader drops the carriage returns.
write_conf() {
    printf '%s\n' "$1" | "$ADB" -s "$SERIAL" shell "su -c 'cat > $CONF'" || die "write failed"
}

# Short names, because the point is to type one between two transfers. The long names are
# accepted too: a script written against the file's own vocabulary should keep working.
norm() {
    case "$1" in
        mss|MSS|segment)            echo mss ;;
        frames|frame|width|win)     echo window_frames ;;
        lasttu|last|tu)             echo window_last_tu ;;
        gap|gapms|interval)         echo window_gap_ms ;;
        idle|idlems|poll)           echo idle_ms ;;
        ch|channel|ignore|ignorech) echo ignore_channel ;;
        # mcs used to be an alias for rate_mbps, back when a rate in Mbps was the only
        # thing that could be asked for. It is its own key now: "mcs=7" the rate is a
        # legacy 36 Mb/s frame and "mcs=7" sets the VHT/HT modulation, and letting the
        # alias stand would quietly turn one into the other.
        rate|mbps|speed)            echo rate_mbps ;;
        mcs|mod)                    echo mcs ;;
        bw|band|bandwidth)          echo bw ;;
        # "agg" is the short name; the node only knows agg_depth, and an unrecognised key
        # is dropped without a word -- which is how a run was spent measuring the previous
        # value. Keys that do not map here are rejected below instead of written.
        agg|depth|aggdepth)         echo agg_depth ;;
        q|queue|qpkts)              echo queue_pkts ;;
        collect|collectus)          echo collect_us ;;
        vht|ac)                     echo vht ;;
        sgi|shortgi)                echo sgi ;;
        *)                          echo "$1" ;;
    esac
}

# Every key the node's parser has a case for. Anything else is refused, because a typo
# would otherwise produce a transfer that measures nothing and reports no error.
known() {
    case "$1" in
        mss|window_frames|window_last_tu|window_gap_ms|idle_ms|agg_depth|queue_pkts|\
        collect_us|ignore_channel|rate_mbps|mcs|bw|vht|sgi) return 0 ;;
        *) return 1 ;;
    esac
}

show() {
    say "--- $CONF"
    "$ADB" -s "$SERIAL" shell "su -c 'cat $CONF 2>/dev/null || echo absent'" | tr -d '\r' || true
    say "--- last applied by the node"
    "$ADB" -s "$SERIAL" shell "su -c 'grep \"awdl-data: tuning\" $LOG 2>/dev/null | tail -3'" \
        | tr -d '\r' || true
}

[ $# -eq 0 ] && { show; exit 0; }

if [ "$1" = "reset" ]; then
    write_conf "$DEFAULTS"
    say "awdl-tune: reset to defaults"
    show
    exit 0
fi

# Build the new file from the current one, so one key can change without the others
# reverting -- a sweep that silently resets its own last step measures nothing.
cur=$("$ADB" -s "$SERIAL" shell "su -c 'cat $CONF 2>/dev/null'" | tr -d '\r' || true)
[ -n "$cur" ] || cur=$DEFAULTS

# Drop lines the node would ignore. A key it does not have a case for is skipped in
# silence, so a stale one in this file looks exactly like a setting that did not work.
pruned=
for line in $cur; do
    key=${line%%=*}
    if known "$key"; then
        pruned="$pruned$line
"
    else
        say "awdl-tune: dropping unknown key '$key' from the file" >&2
    fi
done
cur=${pruned%?}

for arg in "$@"; do
    key=${arg%%=*}
    val=${arg#*=}
    [ "$key" = "$arg" ] && die "'$arg' is not key=value"
    key=$(norm "$key")
    known "$key" || die "'${arg%%=*}' maps to '$key', which the node does not read; \
it has a case for mss, frames, lasttu, gap, idle, agg, q, collect, ch, rate, mcs, bw, vht, sgi"
    case "$val" in
        ''|*[!0-9-]*) die "'$val' is not an integer" ;;
    esac
    if printf '%s\n' "$cur" | grep -q "^$key="; then
        cur=$(printf '%s\n' "$cur" | sed "s/^$key=.*/$key=$val/")
    else
        cur=$(printf '%s\n%s=%s' "$cur" "$key" "$val")
    fi
done

write_conf "$cur"
say "awdl-tune: wrote $CONF"
printf '%s\n' "$cur" | sed 's/^/  /'
say "awdl-tune: waiting for the node to apply it"

# Read the line the node prints back. Two seconds covers the once-a-second re-read, and
# the whole point of this script is that a parameter is only believed once the node has
# said it took it -- "applied within a second" was a claim, this is the evidence.
sleep 2
"$ADB" -s "$SERIAL" shell "su -c 'grep \"awdl-data: tuning\" $LOG 2>/dev/null | tail -1'" \
    | tr -d '\r' | sed 's/^/  /'
say "awdl-tune: if that line does not show what was just written, the node is not running"
