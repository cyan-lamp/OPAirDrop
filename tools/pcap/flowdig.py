#!/usr/bin/env python3
"""把「对端等 22 ms」这件事从空口往下挖到 TCP 头：到底是我们的窗口关了，还是它的 cwnd 关了。

为什么必须挖（2026-11-17 12:45）：

`duty.py` 已经证明「我们这一侧没问题」——数据帧到我们发出递增 ACK 只要 0.40 ms；
慢的是「我们发完 ACK，对端 22.12 ms 之后才发下一帧」。但这句话下面还埋着两种完全
不同的病，靠空口指标区分不了：

  病 A 我们的接收窗口（rwnd）小  —— 我们对端灌 2.7 KB 就停，是我们告诉它「只能塞这些」。
                                    这是**我们自己代码/缓冲**的问题，改得动。
  病 B 它的拥塞窗口（cwnd）小 / 在退避  —— 窗口给它开着，它自己不敢发。教科书式的
                                    cwnd=2，通常意味着它在丢包恢复或者被限速。

区分方法很直接：**我们的 ACK 帧里明明白白写着 rwnd**（TCP 头 14..15 字节），
而对端发多少由它自己的 cwnd 决定。把两份抓包的 rwnd 分布并排放，谁小谁就是瓶颈。

顺带回答另外两个问题（都是上一轮没查、但一句话就能查的）：

  1. 对端有没有在 **TCP 层**重传？同一 TCP seq 出现两次 = 它把同一个字节发了两遍。
     这跟 802.11 的 retry 位不是一回事——实测 retry 位在这块 tap 上不可信
     （Xs Max 每帧都置 1 却一个重复都没有，见 retrydig.py）。
  2. 我们的 ACK 每次推进多少字节？推进 0 是「重复 ACK」（对端会当成丢包信号），
     推进很小是「窗口太窄」。这一列直接解释为什么每突发只有 2 帧。

用法：
    python3 flowdig.py n3-air.pcap t1-air.pcap

口径说明（写下来防止下次又搞混）：
  · 对端 → 我们：src 端口是它的，dst 端口是 8770。它的 seq 记为「已发末端」。
  · 我们 → 对端：同一对端口的反方向。我们的 ack 记为「已收末端」，我们的 window
    就是**我们广播给它的 rwnd**（未经 wscale 还原，两份抓包同等对待即可比）。
"""
import struct
import sys
from collections import Counter

KNOWN_OURS = {'aa:7d:2c:e3:69:41', '36:bc:0c:fc:83:f3'}


def frames(path):
    with open(path, 'rb') as f:
        gh = f.read(24)
        magic = struct.unpack('<I', gh[:4])[0]
        endian = '<' if magic in (0xa1b2c3d4, 0x4d3cb2a1) else '>'
        link = struct.unpack(endian + 'I', gh[20:24])[0]
        while True:
            rh = f.read(16)
            if len(rh) < 16:
                break
            ts, us, incl, orig = struct.unpack(endian + 'IIII', rh)
            d = f.read(incl)
            if len(d) < incl:
                break
            yield link, ts + us / 1e6, d, orig


def rt_len(d):
    if len(d) < 4 or d[0] != 0:
        return 0
    return struct.unpack('<H', d[2:4])[0]


def hdr_len(fc):
    n = 24
    if ((fc >> 8) & 3) == 3:
        n += 6
    if ((fc >> 4) & 0xF) & 0x8:
        n += 2
    if (fc >> 15) & 1:
        n += 4
    return n


