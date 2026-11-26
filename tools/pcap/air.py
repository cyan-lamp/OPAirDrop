#!/usr/bin/env python3
"""Read a radiotap capture and answer one question: is the peer slow because of the rate
it picked, or because of how few frames it sends.

Why this exists
---------------
`Task.md` §5.4 leaves exactly this open:

    1.69 ms 可能是空口时间（→ 6.6 Mbps），也可能是 iPhone 自己的帧间节拍。

Both readings fit the same 24.8 ms / 2 frames / 1386 B the tunnel capture shows, and they
imply opposite fixes:

  * air time  -> the peer is transmitting at the 6 Mb/s mandatory rate, so the fix is in
                 what we advertise (a 20 MHz/no-HT IBSS and a 1-stream HT TLV), and the
                 ceiling is the PHY rate.
  * pacing    -> the peer has a high rate and is choosing not to use it; the fix is in the
                 availability/aggregation we advertise, and the ceiling is frames per
                 opportunity.

The tunnel capture cannot separate them because it timestamps frames when the kernel got
them, not when the radio sent them. The radiotap column can: it carries the rate the frame
was actually modulated at.

What is printed
---------------
1. every sender, with a rate histogram weighted by frames AND by bytes
2. for the peer we are receiving from: frame lengths, the gap structure, and an estimate
   of how much of the wall clock was actually spent on air
3. the peer's AWDL presence frames, decoded far enough to read its channel map and HT
   capabilities -- these are the things that decide the rate and the window

Usage:  python air.py air.pcap [--peer aa:bb:cc:dd:ee:ff]
"""

import struct
import sys
from collections import Counter, defaultdict

# ---------------------------------------------------------------- pcap

PCAP_MAGICS = {
    0xA1B2C3D4: ("<", 1e-6),
    0xD4C3B2A1: (">", 1e-6),
    0xA1B23C4D: ("<", 1e-9),
    0x4D3CB2A1: (">", 1e-9),
}


def read_pcap(path):
    """Yield (seconds, linktype, frame bytes). Only classic pcap; pcapng gets a clear error
    rather than an empty report, because "no packets" and "wrong file format" print the
    same thing and only one of them means the peer never sent anything."""
    with open(path, "rb") as f:
        head = f.read(24)
        if len(head) < 24:
            raise SystemExit("%s: shorter than a pcap header" % path)
        magic = struct.unpack("<I", head[:4])[0]
        if magic == 0x0A0D0D0A:
            raise SystemExit("%s: this is pcapng; re-run tcpdump with -w only (pcap)" % path)
        if magic not in PCAP_MAGICS:
            raise SystemExit("%s: not a pcap file (magic %08x)" % (path, magic))
        endian, ts_scale = PCAP_MAGICS[magic]
        linktype = struct.unpack(endian + "I", head[20:24])[0]
        while True:
            rec = f.read(16)
            if len(rec) < 16:
                return
            sec, sub, caplen, _wirelen = struct.unpack(endian + "IIII", rec)
            data = f.read(caplen)
            if len(data) < caplen:
                return
            yield sec + sub * ts_scale, linktype, data


# ---------------------------------------------------------------- radiotap

# (align, size) per present bit, from the radiotap spec. Alignment is relative to the
# start of the radiotap header, which is why the offset is padded instead of indexed.
RT_FIELDS = [
    (0, 8, 8),   # 0 TSFT
    (1, 1, 1),   # 1 Flags
    (2, 1, 1),   # 2 Rate
    (3, 2, 4),   # 3 Channel
    (4, 2, 2),   # 4 FHSS
    (5, 1, 1),   # 5 dBm antenna signal
    (6, 1, 1),   # 6 dBm antenna noise
    (7, 2, 2),   # 7 lock quality
    (8, 2, 2),   # 8 tx attenuation
    (9, 2, 2),   # 9 db tx attenuation
    (10, 1, 1),  # 10 dbm tx power
    (11, 1, 1),  # 11 antenna
    (12, 1, 1),  # 12 db antenna signal
    (13, 1, 1),  # 13 db antenna noise
    (14, 2, 2),  # 14 rx flags
    (15, 2, 2),  # 15 tx flags
    (16, 1, 1),  # 16 rts retries
    (17, 1, 1),  # 17 data retries
    (18, 4, 8),  # 18 xchannel
    (19, 1, 3),  # 19 MCS
    (20, 4, 8),  # 20 A-MPDU status
    (21, 2, 12), # 21 VHT
    (22, 8, 12), # 22 timestamp
    (23, 2, 12), # 23 HE
    (24, 2, 12), # 24 HE-MU
    (25, 1, 1),  # 25 zero-length PSDU
    (26, 4, 4),  # 26 L-SIG
]

