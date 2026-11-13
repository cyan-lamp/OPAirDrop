#!/usr/bin/env bash
# One-shot aggregation sweep: measures what a transfer is actually limited by.
#
#   tools/agg-sweep.sh                          sweep the default plan (amsdu_max 2/3/5)
#   tools/agg-sweep.sh amsdu_max=2,amsdu_max=5  sweep an explicit parameter list
#   tools/agg-sweep.sh -s 2.5 amsdu_max=2,amsdu_max=5
#   tools/agg-sweep.sh --report-only            re-analyse the last log, no device needed
#
# Why this exists: every number so far came from a whole transfer, which mixes
# the TCP ramp, the peer's warm-up and the parameter under test into one average.
# This runs the sampler first, then steps one driver parameter while the same
# transfer is still in flight, and slices the counters at the steps.
#
# Why amsdu_max and not collect_us: with mss 1440 each subframe body is
# 14 + 1440 = 1454 octets, so five subframes are 26 + 4*1456 + 1454 = 7304
# octets. That is below the 7935 byte ceiling, which means the byte ceiling is
# not the binding constraint at this MSS - the 5-subframe ceiling is. Stepping
# amsdu_max 2 -> 3 -> 5 moves bytes-per-aggregate over a 2.5x range on one
# transfer, and that is what decides the question:
#
#   throughput scales with bytes/agg  ->  per-submit time is fixed, so a driver
#                                        change that raises the ceiling helps
#   throughput flat while bytes rise  ->  the limit is upstream or the air, and
#                                        no ceiling change will move it
set -eu
export MSYS_NO_PATHCONV=1

HERE=$(cd "$(dirname "$0")" && pwd)
ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
P=/sys/module/qca_cld3_peach_v2/parameters
LOG=/data/local/tmp/agg-sample.log
OUTDIR="$HERE/../build/cnt"
STEP=2.0
PLAN=amsdu_max=2,amsdu_max=3,amsdu_max=5
ARM_WAIT=${ARM_WAIT:-120}

say() { printf '%s\n' "$*"; }
die() { printf 'agg-sweep: %s\n' "$*" >&2; exit 1; }
dev() { "$ADB" -s "$SERIAL" shell "su -c '$1'" 2>&1 | tr -d '\r'; }
# adb.exe and the Windows python do not understand MSYS /d/... paths, and the
# script itself is run from Git Bash. Convert only on the way out.
win() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }

# Plan entries are "<param>=<value>"; map the short names onto module params so
# a plan stays readable and a typo fails here instead of silently sweeping
# nothing.
param_path() {
    case "$1" in
        collect_us)  printf '%s' "$P/passthru_awdl_collect_us" ;;
        amsdu_max)   printf '%s' "$P/passthru_awdl_amsdu_max" ;;
        amsdu_bytes) printf '%s' "$P/passthru_awdl_amsdu_bytes" ;;
        amsdu)       printf '%s' "$P/passthru_awdl_amsdu" ;;
        phy)         printf '%s' "$P/passthru_awdl_phy" ;;
        mcs)         printf '%s' "$P/passthru_awdl_mcs" ;;
        nss)         printf '%s' "$P/passthru_awdl_nss" ;;
        *)           return 1 ;;
    esac
}

# Traffic is judged from agg_stats bytes, not from bridge_submitted: the bridge
# also carries keepalive frames, roughly 2.5 a second on an idle link, so a
# submitted-delta would arm on nothing and never detect the end of a transfer.
# The counter is cumulative since boot (and since the last module reload), so
# every reading is compared against a baseline, never an absolute threshold.
agg_bytes() {
    local v
    v=$(dev "cat $P/opairshare_agg_stats" | sed -n 's/.* bytes=\([0-9][0-9]*\).*/\1/p' | head -1)
    printf '%s' "${v:-0}"
}

report_only=0
while [ $# -gt 0 ]; do
    case "$1" in
        --report-only) report_only=1; shift ;;
        -s|--step)     STEP=$2; shift 2 ;;
        -h|--help)     sed -n '2,25p' "$0"; exit 0 ;;
        -*)            die "unknown option $1" ;;
        *)             PLAN=$1; shift ;;
    esac
done

mkdir -p "$OUTDIR"
LOGNAME="agg-sweep-$(date +%m%d-%H%M%S).log"

analyse() {   # analyse <local log> <name>
    local py
    py=$(command -v python || command -v python3 || command -v py) || die "no python on PATH"
    "$py" "$(win "$HERE/agg-analyze.py")" "$(win "$1")" | tee "$OUTDIR/$2.txt"
}

