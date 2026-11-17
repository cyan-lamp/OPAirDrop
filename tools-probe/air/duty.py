#!/usr/bin/env python3
"""§5.9 那套判据的一体化实测器：一次算完「谁慢在哪一环」，并且只输出能对照的数。

为什么要把散落的一次性脚本收成一个（2026-11-17 12:30）：

这一轮为了回答「0.106 MB/s 是 A 还是 B」，前后写了六个 heredoc 脚本，每个只量一条
（帧数 / 帧长 / 帧间隔 / 在途 / ACK 时延 / retry 位）。问题不是麻烦，是**它们各自
可以被读成相反的结论**，而且下一轮重测时没有同一个口径可比。这里把判据固定成一张表，
同一个输入永远得到同一组数，才谈得上 A/B。

判据与配套指标（每条都标注「为什么必须在这里」，全部来自 §5.9 的实战教训）：

  帧率 / 帧长     分清「发得少」和「每帧更小」。帧长必须用 `tcpdump -s 0` 抓：`-s 128`
                  时 iPhone 的 radiotap 头是 48 字节、我们的是 9 字节，128 减一下就成了
                  「iPhone 80 B / 我们 114 B」的假象。
  占空            对端射频有多少时间停在 AWDL 信道上。按 12 Mb/s 折算只是把字节换成时间，
                  列名里写明，防止有人当成实测空口时间；**相对比较有效，绝对值不可信**。
  突发结构        把占空再拆一层：是「每窗帧数变少」还是「窗变稀」。
                  iPhone 13 是每突发 30 帧，Xs Max 是每突发 2 帧 + 22.7 ms 静默。
  retry 位        这块 tap **不交付 802.11 ACK 控制帧**（两份抓包里控制帧子类型全为空，
                  已实测），所以这个位曾经被当成唯一能间接看 MAC 层确认的东西。
                  **13:05 证伪**：iPhone 13 是 4.9%、Xs Max 是 100.0%，差异是真的，
                  但**它不代表重传**——两份抓包里「同 seq 且载荷完全相同」的重复帧
                  **都是 0 个**，这块固件是**无条件给每个 AWDL 数据帧置 retry=1**。
                  这一列现在只保留「两家发送路径不同」这一个读法；判重传请用
                  `retrydig.py`（它比对副本内容），不要看这一列。
  seq 递增 %      **恒真指标，不携带信息**（13:05 补注）。12 位序号写满 4096 就回绕，
                  只要对端在连续发送，相邻帧的序号几乎必然递增——两份抓包都是 100.0%，
                  而其中一份 100% 带 retry、另一份 4.9%。**「100% 递增」不能用来反驳
                  「它在重传」，也不能用来证明「它没重传」。** 保留这一列只是因为
                  它便宜，能在「对端根本没在发」时立刻暴露（那种情况会掉下来）。
  在途字节        只认**单调递增的最大 ack**。陈旧 ACK 被 MAC 层重放时，直接拿最后一帧
                  的 ack 值算会得出 363 KB 这种假象。
  数据 → 递增 ACK  我们这一侧的反应速度。快，说明「不是我们没回」。
  递增 ACK → 下一帧 对端拿到许可之后还等多久。慢，才是对端的锅。这一对就是判据本身。

两条踩过的坑，写在代码里防复发：

  1. **必须按 TCP 五元组分流**。这条链路不止一条连接（数据连接 + 控制/保活连接），
     把它们的 ack 混成一条序列，会得到「60% 的 ack 在倒退」这种不可能的结论——
     上一版就是这样，把一个统计口径问题误读成了协议异常。
  2. **「重复 ack」和「倒退 ack」要分开数**。TCP 重复发送同一个 ack 值是常态
     （窗口更新、重复 ACK），只有严格小于历史最大值才值得怀疑。
  3. **「倒退 ack」不是对端的异常，而是我方的 TX 重发**（2026-11-17 13:05 定案，
     前两轮把它当成解析口径问题改了两遍，都改错了）。实测：把「TCP 头（含选项与
     cksum）+ 载荷」逐字节完全相同当指纹，我方 12004 个 ACK 帧里有 9001 个（75%）
     是更早某个 ACK 的原样重发。所以这一列应当读作「我方发帧队列的重发比例」，
     不要读成「对端在倒退 ack」。
     **13:30 收窄一句曾被写粗的话**：上一版在这里写「t1（快的那轮）同一指标是 62%，
     两台设备都有，属固定开销」——**前半句对，后半句错**。62% 这个数复现无误
     （14702/23712），但它的构成与 n3 完全不同：真正的 16.4 ms 档，n3 占 **68%**，
     t1 只占 **7%**；t1 的重复里 `< 5 ms`（同一批连续补发）占 **16%**、32.8 ms 档占 6%，
     再现间隔 p50 是 **27.46 ms** 而不是 16.48 ms。
     也就是说：**「每 16 TU 把同一个 ACK 再原样吐一遍」是 n3 这条链路的特征，不是两台共有的
     固定开销。** t1 的 62% 主要是「一个 ACK 被连发好几份」，成因不同，别把它当成同一件事
     的证据去论证「这与对端无关」。两台的共同点只有一句：**重复是发送侧的行为，与对端 ack
     有没有前进无关。**
     口径提醒：上面这组数出自 `ackdig.py [4e]`（C 口径，逐字节）。`[4d]` 用的是「只比头字段」
     的松口径，会额外把「内核新造的同值 ACK」算进来（n3 82.3% / t1 62.0%），那一档的
     16.4 ms 占比只有 48% —— 判定时器时别引用它。
  4. **别用「序号在递增」证明「没有重传」**（2026-11-17 13:05 加）。12 位序号只有
     4096 个值，对端发满一圈就回绕；只要它还在连续发，相邻序号几乎必然递增。
     上一轮正是拿「seq 严格递增 100%」去和「retry 100%」对撞，论证出一个根本不
     存在的悖论。**判重传只有一个判据：同序号且载荷相同。** 见 `retrydig.py`。

用法：
    python3 duty.py a.pcap                                            # 单份
    python3 duty.py t1-air.pcap n3-air.pcap --labels iPhone13 XsMax    # 并排对照

抓包要求（脚本无法自检，所以写在这里）：
    adb shell 'timeout 40 tcpdump -i wondertap0 -s 0 -U -w /data/local/tmp/air.pcap -p'
`-s 0` 是全长度；`-U` 让提前停止也能留下最后一缓冲（短抓包的全部内容就在那一块里）。
"""
import struct
import sys
from bisect import bisect_left

