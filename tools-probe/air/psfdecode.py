#!/usr/bin/env python3
"""把对端 presence 帧（PSF / MIF）里的同步参数 TLV 解出来，尤其是**它自己通告的 AW 周期**。

为什么要有这个脚本
==================
2026-11-17 14:00 之后剩下的唯一一个「我们这侧能动的变量」是 presence 帧里那组可用性参数
（`AW_PERIOD_TU = 16` / `SLOT_TU = 64` / `SLOTS = 16`，见 `crates/awdl/src/schedule.rs`）。
动它的前提是「对端是照着**我们通告的窗口**在填帧」。这个前提有一个更便宜的问法：
**对端自己通告的是什么？**

- 如果它通告的 AW 周期也是 16、presence mode 也是 4（全在场），那说明这组参数在空口上是
  **双方同构**的，我们的通告没有给它任何额外信息 —— 「它照我们的窗口填」就失去落点。
- 如果它通告的 AW 周期不是 16，或者 presence mode 小于 4，那说明这个字段是**自由填的**，
  我们改它不会被当成畸形帧，而且能直接从它的数字里读出它认为一个窗有多长。

这件事**零风险**：只读现成抓包，不装包、不改代码。所以放在动代码之前做。

字段位置（`crates/awdl/src/action.rs::sync_params` 写的，逐字节对齐）
=====================================================================
TLV 体（kind = 4）：
    0      channel
    1..3   next_tu            u16 le   —— 它在当前槽里还剩多少 TU
    3      master_channel
    4      guard time
    5..7   AW period          u16 le   ← 就是这个
    7..9   PSF interval       u16 le
    9..11  flags
    11..13 extended AW        u16 le
    13..15 common AW          u16 le
    15..17 remaining AW       u16 le
    17..21 min/max ext、multicast、AF 四个「AW 个数 - 1」
    21..27 master 地址
    27     presence mode      —— 4 = 一个槽里 4 个 AW 全在场
    28     pad
    29..31 AW counter         u16 le
    31..33 AP alignment       u16 le
    33..  序列（6 字节头 + 每槽 2 字节 × SLOTS）
体长应为 **73** 字节。对端不是这个实现，长度可能不同 —— 所以每个字段都做边界检查，
读不到就打 `?`，绝不猜。

用法
====
    python psfdecode.py n3-air.pcap t1-air.pcap
"""
import struct
import sys
from collections import Counter, defaultdict

from holes import frames, rt_len

# 802.11 管理帧 subtype 13 = action；Apple 的 vendor category 是 0x7f。
CATEGORY_VENDOR = 0x7f
OUI = b'\x00\x17\xf2'
AWDL_TYPE = 0x08
TLV_SYNC_PARAMS = 4
TLV_CHANNEL_SEQUENCE = 18

# 我们自己的地址（出现过两代）。判定「谁是我们」只为了给输出分栏，不参与任何推断。
KNOWN_OURS = {
    'aa:7d:2c:e3:69:41',
    '36:bc:0c:fc:83:f3',
}

SUBTYPE = {0: 'PSF', 3: 'MIF'}


def mgmt_hdr_len(fc0, fc1):
    """管理帧的 802.11 头长度。

    **不要用 `holes.hdr_len`**：那个函数是给数据帧写的，它按 bit 7 判 QoS 并加 2 字节，
    而 QoS 位只在数据帧上存在。管理帧的 fc1 恒为 0、bit 7 却会被读成 1，
    于是偏移多 2 —— 实测第一帧解出来的第一个字节是 `17`（OUI 的第二字节）而不是
    `7f`（vendor category），整条 TLV 链往后错两位、静默解不出任何东西。
    （同一个坑在 `holes.py` 的 docstring 里记过一次，那次是把 QoS 位读成 Order 位；
    这次是拿数据帧的规则去量管理帧。**错位不会报错，只会安静地少出结果。**）
    """
    n = 24
    if (fc1 >> 7) & 1:  # Order → HT Control（管理帧上少见，但存在）
        n += 4
    return n


def mac(b):
    return ':'.join(f'{x:02x}' for x in b)


def le16(d, at):
    """读一个 u16，越界返回 None —— 对端帧长短不一定，边界检查比断言安全。"""
    if at + 2 > len(d):
        return None
    return struct.unpack('<H', d[at:at + 2])[0]


