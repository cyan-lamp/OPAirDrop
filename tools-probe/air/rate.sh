#!/system/bin/sh
# What rate the iPhone actually transmits at, sampled while a transfer runs.
#
# Why this exists.  Task.md §5.4 left one question open: the 1.69 ms between the two
# frames of a batch is either airtime at a very low rate (→ ~6.6 Mb/s) or the iPhone's
# own inter-frame pacing.  `tools/tap.sh` and the pcap path answer it, but they need
# tcpdump running, a pull, and a radiotap parser.  They are also wrong tools for the
# first question one should ask, because mac80211 already keeps the answer:
#
#   iw dev wonder0 station dump  →  rx bitrate, rx bytes, rx packets
#
# `rx bitrate` is meant to be the rate of the *last frame received* from that peer, and
# it updates on every frame — but on this vendor driver it is not to be trusted: sampled
# at 02:50 on 2026-11-17 the station record read `rx bitrate: 6.0 MBit/s` and
# `tx bitrate: 6.0 MBit/s` (one cached value echoed into both columns, and 6.0 is not a
# rate this link ever used in that direction) while a simultaneous `tcpdump -e` on
# wondertap0 read every incoming frame at `12.0 Mb/s 5745 MHz 11a`.  The radiotap column
# is the ground truth; this sampler is for the *timeline* of throughput and station
# liveness, and for the case where the tap is busy with something else.
#
# Reading the numbers:
#   * 6.0 / 9.0 / 12.0 / 18.0 / 24.0 / 36.0 … with `preamble: long`
#       → legacy OFDM.  The link is not HT on either side, and the airtime for a
#         1386-byte frame is 11088 bits ÷ rate.  At 6 Mb/s that is 1.85 ms, which is
#         the number that makes 1.69 ms a *transmit* cost rather than a scheduling
#         pause.  Then the ceiling is ours to raise: `ibss join` without HT20 and an
#         HT Capabilities TLV of 0x0000 both tell the peer not to bother.
#   * MCS 0–7 at 20 MHz (6.5 … 65 Mb/s) or 40 MHz (13.5 … 135 Mb/s)
#       → HT is already in use, the rate is not the problem, and 1.69 ms is a pause.
#         Then the only remaining lever is frames per availability window, which is
#         the peer's decision, and Task.md §5.3 stands as written.
#   * `tx bitrate` is the rate *we* use towards the peer.  awdld injects 36 Mb/s, so a
#     `tx bitrate` of 6.0 while `rx bitrate` also reads 6.0 means the station record is
#     echoing one cached value for both and neither column should be trusted on its own
#     — cross-check against `/sys/class/net/wonder0/statistics/rx_packets`, which must
#     grow during a transfer for the station record to be updating at all.
#
# Sampling rather than reading once: a single dump taken at the wrong moment reports
# the presence-frame rate.  The last line of the histogram is what to believe.
#
# Usage:  sh rate.sh [seconds] [interval_ms]
#   default 30 s at 250 ms — long enough to cover a transfer's ramp, short enough that
#   the loop itself is not what the phone is busy with.

secs=${1:-30}
step_ms=${2:-250}
iface=wonder0
tun=awdltun0

end=$(( $(date +%s) + secs ))
printf '# t_ms\trx_mbps\ttx_mbps\tpreamble\trx_bytes\trx_pkts\trx_rate_kBps\ttun_rx_bytes\n'

last_b=0
last_t=0
# The clock is `/proc/uptime` reduced to whole milliseconds by integer maths on its two
# text fields, not `date +%s%N`.  Nanoseconds since the epoch overflow the 32-bit
# signed arithmetic in this shell's `$(( ))` and the last line of the first run came out
# at t_ms = -1882 — a wrapped subtraction, which would silently reorder any timeline
# built on it.  uptime is 26 000 000 ms here and stays small for the life of the boot.
now_ms() {
    u=$(cat /proc/uptime)
    s=${u%% *}
    s=${s%%.*}
    frac=${u#*.}
    frac=${frac%% *}
    echo $(( s * 1000 + frac * 10 ))
}
t0=$(now_ms)
while [ "$(date +%s)" -lt "$end" ]; do
    now=$(now_ms)
    t_ms=$(( now - t0 ))

    dump=$(iw dev "$iface" station dump 2>/dev/null)
    rx=$(printf '%s\n' "$dump" | sed -n 's/.*rx bitrate:[[:space:]]*//p' | head -1)
    tx=$(printf '%s\n' "$dump" | sed -n 's/.*tx bitrate:[[:space:]]*//p' | head -1)
    pre=$(printf '%s\n' "$dump" | sed -n 's/.*preamble:[[:space:]]*//p' | head -1)
    rb=$(printf '%s\n' "$dump" | sed -n 's/.*rx bytes:[[:space:]]*//p' | head -1)
    rp=$(printf '%s\n' "$dump" | sed -n 's/.*rx packets:[[:space:]]*//p' | head -1)
    [ -z "$rx" ] && rx='-' && tx='-' && pre='-' && rb=0 && rp=0
    # Bytes that came up the tunnel in this interval: the ground truth for whether the
    # transfer is actually moving, independent of what the station record says.
    tb=$(cat "/sys/class/net/$tun/statistics/rx_bytes" 2>/dev/null)
    [ -z "$tb" ] && tb=0

    # Interval throughput from the tun counter, not from the station record: the
    # station record counts every frame including presence and ACKs, so its delta is
    # always larger than the file's.
    if [ "$last_t" -gt 0 ]; then
        dt=$(( now - last_t ))
        db=$(( tb - last_b ))
        if [ "$dt" -gt 0 ]; then
            kbps=$(( db * 1000 / dt / 1024 ))
        else
            kbps=0
        fi
    else
        kbps=0
    fi
    last_b=$tb
    last_t=$now

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$t_ms" "$rx" "$tx" "$pre" "$rb" "$rp" "$kbps" "$tb"

    # sleep in milliseconds; toybox sleep accepts a fraction, busybox may not.
    case "$step_ms" in
        *.*) sleep "$step_ms" ;;
        *)   sleep "$(( step_ms / 1000 )).$( printf '%03d' $(( step_ms % 1000 )) )" ;;
    esac
done
