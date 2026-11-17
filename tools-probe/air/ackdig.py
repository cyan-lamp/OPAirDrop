#!/usr/bin/env python3
"""72% 的「重复 ACK」到底是真丢包，还是我们的 ACK 帧被 MAC 重发了？

为什么这一步非做不可（2026-11-17 12:50）：

`flowdig.py` 量出 Xs Max 那轮有 72% 的 ACK 不推进序号，iPhone 13 只有 3%。
两种解释对「接下来改哪儿」是**完全相反**的：

  解释甲 真的丢包：对端的 TCP 段在空口丢了一个，我们后面每收到一段就重发同一个
         「下一个期望字节」，它按 3 个重复 ACK 判断丢包 → 快速重传 → cwnd 砍半。
         要改的是空口可靠性（或者干脆把接收缓冲调大别让它这么敏感）。
  解释乙 我们的 ACK 帧被 MAC 重发：同一个 TCP 头被我们发了两三遍（因为没收到
         802.11 ACK），TCP 层根本没重复。这时「重复 ACK」是我们自己制造的空口
         噪声，占了 2.35 倍的帧数，反而是**发送侧效率**问题。

判据是 802.11 序号：
  · 解释乙里，重复的 ACK 会带着**相同的 802.11 seq** 和**逐字节相同的载荷**，
    而且两帧间隔很短（MAC 重试是 μs~ms 级）。
  · 解释甲里，每帧都是新造的，802.11 seq 一定递增，内容只在 TCP ack 字段上相同。

所以只看一件事：把「TCP ack 值相同」的相邻我方帧分成两组——
802.11 seq 也相同（=MAC 重发）／802.11 seq 不同（=TCP 重复 ACK），各占多少。

顺带量三件配套的事，都是同一批帧免费得到的：
  · 我方帧的 retry 位（如果 MAC 重发，retry 应该置 1）；
  · 我方帧的空口速率（radiotap 里的 rate 字段，legacy 还是 VHT）；
  · 我方帧长分布——上一轮记的是 123/135/408 B，跟这里的 12004 帧对不上就说明
    那个数是采样偏差。

用法：
    python3 ackdig.py n3-air.pcap t1-air.pcap
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


def rt_rate(d):
    """从 radiotap 里取速率（Mb/s），有就用，没有返回 None。

    为什么不用 802.11 头里的东西猜：radiotap 的 present 位决定字段顺序，
    iPhone 的 48 字节头里字段一堆。这里只认两种最常见的编码——
    legacy rate（bit1）和 MCS（bit19，已知 flags 里的带宽/短 GI 就不细算了）。
    取不到就返回 None，不硬凑，免得又造一个假数字。
    """
    if len(d) < 8:
        return None
    pres = struct.unpack('<I', d[4:8])[0]
    off = 8
    rate = None
    # bit0 TSFT(8) bit1 Flags(1) bit2 Rate(1) bit3 Channel(4) ...
    if pres & (1 << 0):
        off += 8
    if pres & (1 << 1):
        off += 1
    if pres & (1 << 2):
        if off < len(d):
            rate = d[off] * 0.5
        off += 1
    return rate


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
    for off in range(start, min(start + 20, limit - 40)):
        if (d[off] >> 4) != 6 or d[off + 6] != 6:
            continue
        plen = struct.unpack('>H', d[off + 4:off + 6])[0]
        if plen == 0 or off + 40 + plen > limit:
            continue
        t = off + 40
        sport, dport = struct.unpack('>HH', d[t:t + 4])
        seq = struct.unpack('>I', d[t + 4:t + 8])[0]
        ack = struct.unpack('>I', d[t + 8:t + 12])[0]
        # 13:10 加 wnd / flags：判「同一段字节」时它们必须进指纹，而 cksum 反而不该进
        # （理由见 [4] 里那段注释）。
        wnd = struct.unpack('>H', d[t + 14:t + 16])[0]
        flags = d[t + 13]
        ck = struct.unpack('>H', d[t + 16:t + 18])[0]
        return sport, dport, seq, ack, ck, wnd, flags
    return None


def dig(path):
    # 认对端：发数据字节最多
    tx = Counter()
    for link, ts, d, orig in frames(path):
        rt = rt_len(d) if link == 127 else 0
        if len(d) < rt + 24 or ((d[rt] >> 2) & 3) != 2:
            continue
        tx[d[rt + 10:rt + 16].hex(':')] += orig
    peer = max(tx, key=tx.get)

    # 收集我方所有帧（我们要看的是"我方发了什么"，不限于主连接）
    our = []
    for link, ts, d, orig in frames(path):
        rt = rt_len(d) if link == 127 else 0
        if len(d) < rt + 26:
            continue
        fc0, fc1 = d[rt], d[rt + 1]
        if ((fc0 >> 2) & 3) != 2:
            continue
        if d[rt + 10:rt + 16].hex(':') not in KNOWN_OURS:
            continue
        seq = struct.unpack('<H', d[rt + 22:rt + 24])[0] >> 4
        got = ip6_tcp(d, rt + hdr_len(fc0 | (fc1 << 8)), len(d))
        # 指纹**不能**用整帧哈希：802.11 头里的 seq 每次都不一样，会导致
        # 「同一段重发」永远被判成「内容不同」。TCP 校验和覆盖 TCP 头+载荷，
        # 不含 802.11 头，正好是「同一段 TCP 字节」的判据。
        our.append({
            'ts': ts, 'seq': seq, 'retry': (fc1 >> 3) & 1, 'len': orig,
            'ack': got[3] if got else None,
            'key': (got[2], got[3]) if got else None,   # (tcp seq, ack)
            'h': (got[0], got[1], got[2], got[3], got[4]) if got else None,
            # 不含 cksum 的 TCP 头指纹（[4] 用）：同一段字节应当永远算出同一个
            # 校验和，实测却不是，所以 cksum 只能当「可能抖动」的字段排除在判据外。
            'b': (got[0], got[1], got[2], got[3], got[5], got[6]) if got else None,
            'ck': got[4] if got else None,
            'rate': rt_rate(d) if link == 127 else None,
        })

    print(f'=== {path} ===')
    print(f'对端 {peer}，我方数据帧 {len(our)}')

    if not our:
        return

    # ---- 核心判据：ack 值相同的相邻我方帧，802.11 seq 是否也相同 -------------
    # 第二轮细化（12:55）：第一版把「MAC 重发」限定成「802.11 seq 也相同」，
    # 实测两份抓包都是 0 —— 但那只能排除「上面那层原样重发」。如果重发发生在
    # **802.11 序号分配之前**（我们自己拼帧，每次尝试重新拿一个 seq），那么
    # 特征就变成「TCP 头逐字节相同、802.11 seq 不同」，会被误判成「真 TCP 重复 ACK」。
    # 所以这里再拆一层：同 ack 且同载荷但 seq 不同 = 我们自己重发；只有载荷也不同
    # 才是内核真的造了一个新的重复 ACK。
    same_ack = mac_retx = reasm = tcp_dup = other = 0
    gaps_retx, gaps_dup, gaps_reasm = [], [], []
    prev = None
    twin = 0     # 相邻帧 TCP 头完全相同（含 seq），只差 802.11 seq 的个数
    for x in our:
        if prev is not None and x['key'] is not None and prev['key'] is not None \
                and x['key'] == prev['key'] and x['h'] == prev['h'] and x['seq'] != prev['seq']:
            twin += 1
        if prev is not None and x['ack'] is not None and prev['ack'] is not None \
                and x['ack'] == prev['ack']:
            same_ack += 1
            if x['seq'] == prev['seq'] and x['h'] == prev['h']:
                mac_retx += 1
                gaps_retx.append((x['ts'] - prev['ts']) * 1000)
            elif x['seq'] != prev['seq'] and x['h'] == prev['h']:
                reasm += 1
                gaps_reasm.append((x['ts'] - prev['ts']) * 1000)
            elif x['seq'] != prev['seq']:
                tcp_dup += 1
                gaps_dup.append((x['ts'] - prev['ts']) * 1000)
            else:
                other += 1     # seq 相同但载荷不同（理论上不该出现）
        prev = x

    tot = max(1, same_ack)
    print(f'\n[1] ack 值相同的相邻帧 {same_ack} 个')
    print(f'    同 802.11 seq + 同载荷（原样重发）    {mac_retx:6d}  {100.0*mac_retx/tot:5.1f}%')
    print(f'    新 802.11 seq + 同载荷（我们自己重拼） {reasm:6d}  {100.0*reasm/tot:5.1f}%')
    print(f'    新 802.11 seq + 载荷不同（真重复 ACK） {tcp_dup:6d}  {100.0*tcp_dup/tot:5.1f}%')
    print(f'    其余                                  {other:6d}  {100.0*other/tot:5.1f}%')
    for name, g in (('原样重发', gaps_retx), ('重拼重发', gaps_reasm), ('真重复 ACK', gaps_dup)):
        if g:
            g.sort()
            print(f'    {name}间隔 p50 {g[len(g)//2]:.3f} ms   p90 {g[int(len(g)*0.9)]:.3f} ms')
    print(f'    相邻两帧 TCP 头完全一样（含 seq）只差 802.11 seq：{twin} 对 '
          f'（{100.0*twin/max(1,len(our)):.1f}%）')

    # ---- 我方帧的 802.11 seq 重复 -------------------------------------------
    # 13:05 改：原来只打「同值计数多出多少帧（66.2% / 82.8%）」，**这个数读不出
    # 任何东西**——12 位序号只有 4096 个值，我方发了上万帧就必然重号好几圈，
    # 重号数量≈帧数/4096×圈内倍数，纯粹是回绕。把它当「MAC 重发」就是又一次
    # 把恒真指标当证据（和 duty.py 里 `seq 递增 %` 同一个坑）。
    # 真正的 MAC 重发在序列表面上必须是**近邻**重号（同一帧重发不会隔几千帧），
    # 所以这里按「与前面 ≤NEAR 帧内某帧同 seq」重算一遍，并把两个数分开打。
    NEAR = 8
    c = Counter(x['seq'] for x in our)
    rep = sum(v - 1 for v in c.values() if v > 1)
    near = sum(1 for i, x in enumerate(our)
               if any(our[j]['seq'] == x['seq'] for j in range(max(0, i - NEAR), i)))
    print(f'\n[2] 我方 802.11 seq：不同 {len(c)} 个 / 总 {len(our)} 帧')
    print(f'    同值多出 {rep} 帧（{100.0*rep/len(our):.1f}%）← **回绕，不是重发**：'
          f'4096 个序号发了 {len(our)/4096:.1f} 圈，必然重号')
    print(f'    近邻（≤{NEAR} 帧内）同 seq：{near} 帧（{100.0*near/len(our):.1f}%）'
          f'← 只有这个才可能是 MAC 重发，须与 [1] 的「原样重发」对读')

    # ---- retry 位 / 速率 / 帧长 -------------------------------------------
    r = sum(x['retry'] for x in our)
    lens = Counter(x['len'] for x in our)
    rates = Counter(round(x['rate'], 1) for x in our if x['rate'])
    print(f'\n[3] 我方帧 retry 位 {100.0*r/len(our):.1f}%   速率 '
          + '  '.join(f'{k}Mb/s×{v}' for k, v in rates.most_common(4)))
    print(f'    帧长前六: ' + '  '.join(f'{k}B×{v}' for k, v in lens.most_common(6)))

    # ---- [4] 全距「同一 ACK 再发」+ 间隔结构 -------------------------------
    # 13:10 加这一块的原因：**[1][2][3] 全是相邻视角，看不见 16.4 ms 那一次再发。**
    # 两次重放之间还夹着内核新造的其它 ACK，所以重放的副本根本不与原件相邻，
    # 相邻比对永远只看到「ack 变了」。文档里 75% / 62% 那个数是全距口径，必须
    # 有一个能复现它的块，否则谁也没法把两个数对上（我自己就先对不上了一次）。
    #
    # 三种指纹一起打，防止挑口径：
    #   A 只看 ack 值                    最松，窗口更新也算重复
    #   B 只比 TCP 头字段（无选项/校验和） 「ACK 信息重复」的上界，含内核新造的同值 ACK
    #   C 逐字节（含选项与 cksum）        「同一个帧原样再发」，判真重放只能用这个
    # （13:50 改：原来把 C 标成「会被 cksum 抖动漏掉」，把 B 标成「推荐」。反了，
    #   理由见 [4c]——cksum 变是 TSval 变导致的**正常现象**，不是噪声。）
    acks = [x['ack'] for x in our if x['ack'] is not None and x['ck'] is not None]
    bs = [x['b'] for x in our if x['b'] is not None]
    cs = [x['h'] for x in our if x['h'] is not None]

    def repro(seq):
        seen, n = set(), 0
        for v in seq:
            if v in seen:
                n += 1
            seen.add(v)
        return n, len(seq)

    ra, na = repro(acks)
    rb, nb = repro(bs)
    rc, nc = repro(cs)
    print(f'\n[4] 「同一 ACK 再发」全距口径（分母为带 TCP 头的我方帧）')
    print(f'    A 只看 ack 值          {ra:6d}/{na}  {100.0*ra/na:5.1f}%')
    print(f'    B 只比头字段（无选项/校验和）{rb:6d}/{nb}  {100.0*rb/nb:5.1f}%  '
          f'← 「ACK 信息重复」的全部')
    print(f'    C 逐字节（含选项与 cksum）  {rc:6d}/{nc}  {100.0*rc/nc:5.1f}%  '
          f'← 「同一个帧原样再发」= 真重放')

    # [4c] 同一 B 指纹对应几个不同的 cksum？>1 就说明这批帧的选项（TSval）在变。
    # **13:50 更正这里的结论**：原来写的是「同一段字节不可能算出两个校验和 → cksum
    # 不可信 → 判据用 B」。方向反了。cksum 覆盖 TCP 头（含选项），而 TSval 选项每帧
    # 都变，所以 cksum 变是**正常的**，它恰恰标记出「这是内核新造的 ACK，不是重放的
    # 旧帧」。所以 C 不是「被 cksum 抖动漏掉一部分」的坏口径，而是**唯一能分出真重放的
    # 口径**。真正算异常的是下面这个 >1 组数——它只有 0.1% 量级，可以忽略。
    perk = {}
    for x in our:
        if x['b'] is None:
            continue
        perk.setdefault(x['b'], set()).add(x['ck'])
    multi = sum(1 for v in perk.values() if len(v) > 1)
    print(f'\n[4c] 同一 B 指纹出现 >1 个 cksum 的组：{multi}/{len(perk)} '
          f'（{100.0*multi/max(1,len(perk)):.1f}%）')
    print('     这些差异来自 TCP Timestamp 选项（TSval 每帧变，cksum 覆盖选项），'
          '**属正常**：\n     B 与 C 之差 = 内核新造的同值 ACK；C 才是「旧帧原样重放」。')

    # [4d] B 指纹再现的间隔结构：是不是 16 TU（16.384 ms）的整数倍。
    last, gaps = {}, []
    for x in our:
        if x['b'] is None:
            continue
        t = last.get(x['b'])
        if t is not None:
            gaps.append((x['ts'] - t) * 1000)
        last[x['b']] = x['ts']
    if gaps:
        gaps.sort()
        n = len(gaps)

        def q(p):
            return gaps[min(n - 1, int(n * p))]
        print(f'\n[4d] 同一内容再现 {n} 次，间隔 p10 {q(.1):.2f} / p50 {q(.5):.2f} / '
              f'p90 {q(.9):.2f} / max {gaps[-1]:.1f} ms')
        tik = [g for g in gaps if abs(g - 16.384) < 1.5]
        tok = [g for g in gaps if abs(g - 32.768) < 1.5]
        fast = [g for g in gaps if g < 5]
        print(f'     16.4±1.5 ms {len(tik)}（{100.0*len(tik)/n:.0f}%）  '
              f'32.8±1.5 ms {len(tok)}（{100.0*len(tok)/n:.0f}%）  '
              f'<5 ms {len(fast)}（{100.0*len(fast)/n:.0f}%）')
        print('     ↑ 这一行含「内核新造的同值 ACK」，定时器成分被稀释；'
              '判 16 TU 重放请看 [4e]')

    # [4e] 同一段分析换到 C 指纹（含选项与 cksum 的逐字节相同）再做一遍。
    # 为什么必须两个都打（13:50）：B 和 C 差的不是「严格程度」，而是**语义**。
    #   · C = 同一个帧被原样再发一次 → 真重放。TSval 与 cksum 都没变才是真重放。
    #   · B = 头字段相同但选项不同 → 内核**新造**了一个 ack 值相同的 ACK，
    #     它的 TSval 和 cksum 会跟着变。
    # 实测 n3：B 的 16.4 ms 档占 48%，C 的同一档占 68%——**真重放里定时器的成分高得多**。
    # 文档里「75% / 68%」那组数是 C 口径，[4d] 打的是 B，不对读就会以为文档写错了。
    lastc, gapsc = {}, []
    for x in our:
        if x['h'] is None:
            continue
        t = lastc.get(x['h'])
        if t is not None:
            gapsc.append((x['ts'] - t) * 1000)
        lastc[x['h']] = x['ts']
    if gapsc:
        gapsc.sort()
        m = len(gapsc)

        def qc(p):
            return gapsc[min(m - 1, int(m * p))]
        tc = [g for g in gapsc if abs(g - 16.384) < 1.5]
        fc = [g for g in gapsc if g < 5]
        print(f'\n[4e] 同上，改用 C 指纹（逐字节相同＝真重放）'
              f'  再现 {m} 次，p50 {qc(.5):.2f} ms')
        print(f'     16.4±1.5 ms {len(tc)}（{100.0*len(tc)/m:.0f}%）   '
              f'<5 ms {len(fc)}（{100.0*len(fc)/m:.0f}%）')
        print('     ↑ 判「16 TU 定时器重放」应引用这一行；[4d] 那行含「内核新造同值 ACK」，'
              '定时器成分会被稀释')


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
