#!/usr/bin/env python3
"""空口聚合状态取证：这条链路到底有没有在用 A-MPDU / Block Ack。

为什么要有这个脚本（2026-11-17 13:20）：

「邀请对端聚合（我们发 ADDBA Request）」这个方案的前提是「对端现在没有聚合，一旦聚合，
每窗能装更多字节，吞吐乘上去」。这个前提**必须先在空口上验一遍**，否则我们会花两小时
改代码，去修一个并不存在的缺口。而且验它的成本只是一条命令。

三件事分开量，因为它们的证据强度不同：

  1. **Block Ack 相关帧**（管理帧 category 3 = BA action，即 ADDBA Request/Response/DELBA）。
     这是最强的正面证据：出现了就说明有会话建立过程，没出现就说明**没人建过会话**。
     **但控制帧（BAR/BA）这条路是死的，别指望它**：实测 n3 / n4 / t1 / m1 四份抓包里
     802.11 控制帧**一个都没有**（type=1 计数为 0）——而每条数据帧在空口上都必然被 ACK，
     所以不是「没有控制帧」，是这个 vendor tap **根本不把控制帧交给抓包**。
     因此 [1] 里那个「BAR/BA 0 个」只能证明 tap 看不见，不能证明空口没有；
     **能当证据的只有管理帧的 action 类别与帧长**。第一版脚本在这里写
     「驱动没法把控制帧伪装成别的」，是错的，13:20 已改。

  2. **radiotap 的 AMPDU 字段**（present bit 20）。出现了，说明这一帧是某个 A-MPDU 里的
     MPDU，`reference` 相同的帧属于同一个 PPDU，数一下就知道每个 PPDU 装了几个 MPDU。
     **但这个证据只能用在「有」的方向上**：n3/n4 的对端帧 present0 = 0x4000086f，连
     MCS(bit 19) 都没有，说明这个 vendor tap 压根不填 RX 的速率/聚合状态（详见 §5.9.y）。
     所以「没有 AMPDU 字段」不等于「没有聚合」，只能说「测不到」。

  3. **MPDU 长度分布**。这是 2 的兜底，也是最不容易被驱动糊弄的一条：A-MPDU 会把多个
     MSDU 装进一个 PPDU，如果驱动把整个 PPDU 作为一个帧交上来，长度会远超 1552 B
     （单个 MSS 段 + 头）。t1 里若出现 3–32 KB 的帧，即使没有 AMPDU 字段也能断定有聚合；
     若最大帧长就是 1538/1552，那至少「大帧聚合」这条路是空的。

判读写在最后一行，不由这个脚本下结论——它只把三个证据并排打出来。

用法：
    python3 agg.py n3-air.pcap n4-air-full.pcap t1-air.pcap m1-snap.pcap
"""
import struct
import sys
from collections import Counter, defaultdict

sys.path.insert(0, '.')
from holes import KNOWN_OURS, frames, rt_len, hdr_len   # noqa: E402

# 控制帧 subtype。802.11 里 type=1 是控制帧，subtype 决定是哪种。
CTRL = {
    2: 'Trigger', 4: 'Beamforming', 5: 'VHT NDP Announcement',
    6: 'Control Wrapper', 7: 'BAR', 8: 'BAR', 9: 'BA',
    10: 'PS-Poll', 11: 'RTS', 12: 'CTS', 13: 'ACK', 14: 'CF-End',
}
# 管理帧 subtype 13 是 action；它的第一字节是 category。
MGMT = {0: 'AssocReq', 1: 'AssocResp', 2: 'ReassocReq', 3: 'ReassocResp',
        4: 'ProbeReq', 5: 'ProbeResp', 8: 'Beacon', 10: 'Disassoc',
        11: 'Auth', 12: 'Deauth', 13: 'Action'}
# BA 相关 action 码（category 3 = BA）
BA_ACTION = {0: 'ADDBA Request', 1: 'ADDBA Response', 2: 'DELBA', 3: 'BAR(action)'}


def radiotap_bits(d):
    """返回 radiotap 的 present 位图（第 0 字），用于判 MCS/AMPDU/VHT 字段是否存在。"""
    if len(d) < 8:
        return 0
    return struct.unpack('<I', d[4:8])[0]