# 抓包里"我们"用过的 MAC。03:26 那次（t1，857 kB/s 基准）是 aa:7d:2c:e3:69:41，
# 重装之后变成 36:bc:0c:fc:83:f3。两个都认，否则拿旧抓包对照时旧帧会被全判成
# 「对端」——实测过一次：按单 MAC 过滤得 4902 vs 1486，把方向整个搞反。
KNOWN_OURS = {
    'aa:7d:2c:e3:69:41',
    '36:bc:0c:fc:83:f3',
}

NOMINAL_MBPS = 12.0   # 两侧一致的强制 OFDM 速率；占空只用来做相对比较


def frames(path):
    """逐 record 产出 (linktype, ts, data, orig_len)。

    两个长度都返回：判断帧长要用 orig（空口真实长度），解析只能用 incl（落盘长度）。
    混淆这两者，就是 `-s 128` 那次假象的第二条成因。
    """
    with open(path, 'rb') as f:
        gh = f.read(24)
        if len(gh) < 24:
            raise SystemExit('不是 pcap：文件太短')
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


def radiotap_len(d):
    """radiotap 头长度在 offset 2（小端 uint16）。**不能硬编码**：iPhone 的帧带
    MCS/VHT 字段，头是 48 字节；我们的是 9 字节。差这 39 字节，所有偏移全错。"""
    if len(d) < 4 or d[0] != 0:
        return 0
    return struct.unpack('<H', d[2:4])[0]


