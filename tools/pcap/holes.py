#!/usr/bin/env python3
"""接收侧「洞」分析：我方 ack 卡在某个值上多久，卡住期间洞上方的数据是否还在到达。

为什么必须单独有这个脚本（2026-11-17 13:20）：

`ackdig.py` 发现 n3（Xs Max，0.119 MB/s）的 ACK 里 **29.5% 带 SACK 块**，而 t1
（iPhone 13，0.294 MB/s）只有 **0.05%**。SACK 块的含义是「我收到了这一段，但它前面
有个洞」。这不是重发、不是窗口、不是调度——是**真的有一段字节没收到**。

于是「Xs Max 慢」这个问题的性质变了：此前把它归到「iOS 18.7 在 A12 上调度保守」
（改不动），如果慢是因为持续的洞在卡 ack 点，那它是**丢包/重排**问题，改法完全不同
（甚至可能是我们这边收帧的问题——同一块 wondertap0，同一台机器）。

这个脚本只回答五件事，不猜原因：
  1. ack 点停在历史最大值上的时间占多大比例。
  2. 卡住期间有没有**更高序号的数据到达**（有 → 洞是真的，且对端还在发新的，
     只是它没法越过洞；没有 → 洞只是「没新数据」，不算丢包）。
  3. 洞有多大、SACK 块左沿相对 ack 点多远、卡住期间我们发了多少重复 ACK。
  4. **长停顿里对端停没停**（[5]）——这是唯一有判别力的一条，见下面的判读。
  5. 对端的帧间隔结构与全片载荷速率（[6]，13:55 加）。它不是「洞」的分析，放进来的
     唯一理由是**可复现**：§5.9 表里「突发周期」与「停顿内到达速率」两行需要一条命令
     就能重算，而它们本来是那个一次性 `/tmp/burst.py` 算的（那批脚本有 `hdr_len`
     位次写错的黑历史，见本文件 hdr_len 的 docstring）。

判读（写在这里，免得下次又倒因为果）。**这一段 13:45 整段重写过，第一版写反了**：

  · 洞确实存在：n3 的 ACK 有 29.5% 带 SACK，最常见的洞是 2772 B（= 2 段 × 1386 B）。
  · 但**洞不是慢的原因**。判据是两条一起看：
      ① 两段最长停顿（5.88 s / 4.89 s）里，对端各送进 629 KB / 507 KB，速率
         106.8 / 103.4 KB/s，与全片平均 106.5 KB/s 一致——**停顿期间吞吐一点没掉**；
      ② 对端每 24.4 ms 送 2 帧 = 2772 B，折算 **113 KB/s，就是实测的那个 106.5**。
    也就是说：**吞吐上限等于节拍，洞只是被节拍顺手带出来的**——每窗只有 2 帧，
    只要其中一帧落在洞的远侧，就要等下一窗才补得上，于是 ack 点走走停停、SACK 常驻。
  · 所以第一版写的「洞存在 + 洞上方持续到达 → 瓶颈是洞，对端开小窗是对端的结果」
    **是错的**：如果瓶颈是洞，补上洞之后 ack 一跳、对端就该加速；实测 ack 结束一段
    停顿只推进 **1386 B**（对端前沿此时还高出 ack 627,858 B），随后又卡住，而速率
    始终贴着 113 KB/s 这条线。
  · 「停顿期间对端照送 106 KB/s」还说明另一件事：**它不看 ack**。5.88 s 里它没拿到
    任何一个新确认，却照发不误——cwnd 要真是限制因素，早就该停了。整段时间里我方
    发了 **1756 个 ACK**（≈300/s），全都带同一个旧 ack 值，所以也不是我们没回。

[5] 是专门量这件事的：把长停顿分成「期间对端也停了」（同时静止，与发送策略无关）
和「期间对端照送 ≥50 KB」（活跃停顿）。n3 = 3 段长停顿里 2 段活跃（10.8 s / 1136 KB /
105.5 KB/s），t1 = 1 段长停顿、0 段活跃。**这才是两条链路的分水岭**；[1] 那个
「卡住占比」两条都高（71% / 66%），没有判别力。

顺带记两条操作教训（都是本轮踩的）：
  · `reached` 只能按**区间增量**用（r1−r0），第一版拿它当「卡住期间送达」的绝对值，
    多算了一大截。
  · 元组字段数改过之后不要用 `*_x, r0, r1` 解包——它会静默取到最后两个，算出负数
    （实测打出 −1133.7 KB）。

用法：
    python3 holes.py n3-air.pcap t1-air.pcap
"""
import struct
import sys
from collections import Counter

