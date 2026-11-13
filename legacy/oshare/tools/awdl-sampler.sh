#!/system/bin/sh
# One line per sample, everything needed to tell "rate too high" from
# "window misaligned" apart:
#
#   R <uptime> <tun_tx> <tun_rx> <acked> <segs_out> <retrans> <cwnd> <rtt> <mcs> <nss> <phy>
#
# Loss is (d retrans / d segs_out) -- retrans is a running total, not a gauge,
# so two samples are always needed. tun_tx is what actually left the box and
# acked is what the iPhone confirmed; the gap between their deltas is the air
# loss the driver's own "fail" counter cannot see, because the driver knows only
# that the frame was injected, never that it arrived.
#
# The socket of interest is the busy one, not the newest one. The discovery
# round opens a fresh connection every ten seconds and any of those has
# segs_out under ten, so "first match" reports a link that is doing nothing.
# Pick the connection with the most segments sent instead.

M=/sys/module/qca_cld3_peach_v2/parameters
GAP=${GAP:-1.0}
LIMIT=${LIMIT:-600}

socks() {
    ss -tin 2>/dev/null | awk '
    function num(s, k,   m) {
        if (match(s, k "[0-9]+")) {
            m = substr(s, RSTART + length(k))
            sub(/[^0-9].*/, "", m)
            return m + 0
        }
        return 0
    }
    /^[^ \t]/ { hdr = $0; next }
    {
        if (hdr !~ /:8770/) next
        s = hdr " " $0
        segs = num(s, "segs_out:")
        if (segs <= best) next
        best = segs
        ba = num(s, "bytes_acked:")
        br = 0
        if (match(s, /retrans:[0-9]+\/[0-9]+/)) {
            t = substr(s, RSTART, RLENGTH); sub(/.*\//, "", t); br = t + 0
        }
        bc = num(s, "cwnd:")
        bt = 0
        if (match(s, / rtt:[0-9.]+/)) { t = substr(s, RSTART + 5, RLENGTH - 5); bt = t + 0 }
    }
    END { print ba + 0, best + 0, br + 0, bc + 0, bt + 0 }
    '
}

one() {
    up=$(cut -d' ' -f1 /proc/uptime)
    set -- $(awk '$1 == "awdltun0:" { print $2, $10 }' /proc/net/dev)
    rx=$1
    tx=$2

    set -- $(socks)

    mcs=$(cat $M/passthru_awdl_mcs 2>/dev/null)
    nss=$(cat $M/passthru_awdl_nss 2>/dev/null)
    phy=$(cat $M/passthru_awdl_phy 2>/dev/null)

    echo "R $up ${tx:-0} ${rx:-0} $1 $2 $3 $4 $5 ${mcs:-?} ${nss:-?} ${phy:-?}"
}

i=0
while [ $i -lt $LIMIT ]; do
    one
    sleep $GAP
    i=$((i + 1))
done
