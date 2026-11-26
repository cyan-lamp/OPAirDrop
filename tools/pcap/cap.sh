#!/system/bin/sh
# Two captures at once, so that one number can be split into the two things it hides.
#
#   tun  (awdltun0)  when the kernel handed the frame to the IP stack
#   air  (wondertap0) what the radio actually heard, radiotap included
#
# The reason is a single measurement that has been read two ways for weeks: "2 frames
# every 24.8 ms, 1.69 ms apart".  That 1.69 ms is either air time (the peer is sending at
# ~6 Mb/s, the mandatory OFDM rate, and 1386 B costs 1.85 ms of air) or the peer's own
# pacing (it has the rate, and is simply not sending more).  The two readings imply
# opposite fixes -- raise the rate we advertise, or raise how many frames the peer is
# willing to send per opportunity -- and only the radiotap rate column can tell them
# apart.  tun alone cannot: it only shows the sum.
#
#   sh cap.sh start [seconds] [snaplen]   start both, self-terminating (no orphan to kill)
#   sh cap.sh stop                stop early, or report what start left behind
#
# **snaplen 现在是个显式参数，默认 0（全量）。** 13:10 踩了一次：沿用原来的 256 抓
# `n4-air.pcap`，结果 tcpdump 把每个数据帧截在 256 字节，`incl=256 / orig=1552`。
# 而 `holes.py` / `ackdig.py` 判「有没有载荷」的方式是
#   `plen == 0 or o + 40 + plen > len(p)` → 跳过
# 1386 B 的载荷配 256 B 的帧，这条恒真，于是整批 TCP 段被**静默丢掉**，脚本报
# 「对端没有带载荷的 TCP 段」。不是没有，是没抓到。256 只适合看 presence 帧
# （前 43 字节就够），任何涉及 TCP 载荷/选项/ack 的分析都必须全量抓。
#
# `timeout` is what makes the capture self-terminating: a `nohup tcpdump &` survives the
# shell that spawned it and stays on the radio forever, stealing frames (a tap can serve
# one listener on some vendor builds) long after the person has forgotten it.
set -u

AIR=/data/local/tmp/air.pcap
TUN=/data/local/tmp/tun.pcap
AIR_PID=/data/local/tmp/air.pid
TUN_PID=/data/local/tmp/tun.pid

stop_one() {
    # $1 pid file, $2 name
    [ -f "$1" ] || return 0
    pid=$(cat "$1" 2>/dev/null)
    [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null
    rm -f "$1"
}

case "${1:-}" in
start)
    secs=${2:-40}
    # 第三个参数是 snaplen，默认 0 = 全量。见文件头的 13:10 教训。
    snap=${3:-0}
    stop_one "$AIR_PID" air
    stop_one "$TUN_PID" tun
    rm -f "$AIR" "$TUN"

    # -s "$snap"：默认 0（全量）。想只看 presence 帧可以传 256 —— 那档能保留完整的
    # 802.11 头加 AWDL TLV（对端的信道图与 HT capabilities 就在那里面），但**会截掉
    # 载荷**，凡是事后要解析 TCP 的抓包都不能用它。
    # -p keeps the interface out of promiscuous mode: the tap is already fed every frame
    # by the vendor driver, and promisc on a virtual monitor can change what arrives.
    # -U is what makes an early stop usable -- without it the last buffer of a killed
    # tcpdump is lost, which is exactly the buffer a short capture consists of.
    #
    # An empty filter is deliberate. A BPF expression on this tap runs in the kernel
    # against a radiotap+802.11 frame, and `ip6` there silently matches nothing on some
    # vendor builds; `tcpdump -d 'ip6'` compiles, so the failure looks like "the peer
    # never sent anything". 全量抓 1552 B/帧、~66 帧/s ≈ 100 KB/s 的文件增长，可以接受。
    timeout "$secs" tcpdump -i wondertap0 -s "$snap" -U -w "$AIR" -p >/dev/null 2>&1 &
    echo $! > "$AIR_PID"

    # The tunnel side is tiny (headers only) and is the only clock the application sees.
    timeout "$secs" tcpdump -i awdltun0 -s "$snap" -U -w "$TUN" -p >/dev/null 2>&1 &
    echo $! > "$TUN_PID"

    echo "capturing ${secs}s:=$AIR $TUN at $(date +%H:%M:%S)"
    ;;
stop)
    stop_one "$AIR_PID" air
    stop_one "$TUN_PID" tun
    # tcpdump needs a moment to write its last block after SIGTERM.
    sleep 1
    for f in "$AIR" "$TUN"; do
        if [ -f "$f" ]; then
            echo "$f $(wc -c < "$f") bytes"
        else
            echo "$f missing"
        fi
    done
    ;;
*)
    echo "usage: sh cap.sh start [seconds] | stop"
    exit 2
    ;;
esac
