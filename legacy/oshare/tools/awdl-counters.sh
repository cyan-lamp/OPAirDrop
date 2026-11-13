#!/bin/sh
# Reads the driver's AWDL counters, and diffs two reads so a single run has an answer.
#
#   tools/awdl-counters.sh                 every counter, one "key = value" per line
#   tools/awdl-counters.sh snap FILE       the same, into FILE
#   tools/awdl-counters.sh diff A B        what changed from A to B, numbers subtracted
#   tools/awdl-counters.sh diff --all A B  the same, plus the counters that held still
#
# dmesg is the wrong instrument for this. The bridge prints every transmit
# completion, but through pr_info_ratelimited, so a hundred frames a second show
# up as a handful of lines and any count taken from them is a count of what the
# rate limiter allowed, not of what happened. The driver keeps exact totals as
# module parameters instead, and reading one is a cat.
#
# The counters are cumulative since the module loaded, so on their own they can
# only ever describe "everything that has happened since boot" -- which is why
# diff exists. Two snapshots around one run is the only reading that answers a
# question about that run.
#
# What the numbers mean, grouped by the question they settle:
#
#   Did the peer's radio hear us?
#     tx.data_accepted / tx.status0 / tx.status3
#                                           the only trustworthy ack accounting.
#                                           status0 is acked, status3 is NO_ACK,
#                                           and they cover the data-plane frames
#                                           (our unicast probes), not the control
#                                           frames going out to multicast.
#     tx.accepted / tx.completed            every frame through the bridge, control
#                                           frames included; multicast control has
#                                           no ack to give, so this pair says the
#                                           submit path works and nothing more
#     tx.worker_drop / tx.submit_fail       frames we never got to transmit
#     bridge_dropped                        NOT an ack failure count. Retry tracking
#                                           is registered only while
#                                           passthru_awdl_retry is on, and it is
#                                           off, so a completion resolves to no item
#                                           and returns before the drop is counted.
#                                           Read it as submit-time rejections, which
#                                           is why it stays equal to
#                                           tx.reject_invalid. bridge_retry_gaveup
#                                           is dead for the same reason.
#
#   Did anything come back?
#     native_raw_seen                       raw frames admitted to the passthru path
#     native_raw_delivered                  frames that reached the radiotap netdev
#     native_raw_to_stack                   frames handed to the kernel stack
#     native_raw_ampdu                      how many of those were A-MPDU
#     native_raw_incomplete / wrong_vdev / bad_headroom
#                                           seen but thrown away, and why
#     awdl_rx_duplicates                    frames the dedup layer refused
#
#   Whose address are we aiming at?
#     awdl_target_state                     one line per peer slot: whether the
#                                           peer exists, is mapped, and its id. A
#                                           run that keeps adding slots is telling
#                                           you the phone's AWDL address moves --
#                                           the driver keeps every one of them, it
#                                           does not move one. Note that reply= is
#                                           t->delete_response, a teardown flag,
#                                           and says nothing about received frames.
#     awdl_rx_mac_filter                    the firmware-side RX match: armed, and
#                                           reply=1 means the firmware accepted it
#
#   Is the sender allowed to run at all?
#     awdl_flow_state                       held/pending show backpressure
#     channel_state                         the channel the radio is actually on
#     awdl_trace                            per-stage counts of the tx trace, which
#                                           stay zero while budget=0
#
# The passthru_* switches are dumped too, because a run that changes nothing and a
# run that silently turned a feature off look identical from the counters alone.
set -eu
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=${ADB:-D:/Downloads/project/tools/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-646e00bd}
MODULE=${MODULE:-qca_cld3_peach_v2}
PARAMS=/sys/module/$MODULE/parameters

# Scalars and state blobs alike; the device side flattens newlines so each field
# is exactly one line here.
fields="bridge_accepted bridge_submitted bridge_completed bridge_dropped
bridge_pending bridge_retried bridge_retry_gaveup bridge_last_status
native_raw_seen native_raw_admitted native_raw_delivered native_raw_to_stack
native_raw_incomplete native_raw_wrong_vdev native_raw_bad_headroom native_raw_ampdu
awdl_rx_duplicates awdl_rx_mac_filter awdl_target_state awdl_flow_state
channel_state rx_dst_calls rx_dst_entries rx_dst_ready
tx_metrics agg_stats ba_stats native_rx_stats rx_audit_stats rx_order_stats
tx_pack_stats aux_seen aux_forwarded aux_dropped
pas_null_raw_delivered pas_null_unauthorized"

