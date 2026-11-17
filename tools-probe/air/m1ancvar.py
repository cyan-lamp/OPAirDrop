#!/usr/bin/env python3
"""Why does one announcement length vary between 249 and 265 bytes.

A fixed-shape announcement should be a fixed length. Ours oscillates by 16 bytes, which
means a length-prefixed field inside is changing size. If a length byte disagrees with
the bytes that follow, a strict parser on the peer rejects the whole frame and the device
simply never appears, with no error at either end. So bucket announcements by length and
diff the buckets against each other to find the field that is mutating.
"""
import struct, sys
from collections import Counter, defaultdict

OURS = '36:bc:0c:fc:83:f3'


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
            d = f.read(incl)
            if len(d) < incl:
                break
            yield ts + us / 1e6, d


bylen = defaultdict(list)
for ts, d in frames(sys.argv[1]):
    rtlen = struct.unpack('<H', d[2:4])[0]
    if len(d) < rtlen + 24:
        continue
    fc = d[rtlen]
    if ((fc >> 2) & 3) != 0 or ((fc >> 4) & 0xF) != 13:
        continue
    if d[rtlen + 10:rtlen + 16].hex(':') != OURS:
        continue
    body = d[rtlen + 24:]
    if len(body) < 5 or body[0] != 127 or body[1:4] != bytes.fromhex('0017f2') or body[4] != 8:
        continue
    bylen[len(body)].append((ts, body))

print('length histogram:', {k: len(v) for k, v in sorted(bylen.items())})

lens = sorted(bylen)
ref_len = lens[0]
ref = bylen[ref_len][0][1]
print(f'\nreference length {ref_len}, hex:\n  {ref.hex()}')
print(f'\nreference as offsets (每 16 字节一行，便于读长度前缀):')
for off in range(0, len(ref), 16):
    print(f'  {off:03d}  {ref[off:off+16].hex(" ")}')

for L in lens[1:]:
    b = bylen[L][0][1]
    print(f'\nlength {L} ({len(bylen[L])} frames), hex:\n  {b.hex()}')
    common = 0
    while common < min(len(b), len(ref)) and b[common] == ref[common]:
        common += 1
    print(f'  common prefix {common} B')
    if common < min(len(b), len(ref)):
        lo, hi = max(0, common - 6), common + 20
        print(f'  ref[{lo}:{hi}]: {ref[lo:hi].hex(" ")}')
        print(f'  new[{lo}:{hi}]: {b[lo:hi].hex(" ")}')
    # Show where the two realign, to expose a pure insertion/deletion.
    tail = 0
    while tail < min(len(b), len(ref)) and b[-1 - tail] == ref[-1 - tail]:
        tail += 1
    print(f'  common suffix {tail} B')