def parse(path, pathname):
    out = []
    for ts, d in frames(path):
        rt = rt_len(d)
        if rt is None or rt + 24 > len(d):
            continue
        fc0, fc1 = d[rt], d[rt + 1]
        # type=0（管理）、subtype=13（action）。控制帧/数据帧一概不看。
        # 类型位在 bit 2–3（见 lockstep.py 里那段注释），不是低四位。
        if ((fc0 >> 2) & 3) != 0 or ((fc0 >> 4) & 0x0f) != 13:
            continue
        at = rt + mgmt_hdr_len(fc0, fc1)
        # category(1) OUI(3) type(1) version(1) subtype(1) pad(1) ts(4) target(4)
        if at + 16 > len(d):
            continue
        if d[at] != CATEGORY_VENDOR or d[at + 1:at + 4] != OUI or d[at + 4] != AWDL_TYPE:
            continue
        subtype = d[at + 6]
        src = mac(d[rt + 10:rt + 16])   # address 2
        body = at + 16
        tlvs = {}
        while body + 3 <= len(d):
            kind = d[body]
            length = struct.unpack('<H', d[body + 1:body + 3])[0]
            body += 3
            if body + length > len(d):
                break
            tlvs[kind] = d[body:body + length]
            body += length
        sync = tlvs.get(TLV_SYNC_PARAMS)
        if sync is None:
            continue
        out.append({
            't': ts,
            'src': src,
            'subtype': SUBTYPE.get(subtype, f'?{subtype:#x}'),
            'channel': sync[0] if len(sync) > 0 else None,
            'next_tu': le16(sync, 1),
            'master_channel': sync[3] if len(sync) > 3 else None,
            'aw_period': le16(sync, 5),
            'psf_interval': le16(sync, 7),
            'flags': le16(sync, 9),
            'ext_aw': le16(sync, 11),
            'common_aw': le16(sync, 13),
            'remaining': le16(sync, 15),
            'counts': sync[17:21].hex() if len(sync) > 20 else '?',
            'master': mac(sync[21:27]) if len(sync) >= 27 else '?',
            'presence_mode': sync[27] if len(sync) > 27 else None,
            'aw_counter': le16(sync, 29),
            'map': tlvs.get(TLV_CHANNEL_SEQUENCE),
            'body_len': len(sync),
            'path': pathname,
        })
    return out


def report(rows):
    by_src = defaultdict(list)
    for r in rows:
        by_src[r['src']].append(r)
    for src, rs in sorted(by_src.items(), key=lambda kv: -len(kv[1])):
        who = '我们' if src in KNOWN_OURS else '对端/其他'
        first = rs[0]
        print(f"\n  {src}（{who}）{len(rs)} 帧  subtitle {Counter(r['subtype'] for r in rs).most_common()}")
        # 「这一列有没有变过」比「平均值」重要：整批一致才说明参数是静态的，
        # 一旦中途变了，后面的吞吐对比就不是同一个条件下的两个样本。
        for key in ('channel', 'aw_period', 'ext_aw', 'common_aw', 'psf_interval',
                    'flags', 'presence_mode', 'counts', 'body_len'):
            seen = Counter(r[key] for r in rs)
            head = ' / '.join(f'{k}×{v}' for k, v in seen.most_common())
            print(f"    {key:<14} {head}")
        nt = [r['next_tu'] for r in rs if r['next_tu'] is not None]
        rem = [r['remaining'] for r in rs if r['remaining'] is not None]
        if nt:
            print(f"    next_tu        min {min(nt)} / max {max(nt)}（{len(set(nt))} 个不同值）")
        if rem:
            print(f"    remaining      min {min(rem)} / max {max(rem)}（{len(set(rem))} 个不同值）")
        awc = [r['aw_counter'] for r in rs if r['aw_counter'] is not None]
        if len(awc) > 1 and rs[-1]['t'] > rs[0]['t']:
            span = rs[-1]['t'] - rs[0]['t']
            step = (awc[-1] - awc[0]) % 65536
            if span > 0 and step > 0:
                print(f"    AW 计数        跨度 {span:.1f}s 走了 {step} 个 AW → "
                      f"**{step / span:.2f} AW/s**，折合一个 AW ≈ {1000 * span / step:.2f} ms")
        maps = Counter(r['map'] for r in rs if r['map'] is not None)
        for blob, n in maps.most_common():
            print(f"    槽表          {n} 帧  {blob.hex(' ')}")
        if first['aw_period'] and first['psf_interval']:
            print(f"    → 它宣称的窗：AW {first['aw_period']} TU"
                  f"（{first['aw_period'] * 1.024:.2f} ms）、"
                  f"presence {first['presence_mode']}/4、"
                  f"PSF 每 {first['psf_interval']} TU = {first['psf_interval'] * 1.024:.1f} ms")


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    for path in argv:
        print(f"\n=== {path} ===")
        report(parse(path, path))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
