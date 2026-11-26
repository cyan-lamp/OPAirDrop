#!/usr/bin/env python3
"""专攻一件事：为什么 Xs Max 的每一帧都带 retry 位，而 802.11 序号却严格递增。

为什么值得单写一个脚本（2026-11-17 12:40）：

`duty.py` 量出两台设备最硬的一条差异——iPhone 13 的 retry 是 4.9%（1374/28043），
Xs Max 是 100%（5100/5100）。但同一个表里还有一行「seq 递增 100%」。这两条放在一起
在 802.11 里是**说不通**的：Retry 位的定义就是「本帧是同一 Sequence Number 的重传」，
既然每帧都标了重传，就应该能找到大量「同一个 seq 出现多次」；而 seq 严格递增意味着
每个 seq 只出现一次。二者只能有一个是真的。

所以这里的任务不是「再算一遍比例」，而是**把这两个数放到同一批帧上逐帧对齐**，
看是三条假设里的哪一条：

  H1 解析错位    —— retry 位不是我读的那一位。FC 的第二个字节 bit3 才是 Retry；
                    如果 FC 只有 1 字节（不可能）或者偏移算错了，读到的是别的位。
                    检查法：把对端数据帧的 FC 两个字节日取出来看直方图。
                    QoS Data 应该是 byte0=0x88，byte1 只该在 bit3 附近变化。
  H2 序号重复    —— 每个 seq 其实出现多次，是我把「去重后的序列」当成了原始序列
                    （或者只看相邻两帧，而相同 seq 的副本被别的 TID/别的方向插在中间）。
                    检查法：直接统计 seq 的重复次数分布，以及重复副本的载荷是否逐字节相同。
  H3 驱动语义    —— Apple 的 AWDL 发送路径确实每帧都置 retry（例如给固件传「允许重传」
                    而不是「这是重传」），此时 retry 位在这块 tap 上没有协议含义。
                    检查法：如果 H1/H2 都被排除，剩下只能是这条；再用「帧长/载荷/间隔
                    是否与 retry 相关」做旁证（真重传会和前一次尝试同长同序号）。

输出分四块：FC 位组合直方图 / seq 重复分布 / 重复副本的载荷比对 / QoS 与时长字段。
每一块都在回答「能不能排除某条假设」。

用法：
    python3 retrydig.py n3-air.pcap t1-air.pcap
    python3 retrydig.py n3-air.pcap --mac 66:82:54:63:ed:6e
不指定 --mac 时自动把「发数据字节最多」的那台当对端（与 duty.py 同口径）。
"""
import struct
import sys
from collections import Counter, defaultdict

# 与 duty.py 保持同一份「我们」的 MAC 名单，避免同一批数据在两处被认成不同方向。
KNOWN_OURS = {'aa:7d:2c:e3:69:41', '36:bc:0c:fc:83:f3'}


def frames(path):
    """逐 record 产出 (linktype, ts, data, orig_len)。orig 判帧长，incl 供解析。"""
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
    """按 ToDS/FromDS/QoS/Order 算 MAC 头长。QoS Data 是 26，四地址是 30。"""
    n = 24
    if ((fc >> 8) & 3) == 3:
        n += 6
    if ((fc >> 4) & 0xF) & 0x8:
        n += 2
    if (fc >> 15) & 1:
        n += 4
    return n


def pick_peer(path):
    """发数据字节最多者 = 对端（与 duty.py 同口径，但这里用 incl 也行，只看相对大小）。"""
    tx = Counter()
    for link, ts, d, orig in frames(path):
        rt = rt_len(d) if link == 127 else 0
        if len(d) < rt + 24:
            continue
        if ((d[rt] >> 2) & 3) != 2:
            continue
        tx[d[rt + 10:rt + 16].hex(':')] += orig
    return max(tx, key=tx.get) if tx else None


