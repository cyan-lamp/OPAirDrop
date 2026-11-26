#!/usr/bin/env python3
"""对端的发送节拍是不是锁在**我们广播的可用性窗口**上？

为什么要有这个脚本
==================
2026-11-17 的 §10 第 2 条是「改我们 broadcast 的可用性窗口」。它的隐含前提是：
**对端读我们 presence 帧里的同步参数，并且按那个窗口决定什么时候发**。
在改固件之前，这个前提可以用现成抓包正面回答——只问「它的突发起点落在我们窗口的哪个相位上」：

- **锁相**：突发起点集中在某个相位 → 它读我们、并且按我们的栅格发。改参数才有意义。
- **自由跑**：相位均匀分布 → 它的节拍是它自己的定时器，我们的广播根本不参与。第 2 条可以直接关掉。

判据用**圆统计的合矢量长度 R**（Rayleigh 检验的统计量）而不是「直方图看起来像不像」：
R = 0 表示完全均匀，R = 1 表示所有相位重合。均匀分布下 R 的期望量级是 1/sqrt(N)
（N = 突发数），所以 R 只要没明显超过 1/sqrt(N) 这个量级，就是自由跑。

同时给出三个模数，因为「窗口」有三种可能的口径：
- **AW 16.384 ms**（`AW_PERIOD_TU = 16`）
- **槽 65.536 ms**（`SLOT_TU = 64`，一个槽 4 个 AW）
- **我们的 PSF 间隔 112.64 ms**（`PSF_INTERVAL_TU = 110`，即我们多久宣告一次）

三个都不合 → 它的节拍和我们的广播无关。

另外给一条正对照：突发起点相对**它自己**最近一条 presence 帧的偏移。它自己的帧如果也不锁，
说明这台设备压根不用任何 presence 帧做节拍（那就不是「只针对我们」的问题）。

用法
====
    python lockstep.py n3-air.pcap t1-air.pcap
"""
import math
import sys
from collections import Counter

from holes import KNOWN_OURS, frames, rt_len, hdr_len, tcp_seg

# 与 action.rs / schedule.rs 对齐的三个窗口口径（TU = 1.024 ms）。
TU_US = 1024
AW_US = 16 * TU_US        # AW_PERIOD_TU = 16
SLOT_US = 64 * TU_US      # SLOT_TU = 64
PSF_US = 110 * TU_US      # PSF_INTERVAL_TU = 110

BURST_GAP_US = 5000       # 与 holes.py [6] 同一个切点，不另立标准


def mac(b):
    return ':'.join(f'{x:02x}' for x in b)


def collect(path):
    """一遍扫完，分出「谁在发 presence」「谁在发数据」。"""
    presence = {}                      # src -> [ts]
    payload = []                       # (ts, src)
    for ts, d in frames(path):
        rt = rt_len(d)
        if rt + 24 > len(d):
            continue
        fc0, fc1 = d[rt], d[rt + 1]
        # 802.11 的帧控制：bit 0–1 协议版本、bit 2–3 类型、bit 4–7 子类型。
        # **别写成 `fc0 & 0x0f`**：数据帧的 fc0 是 0x08，那样读出来的「类型」是 8。
        # 管理帧恰好是 0xd0（低四位 0），所以只在那儿写错过也看不出来。
        kind = (fc0 >> 2) & 3
        subtype = (fc0 >> 4) & 0x0f
        src = mac(d[rt + 10:rt + 16])
        if kind == 0 and subtype == 13:
            # 管理帧 subtype 13 = action。Apple 的 vendor category 在 OUI 之后
            # 那一位，但这里不需要细分 PSF/MIF：两者都是「我们宣告自己在场」。
            presence.setdefault(src, []).append(ts)
            continue
        if kind != 2:
            continue
        seg = tcp_seg(d, rt)
        if seg and seg[5] > 0:
            payload.append((ts, src))
    return presence, payload


def bursts(times):
    """起点到起点的突发划分，与 holes.py [6] 一致（切点 = 帧间隔 > 5 ms）。"""
    if not times:
        return []
    out = [times[0]]
    for prev, now in zip(times, times[1:]):
        if (now - prev) * 1e6 > BURST_GAP_US:
            out.append(now)
    return out


