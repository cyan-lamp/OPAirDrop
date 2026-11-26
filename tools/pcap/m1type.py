#!/usr/bin/env python3
"""One-off: who is on the channel, and what kind of frames are they sending.

The download dropped from 857 kB/s to 105 kB/s between two runs of identical code.
Before blaming us, rule the channel in or out: count frames by subtype and by
transmitter. If only our two MACs are present, the channel is clean and the change is
on the peer; if a third MAC (an access point, or another device) is filling airtime,
the peer's availability windows are being squeezed by contention.
"""
import struct, sys
from collections import Counter, defaultdict

def frames(path):
    with open(path, 'rb') as f:
        gh = f.read(24)
        endian = '<' if struct.unpack('<I', gh[:4])[0] in (0xa1b2c3d4, 0x4d3cb2a1) else '>'
        while True:
            rh = f.read(16)
            if len(rh) < 16:
                break
            ts, us, incl, orig = struct.unpack(endian + 'IIII', rh)
            d = f.read(incl)
            if len(d) < incl:
                break
            yield ts + us / 1e6, orig, d

SUBTYPE = {
    0: 'AssocReq', 1: 'AssocResp', 2: 'ReassocReq', 3: 'ReassocResp',
    4: 'ProbeReq', 5: 'ProbeResp', 8: 'Beacon', 9: 'ATIM', 10: 'Disassoc',
    11: 'Auth', 12: 'Deauth', 13: 'Action', 14: 'ActionNoAck',
}
DS = {0: 'Data', 4: 'Null', 8: 'QoSData', 12: 'QoSNull'}

types = Counter()
bysrc = defaultdict(Counter)
srcbytes = Counter()
t0 = t1 = None

for ts, orig, d in frames(sys.argv[1]):
    if len(d) < 4:
        continue
    rtlen = struct.unpack('<H', d[2:4])[0]
    if len(d) < rtlen + 10:
        continue
    fc = d[rtlen]
    ftype = (fc >> 2) & 3
    sub = (fc >> 4) & 0xF
    addr2 = d[rtlen + 10:rtlen + 16].hex(':') if len(d) >= rtlen + 16 else '?'
    if ftype == 0:
        name = 'MGMT/' + SUBTYPE.get(sub, str(sub))
    elif ftype == 1:
        name = 'CTRL/' + str(sub)
    else:
        name = 'DATA/' + DS.get(sub, str(sub))
    types[name] += 1
    bysrc[addr2][name] += 1
    srcbytes[addr2] += orig
    if t0 is None:
        t0 = ts
    t1 = ts

span = (t1 or 0) - (t0 or 0)
print(f'span {span:.1f} s   frames {sum(types.values())}')
print('\nframe types overall:')
for k, v in types.most_common(12):
    print(f'  {k:18s} {v:7d}')
print('\ntransmitters (top 6):')
for mac, n in srcbytes.most_common(6):
    tot = sum(bysrc[mac].values())
    kinds = ', '.join(f'{k}:{v}' for k, v in bysrc[mac].most_common(3))
    print(f'  {mac}  {tot:6d} frames  {n/1024:8.1f} KB  [{kinds}]')