def dig(path, peer=None):
    peer = peer or pick_peer(path)
    if not peer:
        raise SystemExit(f'{path}: 找不到任何数据帧')

    fc_hist = Counter()          # (byte0, byte1) 直方图——回答 H1
    tid_hist = Counter()
    dur_hist = Counter()
    by_seq = defaultdict(list)   # seq -> [(ts, retry, len, tid, payload_hash)]
    seqs = []                    # 原始出现顺序（不去重）
    n = 0

    for link, ts, d, orig in frames(path):
        rt = rt_len(d) if link == 127 else 0
        if len(d) < rt + 26:
            continue
        fc0, fc1 = d[rt], d[rt + 1]
        if ((fc0 >> 2) & 3) != 2:
            continue
        if d[rt + 10:rt + 16].hex(':') != peer:
            continue

        n += 1
        fc_hist[(fc0, fc1)] += 1
        seq = struct.unpack('<H', d[rt + 22:rt + 24])[0] >> 4
        seqs.append(seq)

        hl = hdr_len(fc0 | (fc1 << 8))
        qos = struct.unpack('<H', d[rt + 24:rt + 26])[0] if hl >= 26 else 0
        tid = qos & 0xF
        tid_hist[tid] += 1
        dur_hist[struct.unpack('<H', d[rt + 2:rt + 4])[0]] += 1

        # 载荷指纹用 LLC 之后的全部字节；真重传必须逐字节相同（MAC 重传不改内容）
        body = d[rt + hl:len(d)]
        by_seq[seq].append((ts, (fc1 >> 3) & 1, orig, tid, hash(body) & 0xFFFFFFFF))

    # ---- 块 1：FC 位组合 ---------------------------------------------------
    print(f'=== {path} ===')
    print(f'对端 {peer}，数据帧 {n}')
    print('\n[1] Frame Control 直方图（回答 H1：retry 位读错了吗）')
    print(f'    {"FC bytes":10s} {"次数":>7s} {"占比":>6s}  位展开')
    for (b0, b1), c in fc_hist.most_common(8):
        bits = []
        if (b1 >> 3) & 1:
            bits.append('Retry')
        if (b1 >> 0) & 1:
            bits.append('ToDS')
        if (b1 >> 1) & 1:
            bits.append('FromDS')
        if (b1 >> 2) & 1:
            bits.append('MoreFrag')
        if (b1 >> 4) & 1:
            bits.append('PwrMgmt')
        if (b1 >> 7) & 1:
            bits.append('Order')
        sub = (b0 >> 4) & 0xF
        print(f'    {b0:02x} {b1:02x}     {c:7d} {100.0*c/n:5.1f}%  subtype={sub} '
              f'{"+".join(bits) or "(无)"}')

    # ---- 块 2：seq 重复分布 -----------------------------------------------
    print('\n[2] seq 重复分布（回答 H2：每帧都标重传，那 seq 该重复）')
    mult = Counter(len(v) for v in by_seq.values())
    print(f'    不同 seq 数 {len(by_seq)}，总帧数 {n}，'
          f'平均每 seq {n/max(1,len(by_seq)):.2f} 帧')
    print(f'    {"每 seq 出现帧数":>14s} {"seq 个数":>8s}')
    for k in sorted(mult):
        print(f'    {k:>14d} {mult[k]:>8d}')

    # 相邻帧之间 seq 的实际行为（这才是「递增 100%」的真实口径）
    inc = sum(1 for i in range(len(seqs) - 1) if seqs[i + 1] == (seqs[i] + 1) % 4096)
    same = sum(1 for i in range(len(seqs) - 1) if seqs[i + 1] == seqs[i])
    jump = len(seqs) - 1 - inc - same
    print(f'    相邻帧关系：+1 占 {100.0*inc/max(1,len(seqs)-1):.1f}%   '
          f'不变 {100.0*same/max(1,len(seqs)-1):.1f}%   其它 {100.0*jump/max(1,len(seqs)-1):.1f}%')

    # ---- 块 3：重复副本是否真的一样 ---------------------------------------
    dup = {s: v for s, v in by_seq.items() if len(v) > 1}
    if dup:
        same_body = same_len = 0
        tot = 0
        for s, v in dup.items():
            tot += 1
            bodies = {x[4] for x in v}
            lens = {x[2] for x in v}
            same_body += (len(bodies) == 1)
            same_len += (len(lens) == 1)
        print(f'\n[3] 重复 seq 的副本比对（真重传必须同长同内容）')
        print(f'    重复 seq {tot} 个；内容完全相同 {same_body} 个（{100.0*same_body/tot:.0f}%）；'
              f'长度相同 {same_len} 个（{100.0*same_len/tot:.0f}%）')
        ex = list(dup.items())[:3]
        for s, v in ex:
            print(f'    seq={s:4d} × {len(v)}：'
                  + ', '.join(f'retry={x[1]} len={x[2]} tid={x[3]}' for x in v[:4]))
    else:
        print('\n[3] 重复 seq 的副本比对：**一个重复都没有**')
        print('    → 每一帧的 Sequence Number 都是新的，「重传」在协议层不存在。')

    # ---- 块 4：QoS / 时长 -------------------------------------------------
    print('\n[4] QoS TID 与 Duration')
    print('    TID: ' + '  '.join(f'{k}:{v}' for k, v in sorted(tid_hist.items())))
    top_dur = dur_hist.most_common(5)
    print('    Duration 前五: ' + '  '.join(f'{k}us×{v}' for k, v in top_dur))
    return

    # ---- 结论（只在三段证据都齐时给出） -----------------------------------


def main(argv):
    mac = None
    if '--mac' in argv:
        i = argv.index('--mac')
        mac = argv[i + 1].lower()
        argv = argv[:i] + argv[i + 2:]
    if not argv:
        print(__doc__)
        return 2
    for p in argv:
        dig(p, mac)
        print()
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
