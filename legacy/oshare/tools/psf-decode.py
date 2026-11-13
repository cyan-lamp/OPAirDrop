#!/usr/bin/env python3
"""Decode AWDL action frames out of a `tcpdump -xx` capture.

The layout comes from the AWDL spec's §3.2 definition, which was checked
against real iOS 26 captures. Use it to read what a peer actually announces
-- its schedule phase, its election state, its channel sequence -- instead of
guessing from offsets.

    tools/psf-decode.py oshare/build/mosey/psf-raw.txt
"""

import re
import sys

TLV_NAMES = {
    2: "ServiceResponse",
    4: "SyncParams",
    5: "ElectionV1",
    6: "ServiceParams",
    7: "HtCapabilities",
    12: "DataPathState",
    16: "Arpa",
    17: "Ieee80211Container",
    18: "ChannelSequence",
    20: "SyncTree",
    21: "Version",
    22: "BloomFilter",
    24: "ElectionV2",
}

SUBTYPES = {0: "PSF", 3: "MIF"}
CHANNEL_SEQ_ENCODING = {0: "simple", 1: "legacy", 3: "opclass"}


def mac(b, off):
    return ":".join("%02x" % x for x in b[off:off + 6])


def le16(b, off):
    return b[off] | (b[off + 1] << 8)


def le32(b, off):
    return int.from_bytes(b[off:off + 4], "little")


def tlvs(body):
    """Walk (type, value) pairs, tolerating the <3 bytes of zero padding
    iPhones add after the last TLV (AWDL-5)."""
    at = 0
    while at + 3 <= len(body):
        kind = body[at]
        length = le16(body, at + 1)
        value = body[at + 3:at + 3 + length]
        if len(value) != length:
            print("  !! truncated TLV %d at %d: wants %d, has %d"
                  % (kind, at, length, len(value)))
            return
        yield kind, value
        at += 3 + length
    rest = body[at:]
    if any(rest):
        print("  !! %d bytes of nonzero trailing data" % len(rest))


def parse_sync(v):
    return {
        "next_aw_channel": v[0],
        "next_tu": le16(v, 1),
        "master_channel": v[3],
        "aw_period": le16(v, 5),
        "af_period_tu": le16(v, 7),
        "flags": hex(le16(v, 9)),
        "ext_aw": le16(v, 11),
        "common_aw": le16(v, 13),
        "remaining_aw": le16(v, 15),
        "master": mac(v, 21),
        "presence_mode": v[27],
        "aw_counter": le16(v, 29),
    }


def parse_election_v1(v):
    return {
        "height": v[3],
        "master": mac(v, 5),
        "master_metric": le32(v, 11),
        "self_metric": le32(v, 15),
    }


def parse_election_v2(v):
    return {
        "master": mac(v, 0),
        "sync": mac(v, 6),
        "master_counter": le32(v, 12),
        "height": le32(v, 16),
        "master_metric": le32(v, 20),
        "self_metric": le32(v, 24),
        "self_counter": le32(v, 36),
    }


def parse_sequence(v):
    slots_less_one = v[0]
    encoding = v[1]
    step = v[3]
    slots = []
    at = 6
    for _ in range(slots_less_one + 1):
        if encoding == 0:
            slots.append(v[at])
            at += 1
        else:
            # legacy is [flags, channel], opclass is [channel, opclass]
            lo, hi = v[at], v[at + 1]
            slots.append(hi if encoding == 1 else lo)
            at += 2
    return {
        "encoding": CHANNEL_SEQ_ENCODING.get(encoding, encoding),
        "slots": slots,
        "step": step + 1,
        "fill": hex(le16(v, 4)),
    }


def parse_version(v):
    return {"awdl_version": hex(v[0]), "device_class": v[1]}


def parse_arpa(v):
    return {"flags": v[0], "name": v[2:2 + v[1]].decode("utf-8", "replace")}


def decode(body, label=""):
    if len(body) < 16:
        print("%s: too short for an action header" % label)
        return
    if body[0] != 0x7F or body[1:4] != b"\x00\x17\xf2" or body[4] != 8:
        print("%s: not an AWDL action body (%s)" % (label, body[:8].hex()))
        return
    subtype = body[6]
    print("%s%s  awdl=%s  phy_tx=%d  target_tx=%d"
          % (label, SUBTYPES.get(subtype, "subtype %d" % subtype),
             hex(body[5]), le32(body, 8), le32(body, 12)))
    for kind, value in tlvs(body[16:]):
        name = TLV_NAMES.get(kind, "type %d" % kind)
        if kind == 4:
            for k, val in parse_sync(value).items():
                print("     sync   %-16s %s" % (k, val))
        elif kind == 5:
            print("     elect1 %s" % parse_election_v1(value))
        elif kind == 24:
            print("     elect2 %s" % parse_election_v2(value))
        elif kind == 18:
            print("     chanseq %s" % parse_sequence(value))
        elif kind == 21:
            print("     version %s" % parse_version(value))
        elif kind == 16:
            print("     arpa %s" % parse_arpa(value))
        else:
            print("     %-10s len=%d %s" % (name, len(value), value[:24].hex()))


