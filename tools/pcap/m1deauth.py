#!/usr/bin/env python3
"""Who killed the link, and are the AWDL presence frames still well formed.

The iPhone stopped showing our device in the share sheet while the awdld process was
alive and wonder0 was UP. A management Deauth on this pcap is the first hard evidence
of a link teardown that the process state cannot show: the kernel interface can stay UP
while the peer has thrown our state away. This prints the Deauth direction and reason
code, then breaks down Action frames by category so AWDL presence (vendor specific) can
be told apart from election and from anything else.
"""
import struct, sys
from collections import Counter, defaultdict

SUBTYPE = {0: 'AssocReq', 1: 'AssocResp', 2: 'ReassocReq', 3: 'ReassocResp',
           4: 'ProbeReq', 5: 'ProbeResp', 8: 'Beacon', 9: 'ATIM', 10: 'Disassoc',
           11: 'Auth', 12: 'Deauth', 13: 'Action', 14: 'ActionNoAck'}
REASON = {1: 'Unspecified', 2: 'PrevAuthInvalid', 3: 'DeauthLeaving',
          4: 'Inactivity', 5: 'DisassocAPFull', 6: 'Class2FromNonAuth',
          7: 'Class3FromNonAssoc', 8: 'DisassocLeaving', 15: '4WayTimeout',
          16: 'GroupKeyTimeout'}


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


OURS = '36:bc:0c:fc:83:f3'
IPHONE = '66:82:54:63:ed:6e'

actions_by_src = defaultdict(Counter)
deauths = []
first_ts = None
per_sec = defaultdict(lambda: [0, 0])  # sec bucket -> [ours, iphone] action counts

for ts, orig, d in frames(sys.argv[1]):
    if len(d) < 4:
        continue
    rtlen = struct.unpack('<H', d[2:4])[0]
    if len(d) < rtlen + 16:
        continue
    fc = d[rtlen]
    ftype = (fc >> 2) & 3
    sub = (fc >> 4) & 0xF
    a1 = d[rtlen + 4:rtlen + 10].hex(':')
    a2 = d[rtlen + 10:rtlen + 16].hex(':')
    a3 = d[rtlen + 16:rtlen + 22].hex(':') if len(d) >= rtlen + 22 else '?'
    body = d[rtlen + 24:] if len(d) > rtlen + 24 else b''
    if first_ts is None:
        first_ts = ts
    if ftype == 0 and sub == 12:
        reason = body[0] if body else -1
        deauths.append((ts - first_ts, a1, a2, a3, reason))
    if ftype == 0 and sub == 13 and body:
        cat = body[0]
        oui = body[1:4].hex(':') if len(body) >= 4 else '?'
        actions_by_src[a2][f'cat{cat}/oui{oui}'] += 1
        bucket = int(ts - first_ts)
        if a2 == OURS:
            per_sec[bucket][0] += 1
        elif a2 == IPHONE:
            per_sec[bucket][1] += 1

print(f'Deauth frames: {len(deauths)}')
for t, a1, a2, a3, r in deauths:
    frm = 'OURS' if a2 == OURS else ('IPHONE' if a2 == IPHONE else a2)
    to = 'OURS' if a1 == OURS else ('IPHONE' if a1 == IPHONE else a1)
    print(f'  t+{t:.3f}s  {frm} -> {to}  bssid={a3}  reason={r} ({REASON.get(r, "?")})')

print('\nAction frames by transmitter:')
for mac, c in actions_by_src.items():
    who = 'OURS' if mac == OURS else ('IPHONE' if mac == IPHONE else mac)
    print(f'  {who} ({mac})')
    for k, v in c.most_common(8):
        print(f'      {k:24s} {v}')

print('\nAction rate per second  (t, ours, iphone):')
for b in sorted(per_sec):
    o, i = per_sec[b]
    print(f'  {b:3d}  {o:3d}  {i:3d}')