# The passthru switches. Only the ones that describe a mode, not the tunables that
# nothing here changes, so the dump stays short enough to read.
switches="passthru_native_data_rx passthru_awdl_native_rx passthru_awdl_raw_rx
passthru_awdl_rx_auto passthru_awdl_rx_rearm passthru_awdl_rx_dedup
passthru_awdl_rx_audit passthru_awdl_target_enable passthru_awdl_target_auto
passthru_awdl_target_auth passthru_awdl_ackfilter passthru_awdl_ack_policy
passthru_awdl_retry passthru_awdl_addba passthru_awdl_amsdu passthru_awdl_trace
passthru_mgmt_bridge passthru_rx_diag_budget passthru_awdl_capacity"

# $f expands here, in this shell, while the string is built; $PARAMS likewise. If
# either were left for the device shell the loop would read a path ending in
# "opairshare_" for every iteration.
body=
for f in $fields; do
    body="$body printf '%s = ' $f; cat $PARAMS/opairshare_$f 2>/dev/null | tr '\\n' ' '; echo;"
done
for f in $switches; do
    body="$body printf '%s = ' $f; cat $PARAMS/$f 2>/dev/null | tr '\\n' ' '; echo;"
done

# Composite parameters arrive as one blob -- "queued=0 processing=0 ... status0=724"
# -- which is useless to diff as a string, so the numeric members of the ones whose
# member names are unique get lifted out as key.member. awdl_target_state is
# deliberately not flattened: it repeats slot= once per peer, so key.member would
# collapse five peers into whichever came last. Anything not listed stays whole.
flatten() {
    awk '
    BEGIN {
        split("tx_metrics agg_stats ba_stats native_rx_stats rx_order_stats " \
              "tx_pack_stats awdl_flow_state channel_state awdl_rx_mac_filter " \
              "passthru_awdl_trace", names, " ")
        for (i in names) lift[names[i]] = 1
    }
    {
        line = $0
        sub(/[ \t]+$/, "", line)
        eq = index(line, " = ")
        if (eq == 0) { print line; next }
        key = substr(line, 1, eq - 1)
        val = substr(line, eq + 3)
        if (!(key in lift)) { print key " = " val; next }
        n = split(val, parts, " ")
        for (i = 1; i <= n; i++) {
            j = index(parts[i], "=")
            # The timing sections that follow in tx_metrics start with a bare stage
            # name ("queue", "prepare", ...) and repeat n=/sum_ns=/max_ns/ per stage,
            # so the lift stops at the first token that is not a counter.
            if (j == 0) break
            if (j > 1 && substr(parts[i], j + 1) ~ /^[0-9]+$/)
                print key "." substr(parts[i], 1, j - 1) " = " substr(parts[i], j + 1)
        }
    }'
}

dump() {
    # One adb round trip: a shell loop on the device is faster than forty pops.
    "$ADB" -s "$SERIAL" shell "su -c \"$body\"" | tr -d '\r' | sed '/^$/d' | flatten
}

case ${1:-dump} in
snap)
    out=${2:?usage: awdl-counters.sh snap FILE}
    dump > "$out"
    echo "awdl-counters: $(wc -l < "$out" | tr -d ' ') counters -> $out"
    ;;
diff)
    all=0
    if [ "${2:-}" = "--all" ]; then
        all=1
        shift
    fi
    a=${2:?usage: awdl-counters.sh diff [--all] A B}
    b=${3:?usage: awdl-counters.sh diff [--all] A B}
    awk -v all="$all" '
    function load(path, arr,    line, eq, key, val) {
        while ((getline line < path) > 0) {
            eq = index(line, " = ")
            if (eq == 0) continue
            key = substr(line, 1, eq - 1)
            val = substr(line, eq + 3)
            arr[key] = val
            # File order, not hash order: the device emits the counters in the
            # groups the header describes, and an arbitrary order makes the same
            # run read differently every time.
            if (!(key in seen)) {
                seen[key] = 1
                order[++count] = key
            }
        }
        close(path)
    }
    BEGIN {
        load(ARGV[1], was)
        load(ARGV[2], now)
        ARGV[1] = ARGV[2] = ""
        width = 0
        for (i = 1; i <= count; i++)
            if (length(order[i]) > width) width = length(order[i])
        if (width > 38) width = 38
        printf "%-*s  %12s  %12s  %12s\n", width, "counter", "before", "after", "delta"
        dashes = ""
        for (i = 0; i < width + 40; i++) dashes = dashes "-"
        print dashes
        changed = 0
        for (i = 1; i <= count; i++) {
            k = order[i]
            o = (k in was) ? was[k] : ""
            n = now[k]
            if (o == n) {
                if (all) printf "%-*s  %12s  %12s  %12s\n", width, k, o, n, "0"
                continue
            }
            changed++
            if (o ~ /^[0-9]+$/ && n ~ /^[0-9]+$/)
                printf "%-*s  %12s  %12s  %+12d\n", width, k, o, n, n - o
            else
                printf "%-*s\n    before: %s\n    after:  %s\n", width, k, o, n
        }
        if (!changed) print "(nothing changed)"
    }' "$a" "$b"
    ;;
dump | *)
    dump
    ;;
esac
