#!/usr/bin/env python3
"""Does the sender ever run more than one transfer at a time?

This is the question that decides whether receiving on several connections at once can help
at all. The listener on this side accepts one connection and serves it to completion before
accepting the next (see Listener#loop), so if the phone only ever holds one connection open,
that serialisation costs nothing and the whole multi-stream idea is dead. If the phone *does*
open several -- one per file, when several files are selected at once -- then this side is
throwing away whatever the phone offers, and the fix is ours to make.

The measurement is on the tunnel, not on the radio: every byte the phone sends crosses
awdltun0, and each TCP connection there is identified by its source port. So the script
answers, for one capture:

  1. how many connections carried payload, and how many bytes each
  2. the peak number of connections that were simultaneously mid-transfer
  3. how often consecutive payload segments came from different connections -- a high switch
     count with a peak of one means interleaving without concurrency, which is a different
     (and cheaper) problem than true parallel streams

Deliberately no opinion about what the numbers should be. Run it on a single-file capture and
on a multi-file capture and compare.
"""
import struct
import sys
from collections import Counter, defaultdict


def segments(path):
    """(t, src, sport, dport, seq, ack, flags, plen) for every TCP segment in a tun capture.

    The tunnel is IPv6-only (AWDL peers are link-local), and the capture is DLT_RAW, so a
    packet starts at the IPv6 header. Parsing it as IPv4 silently yields nothing, which is
    how the first version of this measurement failed.
    """
    out = []
    with open(path, 'rb') as f:
        header = f.read(24)
        magic = struct.unpack('<I', header[:4])[0]
        if magic not in (0xa1b2c3d4, 0xd4c3b2a1):
            sys.exit(f'{path}: not a pcap (magic {magic:#x})')
        endian = '<' if magic == 0xa1b2c3d4 else '>'
        while True:
            rec = f.read(16)
            if len(rec) < 16:
                break
            ts, us, incl, _orig = struct.unpack(endian + 'IIII', rec)
            data = f.read(incl)
            if len(data) < incl:
                break
            if len(data) < 60 or (data[0] >> 4) != 6 or data[6] != 6:
                continue
            src = ':'.join(data[8:24][i:i + 2].hex() for i in range(0, 16, 2))
            sport, dport, seq, ack, off_flags, _win = struct.unpack('>HHIIHH', data[40:56])
            plen = len(data) - 40 - (off_flags >> 12) * 4
            out.append((ts + us / 1e6, src, sport, dport, seq, ack, off_flags & 0x1FF, plen))
    out.sort()
    return out


def main(path):
    segs = segments(path)
    if not segs:
        sys.exit('no TCP segments')

    # Payload only: a connection that only carries ACKs is not a transfer.
    #
    # And payload is not enough either. An AirDrop transfer is preceded by an Ask exchange on
    # its own short-lived connection, so a capture of one file shows three or four sockets,
    # three of which hold a few hundred bytes between them. Counting those as streams makes
    # every run look concurrent. MIN_BYTES separates a transfer from the handshake around it;
    # 1 MB is far above any signal exchange and far below any file worth measuring.
    MIN_BYTES = 1 << 20
    flows = defaultdict(list)
    for t, _src, sport, dport, _seq, _ack, _flags, plen in segs:
        if plen > 0:
            flows[(sport, dport)].append((t, plen))

    total = sum(p for f in flows.values() for _, p in f)
    signal = {k: v for k, v in flows.items() if sum(p for _, p in v) < MIN_BYTES}
    flows = {k: v for k, v in flows.items() if sum(p for _, p in v) >= MIN_BYTES}
    print(f'{path}')
    print(f'  {len(segs)} TCP segments, {total/1e6:.2f} MB of payload, '
          f'{len(flows)} transfer(s) over {MIN_BYTES/1e6:.0f} MB')
    if signal:
        held = sum(p for v in signal.values() for _, p in v)
        print(f'  ignored {len(signal)} signal connection(s) holding {held/1e3:.1f} KB total')

    if len(flows) <= 1:
        print('\n  Only one connection carried data. Multi-stream has nothing to work with on')
        print('  this capture; the peer serialises its files regardless of how they are sent.')
        return

    rows = []
    for (sport, dport), pts in flows.items():
        body = sum(p for _, p in pts)
        span = pts[-1][0] - pts[0][0]
        sizes = Counter(p for _, p in pts)
        rows.append((pts[0][0], sport, dport, len(pts), body, span, sizes.most_common(1)[0][0]))
    rows.sort()

    t0 = rows[0][0]
    print(f'\n  {"port":>6} {"segments":>9} {"MB":>8} {"start":>8} {"span s":>7} {"MB/s":>7}  typical MSS')
    for start, sport, dport, n, body, span, mode in rows:
        rate = body / 1e6 / span if span > 0 else 0.0
        print(f'  {sport:>6} {n:>9} {body/1e6:>8.2f} {start-t0:>8.2f} {span:>7.2f} '
              f'{rate:>7.3f}  {mode}')

    # Peak simultaneity: the largest number of transfers whose intervals overlap at one instant.
    edges = []
    for start, _s, _d, _n, _b, span, _m in rows:
        edges.append((start, 1))
        edges.append((start + span, -1))
    edges.sort()
    live = peak = 0
    for _, delta in edges:
        live += delta
        peak = max(peak, live)
    print(f'\n  overlaps: peak {peak} connection(s) mid-transfer at the same instant')

    # Interleaving: a switch is two consecutive payload segments from different ports. Under
    # true concurrency this tracks the overlap; under round-robin scheduling it can be high
    # while the peak above stays at one.
    order = sorted((t, sport) for (sport, _d), pts in flows.items() for t, _p in pts)
    switches = sum(1 for a, b in zip(order, order[1:]) if a[1] != b[1])
    print(f'  {switches} switches between connections over {len(order)} segments'
          f' ({switches/max(1, len(order)-1)*100:.0f}% of boundaries)')

    if peak == 1 and switches > len(order) * 0.1:
        print('\n  Verdict: files are interleaved but never concurrent -- the phone alternates')
        print('  between connections that are already open. Serving them in parallel would')
        print('  still help if each one is waiting on the other, but this is not the same')
        print('  evidence as an overlap, and the per-connection numbers above decide it.')
    elif peak > 1:
        print(f'\n  Verdict: {peak} transfers were open at once. The listener serves one at a')
        print('  time by design, so the extra streams wait in the accept backlog. Making it')
        print('  concurrent is a real, measurable gain here.')
    else:
        print('\n  Verdict: sequential. Nothing was open at the same time.')


if __name__ == '__main__':
    main(sys.argv[1])