def ip6_tcp(d, start, limit):
    """小窗口扫描 IPv6 + 三重校验，返回 (sport,dport,seq,ack,win,flags,paylen)。"""
    for off in range(start, min(start + 20, limit - 40)):
        if (d[off] >> 4) != 6:
            continue
        if d[off + 6] != 6:
            continue
        plen = struct.unpack('>H', d[off + 4:off + 6])[0]
        if plen == 0 or off + 40 + plen > limit:
            continue
        t = off + 40
        sport, dport = struct.unpack('>HH', d[t:t + 4])
        seq = struct.unpack('>I', d[t + 4:t + 8])[0]
        ack = struct.unpack('>I', d[t + 8:t + 12])[0]
        doff = (d[t + 12] >> 4) * 4
        flags = d[t + 13]
        win = struct.unpack('>H', d[t + 14:t + 16])[0]
        # 有载荷才谈得上重传/推进；纯 ACK 的 paylen=0
        return sport, dport, seq, ack, win, flags, max(0, plen - doff)
    return None


def pctl(xs, p):
    if not xs:
        return float('nan')
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))]


def dig(path):
    # 先认对端（发数据字节最多），与 duty.py / retrydig.py 同口径
    tx = Counter()
    for link, ts, d, orig in frames(path):
        rt = rt_len(d) if link == 127 else 0
        if len(d) < rt + 24 or ((d[rt] >> 2) & 3) != 2:
            continue
        tx[d[rt + 10:rt + 16].hex(':')] += orig
    peer = max(tx, key=tx.get)

    # 按五元组收集双向事件
    flows = {}   # (peer_sport, our_dport) -> {'p': [(ts,seq,paylen)], 'o': [(ts,ack,win,flags,paylen)]}
    for link, ts, d, orig in frames(path):
        rt = rt_len(d) if link == 127 else 0
        if len(d) < rt + 24:
            continue
        fc = d[rt]
        if ((fc >> 2) & 3) != 2:
            continue
        a2 = d[rt + 10:rt + 16].hex(':')
        if a2 != peer and a2 not in KNOWN_OURS:
            continue
        got = ip6_tcp(d, rt + hdr_len(fc), len(d))
        if not got:
            continue
        sport, dport, seq, ack, win, flags, pay = got
        key = (sport, dport) if a2 == peer else (dport, sport)
        f = flows.setdefault(key, {'p': [], 'o': []})
        if a2 == peer:
            f['p'].append((ts, seq, pay))
        else:
            f['o'].append((ts, ack, win, flags, pay))

    main = max(flows, key=lambda k: sum(x[2] for x in flows[k]['p'])) if flows else None
    f = flows[main]

    print(f'=== {path} ===')
    print(f'对端 {peer}   主连接 {main[0]}→{main[1]}   （共 {len(flows)} 条）')

    # ---- 1. 对端 TCP 层有没有重传 ----------------------------------------
    seqs = [s for _, s, pay in f['p'] if pay > 0]
    uniq = len(set(seqs))
    print(f'\n[1] 对端 TCP 段：{len(seqs)} 段，不同 seq {uniq} 个，'
          f'重复 seq {len(seqs) - uniq} 个'
          f'（{100.0*(len(seqs)-uniq)/max(1,len(seqs)):.1f}%）')
    print(f'    载荷 p50 {pctl([p for _,_,p in f["p"] if p>0], 0.5):.0f} B，'
          f'max {max([p for _,_,p in f["p"] if p>0] or [0])} B')
    if len(seqs) - uniq > 0:
        d = Counter(seqs)
        top = [k for k, v in d.most_common(5) if v > 1]
        print(f'    有重传，样本 seq: {top[:5]}')

    # ---- 2. 我们广播的 rwnd ----------------------------------------------
    wins = [w for _, _, w, _, _ in f['o']]
    print(f'\n[2] 我们的 ACK 帧：{len(wins)} 个')
    if wins:
        print(f'    rwnd 原始值  min {min(wins)}  p50 {pctl(wins,0.5):.0f}  '
              f'p90 {pctl(wins,0.9):.0f}  max {max(wins)}')
        z = sum(1 for w in wins if w == 0)
        print(f'    零窗口占比 {100.0*z/len(wins):.1f}%   '
              f'（若接近 0，说明不是我们关门）')

    # ---- 3. 每次 ACK 推进多少 --------------------------------------------
    adv = []
    prev = None
    for _, ack, _, _, _ in f['o']:
        if prev is not None and ack >= prev:
            adv.append(ack - prev)
        prev = ack if prev is None else max(prev, ack)
    nz = [a for a in adv if a > 0]
    print(f'\n[3] ACK 推进量：{len(adv)} 次，其中 0 推进（重复 ACK）'
          f' {len(adv)-len(nz)} 次（{100.0*(len(adv)-len(nz))/max(1,len(adv)):.0f}%）')
    if nz:
        print(f'    非零推进 p50 {pctl(nz,0.5):.0f} B  p90 {pctl(nz,0.9):.0f} B  '
              f'max {max(nz)} B')

    # ---- 4. 时间轴自检：两个方向各多少帧 --------------------------------
    print(f'\n[4] 方向计数：对端 {len(f["p"])} 帧（含纯 ACK '
          f'{sum(1 for x in f["p"] if x[2]==0)}），我方 {len(f["o"])} 帧（含纯 ACK '
          f'{sum(1 for x in f["o"] if x[4]==0)}）')
    gaps = [(f['p'][i+1][0]-f['p'][i][0])*1000 for i in range(len(f['p'])-1)]
    print(f'    对端段间隔 p50 {pctl(gaps,0.5):.2f} ms   '
          f'p90 {pctl(gaps,0.9):.2f} ms')

    # ---- 5. 对端的字节流到底连不连续 --------------------------------------
    # 为什么必须单独算：上一节只说「72% 的 ACK 不推进」，但没说是「我们没收到」
    # 还是「收到了却重复 ACK」。这里按「期望的下一字节」逐段对账：
    #   seq > expected → 中间缺了一段（真空洞，真空口丢包）
    #   seq < expected → 补洞重传（这是对端在救自己）
    # 两者都接近 0 却还有大量重复 ACK，就说明重复 ACK 是我们自己造的。
    exp = None
    holes = hole_bytes = fill = fill_bytes = 0
    for _, seq, pay in f['p']:
        if pay == 0:
            continue
        if exp is None:
            exp = seq + pay
            continue
        if seq == exp:
            exp += pay
        elif seq > exp:
            holes += 1
            hole_bytes += seq - exp
            exp = seq + pay
        else:
            fill += 1
            fill_bytes += pay
            exp = max(exp, seq + pay)
    print(f'\n[5] 对端字节流连续性：空洞 {holes} 处 / {hole_bytes} B；'
          f'补洞重传 {fill} 段 / {fill_bytes} B')
    print(f'    空洞率 {100.0*hole_bytes/max(1,hole_bytes+sum(x[2] for x in f["p"])):.2f}%')

    # ---- 6. 我们的 ACK 落后对端多少字节 -----------------------------------
    # lag = 对端当时已发末端 − 我们这条 ACK 里的 ack 值。
    # lag≈0 → 我们贴着实收在 ACK；lag≈1 个段 → 少 ACK 一段（内核延迟 ACK）；
    # lag 长期 ≈ 2 段且抖动 → 我们的 ACK 一直落在后面，对端的 snd_una 推不动。
    lags = []
    pend = 0
    j = 0
    pv = [x for x in f['p'] if x[2] > 0]
    for ts, ack, _, _, _ in f['o']:
        while j < len(pv) and pv[j][0] <= ts:
            pend = max(pend, pv[j][1] + pv[j][2])
            j += 1
        if pend:
            lags.append(pend - ack)
    if lags:
        print(f'\n[6] ACK 落后量（字节）：p10 {pctl(lags,0.1):.0f}  '
              f'p50 {pctl(lags,0.5):.0f}  p90 {pctl(lags,0.9):.0f}  max {max(lags)}')
        neg = sum(1 for v in lags if v < 0)
        print(f'    负值（ack 超过已发末端，正常说明对端还在路上）{neg} 个 '
              f'（{100.0*neg/max(1,len(lags)):.0f}%）')


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    for p in argv:
        dig(p)
        print()
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