def rayleigh(phases):
    """圆统计：合矢量长度 R 与平均相位。R 越大越像锁相。"""
    if not phases:
        return 0.0, 0.0
    sx = sum(math.cos(2 * math.pi * p) for p in phases)
    sy = sum(math.sin(2 * math.pi * p) for p in phases)
    n = len(phases)
    r = math.hypot(sx, sy) / n
    return r, (math.atan2(sy, sx) / (2 * math.pi)) % 1.0


def bins(phases, k=8):
    c = Counter(min(int(p * k), k - 1) for p in phases)
    return [c.get(i, 0) for i in range(k)]


def report(name, phase_sets):
    for label, phases in phase_sets:
        if not phases:
            continue
        r, mean = rayleigh(phases)
        n = len(phases)
        # 均匀分布的参考量级：R 的期望约 sqrt(pi)/2 / sqrt(N)。
        floor = math.sqrt(math.pi) / 2 / math.sqrt(n)
        verdict = ('**锁相**' if r > 3 * floor else
                   '可能是锁相（弱）' if r > 1.5 * floor else '自由跑（均匀）')
        h = bins(phases)
        print(f"    {label:<26} N={n:<5} R={r:.3f}  均匀参考≈{floor:.3f}  "
              f"主相位≈{mean:.2f}  {verdict}")
        print(f"    {'':<26} 八分箱 {h}")


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    for path in argv:
        print(f"\n=== {path} ===")
        presence, payload = collect(path)
        if not payload:
            print("  没有带载荷的 TCP 段")
            continue
        # 链路对端 = 数据段最多的那个非我方地址（与 agg.py 同一个做法）。
        peer = Counter(src for _t, src in payload if src not in KNOWN_OURS).most_common(1)
        if not peer:
            print("  只看到我方在发数据")
            continue
        peer = peer[0][0]
        mine = [t for t, src in payload if src == peer]
        starts = bursts(mine)
        span = mine[-1] - mine[0]
        print(f"  链路对端 {peer}：{len(mine)} 段 / {len(starts)} 个突发 / 跨度 {span:.1f}s"
              f"（每突发 {len(mine) / max(1, len(starts)):.2f} 帧）")

        # 我们自己的 presence 时刻：取非对端、且在 KNOWN_OURS 里的地址。
        ours = sorted(t for src, lst in presence.items() if src in KNOWN_OURS for t in lst)
        theirs = sorted(presence.get(peer, []))
        print(f"  我们的 presence {len(ours)} 条，对端自己的 presence {len(theirs)} 条")

        sets = [
            (f"突发起点 mod AW {AW_US / 1000:.2f} ms",
             [(t % (AW_US / 1e6)) / (AW_US / 1e6) for t in starts]),
            (f"突发起点 mod 槽 {SLOT_US / 1000:.2f} ms",
             [(t % (SLOT_US / 1e6)) / (SLOT_US / 1e6) for t in starts]),
            (f"突发起点 mod 我们的 PSF {PSF_US / 1000:.1f} ms",
             [(t % (PSF_US / 1e6)) / (PSF_US / 1e6) for t in starts]),
        ]
        report(path, sets)

        # 正对照：突发起点相对「它自己最近一条 presence」的偏移，模它自己的间隔。
        if len(theirs) > 2:
            iv = sorted(b - a for a, b in zip(theirs, theirs[1:]))
            own = iv[len(iv) // 2]
            gaps = []
            j = 0
            for t in starts:
                while j + 1 < len(theirs) and theirs[j + 1] <= t:
                    j += 1
                gaps.append((t - theirs[j]) / own)
            print(f"    相对它自己 presence 的偏移（模自己的 p50 间隔 {own * 1000:.1f} ms）")
            report(path, [("突发起点 / 它自己的 PSF", [g % 1.0 for g in gaps])])
        else:
            print("    对端自己的 presence 太少（或为 0），无法做自身正对照")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
