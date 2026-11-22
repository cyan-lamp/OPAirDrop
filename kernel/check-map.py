#!/usr/bin/env python3
"""Check every source-map.json entry against what the build really compiles.

Why this exists: the overlay is plain "copy file X over kernel_path Y". Nothing
verifies that Y is the copy the compiler reads. On 2026-11-20 a hook placed in
target_if_p2p.c never ran, because source-map.json pointed at the qca-wifi-host-cmn
copy while wlan_qcacld3_modules.bzl compiles the qcacld-3.0/components one. The
module built, the hook was dead, and the failure looked like a firmware timeout.

The bazel lists under vendor/qcom/opensource/wlan are the authority. Entries there
are relative to that wlan/ directory with a prefix remap:

    qca-wifi-host-cmn/<x>  ->  cmn/<x>
    qcacld-3.0/<x>         ->  <x>

Usage (inside WSL, where the build tree lives):

    python3 check-map.py [--wlan vendor/qcom/opensource/wlan] [--root /mnt/opairshare-build/opairshare]

Exit status: 0 when every .c entry is listed, 1 when any is not. Header (.h)
entries are reported but cannot be checked this way - a header is compiled as
part of whoever includes it, not on its own.
"""

import argparse
import glob
import json
import os
import sys


def remap(kernel_path, wlan_prefix):
    rel = kernel_path.split(wlan_prefix, 1)[-1]
    for old, new in (("qca-wifi-host-cmn/", "cmn/"), ("qcacld-3.0/", "")):
        if rel.startswith(old):
            return new + rel.split(old, 1)[1]
    return rel


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-map",
                    default=os.path.join(here, "source", "source-map.json"))
    ap.add_argument("--root", default="/mnt/opairshare-build/opairshare")
    ap.add_argument("--wlan", default="vendor/qcom/opensource/wlan")
    args = ap.parse_args()

    wlan = os.path.join(args.root, args.wlan)
    lists = glob.glob(os.path.join(wlan, "**", "*.bzl"), recursive=True)
    if not lists:
        sys.exit("no .bzl under %s - wrong --root?" % wlan)
    text = "".join(open(p, errors="ignore").read() for p in lists)

    entries = json.load(open(args.source_map))
    dead, headers, ok = [], [], 0
    for name, value in sorted(entries.items()):
        if not isinstance(value, dict) or "kernel_path" not in value:
            continue
        rel = remap(value["kernel_path"], args.wlan + "/")
        if not name.endswith(".c"):
            headers.append((name, rel))
            continue
        if rel in text:
            ok += 1
            continue
        base = os.path.basename(rel)
        near = [l.strip().rstrip(",") for l in text.splitlines()
                if l.strip().startswith('"') and base in l]
        dead.append((name, rel, near[0] if near else "-"))

    for name, rel, near in dead:
        print("DEAD  %-34s mapped=%-64s compiled=%s" % (name, rel, near))
    print("\n.c listed: %d   .c dead: %d   .h unchecked: %d"
          % (ok, len(dead), len(headers)))
    if dead:
        print("Fix kernel_path for the DEAD entries, then re-copy those files.")
    return 1 if dead else 0


if __name__ == "__main__":
    sys.exit(main())