if [ "$report_only" = 0 ]; then
    [ -x "$ADB" ] || die "adb not found at $ADB"
    "$ADB" get-state >/dev/null 2>&1 || die "device $SERIAL is not connected"

    [ "$(dev 'id -u')" = "0" ] || die "su -c did not give root"

    # Resolve and validate every step before touching the device, and remember
    # the original value of each parameter so the sweep always leaves the
    # driver the way it found it.
    entries=$(printf '%s' "$PLAN" | tr ',' ' ')
    [ -n "$entries" ] || die "empty plan"
    restore=""
    nsteps=0
    for e in $entries; do
        k=${e%%=*}; v=${e#*=}
        [ "$k" != "$e" ] || die "plan entry '$e' is not param=value"
        path=$(param_path "$k") || die "unknown parameter '$k'"
        cur=$(dev "cat $path" || true)
        [ -n "$cur" ] || die "drv param missing: $path"
        case " $restore " in *" $path "*) ;; *) restore="$restore $path=$cur" ;; esac
        nsteps=$((nsteps + 1))
    done
    say "agg-sweep: plan $PLAN, ${STEP}s per step"
    say "agg-sweep: will restore:$restore"

    say "agg-sweep: installing sampler"
    "$ADB" -s "$SERIAL" push "$(win "$HERE/agg-sample.sh")" /data/local/tmp/agg-sample.sh \
        || die "push failed"
    dev 'chmod 755 /data/local/tmp/agg-sample.sh' >/dev/null

    # The sampler is started only once traffic is seen: arming it up front with
    # a fixed duration meant that a user who took longer than the scan length
    # walked into an empty log.
    total=$(awk -v n="$nsteps" -v s="$STEP" 'BEGIN{printf "%.0f", n*s+12}')

    say ""
    say "  ┌────────────────────────────────────────────────────────────┐"
    say "  │  现在在 iPhone 上发那个 24 MB 的视频                        │"
    say "  │  脚本检测到真实传输后自己开始采样并切参数（最多等 ${ARM_WAIT}s）│"
    say "  └────────────────────────────────────────────────────────────┘"

    armed=0
    waited=0
    hpolls=0
    # Arm on sustained rate, not on absolute growth and not on a single burst.
    # The link always carries a background stream (measured at ~80 KB/s with 76
    # submits a second, and a decaying burst that peaked at 350 KB/s). Any byte
    # threshold, and any single-window test, eventually crosses on that alone.
    # A real transfer runs at 3.2 MB/s, so two consecutive poll windows above
    # 1.2 MB cannot be background traffic.
    base=$(agg_bytes)
    prev_b=$base
    say "agg-sweep: baseline bytes=$base, waiting for a real transfer (2 windows >1.2 MB)"
    while [ "$waited" -lt "$ARM_WAIT" ]; do
        sleep 1
        waited=$((waited + 1))
        now_b=$(agg_bytes)
        delta=$((now_b - prev_b))
        prev_b=$now_b
        if [ "$delta" -gt 1200000 ]; then
            hpolls=$((hpolls + 1))
            if [ "$hpolls" -ge 2 ]; then armed=1; break; fi
            say "  → $((delta / 1000)) KB/s, confirming…"
            continue
        fi
        hpolls=0
        [ $((waited % 10)) = 0 ] && say "  … 还在等 (${waited}s, 当前 $((delta / 1000)) KB/s)" || true
    done
    [ "$armed" = 1 ] || die "no traffic started within ${ARM_WAIT}s; nothing to measure"

    say "agg-sweep: traffic detected, starting sampler for ${total}s"
    # Kept in the foreground of its own adb shell and backgrounded on this side:
    # "adb shell ... &" returns immediately, but nohup on the device keeps the
    # shell's stdout open and adb blocks until the sampler exits, which would
    # swallow the whole sweep.
    "$ADB" -s "$SERIAL" shell "su -c '/data/local/tmp/agg-sample.sh $total 0.12 $LOG'" \
        >/dev/null 2>&1 &
    sampler=$!
    trap 'kill "$sampler" 2>/dev/null || true' EXIT INT TERM
    sleep 0.6

    prev=$(agg_bytes)
    for e in $entries; do
        k=${e%%=*}; v=${e#*=}
        path=$(param_path "$k")
        dev "echo $v > $path && echo MARK $k=$v >> $LOG" >/dev/null
        say "  → $k=$v for ${STEP}s"
        sleep "$STEP"
        # A section with no movement only pads the table, so stop as soon as the
        # peer has clearly finished rather than sleeping through the rest. The
        # counter is cumulative, so the check is a delta against the previous
        # reading, never an absolute value.
        cur2=$(agg_bytes)
        if [ "$cur2" -le "$prev" ]; then
            say "  (transfer ended, stopping the sweep here)"
            break
        fi
        prev=$cur2
    done

    dev "echo MARK done >> $LOG" >/dev/null
    sleep 1
    "$ADB" -s "$SERIAL" pull "$LOG" "$(win "$OUTDIR/$LOGNAME")" >/dev/null || die "pull failed"
    for kv in $restore; do
        dev "echo ${kv#*=} > ${kv%%=*}" >/dev/null
    done
    say "agg-sweep: restored:$restore"
    say "agg-sweep: log -> build/cnt/$LOGNAME"
else
    last=$(ls -t "$OUTDIR"/agg-sweep-*.log 2>/dev/null | head -1) || true
    [ -n "${last:-}" ] || die "no previous sweep log in build/cnt"
    LOGNAME=$(basename "$last")
    say "agg-sweep: re-analysing $last"
fi

analyse "$OUTDIR/$LOGNAME" "${LOGNAME%.log}"
