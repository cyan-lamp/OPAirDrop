#!/usr/bin/env python3
"""One-off: how many bytes the peer is allowed to have in flight.

Sixty percent of every 16 ms availability window goes unused. Two very different causes:

  * the peer's congestion window is the limiter -> in-flight saturates well below the BDP,
    and the ceiling is a receiver-side signalling problem we can still attack
  * the window itself is the limiter -> in-flight grows unbounded but the radio still stops
    at the window edge, and 60% is simply what AWDL gives a single stream

Measure in-flight as (highest sequence end received) - (highest ack we have sent). If the
peak is flat across the run, it is a window; if it ramps, it is congestion control.
"""
import struct, sys

def run(path):
    ev = []
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
            sport, dport, seq, ack, off_flags, win = struct.unpack('>HHIIHH', d[40:56])
            plen = len(d) - 40 - (off_flags >> 12) * 4
            ev.append((ts + us / 1e6, sport, dport, seq, ack, plen))
    ev.sort()

    hi_rx = 0
    hi_ack = 0
    samples = []
    t0 = ev[0][0]
    for t, sport, dport, seq, ack, plen in ev:
        if dport == 8770 and plen > 0:
            end = (seq + plen) & 0xFFFFFFFF
            if end < 0x40000000:
                hi_rx = max(hi_rx, end)
        elif sport == 8770:
            # our ack numbers ride the reverse direction, never wrapped in 40 MB
            if ack < 0x40000000:
                hi_ack = max(hi_ack, ack)
        inflight = hi_rx - hi_ack
        if 0 <= inflight < 40_000_000:
            samples.append((t - t0, inflight))
    return samples

s = run(sys.argv[1])
if not s:
    sys.exit('no samples')
print(f'{len(s)} samples, span {s[-1][0]:.1f} s')
def q(v, p):
    v = sorted(v); return v[min(len(v)-1, int(len(v)*p))]
vals = [x[1] for x in s]
print(f'in-flight (B): p10 {q(vals,.1)}  p50 {q(vals,.5)}  p90 {q(vals,.9)}  max {max(vals)}')
print(f'                as MSS (1386): p10 {q(vals,.1)/1386:.1f}  p50 {q(vals,.5)/1386:.1f}  p90 {q(vals,.9)/1386:.1f}  max {max(vals)/1386:.1f}')
# ramp: one sample per second
print('\nt   in-flight(KB)')
step = 0
for t, v in s:
    if t >= step:
        print(f'{t:5.1f}  {v/1024:7.1f}')
        step += 5
