#!/usr/bin/env python3
"""我们的 ACK 在对端的 TCP 里有没有被数成「丢包信号」，以及接收侧还剩多少空口没吃到？

为什么写这个（2026-11-17 13:45）：

`ackdig.py` 已经量出一件很扎眼的事——**n3 那轮，我方发出的 12004 个带 TCP 头的帧里，
75% 是逐字节原样重放，其中 68% 落在 16.4±1.5 ms**。那是我们自己的 16 TU 复制机制
（`link.rs`：`head.due_us = start + AW_PERIOD_TU * TU_US`）在为 ACK 大小的帧
（123/135 B，远小于 `BURST_MAX = 496`）做副本。同一份代码在 t1 那轮只占 7%。

于是「对端为什么每 24.45 ms 只发 2 帧」有了一个机制层面的候选解释：ACK 被复制几份、
彼此相隔 16.4 ms，对端 TCP 收到的是**一串 ack 号相同的段**，第 3 个就被当成「下一段丢了」
→ 快速重传 → 拥塞窗口砍半 → 每轮只放 2 个段出来。

但「吻合」不是证据。这个脚本量的就是因果链上的两个数：

  ① **对端的重传率**——它到底有没有在重传。接近 0 就说明我们的重复 ACK 没被当成
     丢包信号，那条解释当场作废。
  ② **「连续 3 个同号 ACK」有多少次**——快速重传的触发条件本身，不是它的相关性代理。
  ③ **ACK 时延**（对端段 → 第一个覆盖它的我方 ACK）。这是接收侧唯一还没人量过的指标。
  ④ **空口效率** = 新字节 / 空口上发出的字节。这条决定「接收侧还有没有东西可捞」：
     98% 就意味着链路上没有可恢复的损失，慢是因为**对端只肯发这么多**。

两条踩过的坑都写进代码里了，别重犯：

  · 类型位在 `fc0` 的 bit 2–3。写成 `fc0 & 0x0f` 时数据帧（0x08）会被读成类型 8，
    于是所有数据帧被跳过，脚本安静地什么都不输出。
  · **必须按连接分段。** 同一个端口对会被多条连接复用（每条有自己的 ISN），
    混在一起算 ack/seq 的大小关系全是垃圾：第一版在 t1 上配出 p50 = 272 ms，
    而 t1 的吞吐是 856 kB/s——那个时延下根本不可能。判据是 SYN，不是端口。

用法：
    python cwnddig.py n3-air.pcap t1-air.pcap
"""
import bisect
import struct
import sys
from collections import Counter

# 我方 MAC 的两个历史值（n3/t1 里的旧地址与现地址），与 holes.py 保持一致。
KNOWN_OURS = {'aa:7d:2c:e3:69:41', '36:bc:0c:fc:83:f3'}

FIN, SYN, RST, ACK = 0x01, 0x02, 0x04, 0x10


def frames(path):
    """按 pcap 逐帧产出 (ts, bytes)：radiotap + 802.11。"""
    d = open(path, 'rb').read()
    magic = struct.unpack('<I', d[:4])[0]
    e = '<' if magic in (0xa1b2c3d4, 0xa1b23c4d) else '>'
    div = 1e9 if magic == 0xa1b23c4d else 1e6
    o = 24
    while o + 16 <= len(d):
        ts, tus, caplen, _wire = struct.unpack(e + 'IIII', d[o:o + 16])
        o += 16
        yield ts + tus / div, d[o:o + caplen]
        o += caplen


def rt_len(d):
    return struct.unpack('<H', d[2:4])[0] if len(d) >= 4 else 0


def hdr_len(fc0, fc1):
    """数据帧头长。**QoS 位只在数据帧存在**，所以这个函数只能用在数据帧上。"""
    fc = fc0 | (fc1 << 8)
    n = 24
    if ((fc >> 8) & 3) == 3:      # ToDS=FromDS=1 → addr4
        n += 6
    if (fc >> 7) & 1:             # QoS
        n += 2
    if (fc >> 15) & 1:            # Order → HT Control
        n += 4
    return n


def tcp_seg(p, rt):
    """(sport, dport, seq, ack, wnd, payload_len, flags)。比 holes.py 多返回 flags。"""
    s = rt + hdr_len(p[rt], p[rt + 1])
    for o in range(s, min(s + 20, len(p) - 40)):
        if (p[o] >> 4) != 6 or p[o + 6] != 6:
            continue
        plen = struct.unpack('>H', p[o + 4:o + 6])[0]
        if plen == 0 or o + 40 + plen > len(p):
            continue
        t = o + 40
        sport, dport = struct.unpack('>HH', p[t:t + 4])
        seq = struct.unpack('>I', p[t + 4:t + 8])[0]
        ack = struct.unpack('>I', p[t + 8:t + 12])[0]
        wnd = struct.unpack('>H', p[t + 14:t + 16])[0]
        doff = (p[t + 12] >> 4) * 4
        return sport, dport, seq, ack, wnd, max(0, plen - doff), p[t + 13]
    return None