KNOWN_OURS = {
    'aa:7d:2c:e3:69:41',
    '36:bc:0c:fc:83:f3',
}


def frames(path):
    """按 pcap 逐帧产出 (ts, bytes)。支持微秒/纳秒两种魔数、两种字节序。"""
    d = open(path, 'rb').read()
    magic = struct.unpack('<I', d[:4])[0]
    if magic in (0xa1b2c3d4, 0xa1b23c4d):
        e = '<'
    else:
        e = '>'
    o = 24
    while o + 16 <= len(d):
        ts, tus, caplen, _wire = struct.unpack(e + 'IIII', d[o:o + 16])
        o += 16
        # a1b23c4d 是纳秒分辨率，除法不同；这里两份抓包都是微秒，仍按魔数判一次，
        # 免得以后用 -j nano 抓一份就静默错 1000 倍。
        div = 1e9 if magic in (0xa1b23c4d, 0xa1b23c4d) else 1e6
        yield ts + tus / div, d[o:o + caplen]
        o += caplen


def rt_len(d):
    return struct.unpack('<H', d[2:4])[0] if len(d) >= 4 else 0


def hdr_len(fc0, fc1):
    """802.11 头到 LLC 之间的长度。

    **这条注释是给自己留的教训，不是指控代码。** 13:20 我写了一批一次性脚本
    （`/tmp` 里那几个），里面有个 `hdrlen(fc0, fc1)` 把位次写成了
    `(fc1 >> 4) & 0x8` / `(fc1 >> 7) & 1` —— 取的都是 FC 的 Order 位（bit 15），
    而不是 QoS 位（bit 7），算出来的偏移就少 2 字节。它们之所以还能出结果，
    是因为后面那段「在 [start, start+20] 里扫 IPv6 特征」把偏移救回来了
    （实测 12004/12004 帧都能解出双端 fe80::，所以当时的结论没被污染）。
    但那是**兜底不是正确**：扫描一旦落在别的位置，所有字段都会静默错位。
    同一个错误我差点写进这里，所以这段按位次老老实实写对：
      bit 7  = QoS Data（+2，QoS Control）
      bit 15 = Order（QoS 数据帧 + HT Control 时置位，+4）
    `duty.py` 的 `dot11_hdr_len` 和 `ackdig.py` 的 `hdr_len` 都是对的（它们传入
    的是把 fc1 左移 8 位合并后的整值，所以用 bit 8/9/15 的写法没问题）。
    """
    fc = fc0 | (fc1 << 8)
    n = 24
    if ((fc >> 8) & 3) == 3:      # ToDS=FromDS=1 → 有 addr4
        n += 6
    if (fc >> 7) & 1:             # QoS
        n += 2
    if (fc >> 15) & 1:            # Order → HT Control
        n += 4
    return n


def tcp_seg(p, rt):
    """返回 (sport,dport,seq,ack,wnd,payload_len,sack_tuple)。

    AWDL 的封装是 802.11 | LLC/SNAP(8) | Apple AF(8) | LLC/SNAP(8) | IPv6 | TCP，
    所以从 802.11 头结束还要跳 16 字节才是 IPv6。这里不硬编码 16，而是扫描 ——
    但扫描范围只有 20 字节，且要求 IPv6 头自洽（版本 6、NextHeader 6、长度够），
    实测没有误判（见 hdr_len 的注释）。
    """
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
        opts = p[t + 20:t + doff]
        sack = None
        i = 0
        while i < len(opts):
            k = opts[i]
            if k == 0:
                break
            if k == 1:
                i += 1
                continue
            if i + 1 >= len(opts):
                break
            ln = opts[i + 1]
            if ln < 2 or i + ln > len(opts):
                break
            if k == 5 and ln >= 10:      # SACK，取第一块（够用：其余块极少）
                sack = struct.unpack('>II', opts[i + 2:i + 10])
            i += ln
        return sport, dport, seq, ack, wnd, max(0, plen - doff), sack
    return None


def pctl(v, q):
    if not v:
        return 0.0
    s = sorted(v)
    return s[min(len(s) - 1, int(len(s) * q))]


