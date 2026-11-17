#!/usr/bin/env python3
"""Split the Apple vendor Action frames by AWDL frame type.

Both sides were emitting ~25/s and ~9/s of category-127 Action frames, yet the iPhone
share sheet showed nothing. "Sending Action frames" is not the same as "announcing
presence": AWDL multiplexes election, presence, committed and data all under the same
category 127 / OUI 00:17:f2, distinguished only by the byte right after the OUI. If the
peer only emits election frames and no presence frame, it is on the channel but is not
advertising itself as discoverable, and the share sheet stays empty while every counter
here still looks healthy. So classify by that type byte and count each side separately.
"""
import struct, sys
from collections import Counter, defaultdict

OURS = '36:bc:0c:fc:83:f3'
IPHONE = '66:82:54:63:ed:6e'

# Public references for the AWDL sub-protocol identifiers used here:
#   frametype 1 = election, 2 = election response, 3 = data, 4 = presence, 5 = committed
AWDL_T = {1: 'election', 2: 'election-rsp', 3: 'data', 4: 'presence', 5: 'committed'}


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
            yield ts + us / 1e6, orig, d


cnt = defaultdict(Counter)
lenhint = defaultdict(list)
samples = {}
first_ts = None

for ts, orig, d in frames(sys.argv[1]):
    if len(d) < 4:
        continue
    rtlen = struct.unpack('<H', d[2:4])[0]
    if len(d) < rtlen + 24:
        continue
    fc = d[rtlen]
    if ((fc >> 2) & 3) != 0 or ((fc >> 4) & 0xF) != 13:
        continue
    a2 = d[rtlen + 10:rtlen + 16].hex(':')
    body = d[rtlen + 24:]
    if len(body) < 5 or body[0] != 127:
        continue
    if body[1:4] != bytes.fromhex('0017f2'):
        continue
    t = body[4]
    who = 'OURS' if a2 == OURS else ('IPHONE' if a2 == IPHONE else a2)
    name = AWDL_T.get(t, f'type{t}')
    cnt[who][name] += 1
    lenhint[(who, name)].append(len(body))
    if (who, name) not in samples:
        samples[(who, name)] = (ts, body)

print('AWDL frame types by transmitter:')
for who in ('OURS', 'IPHONE'):
    print(f'  {who}:')
    for name, n in cnt[who].most_common():
        ls = sorted(lenhint[(who, name)])
        print(f'      {name:14s} {n:4d}   payload {ls[0]}..{ls[-1]} B')

if first_ts is None:
    first_ts = min(s[0] for s in samples.values()) if samples else 0.0

print('\nfirst sample of each type (hex):')
for (who, name), (ts, body) in samples.items():
    print(f'  {who} {name}: {body.hex()}')