def dot11_hdr_len(fc):
    """802.11 MAC 头长度，按 ToDS/FromDS/QoS/Order 四位算，不要写死 24。

    AWDL 的对端数据帧是 3 地址 + QoS（26 字节），我们的可能是 4 地址（30 字节），
    写死 24 会让 LLC 从中间开始，IPv6 版本号对不上，于是"没有 TCP 载荷"，于是
    "在途算不出来"——这条上一轮踩过。
    """
    to_ds = (fc >> 8) & 1
    from_ds = (fc >> 9) & 1
    sub = (fc >> 4) & 0xF
    order = (fc >> 15) & 1
    n = 24
    if to_ds and from_ds:
        n += 6
    if sub & 0x8:
        n += 2
    if order:
        n += 4
    return n


def ip6_tcp(d, start, limit):
    """在 [start, start+20] 里找 IPv6 头，返回 (sport, dport, seq, ack, plen)。

    为什么是"扫"而不是"算偏移"：AWDL 的 LLC 头是 16 字节而不是普通 SNAP 的 8 字节
    （`aa aa 03 | 00 17 f2 | 08 00 | 03 04 | seq`），具体多长还跟 QoS/HT Control 有关。
    上一轮因为按 8 字节解析，所有 mDNS 帧被漏读，得出了"宣告器没在跑"的错误结论。
    这里用小窗口扫描 + 三重校验（版本号 6、next header 是 TCP、payload length 自洽），
    自校验，比猜偏移可靠。
    """
    for off in range(start, min(start + 20, limit - 40)):
        if (d[off] >> 4) != 6:
            continue
        if d[off + 6] != 6:                        # next header = TCP
            continue
        plen = struct.unpack('>H', d[off + 4:off + 6])[0]
        if plen == 0 or off + 40 + plen > limit:
            continue
        t = off + 40
        sport, dport = struct.unpack('>HH', d[t:t + 4])
        seq = struct.unpack('>I', d[t + 4:t + 8])[0]
        ack = struct.unpack('>I', d[t + 8:t + 12])[0]
        doff = (d[t + 12] >> 4) * 4
        return sport, dport, seq, ack, max(0, plen - doff)
    return None


def pctl(xs, p):
    if not xs:
        return float('nan')
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))]