def ampdu_ref(d):
    """若 radiotap 带 AMPDU 字段，取它的 reference 号；没有则返回 None。

    radiotap 字段是**按 present 位图逐位累加、每段按自己的对齐要求补齐**的，
    所以不能直接跳到某个固定偏移。这里按规范把前 20 个字段的长度/对齐写死走一遍，
    只为了拿到 bit 20。走不通就返回 None——宁可说测不到，也不要错位读出一个假 ref。
    """
    if len(d) < 8:
        return None
    # present 可能有多字（bit 31 是扩展标志）
    words, o = [], 8
    p = struct.unpack('<I', d[4:8])[0]
    words.append(p)
    while p & (1 << 31):
        if len(d) < o + 4:
            return None
        p = struct.unpack('<I', d[o:o + 4])[0]
        words.append(p)
        o += 4
    w0 = words[0]
    if not (w0 >> 20) & 1:
        return None
    # (bit, size, align) —— 到 bit20 为止的全部字段，规范定义
    specs = [(0, 8, 8), (1, 1, 1), (2, 1, 1), (3, 4, 2), (4, 2, 2), (5, 1, 1),
             (6, 1, 1), (7, 2, 2), (8, 2, 2), (9, 2, 2), (10, 1, 1), (11, 1, 1),
             (12, 1, 1), (13, 1, 1), (14, 2, 2), (15, 2, 2), (16, 1, 1), (17, 1, 1),
             (18, 8, 4), (19, 3, 1), (20, 8, 4)]
    for bit, size, al in specs:
        if not (w0 >> bit) & 1:
            continue
        o += (-o) % al
        if bit == 20:
            if o + 4 > len(d):
                return None
            return struct.unpack('<I', d[o:o + 4])[0] & 0xfff
        o += size
    return None