BW_NAME = {0: "20", 1: "40", 2: "20L", 3: "20U"}

# Nominal 1-stream rates in Mb/s: [20 MHz, 40 MHz, 80 MHz] x [long GI, short GI]
HT_RATES = {
    ("20", False): [6.5, 13.0, 19.5, 26.0, 39.0, 52.0, 58.5, 65.0],
    ("20", True): [7.2, 14.4, 21.7, 28.9, 43.3, 57.8, 65.0, 72.2],
    ("40", False): [13.5, 27.0, 40.5, 54.0, 81.0, 108.0, 121.5, 135.0],
    ("40", True): [15.0, 30.0, 45.0, 60.0, 90.0, 120.0, 135.0, 150.0],
    ("80", False): [29.3, 58.5, 87.8, 117.0, 175.5, 234.0, 263.3, 292.5],
    ("80", True): [32.5, 65.0, 97.5, 130.0, 195.0, 260.0, 292.5, 325.0],
}


def parse_radiotap(buf):
    """Return a dict of the fields this report needs, or None if the header is broken."""
    if len(buf) < 8 or buf[0] != 0:
        return None
    rt_len = struct.unpack_from("<H", buf, 2)[0]
    if rt_len > len(buf) or rt_len < 8:
        return None

    present_words = []
    off = 4
    while True:
        if off + 4 > rt_len:
            return None
        word = struct.unpack_from("<I", buf, off)[0]
        present_words.append(word)
        off += 4
        if not (word & 0x80000000):
            break

    bits = set()
    for w, word in enumerate(present_words):
        for b in range(32):
            if word & (1 << b):
                bits.add(w * 32 + b)

    out = {"len": rt_len, "bits": bits, "rate": None, "mcs": None, "bw": None,
           "sgi": None, "freq": None, "retries": None, "ampdu": False, "fcs": False}
    off = 4 + 4 * len(present_words)
    for bit, align, size in RT_FIELDS:
        if bit not in bits:
            continue
        if align > 1:
            off = (off + align - 1) & ~(align - 1)
        if off + size > rt_len:
            return out
        raw = buf[off:off + size]
        if bit == 1:
            out["fcs"] = bool(raw[0] & 0x10)
        elif bit == 2:
            out["rate"] = raw[0] / 2.0
        elif bit == 3:
            out["freq"] = struct.unpack_from("<H", raw, 0)[0]
        elif bit == 17:
            out["retries"] = raw[0]
        elif bit == 19:
            known, flags, idx = raw[0], raw[1], raw[2]
            if known & 0x01:
                out["bw"] = BW_NAME.get(flags & 0x03)
            if known & 0x04:
                out["sgi"] = bool(flags & 0x04)
            if known & 0x02:
                out["mcs"] = idx
        elif bit == 20:
            out["ampdu"] = True
        off += size
    return out


def air_time_us(rt, frame_len):
    """Air time of one frame, in microseconds.

    Preamble is 20 us for legacy OFDM and 36 us for an HT mixed-mode PPDU (L-STP + HT-SIG
    + training). Data time is bits over rate. Everything else -- SIFS, backoff, the ACK the
    peer waits for -- is not in here, which is the point: this is the floor, and comparing
    it against the measured inter-frame gap says how much of the gap is the radio and how
    much is the peer thinking.
    """
    if rt is None:
        return None
    bits = frame_len * 8 + 22
    if rt.get("mcs") is not None:
        bw = rt.get("bw") or "20"
        rate = HT_RATES.get((bw, bool(rt.get("sgi"))))
        if not rate:
            return None
        idx = rt["mcs"]
        mbps = rate[idx] if idx < len(rate) else None
        if not mbps:
            return None
        return 36.0 + bits / mbps
    if rt.get("rate"):
        return 20.0 + bits / rt["rate"]
    return None