def analyze(path):
    link = None
    tx_bytes = {}       # mac -> 发出的数据帧字节（认对端：灌数据的那方）
    tx_frames = {}      # mac -> 发出的数据帧数（认自己：回 ACK 的那方）
    first_ts = last_ts = None

    for link, ts, d, orig in frames(path):
        rt = radiotap_len(d) if link == 127 else 0
        if len(d) < rt + 24:
            continue
        if ((d[rt] >> 2) & 3) != 2:
            continue
        a2 = d[rt + 10:rt + 16].hex(':')
        first_ts = ts if first_ts is None else first_ts
        last_ts = ts
        tx_bytes[a2] = tx_bytes.get(a2, 0) + orig
        tx_frames[a2] = tx_frames.get(a2, 0) + 1

    span = (last_ts - first_ts) if first_ts is not None else 0.0
    if not tx_bytes:
        raise SystemExit(f'{path}: 没有数据帧')

    # 对端 = 发数据字节最多的；我们 = 其余里发数据帧最多的。
    # **不能用控制帧认自己**：这块 tap 不交付 802.11 ACK（已实测），我们发出去的 ACK
    # 是带 TCP 头的 QoS 数据帧，只能按数据帧认。
    peer = max(tx_bytes, key=tx_bytes.get)
    cand = [m for m in tx_frames if m != peer]
    ours = max(cand, key=tx_frames.get) if cand else None

    # ---- 第二遍：帧级指标 + 按五元组分流的 TCP 事件 -------------------------
    peer_data = []                  # (ts, 空口长度, retry)
    peer_seq = []
    flows = {}                      # (sport, dport) -> {'peer': [(ts,end)], 'our': [(ts,ack)], 'bytes': int}
    n_our = 0

    for link, ts, d, orig in frames(path):
        rt = radiotap_len(d) if link == 127 else 0
        if len(d) < rt + 24:
            continue
        fc = d[rt]
        typ = (fc >> 2) & 3
        a2 = d[rt + 10:rt + 16].hex(':')
        if typ != 2:
            continue

        got = ip6_tcp(d, rt + dot11_hdr_len(fc), len(d))

        if a2 == peer:
            peer_data.append((ts, orig, (d[rt + 1] >> 3) & 1))
            peer_seq.append(struct.unpack('<H', d[rt + 22:rt + 24])[0] >> 4)
            if got and got[4]:
                sport, dport, seq, _ack, plen = got
                # 对端的帧是它发出的：src 端口是它的，dst 端口是我们的，
                # 所以这里把它读到的 (sport,dport) 直接当键，我们的帧用同一对端口。
                f = flows.setdefault((sport, dport), {'peer': [], 'our': [], 'bytes': 0})
                f['peer'].append((ts, seq + plen))
                f['bytes'] += plen
        elif ours and a2 == ours:
            n_our += 1
            if got:
                sport, dport, _seq, ack, _plen = got
                f = flows.setdefault((dport, sport), {'peer': [], 'our': [], 'bytes': 0})
                f['our'].append((ts, ack))

    # ---- 主连接：对端灌数据最多的那条 --------------------------------------
    main = max(flows, key=lambda k: flows[k]['bytes']) if flows else None
    f = flows.get(main, {'peer': [], 'our': []})

    mono = []
    best = -1
    repeat = regress = 0
    for ts, ack in f['our']:
        if ack > best:
            best = ack
            mono.append((ts, ack))
        elif ack == best:
            repeat += 1          # 正常：窗口更新 / 重复 ACK
        else:
            regress += 1         # 严格小于：只可能是 MAC 层重放的旧 ACK

    # 在途：对端已发末端 − 我们单调最大 ack，按数据帧到达逐点采样
    inflight = []
    peer_end = 0
    best_ack = -1
    have = False
    evs = [(t, 'd', v) for t, v in f['peer']] + [(t, 'a', v) for t, v in f['our']]
    for ts, kind, val in sorted(evs):
        if kind == 'd':
            peer_end = max(peer_end, val)
            if have:
                inflight.append(max(0, peer_end - best_ack))
        elif val > best_ack:
            best_ack = val
            have = True

    ts_list = [t for t, _, _ in peer_data]
    gaps = [(ts_list[i + 1] - ts_list[i]) * 1000 for i in range(len(ts_list) - 1)]
    intra = [g for g in gaps if g < 5.0]
    sil = [g for g in gaps if g >= 5.0]

    d2a, a2d = [], []
    for ts_ack, _ in mono:
        i = bisect_left(ts_list, ts_ack)
        if i > 0:
            d2a.append((ts_ack - ts_list[i - 1]) * 1000)
        if i < len(ts_list):
            a2d.append((ts_list[i] - ts_ack) * 1000)

    lens = [L for _, L, _ in peer_data]
    retries = sum(r for _, _, r in peer_data)
    # 相邻序号「严格变大」的比例。**这是个恒真指标，别当证据用**：12 位序号
    # 写满 4096 就回绕，而这里用 `>` 比较，回绕处会掉一次，但 5000 帧里只掉
    # 1 次（99.98% → 显示成 100.0%），所以两份抓包不管快慢都是 100.0%。
    # 它唯一的价值是「对端根本没在发数据」时会明显掉下来。判重传请看
    # retrydig.py 的「同 seq 且同载荷」，不是看这里。
    ordered = sum(1 for i in range(len(peer_seq) - 1) if peer_seq[i + 1] > peer_seq[i])
    total = sum(lens)

    return {
        'file': path,
        'span_s': span,
        'linktype': link,
        'peer': peer,
        'ours': ours,
        'flow': main,
        'flows': len(flows),
        'peer_frames': len(peer_data),
        'fps': len(peer_data) / span if span else 0,
        'MBps': total / span / 1e6 if span else 0,
        'len_p50': pctl(lens, 0.5),
        'len_max': max(lens) if lens else 0,
        'duty_pct': (total * 8 / (NOMINAL_MBPS * 1e6) / span * 100) if span else 0,
        'intra_ms': pctl(intra, 0.5),
        'silence_ms': pctl(sil, 0.5),
        'burst': len(peer_data) / (len(sil) + 1) if peer_data else 0,
        'retry_pct': 100.0 * retries / len(peer_data) if peer_data else 0,
        'seq_ordered_pct': 100.0 * ordered / max(1, len(peer_seq) - 1),
        'ack_ratio': n_our / len(peer_data) if peer_data else 0,
        'ack_repeat_pct': 100.0 * repeat / max(1, len(f['our'])),
        'ack_regress_pct': 100.0 * regress / max(1, len(f['our'])),  # 我方 TX 重发，不是对端异常
        'inflight_p50_KB': pctl(inflight, 0.5) / 1024,
        'inflight_le3KB_pct': 100.0 * sum(1 for v in inflight if v <= 3072) / max(1, len(inflight)),
        'd2a_ms': pctl(d2a, 0.5),
        'a2d_ms': pctl(a2d, 0.5),
    }


