#!/usr/bin/env python3
"""空口抓包总体画像：链路类型、每 MAC 每类型帧数与字节、帧长分布、帧间隔。

为什么单独写一个而不是复用旧的：
旧脚本各自只回答一个问题（m1type 看 MAC、m1retry 数 retry）。
这轮的问题是「整体退化了」，需要一张能一眼看出「谁在发多少、帧多长、多密」的总表，
所以先做画像，再决定往下挖哪一条。
"""
import struct
import sys
from collections import Counter, defaultdict

OURS_DEFAULT = '36:bc:0c:fc:83:f3'


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
            yield link, ts + us / 1e6, d


def radiotap_len(d):
    """radiotap 版本 0，长度在 offset 2（小端）。"""
    if len(d) < 4 or d[0] != 0:
        return 0
    return struct.unpack('<H', d[2:4])[0]


TYPES = {0: 'mgmt', 1: 'ctrl', 2: 'data', 3: 'ext'}


def main(path):
    our_macs = {OURS_DEFAULT}
    if len(sys.argv) > 2:
        our_macs.add(sys.argv[2])

    # 第一遍：找出空口上出现的所有 MAC，确定哪个是我们（本地管理位置位 / 已知地址）
    seen = Counter()
    stat = defaultdict(lambda: [0, 0])         # (who, type, sub) -> [frames, bytes]
    lens = defaultdict(list)                    # (who, type, sub) -> 帧长列表
    times = defaultdict(list)                   # (who, type, sub) -> 时间戳
    link_used = None

    for link, ts, d in frames(path):
        link_used = link
        if link == 127:
            rt = radiotap_len(d)
        elif link == 105:
            rt = 0
        else:
            rt = radiotap_len(d) if d[:1] == b'\x00' else 0
        if len(d) < rt + 24:
            continue
        fc = d[rt]
        typ = (fc >> 2) & 3
        sub = (fc >> 4) & 0xF
        dur = struct.unpack('<H', d[rt + 2:rt + 4])[0]
        a1 = d[rt + 4:rt + 10].hex(':')
        a2 = d[rt + 10:rt + 16].hex(':')
        # 发端判定：Type/Subtype 里控制帧没有 a2，用 a1 反推不可靠，先按地址集合
        src = a2
        if typ == 1:
            src = a1 if a1 in our_macs or a1 == 'ff:ff:ff:ff:ff:ff' else a2
        for m in (a1, a2):
            if m != 'ff:ff:ff:ff:ff:ff':
                seen[m] += 1
        who = 'OURS' if src in our_macs else src
        # 把非我们的统一按前 3 字节归并，避免随机 MAC 把表撑爆
        if who != 'OURS':
            who = 'PEER' if src.endswith(src[-5:]) else src
            who = 'PEER'
        flen = len(d) - rt
        stat[(who, typ, sub)][0] += 1
        stat[(who, typ, sub)][1] += flen
        lens[(who, typ, sub)].append(flen)
        times[(who, typ, sub)].append(ts)

    print(f'linktype={link_used}  file={path}')
    print('top MACs on air:')
    for m, n in seen.most_common(6):
        print(f'   {m}  {n}')
    print()

    print(f'{"who":6s} {"type":5s} {"sub":3s} {"frames":>7s} {"bytes":>10s} {"avg":>6s} {"p50len":>6s} {"fps":>7s}')
    total_span = {}
    for (who, typ, sub), (n, b) in sorted(stat.items(), key=lambda kv: -kv[1][0]):
        ts = times[(who, typ, sub)]
        span = ts[-1] - ts[0] if len(ts) > 1 else 0
        fps = n / span if span > 0 else 0
        ls = sorted(lens[(who, typ, sub)])
        p50 = ls[len(ls) // 2]
        print(f'{who:6s} {TYPES.get(typ,typ):5s} {sub:3d} {n:7d} {b:10d} {b/n:6.0f} {p50:6d} {fps:7.2f}')
        total_span[(who, typ, sub)] = span

    # 帧长分布（只看数据帧）
    print('\ndata frame length histogram (top 12):')
    all_lens = Counter()
    for (who, typ, sub), ls in lens.items():
        if typ == 2:
            for L in ls:
                all_lens[(who, L)] += 1
    for (who, L), n in all_lens.most_common(12):
        print(f'   {who:6s} len={L:5d}  n={n}')

    # iPhone 数据帧间隔
    print('\ninter-frame gap of peer data frames (ms):')
    for (who, typ, sub), ts in times.items():
        if typ != 2 or who == 'OURS':
            continue
        ts = sorted(ts)
        gaps = sorted((ts[i + 1] - ts[i]) * 1000 for i in range(len(ts) - 1))
        if len(gaps) < 10:
            continue
        q = lambda p: gaps[int(len(gaps) * p)]
        print(f'   sub={sub} n={len(gaps)+1} p10={q(0.1):.2f} p50={q(0.5):.2f} '
              f'p90={q(0.9):.2f} p99={q(0.99):.2f} max={gaps[-1]:.1f}')


if __name__ == '__main__':
    main(*sys.argv[1:])