def rate_label(rt):
    if rt is None:
        return "?"
    if rt.get("mcs") is not None:
        bw = rt.get("bw") or "?"
        return "MCS%d/%sMHz%s" % (rt["mcs"], bw, "/SGI" if rt.get("sgi") else "")
    if rt.get("rate"):
        return "%.1fM" % rt["rate"]
    return "?"


# ---------------------------------------------------------------- 802.11

def parse_dot11(buf, rt_len):
    """(type, subtype, addr1, addr2, addr3, seq, body_len) or None."""
    if len(buf) < rt_len + 24:
        return None
    fc = struct.unpack_from("<H", buf, rt_len)[0]
    ftype = (fc >> 2) & 0x3
    subtype = (fc >> 4) & 0xF
    a1 = buf[rt_len + 4: rt_len + 10]
    a2 = buf[rt_len + 10: rt_len + 16]
    a3 = buf[rt_len + 16: rt_len + 22]
    seq = struct.unpack_from("<H", buf, rt_len + 22)[0] >> 4
    hdr = 24
    if ftype == 2 and (subtype & 0x8):   # QoS data
        hdr = 26
    elif ftype == 1:                      # control frames carry no addresses this way
        return (ftype, subtype, a1, a2, a3, seq, 0)
    return (ftype, subtype, a1, a2, a3, seq, max(0, len(buf) - rt_len - hdr))


def mac(b):
    return ":".join("%02x" % x for x in b)


# ---------------------------------------------------------------- AWDL presence frames

AWDL_OUI = b"\x00\x17\xf2"


def awdl_tlvs(buf, rt_len):
    """The TLVs of an AWDL action frame, as (kind, body) pairs."""
    off = rt_len + 24
    # category(1) OUI(3) type(1) version(1) subtype(1) pad(1) t1(4) t2(4)
    if len(buf) < off + 16:
        return []
    if buf[off] != 127 or buf[off + 1: off + 4] != AWDL_OUI:
        return []
    off += 16
    out = []
    while off + 3 <= len(buf):
        kind = buf[off]
        ln = struct.unpack_from("<H", buf, off + 1)[0]
        if off + 3 + ln > len(buf):
            # Truncated by the snapshot length; keep what is there and stop.
            out.append((kind, buf[off + 3:]))
            break
        out.append((kind, buf[off + 3: off + 3 + ln]))
        off += 3 + ln
    return out


def describe_presence(tlvs):
    """One line per presence frame: what the peer claims about time and about rate.

    Only two of the TLVs matter for throughput, and they are printed raw on purpose --
    a wrong decode here would read as a fact, and this file exists because a wrong decode
    is expensive. Kind numbers come from `crates/awdl/src/action.rs`.
    """
    bits = []
    for kind, body in tlvs:
        if kind == 4 and len(body) >= 8:      # sync parameters
            # channel, next_tu(2), master_channel, guard, aw_period(2), psf_interval(2)
            bits.append("sync ch=%d next_tu=%d master_ch=%d aw=%d psf=%d" % (
                body[0], struct.unpack_from("<H", body, 1)[0], body[3],
                struct.unpack_from("<H", body, 5)[0],
                struct.unpack_from("<H", body, 7)[0]))
        elif kind == 7:                        # HT capabilities
            bits.append("ht=%s" % body.hex())
        elif kind == 18:                       # channel sequence
            bits.append("seq=%s" % body.hex())
        elif kind == 24:                       # election v2
            bits.append("el2=%s" % body.hex())
    return " ".join(bits)


# ---------------------------------------------------------------- report

