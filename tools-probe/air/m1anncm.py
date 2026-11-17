#!/usr/bin/env python3
"""Compare our own AWDL announcement across time inside one continuous capture.

The iPhone stopped listing our device, while this capture proves we kept announcing at
~24 frames/s with the service name OPAirDrop intact. The remaining question is whether
the announcement *content* changed at some point, because a peer that cannot parse a
malformed announcement behaves exactly like a peer that heard nothing: it silently drops
us and the share sheet goes empty with no error on either side.

So pull our announcements from the start, middle and end of the capture and diff their
bytes. Identical bytes mean we are broadcasting the same thing we broadcast during the
successful 0.857 MB/s run, and the regression is on the peer's side.
"""
import struct, sys
from collections import Counter

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


target = sys.argv[1]
bodies = []   # (ts, body) for our type-8 announcements
for ts, d in frames(target):
    if len(d) < 30:
        continue
    rtlen = struct.unpack('<H', d[2:4])[0]
    if len(d) < rtlen + 24:
        continue
    fc = d[rtlen]
    if ((fc >> 2) & 3) != 0 or ((fc >> 4) & 0xF) != 13:
        continue
    a2 = d[rtlen + 10:rtlen + 16].hex(':')
    if a2 != OURS:
        continue
    body = d[rtlen + 24:]
    if len(body) < 5 or body[0] != 127 or body[1:4] != bytes.fromhex('0017f2'):
        continue
    if body[4] != 8:
        continue
    bodies.append((ts, body))

if not bodies:
    print('no announcements found')
    sys.exit(0)

print(f'our announcements: {len(bodies)}  span {bodies[-1][0]-bodies[0][0]:.1f}s')
print(f'length histogram: {dict(Counter(len(b) for _, b in bodies).most_common(6))}')

picks = [0, len(bodies) // 2, len(bodies) - 1]
ref = None
for idx in picks:
    ts, b = bodies[idx]
    tag = f'#{idx} t+{ts-bodies[0][0]:.1f}s len={len(b)}'
    print(f'\n{tag}\n  {b.hex()}')
    # Diff against the first pick; identical means the advertisement never changed.
    if ref is None:
        ref = (idx, ts, b)
    elif b == ref[2]:
        print(f'  -> byte-identical to #{ref[0]}')
    else:
        print(f'  -> DIFFERS from #{ref[0]}')
        # Report the first differing offset so a single mutated field is easy to spot.
        for i in range(min(len(b), len(ref[2]))):
            if b[i] != ref[2][i]:
                lo, hi = max(0, i - 8), min(len(b), i + 8)
                print(f'     first diff at offset {i}')
                print(f'     ref: {ref[2][lo:hi].hex()}')
                print(f'     new: {b[lo:hi].hex()}')
                break
        if len(b) != len(ref[2]):
            print(f'     length {len(ref[2])} -> {len(b)}')
