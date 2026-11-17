#!/usr/bin/env python3
"""Decode the mDNS records we put on AWDL, because the name inside decides everything.

The peer never answered a single data frame, so it is dropping us rather than refusing
us. AirDrop discovery is plain DNS-SD over AWDL: the peer lists a device only if it sees
an "_airdrop._tcp" PTR whose target resolves to an SRV/TXT pair. A typo in the service
string, or a missing PTR, produces exactly this: we broadcast, nobody answers, no error
anywhere. So parse the actual DNS message and print the questions and the names.
"""
import struct, sys


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


def read_name(b, off):
    """Read a DNS name with compression pointers; return (name, next_offset)."""
    parts, jumped, nxt, hops = [], False, off, 0
    while True:
        if off >= len(b) or hops > 32:
            return '.'.join(parts), nxt
        l = b[off]
        if l == 0:
            off += 1
            if not jumped:
                nxt = off
            return '.'.join(parts), nxt
        if l & 0xC0 == 0xC0:
            ptr = struct.unpack('>H', b[off:off + 2])[0] & 0x3FFF
            if not jumped:
                nxt = off + 2
            off, jumped, hops = ptr, True, hops + 1
            continue
        parts.append(b[off + 1:off + 1 + l].decode('utf-8', 'replace'))
        off += 1 + l
        if not jumped:
            nxt = off


TYPES = {1: 'A', 12: 'PTR', 16: 'TXT', 28: 'AAAA', 33: 'SRV'}

seen = 0
for ts, d in frames(sys.argv[1]):
    rt = struct.unpack('<H', d[2:4])[0]
    if len(d) < rt + 24:
        continue
    fc = d[rt]
    if ((fc >> 2) & 3) != 2:
        continue
    sub = (fc >> 4) & 0xF
    hdr = 24 + (2 if sub & 8 else 0)
    p = d[rt + hdr:]
    # Walk the LLC/SNAP header to the IPv6 payload, then to UDP, then to DNS.
    if len(p) < 8 or p[:3] != b'\xaa\xaa\x03':
        continue
    if p[6:8] != b'\x86\xdd':
        continue
    ip = p[8:]
    if len(ip) < 48 or ip[6] != 17:
        continue
    udp = ip[40:]
    if len(udp) < 8:
        continue
    sport, dport = struct.unpack('>HH', udp[:4])
    if 5353 not in (sport, dport):
        continue
    dns = udp[8:]
    if len(dns) < 12:
        continue
    qd, an, ns, ar = struct.unpack('>HHHH', dns[4:12])
    print(f'\n=== mDNS sport={sport} dport={dport} qd={qd} an={an} ns={ns} ar={ar} len={len(dns)}')
    off = 12
    for _ in range(qd):
        name, off = read_name(dns, off)
        qt, qc = struct.unpack('>HH', dns[off:off + 4]); off += 4
        print(f'  Q  {name}  {TYPES.get(qt, qt)} class=0x{qc:04x}')
    for sect, cnt in (('AN', an), ('NS', ns), ('AR', ar)):
        for _ in range(cnt):
            name, off = read_name(dns, off)
            if off + 10 > len(dns):
                break
            rtype, rclass, ttl, rdlen = struct.unpack('>HHIH', dns[off:off + 10]); off += 10
            rd = dns[off:off + rdlen]
            off += rdlen
            detail = ''
            if rtype == 12:
                tgt, _ = read_name(dns, off - rdlen)
                detail = tgt
            elif rtype == 33 and rdlen >= 6:
                prio, weight, port = struct.unpack('>HHH', rd[:6])
                host, _ = read_name(dns, off - rdlen + 6)
                detail = f'{host}:{port}'
            elif rtype == 16:
                detail = rd.decode('utf-8', 'replace')
            print(f'  {sect} {name}  {TYPES.get(rtype, rtype)} ttl={ttl} -> {detail}')
    seen += 1
    if seen >= 3:
        break