def main(argv):
    if len(argv) < 2:
        raise SystemExit("usage: python air.py air.pcap [--peer mac]")
    path = argv[1]
    want = None
    if "--peer" in argv:
        want = argv[argv.index("--peer") + 1].lower()

    frames = []           # (ts, sa, da, len, rt, type, subtype, seq)
    presence = []         # (ts, sa, line)
    for ts, linktype, data in read_pcap(path):
        rt = parse_radiotap(data)
        if rt is None:
            continue
        d = parse_dot11(data, rt["len"])
        if d is None:
            continue
        ftype, subtype, a1, a2, a3, seq, body = d
        sa, da = mac(a2), mac(a1)
        frames.append((ts, sa, da, body, rt, ftype, subtype, seq))
        if ftype == 0 and subtype == 13:
            t = awdl_tlvs(data, rt["len"])
            if t:
                presence.append((ts, sa, describe_presence(t)))

    if not frames:
        print("no 802.11 frames in %s" % path)
        return

    t0, t1 = frames[0][0], frames[-1][0]
    span = max(1e-6, t1 - t0)
    print("==== %s: %d frames, %.1f s ====\n" % (path, len(frames), span))

    # --- per sender -------------------------------------------------------
    per = defaultdict(lambda: {"n": 0, "bytes": 0, "rates": Counter(), "bytes_by_rate": Counter()})
    for ts, sa, da, blen, rt, ft, st, sq in frames:
        if ft != 2:
            continue
        e = per[sa]
        e["n"] += 1
        e["bytes"] += blen
        lbl = rate_label(rt)
        e["rates"][lbl] += 1
        e["bytes_by_rate"][lbl] += blen

    print("---- data senders (802.11 data frames only) ----")
    for sa, e in sorted(per.items(), key=lambda kv: -kv[1]["bytes"]):
        print("%s  %d frames  %.1f KB  %.1f KB/s  mean %d B" % (
            sa, e["n"], e["bytes"] / 1024.0, e["bytes"] / 1024.0 / span,
            e["bytes"] // max(1, e["n"])))
        for lbl, n in e["rates"].most_common(6):
            print("      %-14s %6d frames  %8.1f KB" % (lbl, n, e["bytes_by_rate"][lbl] / 1024.0))
    print()

    # --- the peer we are receiving from ------------------------------------
    peer = want or (max(per.items(), key=lambda kv: kv[1]["bytes"])[0] if per else None)
    if not peer:
        return
    times = [(ts, blen, rt) for ts, sa, da, blen, rt, ft, st, sq in frames
             if ft == 2 and sa == peer]
    if not times:
        print("no data frames from %s" % peer)
        return

    lens = Counter(b for _, b, _ in times)
    gaps = []
    for i in range(1, len(times)):
        gaps.append((times[i][0] - times[i - 1][0]) * 1000.0)
    airs = [air_time_us(rt, b) for _, b, rt in times]
    airs = [a for a in airs if a]
    air_total_ms = sum(airs) / 1000.0

    def pct(vals, p):
        if not vals:
            return 0.0
        s = sorted(vals)
        return s[min(len(s) - 1, int(len(s) * p))]

    print("---- from %s ----" % peer)
    print("frame lengths (top 5): %s" % ", ".join("%dB x%d" % (k, v) for k, v in lens.most_common(5)))
    print("gaps  p10 %.2f  p50 %.2f  p90 %.2f  p99 %.2f ms" % (
        pct(gaps, .1), pct(gaps, .5), pct(gaps, .9), pct(gaps, .99)))
    bursts = sum(1 for g in gaps if g < 0.5)
    print("frames with a <0.5 ms gap before them: %d (%.1f%%) -> %s" % (
        bursts, 100.0 * bursts / max(1, len(gaps)),
        "bursty (aggregation or back-to-back)" if bursts > len(gaps) * 0.3 else "one at a time"))
    print("air time total %.1f ms over %.1f ms wall = %.1f%% duty" % (
        air_total_ms, span * 1000.0, 100.0 * air_total_ms / (span * 1000.0)))
    print("mean air per frame %.2f ms" % (air_total_ms / max(1, len(airs))))
    print()

    # --- the peer's own claim about time and rate --------------------------
    seen = set()
    print("---- peer presence frames (what it advertises) ----")
    for ts, sa, line in presence:
        if want and sa != want:
            continue
        if line in seen:
            continue
        seen.add(line)
        print("%s  %s" % (sa, line))
        if len(seen) >= 6:
            break
    if not seen:
        print("none -- the peer was not heard announcing (snapshot too short, or the "
              "capture window fell between two PSFs)")


if __name__ == "__main__":
    main(sys.argv)
