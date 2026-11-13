#!/usr/bin/env python3
"""Summarise an AWDL capture: who is on the air, saying what, addressed to whom.

    tools/analyze-pcap.py capture.pcap [our-mac]

The question this answers is narrower than a decoder's: after we start sending
MIFs, does anything on the air address *us*? A peer that has accepted us says so
either by unicasting an AWDL action frame to our MAC or by sending a PSF at all,
and the two are easy to tell apart in the address fields. Aggregating by
(sender, subtype) plus a count of frames addressed to us keeps the output short
enough to read while still showing a change in peer behaviour.
"""

import struct
import sys
from collections import Counter, OrderedDict

TLV_NAMES = {
    2: "ServiceResponse", 4: "SyncParams", 5: "ElectionV1", 6: "ServiceParams",
    7: "HtCapabilities", 12: "DataPathState", 16: "Arpa",
    17: "Ieee80211Container", 18: "ChannelSequence", 20: "SyncTree",
    21: "Version", 22: "BloomFilter", 24: "ElectionV2",
}
SUBTYPES = {0: "PSF", 1: "Synchronization", 2: "Reserved", 3: "MIF"}
AWDL_BSSID = "00:25:00:ff:94:73"


def mac(b, off):
    return ":".join("%02x" % x for x in b[off:off + 6])


def read_pcap(path):
    with open(path, "rb") as f:
        data = f.read()
    magic = data[:4]
    if magic == b"\xd4\xc3\xb2\xa1":
        endian, nano = "<", False
    elif magic == b"\x4d\x3c\xb2\xa1":
        endian, nano = "<", True
    elif magic == b"\xa1\xb2\xc3\xd4":
        endian, nano = ">", False
    else:
        raise SystemExit("not a pcap: %r" % magic)
    linktype = struct.unpack(endian + "I", data[20:24])[0]
    off = 24
    while off + 16 <= len(data):
        ts, tusec, caplen, _ = struct.unpack(endian + "IIII", data[off:off + 16])
        off += 16
        yield ts + tusec / 1e9, linktype, data[off:off + caplen]
        off += caplen


def strip_radiotap(pkt):
    if len(pkt) < 8:
        return None
    it_len = struct.unpack("<H", pkt[2:4])[0]
    if it_len < 8 or it_len > len(pkt):
        return None
    return pkt[it_len:]


def parse(pkt):
    """Return a dict for an AWDL action frame, or a coarse tag for anything else."""
    body = strip_radiotap(pkt)
    if body is None or len(body) < 24:
        return None
    fc = body[0] | (body[1] << 8)
    ftype = (fc >> 2) & 0x3
    subtype = (fc >> 4) & 0xf
    hdr = {"fc": fc, "ftype": ftype, "subtype": subtype}
    off = 24 if (fc & 0x0300) != 0x0300 else 30  # QoS data has two extra bytes
    if hdr["ftype"] == 0 and subtype == 13:  # action
        hdr["dst"] = mac(body, 4)
        hdr["sa"] = mac(body, 10)
        hdr["bssid"] = mac(body, 16)
        hdr["seq"] = struct.unpack("<H", body[22:24])[0] >> 4
        if len(body) >= 24 + 8:
            category = body[24]
            oui = body[25:28]
            if category == 127 and oui == b"\x00\x17\xf2":
                hdr["awdl"] = True
                # AWDL action body is action header (16) then TLVs. The
                # action header is category(1) OUI(3) awdl-type(1) version(1)
                # subtype(1) reserved(1); subtype is therefore body[30], not
                # body[31] (the reserved byte, always 0x00 -- reading it
                # labelled every MIF as PSF).
                hdr["awdlSub"] = body[30]
                tlvs = []
                p = 40  # 24 header + 16 action header
                while p + 3 <= len(body):
                    t = body[p]
                    ln = body[p + 1] | (body[p + 2] << 8)
                    if t == 0 and ln == 0:
                        # Trailing zero padding. tlv.rs keeps consuming these
                        # as empty type-0 TLVs, but they carry nothing, so
                        # skip them without truncating the walk.
                        p += 3
                        continue
                    tlvs.append((t, ln))
                    p += 3 + ln
                hdr["tlvs"] = tlvs
        return hdr
    if hdr["ftype"] == 0:
        hdr["dst"] = mac(body, 4)
        hdr["sa"] = mac(body, 10)
    elif hdr["ftype"] == 2:  # data
        hdr["dst"] = mac(body, 4)
        hdr["sa"] = mac(body, 10)
    return hdr


def main():
    path = sys.argv[1]
    us = sys.argv[2].lower() if len(sys.argv) > 2 else None

    senders = Counter()
    tlv_seen = {}
    to_us = Counter()
    mdns = 0
    first_seen = {}
    last_seen = {}
    total = 0
    non_awdl_actions = Counter()

    for ts, linktype, pkt in read_pcap(path):
        h = parse(pkt)
        if not h:
            continue
        total += 1
        if h.get("awdl"):
            name = SUBTYPES.get(h["awdlSub"], "sub-0x%02x" % h["awdlSub"])
            key = (h["sa"], name)
            senders[key] += 1
            first_seen.setdefault(key, ts)
            last_seen[key] = ts
            tlv_seen.setdefault(key, Counter()).update(t for t, _ in h.get("tlvs", []))
            if us and h["dst"] == us:
                to_us[key] += 1
        elif h["ftype"] == 0 and h["subtype"] == 13 and h.get("dst"):
            non_awdl_actions[(h.get("sa"), h["dst"])] += 1
        elif h["ftype"] == 2 and h.get("dst"):
            # Data frames were never checked against `us` before, so the one
            # signal that matters most -- whether the peer ever addressed a
            # frame to us -- was invisible for every non-action frame.
            if h["dst"] == "33:33:00:00:00:fb":
                mdns += 1
            if us and h["dst"] == us:
                key = (h.get("sa"), "DATA")
                senders[key] += 1
                to_us[key] += 1
                first_seen.setdefault(key, ts)
                last_seen[key] = ts

    print("packets=%d  linktype=%d  awdl-groups=%d" % (total, linktype, len(senders)))
    print()
    print("%-18s %-18s %6s %6s %8s  %s" % ("sender", "subtype", "count", "to-us", "span", "tlvs"))
    t0 = min(first_seen.values()) if first_seen else 0
    for (sa, name), n in senders.most_common():
        tlvs = ",".join("%s" % TLV_NAMES.get(t, str(t))
                        for t, _ in sorted(tlv_seen[(sa, name)].items()))
        print("%-18s %-18s %6d %6d %7.1fs  %s" % (
            sa, name, n, to_us.get((sa, name), 0),
            last_seen[(sa, name)] - first_seen[(sa, name)], tlvs))
    if mdns:
        print()
        print("mdns frames to 33:33:00:00:00:fb: %d" % mdns)
    if non_awdl_actions:
        print()
        print("other action frames (top 5):")
        for (sa, dst), n in non_awdl_actions.most_common(5):
            print("  %s -> %s  x%d" % (sa, dst, n))


if __name__ == "__main__":
    main()
