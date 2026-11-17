#!/usr/bin/env python3
"""One-off: burst structure of the download, on the tunnel timeline.

The air capture shows the frame gaps, but it cannot say how much of the wall clock was
spent waiting between bursts. That is the only remaining slack, so it has to be measured
directly: split the arrival timeline at gaps larger than 3 ms and report the burst period,
the frames inside a burst, and the idle share.

If the period sits on 16.4 ms (one AWDL availability window, AW_PERIOD_TU = 16) the slack
belongs to the peer's window schedule and is not ours to reclaim. If the period is longer
than the window and the idle share grows with the rate, the peer is running out of data --
a congestion-window problem, which we can still influence.
"""
import struct, sys
from collections import Counter

CUT = 0.003          # 3 ms: comfortably above a frame gap (p90 1.73 ms), below a window


def segs(path):
    out = []
    with open(path, 'rb') as f:
        f.read(24)
        while True:
            rh = f.read(16)
            if len(rh) < 16:
                break
            ts, us, incl, _ = struct.unpack('<IIII', rh)
            d = f.read(incl)
            if len(d) < 60 or (d[0] >> 4) != 6 or d[6] != 6:
                continue
            tcp = d[40:]
            sport, dport, seq, ack, off_flags, win = struct.unpack('>HHIIHH', tcp[:16])
            plen = len(d) - 40 - (off_flags >> 12) * 4
            if plen > 0 and dport == 8770:
                out.append((ts + us / 1e6, plen))
    out.sort()
    return out


s = segs(sys.argv[1])
print(f'{len(s)} payload segments')

bursts = [[s[0]]]
for prev, cur in zip(s, s[1:]):
    if cur[0] - prev[0] > CUT:
        bursts.append([])
    bursts[-1].append(cur)

bursts = [b for b in bursts if len(b) > 1]
sizes = [len(b) for b in bursts]
span = [b[-1][0] - b[0][0] for b in bursts]
periods = [b[0][0] - a[0][0] for a, b in zip(bursts, bursts[1:])]
idle = [p - sp for p, sp in zip(periods, span)]

def q(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * p))]

print(f'bursts {len(bursts)}')
print(f'  segments/burst  p10 {q(sizes,.1)}  p50 {q(sizes,.5)}  p90 {q(sizes,.9)}  mean {sum(sizes)/len(sizes):.2f}')
print(f'  burst span ms   p50 {q(span,.5)*1e3:.2f}  mean {sum(span)/len(span)*1e3:.2f}')
print(f'  period ms       p10 {q(periods,.1)*1e3:.2f}  p50 {q(periods,.5)*1e3:.2f}  p90 {q(periods,.9)*1e3:.2f}  mean {sum(periods)/len(periods)*1e3:.2f}')
print(f'  idle ms         p50 {q(idle,.5)*1e3:.2f}  mean {sum(idle)/len(idle)*1e3:.2f}')
duty = sum(span) / (span[0] + sum(periods[1:])) if len(periods) > 1 else 0
print(f'  duty {duty*100:.1f}%  (busy {sum(span):.2f} s / {span[0]+sum(periods[1:]):.2f} s)')
print(f'  gap inside burst ms mean {(sum(span)/sum(sizes))*1e3:.3f}')
print(f'  period histogram (rounded to 1 ms):',
      Counter(round(p*1e3) for p in periods).most_common(8))
