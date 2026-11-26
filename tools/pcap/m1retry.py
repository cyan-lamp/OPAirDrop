#!/usr/bin/env python3
"""One-off: is the peer retrying, and are we flooding ACKs?

The air capture holds 19,635 data frames from the peer but the tunnel only delivered
9,811 TCP segments -- a factor of two. Two candidate explanations, and they mean
opposite things:

  * retries  -> the peer transmits each frame twice because the first one is not acked.
    Then the link is lossy, throughput loss is physical, and no amount of protocol work
    on our side helps.
  * copies   -> the peer deliberately sends each segment twice (redundancy), which would
    be a policy we could in principle influence.

The 802.11 retry bit and the sequence number separate them: a retry repeats the same
sequence number with the retry bit set. This also counts our own frames per delivered
segment, which tells whether we are dumping redundant ACKs onto the channel.
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

PEER = '66:82:54:63:ed:6e'
OURS = '36:bc:0c:fc:83:f3'

ev = []
for ts, orig, d in frames(sys.argv[1]):
    if len(d) < 4:
        continue
    rtlen = struct.unpack('<H', d[2:4])[0]
    if len(d) < rtlen + 24:
        continue
    fc0, fc1 = d[rtlen], d[rtlen + 1]
    if ((fc0 >> 2) & 3) != 2:          # data only
        continue
    retry = bool(fc1 & 0x08)
    addr2 = d[rtlen + 10:rtlen + 16].hex(':')
    seq = struct.unpack('<H', d[rtlen + 22:rtlen + 24])[0] >> 4
    ev.append((ts, addr2, seq, retry, orig, (fc0 >> 4) & 0xF))

for who, label in ((PEER, 'peer -> us'), (OURS, 'us -> peer')):
    sub = [e for e in ev if e[1] == who]
    if not sub:
        continue
    t0, t1 = sub[0][0], sub[-1][0]
    retries = sum(1 for e in sub if e[3])
    seqs = Counter(e[2] for e in sub)
    dupseq = sum(c - 1 for c in seqs.values() if c > 1)
    print(f'{label}: {len(sub)} data frames over {t1-t0:.1f} s, {len(sub)/(t1-t0):.1f} fps')
    print(f'  retry bit set      {retries}  ({retries/len(sub)*100:.1f}%)')
    print(f'  repeated seq nums  {dupseq}  over {len(seqs)} distinct seqs')
    print(f'  frame length p50   {sorted(e[4] for e in sub)[len(sub)//2]} B')
    # how far apart are consecutive copies of the same sequence number
    first = {}
    gaps = []
    for ts, _a, seq, _r, _o, _s in sub:
        if seq in first:
            gaps.append((ts - first[seq]) * 1e3)
        else:
            first[seq] = ts
    if gaps:
        gaps.sort()
        print(f'  same-seq gap ms    p50 {gaps[len(gaps)//2]:.2f}  p90 {gaps[int(len(gaps)*.9)]:.2f}')
    print()

# what our frames carry, as a sanity check on the ACK count
ours = [e for e in ev if e[1] == OURS]
print('our data subtype histogram:', Counter(e[5] for e in ours).most_common())