def pctl(xs, q):
    """线性插值分位数；xs 需已排序。空表返回 0，免得调用点到处写 if。"""
    if not xs:
        return 0.0
    k = (len(xs) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def scan(path):
    ctrl = Counter()          # 控制帧 subtype → 次数
    mgmt = Counter()          # 管理帧 subtype → 次数
    act_cat = Counter()       # action 帧 category → 次数
    ba_act = Counter()        # category 3 的 action 码 → 次数
    ba_who = []               # (ts, act码, src, dst, 帧体) —— 谁跟谁建会话
    bits = Counter()          # radiotap present0 → 次数
    refs = Counter()          # (发送方, reference) → 该 ref 下的 MPDU 数
    lens = defaultdict(list)  # 发送方 → MPDU 长度列表
    qos_amsdu = 0             # QoS Control 里 A-MSDU Present 置位的帧数
    total = 0
    t0 = t1 = None

    for ts, d in frames(path):
        total += 1
        t0 = ts if t0 is None else t0
        t1 = ts
        rt = rt_len(d)
        if len(d) < rt + 24:
            continue
        fc0, fc1 = d[rt], d[rt + 1]
        typ, sub = (fc0 >> 2) & 3, (fc0 >> 4) & 0xf
        src = d[rt + 10:rt + 16].hex(':')
        dst = d[rt + 4:rt + 10].hex(':')
        ml = len(d) - rt                    # MPDU 长度（去掉 radiotap）

        if typ == 1:
            ctrl[sub] += 1
            continue
        if typ == 0:
            mgmt[sub] += 1
            if sub == 13:
                a = rt + 24
                if len(d) > a + 1:
                    cat = d[a]
                    act_cat[cat] += 1
                    if cat == 3:
                        ba_act[d[a + 1] if len(d) > a + 1 else -1] += 1
                        ba_who.append((ts - t0, d[a + 1] if len(d) > a + 1 else -1,
                                       src, dst, d[a:min(a + 9, len(d))].hex(' ')))
            continue

        # 数据帧：只统计 MPDU 长度分布与 radiotap 聚合线索
        lens[src].append(ml)
        bits[radiotap_bits(d)] += 1
        r = ampdu_ref(d)
        if r is not None:
            refs[(src, r)] += 1
        if (fc1 >> 7) & 1 and len(d) >= rt + hdr_len(fc0, fc1):
            qc = struct.unpack('<H', d[rt + 24 + 2:rt + 26 + 2])[0] if len(d) > rt + 27 else 0
            if qc & 0x80:
                qos_amsdu += 1

    span = (t1 - t0) if (t0 is not None and t1 is not None) else 0
    return dict(ctrl=ctrl, mgmt=mgmt, act_cat=act_cat, ba_act=ba_act, ba_who=ba_who,
                bits=bits, refs=refs, lens=lens, amsdu=qos_amsdu, total=total, span=span)


def report(path):
    s = scan(path)
    # 链路对端 = 非我方的、发数据帧最多的那个地址。t1 / m1 里同一信道还有别的 BSS
    # （36:82:77… ↔ f8:ce:21…），它们是真的在上网、真的在建 BA 会话，
    # 混进来会把「有聚合迹象」判给一条跟我们无关的链路——所以后面一律分开发言。
    peer = max((m for m in s['lens'] if m not in KNOWN_OURS),
               key=lambda m: len(s['lens'][m]), default=None)
    print(f'\n=== {path}   帧 {s["total"]}  跨度 {s["span"]:.1f}s   链路对端 {peer}')

    # ---- 1. 控制帧：先说清「看不见」 ----
    ba_frames = s['ctrl'].get(7, 0) + s['ctrl'].get(8, 0) + s['ctrl'].get(9, 0)
    top = ', '.join(f'{CTRL.get(k, k)}×{v}' for k, v in s['ctrl'].most_common(6))
    print(f'[1] 控制帧：{top or "**一个都没有**（含 ACK）"}')
    print(f'    Block Ack 控制帧（BAR/BA）：{ba_frames} 个  '
          f'← 控制帧总数 {sum(s["ctrl"].values())}，tap 不交控制帧，这个 0 不能当证据')

    # ---- 2. 管理帧与 action 类别 ----
    mt = ', '.join(f'{MGMT.get(k, k)}×{v}' for k, v in s['mgmt'].most_common(6))
    print(f'[2] 管理帧：{mt or "无"}')
    if s['act_cat']:
        cats = ', '.join(f'cat{k}×{v}' for k, v in s['act_cat'].most_common(6))
        print(f'    action 类别：{cats}   （cat3 = BA，cat127 = Apple vendor）')
        if s['ba_act']:
            names = ', '.join(f'{BA_ACTION.get(k, k)}×{v}' for k, v in s['ba_act'].items())
            print(f'    └ BA action 明细：{names}')
            for t, act, src, dst, body in s['ba_who'][:8]:
                print(f'        {t:7.2f}s  {BA_ACTION.get(act, act):15s} {src} → {dst}  {body}')
            if len(s['ba_who']) > 8:
                print(f'        …… 另有 {len(s["ba_who"]) - 8} 条')
        else:
            print('    └ BA action 明细：**0 个**（没有任何 ADDBA Request/Response/DELBA）')

    # ---- 3. radiotap：能不能测到聚合 ----
    rare = [(b, n) for b, n in s['bits'].most_common() if (b >> 20) & 1]
    print(f'[3] radiotap present0 分布（数据帧）：')
    for b, n in s['bits'].most_common(3):
        print(f'      0x{b:08x}  {n} 帧   MCS(bit19)={"有" if (b >> 19) & 1 else "无"}  '
              f'AMPDU(bit20)={"有" if (b >> 20) & 1 else "无"}  '
              f'VHT(bit21)={"有" if (b >> 21) & 1 else "无"}')
    if rare:
        print(f'    ↑ 另有带 AMPDU 字段的 present0：'
              f'{", ".join(f"0x{b:08x}×{n}" for b, n in rare)}  '
              f'← **tap 能报，只是极少**，所以「没报 AMPDU」既可能是没聚合也可能是漏报')
    else:
        print('    AMPDU 字段：**任何 present0 都没有**')
    if s['refs']:
        for (src, r), n in sorted(s['refs'].items(), key=lambda kv: -kv[1])[:4]:
            print(f'    AMPDU ref 0x{r:03x}  发送方 {src}{"（我方）" if src in KNOWN_OURS else ""}  '
                  f'{n} 个 MPDU/PPDU')
    else:
        print('    AMPDU reference：**一个都没有**')
    print(f'    QoS A-MSDU Present 置位：{s["amsdu"]} 帧')

    # ---- 4. MPDU 长度分布：谁发了多长的帧 ----
    rows = sorted(s['lens'].items(), key=lambda kv: -len(kv[1]))[:3]
    print('[4] 数据帧 MPDU 长度（按发送方）：')
    peer_max = 0
    for mac, ls in rows:
        ls.sort()
        mine = '（我方）' if mac in KNOWN_OURS else ('（链路对端）' if mac == peer else '（同信道其他设备）')
        big = sum(1 for x in ls if x > 1600)
        huge = sum(1 for x in ls if x > 3000)
        if mac == peer:
            peer_max = ls[-1]
        print(f'    {mac}{mine}  {len(ls)} 帧  p50 {pctl(ls, .5):.0f}  '
              f'p90 {pctl(ls, .9):.0f}  p99 {pctl(ls, .99):.0f}  max {ls[-1]}'
              f'   >1600B {big}  >3000B {huge}')

    # ---- 5. 一行判定：只针对链路对端 ----
    # 判据只用「对端自己发的 MPDU 最大长度」与「对端参与的 BA action 帧」，
    # 因为这两条不依赖 tap 的漏报，也分得清是哪条链路。
    ours_ba = sum(1 for _t, _a, src, dst, _b in s['ba_who']
                  if peer in (src, dst))
    agg = ('有聚合迹象' if (peer_max > 1600 and ours_ba) else
           '有会话但没聚合' if ours_ba else
           '无会话、无聚合' if peer_max <= 1600 else '有长帧但没会话')
    print(f'[5] 判定（只算链路对端）：对端最大 MPDU {peer_max} B，'
          f'对端参与的 BA action {ours_ba} 个 → {agg}')
    return s


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    for p in sys.argv[1:]:
        report(p)


if __name__ == '__main__':
    main()