def action_body(raw):
    """Trim radiotap and the 802.11 MAC header, leaving the action body.

    `tcpdump -xx` on a radiotap interface hands back the whole frame, so both
    layers come off before the AWDL fields line up. Captures that were already
    trimmed are passed through untouched, which keeps the older files usable.
    """
    if len(raw) < 8:
        return raw
    rt = le16(raw, 2)
    if rt < 4 or rt > len(raw) - 24:
        return raw
    fc = raw[rt] | (raw[rt + 1] << 8)
    if (fc & 0x0003) != 0 or ((fc >> 2) & 0x3) != 0:
        return raw          # not a management frame; leave it to the caller
    return raw[rt + 24:]


HEX_LINE = re.compile(r"^\s*0x[0-9a-fA-F]+:\s+(.*)$")


def frames(path):
    """Group tcpdump's `-xx` hexdump lines into frames.

    `tcpdump -xx` prints `\\t0x0000:  7f00 17f2 ...  ........` -- one space
    between the 4 half-word groups, two spaces before the ASCII column. Split
    on the two-space gap, never on the first one, or the payload disappears.
    """
    header = None
    cur = bytearray()
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.startswith("\t"):
                if cur:
                    yield header, bytes(cur)
                header, cur = line.strip(), bytearray()
                continue
            m = HEX_LINE.match(line.rstrip("\r\n"))
            if not m:
                continue
            payload = m.group(1).strip().split("  ")[0].strip()
            if not payload:
                continue
            try:
                cur.extend(bytes.fromhex("".join(payload.split())))
            except ValueError:
                print("  !! unparsable hexdump line: %r" % line[:60])
    if cur:
        yield header, bytes(cur)


def summary(path):
    """Aggregate a whole capture: who talks, at what cadence, with what
    schedule. This is the comparison table we hold our own output against."""
    from collections import Counter

    total = 0
    skipped = 0
    subtypes = Counter()
    senders = Counter()
    sequences = Counter()
    versions = Counter()
    orders = Counter()
    metrics = Counter()
    gaps = Counter()
    counters = []
    last_phy = None

    for header, raw in frames(path):
        body = action_body(raw)
        if len(body) < 16 or body[0] != 0x7F or body[1:4] != b"\x00\x17\xf2":
            skipped += 1
            continue
        total += 1
        subtypes[SUBTYPES.get(body[6], "subtype %d" % body[6])] += 1
        m = re.search(r"SA:([0-9a-f:]{17})", header or "")
        sender = m.group(1) if m else "?"
        senders[sender] += 1
        phy = le32(body, 8)
        if last_phy is not None and 0 <= phy - last_phy < 5_000_000:
            gaps[(phy - last_phy) // 1000] += 1
        last_phy = phy

        order = []
        for kind, value in tlvs(body[16:]):
            order.append(kind)
            if kind == 18:
                seq = parse_sequence(value)
                sequences[(sender, seq["encoding"], tuple(seq["slots"]))] += 1
            elif kind == 21:
                versions[(sender, value[0], value[1])] += 1
            elif kind == 5 and len(value) >= 19:
                metrics[(sender, le32(value, 11), le32(value, 15), value[3])] += 1
            elif kind == 4 and len(value) >= 31:
                counters.append(le16(value, 29))
        orders[tuple(order)] += 1

    print("frames: %d awdl, %d skipped" % (total, skipped))
    print("subtypes: %s" % dict(subtypes))
    print("senders:")
    for k, v in senders.most_common():
        print("  %s  %d" % (k, v))
    print("phy_tx deltas (ms): %s" % dict(sorted(gaps.items())))
    print("tlv orders:")
    for k, v in orders.most_common(8):
        print("  %s  x%d" % ("-".join(str(x) for x in k), v))
    print("versions (sender, awdl_ver, device_class): %s" % dict(versions))
    print("election (sender, master_metric, self_metric, height):")
    for k, v in metrics.most_common(8):
        print("  %s  x%d" % (k, v))
    print("channel sequences:")
    for k, v in sequences.most_common(8):
        print("  %s %s %s  x%d" % (k[0], k[1], list(k[2]), v))
    if counters:
        print("aw_counter: %d samples, %d..%d" % (len(counters), min(counters), max(counters)))


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "oshare/build/mosey/psf-raw.txt"
    if len(sys.argv) > 2 and sys.argv[2] == "--summary":
        summary(path)
        return
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    seen = 0
    for header, raw in frames(path):
        if not raw:
            continue
        seen += 1
        if seen > limit:
            break
        print("--- frame %d" % seen)
        if header:
            print("    %s" % header)
        decode(action_body(raw), "    ")
    print("decoded %d of %d frames" % (min(seen, limit), seen))


if __name__ == "__main__":
    main()