ROWS = [
    ('对端数据帧', 'peer_frames', '{:.0f}'),
    ('对端帧率 fps', 'fps', '{:.0f}'),
    ('对端数据 MB/s', 'MBps', '{:.3f}'),
    ('帧长 p50 B', 'len_p50', '{:.0f}'),
    ('帧长 max B', 'len_max', '{:.0f}'),
    ('占空 %（12M 折算）', 'duty_pct', '{:.0f}'),
    ('突发内间隔 p50 ms', 'intra_ms', '{:.2f}'),
    ('静默 p50 ms', 'silence_ms', '{:.2f}'),
    ('每突发帧数', 'burst', '{:.1f}'),
    ('retry %（无重传含义）', 'retry_pct', '{:.1f}'),
    ('seq 递增 %（恒真）', 'seq_ordered_pct', '{:.1f}'),
    ('我方帧/对端帧', 'ack_ratio', '{:.2f}'),
    ('ack 重复 %', 'ack_repeat_pct', '{:.0f}'),
    ('ack 低于历史最大 %', 'ack_regress_pct', '{:.0f}'),
    ('在途 p50 KB', 'inflight_p50_KB', '{:.1f}'),
    ('在途 ≤3KB %', 'inflight_le3KB_pct', '{:.0f}'),
    ('数据→递增ACK p50 ms', 'd2a_ms', '{:.2f}'),
    ('递增ACK→下一帧 p50 ms', 'a2d_ms', '{:.2f}'),
]

TAIL = [
    ('span', 'span_s', '{:.1f}s'),
    ('TCP 连接数', 'flows', '{}'),
    ('主连接', 'flow', '{}'),
    ('ours', 'ours', '{}'),
    ('peer', 'peer', '{}'),
]


def main(argv):
    labels = None
    if '--labels' in argv:
        i = argv.index('--labels')
        labels = argv[i + 1:]
        argv = argv[:i]
    if not argv:
        print(__doc__)
        return 2

    res = [analyze(p) for p in argv]
    if len(res) == 1:
        for name, key, fmt in ROWS + TAIL:
            print(f'{name:22s} {fmt.format(res[0][key])}')
        return 0

    labels = labels or [p.split('/')[-1][:12] for p in argv]
    w = max(len(n) for n, _, _ in ROWS + TAIL) + 2
    # 列宽必须按**实际内容**算：MAC 是 17 字符，写死 14 会让
    # `rjust(14)` 撑破格子，两行 MAC 直接粘成一串（踩过一次，纯显示问题但看着像 bug）。
    cw = max([14] + [len(fmt.format(r[key])) + 2
                     for _, key, fmt in ROWS + TAIL for r in res])
    print('指标'.ljust(w) + ''.join(l.rjust(cw) for l in labels))
    print('-' * (w + cw * len(labels)))
    for name, key, fmt in ROWS:
        print(name.ljust(w) + ''.join(fmt.format(r[key]).rjust(cw) for r in res))
    print('-' * (w + cw * len(labels)))
    for name, key, fmt in TAIL:
        print(name.ljust(w) + ''.join(fmt.format(r[key]).rjust(cw) for r in res))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