def analyze(path):
    # ---- 认对端：排除自己之后，发字节最多的 MAC ---------------------------
    # 按**字节**不按帧数：n3 里我方 12004 帧 > 对端 5100 帧，按帧数会把对端认成自己。
    tx = Counter()
    for _ts, p in frames(path):
        rt = rt_len(p)
        if len(p) < rt + 24 or ((p[rt] >> 2) & 3) != 2:
            continue
        m = p[rt + 10:rt + 16].hex(':')
        if m in KNOWN_OURS:
            continue
        tx[m] += len(p)
    if not tx:
        print(f'{path}: 找不到对端')
        return
    peer = max(tx, key=tx.get)

    # ---- 主连接：对端灌数据最多的五元组 -----------------------------------
    vol = Counter()
    for _ts, p in frames(path):
        rt = rt_len(p)
        if len(p) < rt + 26 or ((p[rt] >> 2) & 3) != 2:
            continue
        if p[rt + 10:rt + 16].hex(':') != peer:
            continue
        g = tcp_seg(p, rt)
        if g and g[5]:
            vol[(g[0], g[1])] += g[5]
    if not vol:
        print(f'{path}: 对端没有带载荷的 TCP 段')
        return
    flow = vol.most_common(1)[0][0]

    ev = []
    for ts, p in frames(path):
        rt = rt_len(p)
        if len(p) < rt + 26 or ((p[rt] >> 2) & 3) != 2:
            continue
        m = p[rt + 10:rt + 16].hex(':')
        if m != peer and m not in KNOWN_OURS:
            continue
        g = tcp_seg(p, rt)
        if not g:
            continue
        if m == peer and (g[0], g[1]) == flow and g[5]:
            ev.append((ts, 'DATA', g[2], g[2] + g[5]))
        elif m != peer and (g[1], g[0]) == flow and g[5] == 0:
            ev.append((ts, 'ACK', g[3], g[6]))
    ev.sort(key=lambda x: x[0])
    if not ev:
        print(f'{path}: 主连接上没有可用事件')
        return

    t0 = ev[0][0]
    span = ev[-1][0] - t0
    print(f'=== {path} ===')
    print(f'对端 {peer}   主连接 {flow}   跨度 {span:.1f}s   '
          f'对端段 {sum(1 for e in ev if e[1]=="DATA")}   我方纯 ACK '
          f'{sum(1 for e in ev if e[1]=="ACK")}')

    # ---- 1/2. ack 推进时间线 ---------------------------------------------
    # 注意 `reached` 是**滚动累计最大值**，只能回答「卡住时对端已经发到多前」，
    # 不能回答「卡住期间又来了多少」——后者要记卡住起点和终点的差。第一版把两者
    # 混成一个数，得出「卡住的 5.9 s 里对端发了 629 KB」，实际那 629 KB 里有一大
    # 截是卡住之前就发出来的。区别很重要：前者说明「对端仍在推进」，后者才说明
    # 「对端在这段停顿里一直在发新数据」。
    best = -1
    last_adv = None
    stuck_from = None
    reached_start = 0
    stalls = []   # (起, 止, 卡住时的 ack, 起点对端末端, 终点对端末端, 结束帧的 ack)
    adv_gaps = []
    reached = 0
    for ts, kind, val, extra in ev:
        if kind == 'DATA':
            reached = max(reached, extra)
            continue
        if val > best:
            if stuck_from is not None:
                # 第 6 个字段（vend）是**结束这段停顿的那一帧到底把 ack 推到哪**。
                # 13:45 补：没有它，[2] 里那个「对端末端 +629244 B」会被读成
                # 「ack 一次推进 629 KB」，而它其实是对端前沿，不是 ack 的推进量。
                # 实测两段大停顿的 ack 推进分别只有 1386 B 和几百字节——ack 是被
                # 一段一段的洞按住的，不是一口气追上对端。
                stalls.append((stuck_from, ts, best, reached_start, reached, val))
            if last_adv is not None:
                adv_gaps.append(ts - last_adv)
            best = val
            last_adv = ts
            stuck_from = None
        elif stuck_from is None:
            stuck_from = ts
            reached_start = reached
    if stuck_from is not None:
        stalls.append((stuck_from, ev[-1][0], best, reached_start, reached, None))

    stuck = sum(b - a for a, b, *_ in stalls)
    # 13:35 加一条限定：这个百分比**不区分「真卡住」和「重复 ACK」**。只要有一帧
    # ack 等于历史最大值就算卡住，而对端每发一段、我们就补一个同值 ACK，所以任何
    # 一条 TCP 流都会给出很高的比例。实测：n3 = 71%，t1（快的那条）= 66%。
    # **它不能单独判死任何事**，只能当成「ack 点推进得稀」的粗指标。真正有判别力的是
    # [5]：长停顿期间对端还在不在送新数据。
    print(f'\n[1] ack 等于历史最大值的时长占比 {stuck:.1f}s / {span:.1f}s '
          f'= {100.0*stuck/max(span,1e-9):.0f}%   ← 含重复 ACK，不区分真假卡住，'
          f'两条链路都会很高')
    print(f'    ack 推进 {len(adv_gaps)} 次，间隔 p50 {pctl(adv_gaps,.5)*1000:.1f} ms  '
          f'p90 {pctl(adv_gaps,.9)*1000:.1f} ms  max {max(adv_gaps or [0])*1000:.0f} ms')

    # ---- 2. 卡住期间洞上方有没有新数据 -----------------------------------
    # 「卡住时对端末端 > ack」＝ 有洞；「卡住区间内对端末端还在涨」＝ 对端在继续
    # 发新数据，只是它不补洞里那一段。两件事分开看，缺一个都不能定罪。
    # 只统计 ≥50 ms 的停顿：低于这个量级就是 ACK 之间的正常间隙，凑数百段没意义。
    long_stalls = [s for s in stalls if s[1] - s[0] >= 0.05]
    long_stalls.sort(key=lambda s: s[1] - s[0], reverse=True)
    print(f'\n[2] 卡住 ≥50 ms 的段 {len(long_stalls)} 个 '
          f'（占卡住总时长 {100.0*sum(b-a for a,b,*_ in long_stalls)/max(stuck,1e-9):.0f}%）')
    for a, b, v, r0, r1, ve in long_stalls[:3]:
        # 「ack 推进」与「对端前沿」是两件事，分两列打，别再混成一个数（13:45）。
        print(f'    {a-t0:7.2f}s → {b-t0:7.2f}s  {(b-a)*1000:8.1f} ms   '
              f'ack={v}  期间对端末端 +{r1-r0} B  洞 {max(0, r0-v)} B  '
              f'结束时 ack 推进 {0 if ve is None else ve-v} B')
    with_hole = sum(1 for a, b, v, r0, r1, _ve in stalls if r0 > v)
    print(f'    全部 {len(stalls)} 段里，{with_hole} 段「卡住时对端已发末端就高于 ack」（= 有洞）')

    # ---- 3. SACK ---------------------------------------------------------
    sacks = [(ts, val, extra) for ts, kind, val, extra in ev
             if kind == 'ACK' and extra]
    print(f'\n[3] 带 SACK 的 ACK {len(sacks)} 个 '
          f'（{100.0*len(sacks)/max(1,sum(1 for e in ev if e[1]=="ACK")):.1f}%）')
    if sacks:
        # 左沿−ack 就是「洞有多大」的直接读数，比任何推算都硬。
        d = Counter(e[0] - v for _ts, v, e in sacks)
        print(f'    SACK左沿 − ack（洞大小）取值 ' + str(d.most_common(5)))
        w = Counter(e[1] - e[0] for _ts, v, e in sacks)
        print(f'    SACK 块宽度 取值 ' + str(w.most_common(5)))

    # ---- 4. 停顿区间内对端的到达速率 -------------------------------------
    # 这一块用来判死一件事：**对端的发送速率跟 ack 有没有关系。**
    # 如果在一个「ack 连续 5 秒不动」的区间里，对端仍然以和平时一样的速率送新数据，
    # 那就说明它既不是 cwnd 受限、也不是被 ack 时钟驱动——它就是按自己的节奏发。
    # 反过来，如果停顿区间内到达速率明显掉下来，那才轮到「它在等我们的 ack」。
    datas = [(ts, s, e) for ts, kind, s, e in ev if kind == 'DATA']
    acks = [(ts, v) for ts, kind, v, _x in ev if kind == 'ACK']
    print(f'\n[4] 停顿区间内的到达速率（全片平均 '
          f'{sum(e-s for _,s,e in datas)/max(span,1e-9)/1000:.1f} KB/s）')
    for a, b, v, r0, r1, ve in long_stalls[:2]:
        inside = [(ts, s, e) for ts, s, e in datas if a <= ts <= b]
        byt = sum(e - s for _, s, e in inside)
        dur = max(b - a, 1e-9)
        gaps = [inside[i + 1][0] - inside[i][0] for i in range(len(inside) - 1)]
        # 洞是怎么被填的：停顿结束后第一个把 seq 落到 ack..ack+洞 区间的段
        filler = next((ts for ts, s, e in datas if ts >= b and s <= v < e), None)
        print(f'    {a-t0:7.2f}s 段：{len(inside)} 段 / {(b-a):.1f}s = '
              f'{len(inside)/dur:.1f} 帧/s，{byt/1000:.1f} KB，'
              f'{byt/dur/1000:.1f} KB/s，帧间隔 p50 {pctl(gaps,.5)*1000:.1f} ms')
        # 13:40 加这一行：停顿里我们到底有没有在发 ACK、发的是哪个值。
        # 没有这一行，[4] 会被读成「对端不理我们」；有了它才能分清两种可能：
        #   · 我们在这 5.9 s 里一帧没发 → 是我们自己发不出去（TX 队列堵住）；
        #   · 我们发了 N 帧、全带同一个旧 ack → 我们发出去了，是 ack 点本身没推进
        #     （接收侧有个洞卡住了累计确认，对端继续往洞里灌新数据）。
        ai = [(ts, val) for ts, val in acks if a <= ts <= b]
        if ai:
            print(f'      期间我方 ACK {len(ai)} 帧，携带的 ack 全部 ≤ {max(v for _, v in ai)}'
                  f'（卡住值 {v}），最大 ack 是否推进：'
                  f'{"是" if max(x for _, x in ai) > v else "否"}')
        else:
            print('      期间我方一帧 ACK 都没发（← 这一条会把责任指回我们自己）')
        print(f'      停顿结束由「seq ≤ ack < seq+len」的补洞段触发：'
              f'{"是，+" + f"{(filler-b)*1000:.1f} ms" if filler else "否（没找到补洞段）"}'
              f'   结束时 ack 推进 {0 if ve is None else ve-v} B，'
              f'对端前沿还高出 ack {r1 - max(v, ve or v)} B')

    # ---- 5. 长停顿里对端到底停没停（这才是判据） -------------------------
    # 13:35 新增。为什么必须加这一块：[1] 的「卡住占比」两条链路都高（71% / 66%），
    # 没有判别力。真正的差别在**长停顿的性质**上：
    #   · 一条流里可能出现长停顿，但那种停顿里对端也不发（它在等别的东西，或者传输本来
    #     就空着）——这是「同时静止」，与发送策略无关。
    #   · 另一种长停顿是「ack 几秒不动，而同一时间对端以满速往里灌」——这只可能发生在
    #     发送方**不看窗口、不看 ack、按自己节拍发**的时候，因为 cwnd 早就该耗尽。
    # 用「停顿期间送达 ≥50 KB」区分这两类：50 KB 远超一时吞吐抖动，只有持续发送才做得到。
    ACTIVE = 50000
    longs = [s for s in stalls if s[1] - s[0] >= 0.5]
    act = [s for s in longs if s[4] - s[3] >= ACTIVE]
    print(f'\n[5] 长停顿（≥500 ms）{len(longs)} 段；其中「期间对端仍送达 ≥{ACTIVE//1000} KB」'
          f'{len(act)} 段（= 活跃停顿）')
    if longs:
        dt = sum(s[1] - s[0] for s in longs)
        # 用下标不用解包：元组在 13:45 从 5 个字段变成 6 个，`*_x, r0, r1` 这种写法
        # 会静默地取到最后两个（reached_end, vend），算出负数——实测过一次 −1133.7 KB。
        bt = sum(s[4] - s[3] for s in longs)
        print(f'    全部长停顿合计 {dt:.1f}s，期间送达 {bt/1000:.1f} KB '
              f'（{bt/max(dt,1e-9)/1000:.1f} KB/s）')
    if act:
        dt = sum(s[1] - s[0] for s in act)
        bt = sum(s[4] - s[3] for s in act)
        print(f'    活跃停顿合计 {dt:.1f}s，期间送达 {bt/1000:.1f} KB '
              f'（{bt/max(dt,1e-9)/1000:.1f} KB/s）')
        print('    → 活跃停顿存在 ⇒ 对端的发送与 ack/cwnd 解耦（它不读窗口、不等确认）')
    else:
        print('    → 没有活跃停顿：所有长停顿期间对端也停了，说明它确实在等某个条件')

    # ---- 6. 对端帧间隔结构 + 全片速率（供 Task.md §5.9 表格那两行复现） ----
    # 13:55 新增。为什么放进这个脚本、而不是再写一个一次性脚本：13:20 那批丢在 /tmp
    # 的一次性脚本里有 `hdr_len` 位次写错的历史（见本文件 hdr_len 的 docstring），
    # 它们还能出对结果全靠后面「扫 IPv6 特征」兜回来。同一批数字如果只活在随手写的
    # 脚本里，下次复查就得把解析重写一遍、并且再冒一次同样的错。这里多打十几行，
    # 换来「一条命令复现表里两行」。
    #
    # 两个坑（第二个是我 13:55 第一版真踩的，已改）：
    #   · 帧间隔的 p50（2.22 ms）是「两帧挨着」的间隙，不是「一个开窗到下一个开窗」
    #     的周期。要看周期必须切突发。
    #   · **切突发之后，周期要按「突发起点 → 下一个突发起点」算，不能取「单个 >CUT 的
    #     间隔」**。第一版写的就是后者（`[g for g in gaps if g > 3]`），n3 打出 p50
    #     **22.71 ms**，与 §5.9 早就写下的 24.44 ms 对不上。差的正是**突发内部**那几个
    #     4–8 ms 的间隙：它们大于 3 ms、却不是跨窗口的间隔，混进来把 p50 往下拽了 1.7 ms。
    #     改成起点法、CUT 取 5 ms 后：n3 = 突发 2526 个、p10 23.82 / p50 24.45 /
    #     p90 25.63、>35 ms 占 2.7%——与 13:05 那次测量（同样 5 ms、同样 2526 个突发）
    #     逐个对上。**这就是「同一个数两个脚本给出两个值」的典型来源：不是精度，是定义。**
    #   · 全片速率要用**对端载荷字节 / 事件跨度**，不能用帧数 × 帧长估——t1 里
    #     28015 段里有几段属于别的流，跨度也不同（58.84 s 含尾部，主连接只有
    #     45.4 s），换分母就能差出 20%。
    CUT = 5.0        # ms，「跨窗口」与「突发内部间隙」的分界；与 §5.9 13:05 那次测量一致
    dts = [t for t, _s, _e in datas]
    gaps = [(b - a) * 1000 for a, b in zip(dts, dts[1:])]
    if gaps:
        starts = [dts[0]] + [b for a, b in zip(dts, dts[1:]) if (b - a) * 1000 > CUT]
        per = [(b - a) * 1000 for a, b in zip(starts, starts[1:])]
        print(f'\n[6] 对端帧间隔 p50 {pctl(gaps,.5):.2f} ms；'
              f'<2 ms 档 {100.0*sum(1 for g in gaps if g < 2)/len(gaps):.1f}%；'
              f'20–26 ms 档 {100.0*sum(1 for g in gaps if 20 <= g <= 26)/len(gaps):.1f}%')
        if per:
            print(f'    突发 {len(starts)} 个（切点 >{CUT:.0f} ms）；突发起点间隔 '
                  f'p10 {pctl(per,.1):.2f}  p50 {pctl(per,.5):.2f}  p90 {pctl(per,.9):.2f}  '
                  f'mean {sum(per)/len(per):.2f} ms；'
                  f'>35 ms 占 {100.0*sum(1 for x in per if x > 35)/len(per):.1f}%')
            print(f'    ← 这一行的 p50 才是「突发周期」；帧间隔的 p50 {pctl(gaps,.5):.2f} ms '
                  f'是突发内部两帧的间隙，别拿它当周期')
        else:
            print('    只有 1 个突发：对端是连续流，没有开窗结构')
        print(f'    全片对端载荷 {sum(e-s for _t,s,e in datas)/1000:.1f} KB / '
              f'{span:.1f}s = {sum(e-s for _t,s,e in datas)/max(span,1e-9)/1000:.1f} KB/s'
              f'（{len(datas)} 段）')
    return stalls


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    for p in argv:
        analyze(p)
        print()
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
