#!/usr/bin/env python3
"""Prints the AWDL address of the peer that is sending periodic frames.

An AWDL frame is separable at L2 by its address 3, the fixed BSSID
00:25:00:ff:94:73, and every node's periodic frame is a vendor action frame on
it. This reads a pcap, keeps the frames that carry that BSSID, and prints the
address that sent the most of them.

The address matters because a node randomises it: it changes per AWDL session,
so an address from an earlier capture is worthless for addressing a frame now.
That is the whole reason this is a program and not a constant.

Usage: peer-mac.py <pcap> [bssid]

Prints one address, or nothing and exit 1 when the capture holds no AWDL frame.
"""

import struct
import sys

BSSID = bytes.fromhex("002500ff9473")
MAGICS = {
    b"\xd4\xc3\xb2\xa1": "<",
    b"\xa1\xb2\xc3\xd4": ">",
    b"\x4d\x3c\xb2\xa1": "<",
    b"\xa1\xb2\x3c\x4d": ">",
}


def frames(path):
    """Yields each frame with its radiotap header removed."""
    data = open(path, "rb").read()
    if len(data) < 24:
        return
    endian = MAGICS.get(data[:4])
    if endian is None:
        raise SystemExit("peer-mac: not a pcap: %s" % data[:4].hex())
    offset = 24
    while offset + 16 <= len(data):
        _, _, caplen, _ = struct.unpack(endian + "IIII", data[offset:offset + 16])
        packet = data[offset + 16:offset + 16 + caplen]
        offset += 16 + caplen
        if len(packet) < 8:
            continue
        header = struct.unpack("<H", packet[2:4])[0]
        if 8 <= header <= len(packet):
            yield packet[header:]


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__.strip().splitlines()[-1])
    want = bytes.fromhex(argv[2].replace(":", "")) if len(argv) > 2 else BSSID
    counts = {}
    for frame in frames(argv[1]):
        # 24-byte 802.11 header: address 1 at 4, address 2 at 10, address 3 at 16.
        if len(frame) < 40 or frame[16:22] != want:
            continue
        sender = frame[10:16]
        counts[sender] = counts.get(sender, 0) + 1
    if not counts:
        return 1
    best = max(counts, key=counts.get)
    print(":".join("%02x" % b for b in best))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
