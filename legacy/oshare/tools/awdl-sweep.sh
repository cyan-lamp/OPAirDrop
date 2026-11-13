#!/system/bin/sh
# Walk the AWDL transmit parameters through the combinations that decide
# whether a frame survives the air, one phase at a time, and record what TCP
# paid for each of them.
#
# The question this answers: the driver reports fail=17 out of 3240 frames sent
# while the sender retransmits a quarter of its segments. A frame the driver
# believes it sent and the peer never acknowledged can only be lost between the
# antenna and the peer's decoder, and the only knob that decides whether a frame
# survives that trip is the rate it was sent at. So: hold everything else still,
# move the rate, and watch RetransSegs per OutSegs.
#
# Usage (as root on the device, with a transfer already running):
#   LIMIT=150 sh /data/local/tmp/awdl-sweep.sh
#
# Prints one CSV line per second. The phase column is written the moment the
# parameters are changed, so the sample that follows a phase change is the first
# one sent under the new settings.

GAP=${GAP:-1}
LIMIT=${LIMIT:-150}

# One glob expansion, not a stat per module: iterating /sys/module by hand walks
# several hundred entries and makes the script look hung before it prints a line.
F=$(ls -d /sys/module/*/parameters/passthru_awdl_amsdu 2>/dev/null | head -1)
if [ -z "$F" ]; then
    echo "no module exposes passthru_awdl_amsdu" >&2
    exit 1
fi
P=${F%/*}

get() { cat "$P/passthru_awdl_$1" 2>/dev/null; }
put() { echo "$2" > "$P/passthru_awdl_$1" 2>/dev/null; }

# 20 s of the link as it stands, then one variable at a time: the rate halved,
# the aggregation cut to a single subframe, aggregation off entirely, and the
# original settings back for a closing baseline.
phase_for() {
    if   [ "$1" -lt 20 ];  then echo 0
    elif [ "$1" -lt 45 ];  then echo 1
    elif [ "$1" -lt 70 ];  then echo 2
    elif [ "$1" -lt 95 ];  then echo 3
    else                        echo 4
    fi
}

apply() {
    # Phase 0/4 is the shipped baseline: mcs=4.  256-QAM (mcs=8) is kept as
    # phase 1 purely as the control that shows the air-loss cliff.
    case "$1" in
    0|4) put mcs 4; put amsdu_max 5; put amsdu 2 ;;
    1)   put mcs 8; put amsdu_max 5; put amsdu 2 ;;
    2)   put mcs 4; put amsdu_max 1; put amsdu 2 ;;
    3)   put mcs 4; put amsdu_max 5; put amsdu 0 ;;
    esac
}

# Three counters, comma separated, no spaces.
#
# OutSegs and RetransSegs live in the Tcp: block of /proc/net/snmp; TCPTimeouts
# lives in the TcpExt: block of /proc/net/netstat. Reading OutSegs out of the
# TcpExt block returns nothing at all, because no such field exists there -- it
# silently indexes field zero and every sample reads as a confident 0. Both files
# print a header line of names above a line of values, so the index is taken from
# the header rather than assumed.
pick() {
    awk -v want="$1" -v pre="$2" -v file="$3" '
        index($0, pre ":") == 1 {
            n = split($0, a, " ")
            if (++seen == 1) { for (i = 2; i <= n; i++) h[a[i]] = i }
            else if (!done) { printf "%.0f", a[h[want]] + 0; done = 1 } }' "$file"
}

# /proc/net/snmp opens with the Ip: block, so the Tcp: pair is neither the first
# nor the second line -- the block has to be selected by name, not by position.
tcp() {
    echo "$(pick OutSegs Tcp /proc/net/snmp),$(pick RetransSegs Tcp /proc/net/snmp),$(pick TCPTimeouts TcpExt /proc/net/netstat)"
}

echo "t,phase,ifidx,txK,rxK,outsegs,retrans,timeouts,mcs,amsdu,amsdu_max,phy,nss"
i=0
cur=-1
while [ "$i" -lt "$LIMIT" ]; do
    t=$i
    ph=$(phase_for "$t")
    if [ "$ph" != "$cur" ]; then
        apply "$ph"
        cur=$ph
    fi
    # The interface index changes whenever the node recreates the tun, and with it
    # the byte counters restart from zero. Recording the index makes a restart a
    # visible step in the series instead of a downward spike that reads as the
    # transfer going backwards.
    ifx=$(cat /sys/class/net/awdltun0/ifindex 2>/dev/null)
    dev=$(awk '/awdltun0/{printf "%d %d", int($2/1024), int($10/1024)}' /proc/net/dev)
    echo "$t,$ph,$ifx,${dev#* },${dev%% *},$(tcp),$(get mcs),$(get amsdu),$(get amsdu_max),$(get phy),$(get nss)"
    sleep "$GAP"
    i=$((i + 1))
done

# Leave the module the way it was found: these values are what the device has
# been running in every measurement taken so far, and a sweep that ends on one
# of its own experiments would silently change the baseline.
apply 4
echo "restored: mcs=4 amsdu=2 amsdu_max=5" >&2
