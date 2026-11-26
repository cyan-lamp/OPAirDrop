#!/usr/bin/env python3
"""One-off: what did the tun capture say about this run.

The tunnel is IPv6-only (AWDL peers are reached over link-local), so this parses IPv6 with
a directly-following TCP header and nothing else. It answers exactly three things:

  1. wall clock from first payload segment to last -- the number that competes with the
     60.38 s baseline, measured on the same device with the same file size
  2. whether TCP misbehaved (reorder / dup ack), so a throughput change can be credited to
     the radio rather than to the stack
  3. how many segments per second, which is the quantity the peer's frame rate caps
"""
import struct, sys
from collections import Counter


def frames(path):
    with open(path, 'rb') as f:
        gh = f.read(24)
        magic = struct.unpack('<I', gh[:4])[0]
        endian = '<' if magic in (0xa1b2c3d4, 0x4d3cb2a1) else '>'
        while True:
            rh = f.read(16)
            if len(rh) < 16:
                break
            ts, us, incl, orig = struct.unpack(endian + 'IIII', rh)
            data = f.read(incl)
            if len(data) < incl:
                break
            yield ts + us / 1e6, data


def main(path):
    t0 = t1 = None
    n = 0
    segs = []
    for t, data in frames(path):
        if len(data) < 40 or (data[0] >> 4) != 6:
            continue
        if data[6] != 6:                       # next header must be TCP
            continue
        src = ':'.join(data[8:24][i:i + 2].hex() for i in range(0, 16, 2))
        dst = ':'.join(data[24:40][i:i + 2].hex() for i in range(0, 16, 2))
        tcp = data[40:]
        if len(tcp) < 20:
            continue
        sport, dport, seq, ack, off_flags, win = struct.unpack('>HHIIHH', tcp[:16])
        doff = (off_flags >> 12) * 4
        flags = off_flags & 0x1FF
        plen = len(data) - 40 - doff
        n += 1
        if t0 is None:
            t0 = t
        t1 = t
        segs.append((t, src, dst, sport, dport, seq, ack, flags, win, plen))

    span = t1 - t0
    print(f'tun: {n} tcp frames, span {span:.3f} s')

    pay = Counter()
    for s in segs:
        if s[9] > 0:
            pay[(s[1], s[3])] += s[9]
    print('top payload senders:')
    for (ip, port), b in pay.most_common(3):
        print(f'  [{ip}]:{port}  {b/1e6:.2f} MB')

    peer = pay.most_common(1)[0][0]
    ours = [s for s in segs if (s[1], s[3]) == (peer[0], peer[1]) and s[9] > 0]
    total = sum(s[9] for s in ours)
    ft, lt = ours[0][0], ours[-1][0]
    print(f'\npeer [{peer[0]}]:{peer[1]}')
    print(f'  segments {len(ours)}, payload {total/1e6:.2f} MB')
    print(f'  first {ft:.3f}  last {lt:.3f}  -> {lt-ft:.3f} s')
    print(f'  throughput (first->last seg) {total/1e6/(lt-ft):.3f} MB/s')
    print(f'  throughput (capture span)    {total/1e6/span:.3f} MB/s')
    print(f'  segments/s {len(ours)/(lt-ft):.1f}')

    bad = 0
    prev = None
    for s in ours:
        if prev is not None:
            d = (s[5] - prev) & 0xFFFFFFFF
            if d >= 0x80000000:
                bad += 1
        prev = (s[5] + s[9]) & 0xFFFFFFFF
    print(f'  out-of-order / retrans: {bad}')
    print('  payload size top5:', Counter(s[9] for s in ours).most_common(5))

    acks = Counter()
    for s in segs:
        if (s[1], s[3]) == (peer[0], peer[1]) and s[9] == 0 and (s[7] & 0x10):
            acks[s[6]] += 1
    dup = sum(c - 1 for c in acks.values() if c > 1)
    print(f'  dup acks: {dup} over {len(acks)} distinct ack numbers')
    print(f'  max advertised window seen: {max(s[8] for s in segs)}')


if __name__ == '__main__':
    main(sys.argv[1])