def pctl(v, q):
    if not v:
        return float('nan')
    v = sorted(v)
    i = min(len(v) - 1, max(0, int(round(q / 100.0 * (len(v) - 1)))))
    return v[i]


def dig(path):
    # 一次遍历分拣。方向靠 addr2（发射方）判，而不是靠 IP——两端都是 fe80::，分不开。
    segs = []      # (ts, seq, payload_len, flow, flags)
    acks = []      # (ts, ack_value, flow)
    flows = Counter()
    for ts, d in frames(path):
        rt = rt_len(d)
        if len(d) < rt + 24:
            continue
        fc0 = d[rt]
        # 类型位在 bit 2–3，**别写成 fc0 & 0x0f**（见文件头）。
        if ((fc0 >> 2) & 3) != 2:
            continue
        src = d[rt + 10:rt + 16].hex(':')
        got = tcp_seg(d, rt)
        if not got:
            continue
        sport, dport, seq, ack, plen, flags = got[0], got[1], got[2], got[3], got[5], got[6]
        if src in KNOWN_OURS:
            # 只关心纯 ACK：它是对端唯一会读到的窗口信号。
            # 键取反向 4 元组，好和载荷流对上。
            if plen == 0:
                acks.append((ts, ack, (dport, sport)))
        else:
            if plen > 0:
                segs.append((ts, seq, plen, (sport, dport), flags))
                flows[(sport, dport)] += plen

    if not segs:
        print(f'=== {path} ===\n  没有对端载荷段，跳过')
        return
    flow = flows.most_common(1)[0][0]
    segs = [s for s in segs if s[3] == flow]
    acks = [a for a in acks if a[2] == flow]
    acks.sort()
    print(f'=== {path} ===')
    if len(flows) > 1:
        others = ', '.join(f'{k[0]}→{k[1]}:{v}B' for k, v in flows.most_common(4)[1:])
        print(f'（另有 {len(flows) - 1} 条流未计入：{others}）')

    # ---- 按连接分段 --------------------------------------------------------
    # 同一个端口对会被多条连接复用：AirDrop 一次传输里会重连，抓包里也可能是两次
    # 前后相连的传输。每条连接有独立的 ISN，混在一起算 seq 大小关系没有意义。
    # 判据只用 SYN（可靠的连接起点），不靠端口、也不靠时间间隔猜。
    epoch = 0
    ep_of = []
    prev_seq = None
    for s in segs:
        if (s[4] & SYN) or (prev_seq is not None and s[1] < prev_seq - (1 << 20)):
            epoch += 1
        ep_of.append(epoch)
        prev_seq = s[1]
    eps = sorted(set(ep_of))
    ep_span = {}
    for s, e in zip(segs, ep_of):
        lo, hi = ep_span.get(e, (s[0], s[0]))
        ep_span[e] = (min(lo, s[0]), max(hi, s[0]))
    if len(eps) > 1:
        print(f'这条 5 元组上有 {len(eps)} 条连接（按 SYN 切），ACK 只在各自连接内配')

    span = segs[-1][0] - segs[0][0]
    total = sum(s[2] for s in segs)
    print(f'主数据流 {flow[0]}→{flow[1]}，载荷段 {len(segs)}，{total} B / {span:.1f}s '
          f'= **{total / span / 1000:.1f} kB/s**（含重传）')

    # ---- ① 对端的重传率（每条连接各自重置判据） -----------------------------
    # 「序号区间落在已见过的最大末端之内」= 重传或乱序。单跳空口上乱序极少，
    # 两者对「它的节奏不由应用决定」这个结论是同向的，所以不再细分。
    retx_segs = retx_bytes = 0
    gaps = []
    for e in eps:
        end = 0
        prev_ts = None
        for s, ee in zip(segs, ep_of):
            if ee != e:
                continue
            ts, seq, plen = s[0], s[1], s[2]
            if seq + plen <= end:
                retx_segs += 1
                retx_bytes += plen
                if prev_ts is not None:
                    gaps.append(ts - prev_ts)
            else:
                end = max(end, seq + plen)
            prev_ts = ts
    print(f'\n[1] 对端重传 {retx_segs} 段 / {retx_bytes} B '
          f'= **{100.0 * retx_bytes / max(1, total):.1f}% 的空口字节**'
          f'（{len(segs)} 段里 {100.0 * retx_segs / len(segs):.1f}%）')

    # ---- ② 我们制造的「连续 3 个同号 ACK」 ---------------------------------
    # 这正是 TCP 快速重传的触发条件：3 个 ack 号相同、不携带新数据的 ACK。
    # 必须按**发出的顺序**扫，所以 acks 已按 ts 排序。
    triples = dup_extra = 0
    run = 1
    for i in range(1, len(acks)):
        if acks[i][1] == acks[i - 1][1]:
            run += 1
        else:
            if run >= 3:
                triples += 1
                dup_extra += run - 1
            run = 1
    if run >= 3:
        triples += 1
        dup_extra += run - 1
    print(f'\n[2] 我方纯 ACK {len(acks)} 个；连续 ≥3 个同号 {triples} 组'
          f'（每组被对端 TCP 读成一次快速重传的触发）')
    print(f'    「多出来」的重复 ACK {dup_extra} 个 = '
          f'{100.0 * dup_extra / max(1, len(acks)):.1f}%'
          f'；平均每收到 1 个对端段，我们发 {len(acks) / len(segs):.2f} 个 ACK')

    # ---- ③ ACK 时延（只在同一条连接、同一个方向内配） -----------------------
    # 对每个段找**第一个**覆盖它的我方 ACK（ack >= seq+plen）。这是半程下界，
    # 不是 RTT；但 20 ms 对单跳 Wi-Fi 已经足够异常。
    lat = []
    lat_ok = False
    unmatched = 0
    for e in eps:
        lo, hi = ep_span[e]
        sub = [a for a in acks if lo - 0.5 <= a[0] <= hi + 0.5]
        ts_list = [a[0] for a in sub]
        for s, ee in zip(segs, ep_of):
            if ee != e:
                continue
            ts, seq, plen = s[0], s[1], s[2]
            i = bisect.bisect_left(ts_list, ts)
            hit = False
            for j in range(i, min(i + 400, len(sub))):
                if sub[j][1] >= seq + plen:
                    lat.append((sub[j][0] - ts) * 1000)
                    hit = True
                    break
            if not hit:
                unmatched += 1
    if lat:
        # 可信性闸门（14:05 加）。t1 上这条配出 p50 = 292 ms，而它的重传率是 0.0%、
        # 吞吐 856 kB/s——三个数不可能同时为真：ack 号落后约 510 KB 意味着我们的内核
        # 有 510 KB 没确认，那对端早该被窗口卡住并重传了。所以要么是抓包点听到了设备
        # 并未真正收下的帧（监听口和收包不是一回事），要么是帧的时间戳顺序不可靠。
        # 分不清是哪一种，就不许把这个数印成结论。
        late = sum(1 for x in lat if x > 100)
        if late > 0.2 * len(lat):
            print(f'\n[3] **本条不可引用**：{late}/{len(lat)} 个段的配对时延 >100 ms，'
                  f'配出 p50 {pctl(lat, 50):.1f} ms。这与 [1] 的重传率 '
                  f'{100.0 * retx_bytes / max(1, total):.1f}% 矛盾——若 ack 真的落后这么多，'
                  '对端早该被窗口挡住并重传。抓包点听到的帧不等于设备收下的帧。')
        else:
            print(f'\n[3] 段 → 覆盖它的第一个我方 ACK：n={len(lat)}  '
                  f'p50 **{pctl(lat, 50):.1f} ms**  p90 {pctl(lat, 90):.1f} ms  '
                  f'min {min(lat):.1f}  max {max(lat):.1f}')
            b = Counter()
            for x in lat:
                b['<1ms' if x < 1 else '1-5ms' if x < 5 else '5-15ms' if x < 15
                  else '15-30ms' if x < 30 else '>30ms'] += 1
            print('    分箱 ' + '  '.join(f'{k}:{v}' for k, v in b.most_common())
                  + f'   未配上 {unmatched}')
    else:
        print('\n[3] 没配上任何 ACK——方向的判定可能反了，先别看结论')

    # ---- ④ 空口效率 --------------------------------------------------------
    # 新字节 / 空口字节。这条决定「接收侧还有没有东西可捞」：
    # 接近 100% 说明链路上没有可恢复的损失，慢是因为对端只肯发这么多。
    new_bytes = total - retx_bytes
    print(f'\n[4] 新字节 {new_bytes} B / {span:.1f}s = **{new_bytes / span / 1000:.1f} kB/s**'
          f'；空口发了 {total} B，其中 {100.0 * retx_bytes / max(1, total):.1f}% 是重传'
          f' → 空口效率 **{100.0 * new_bytes / max(1, total):.1f}%**')

    # ---- ⑤ 判读 ------------------------------------------------------------
    rate = 100.0 * retx_bytes / max(1, total)
    p50 = pctl(lat, 50) if lat else float('nan')
    print('\n判读：')
    if rate < 2 and triples > len(segs) / 10:
        print(f'  · 重传率只有 {rate:.1f}%，但快速重传触发点有 {triples} 次 → '
              '副本是空口浪费，不是对端慢的原因。拥塞这条解释作废。')
    elif rate >= 10:
        print(f'  · 重传率 {rate:.1f}% → 因果链成立：对端在重传、窗口被砍。'
              '下一步是 copies=1 或把 ACK 排除在复制之外（一行配置的实验）。')
    else:
        print(f'  · 重传率 {rate:.1f}%，中间地带：不足以证明因果，也不足以排除。')
    if lat_ok and p50 == p50 and p50 > 10:
        print(f'  · ACK 时延 p50 {p50:.1f} ms 远超单跳该有的 1~2 ms。')
    if new_bytes / max(1, total) > 0.95 and rate < 2:
        print('  · 空口效率 >95%：接收侧没有可恢复的损失。剩下的差距只能从'
              '「让对端多发」去要，而不是从「我们收得更干净」去要。')


def main(argv):
    for p in argv[1:]:
        dig(p)
        print()


if __name__ == '__main__':
    main(sys.argv)
