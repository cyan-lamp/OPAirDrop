#!/usr/bin/env python3
"""Turn an agg-sample log into per-section rates and a verdict.

The log is written by tools/agg-sample.sh on the device and by tools/agg-sweep.sh
on the host, which appends "MARK <label>" lines whenever it changes a parameter.
Sections are computed from the first and last sample inside each one, so a slow
first second of TCP ramp-up shows up as its own section instead of being averaged
into the baseline.

The number that decides where to spend effort is milliseconds per aggregate:

  * flat in bytes-per-aggregate  -> the cost is a fixed per-submit round trip, so
                                    only sending more bytes per submit helps;
  * proportional to bytes        -> the air is the limit, so raising the rate
                                    (MCS/width) or coalescing more helps.

Usage: agg-analyze.py <log> [--json]
"""
import json
import sys


def parse(path):
    samples, marks = [], []
    with open(path, "r", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            if line.startswith("MARK"):
                marks.append((len(samples), line[4:].strip()))
                continue
            if line == "DONE":
                continue
            parts = line.split()
            if len(parts) < 5:
                continue
            try:
                samples.append({
                    "t": float(parts[0]),
                    "bytes": int(parts[1]),
                    "agg": int(parts[2]),
                    "msdus": int(parts[3]),
                    "collect_us": int(parts[4]) if parts[4].isdigit() else 0,
                    "sub": int(parts[5]) if len(parts) > 5 and parts[5].isdigit() else 0,
                    "comp": int(parts[6]) if len(parts) > 6 and parts[6].isdigit() else 0,
                    "pend": int(parts[7]) if len(parts) > 7 and parts[7].isdigit() else 0,
                })
            except ValueError:
                continue
    return samples, marks


def section_stats(chunk):
    if len(chunk) < 4:
        return None
    a, b = chunk[0], chunk[-1]
    dt = b["t"] - a["t"]
    db = b["bytes"] - a["bytes"]
    da = b["agg"] - a["agg"]
    dm = b["msdus"] - a["msdus"]
    if dt <= 0.05 or db <= 0:
        return None
    return {
        "dt": dt,
        "bytes": db,
        "agg": da,
        "msdus": dm,
        "mb_s": db / dt / 1e6,
        "agg_s": da / dt if da else 0.0,
        "bytes_per_agg": db / da if da else 0.0,
        "msdu_per_agg": dm / da if da else 0.0,
        "ms_per_agg": dt / da * 1000 if da else 0.0,
        "air_mbps": (db * 8 / 1e6) / dt if dt else 0.0,
        "collect_us": b["collect_us"],
        "pend_max": max(s["pend"] for s in chunk),
    }


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    samples, marks = parse(sys.argv[1])
    as_json = "--json" in sys.argv
    if len(samples) < 4:
        print("agg-analyze: too few samples; was the sampler running?")
        return 1

    # A mark is recorded at the sample index *after* which the new setting applies,
    # so section i runs from marks[i] to marks[i+1] and carries marks[i]'s label.
    # Anything sampled before the first mark is the pre-roll and is reported apart.
    sections = []
    for i, (pos, label) in enumerate(marks):
        end = marks[i + 1][0] if i + 1 < len(marks) else len(samples)
        chunk = samples[pos:end]
        stats = section_stats(chunk)
        if stats is None:
            continue
        stats["label"] = label
        stats["start"] = chunk[0]["t"]
        sections.append(stats)
    if samples and (not marks or marks[0][0] > 0):
        stats = section_stats(samples[:marks[0][0]] if marks else samples)
        if stats:
            stats["label"] = "pre-roll"
            stats["start"] = samples[0]["t"]
            sections.insert(0, stats)

    if not sections:
        print("agg-analyze: no section had enough movement to measure.")
        return 1

    if as_json:
        print(json.dumps({"sections": sections, "samples": len(samples)}, indent=2))
        return 0

    print()
    print("  #  parameter          secs      MB/s   agg/s   B/agg  MSDU/agg  ms/agg  air")
    print("  -  -----------------  ------  ------  ------  ------  --------  ------  ------")
    for i, s in enumerate(sections, 1):
        print("  %d  %-17s  %6.2f  %6.2f  %6.0f  %6.0f  %8.2f  %6.2f  %5.1f"
              % (i, s["label"][:17], s["dt"], s["mb_s"], s["agg_s"],
                 s["bytes_per_agg"], s["msdu_per_agg"], s["ms_per_agg"], s["air_mbps"]))

    total = section_stats(samples)
    if total:
        print("  -  %-17s  %6.2f  %6.2f  %6.0f  %6.0f  %8.2f  %6.2f  %5.1f"
              % ("whole transfer", total["dt"], total["mb_s"], total["agg_s"],
                 total["bytes_per_agg"], total["msdu_per_agg"], total["ms_per_agg"],
                 total["air_mbps"]))
    print()

    # Verdict. Two shapes are worth telling apart, and the giveaway is which of the
    # two per-section numbers stays put while the aggregate size moves:
    #   air throughput flat  -> the radio is already full; only rate/width help;
    #   ms per submit flat   -> the cost is one round trip per submit; only sending
    #                           more bytes in that round trip helps.
    usable = [s for s in sections if s["bytes_per_agg"] > 0 and s["ms_per_agg"] > 0]
    if len(usable) >= 2:
        b_lo = min(usable, key=lambda s: s["bytes_per_agg"])
        b_hi = max(usable, key=lambda s: s["bytes_per_agg"])
        ratio_b = b_hi["bytes_per_agg"] / b_lo["bytes_per_agg"]
        ratio_ms = b_hi["ms_per_agg"] / b_lo["ms_per_agg"]
        air_lo = min(s["air_mbps"] for s in usable)
        air_hi = max(s["air_mbps"] for s in usable)
        air_spread = air_hi / air_lo if air_lo > 0 else 99
        ms_spread = max(s["ms_per_agg"] for s in usable) / min(s["ms_per_agg"] for s in usable)
        print("  bytes/aggregate %.0f -> %.0f  (x%.2f)" %
              (b_lo["bytes_per_agg"], b_hi["bytes_per_agg"], ratio_b))
        print("  ms/aggregate    %.2f -> %.2f  (x%.2f)" %
              (b_lo["ms_per_agg"], b_hi["ms_per_agg"], ratio_ms))
        print("  air Mbps        %.1f -> %.1f  (x%.2f)" % (air_lo, air_hi, air_spread))
        if ratio_b < 1.15:
            print("\n  VERDICT: aggregate size barely moved between sections, so this")
            print("           sweep did not test what it meant to. Re-run with the peer")
            print("           already streaming before the first step.")
        elif air_spread < 1.2:
            print("\n  VERDICT: the air is saturated at %.1f Mbps and stays there" % air_lo)
            print("           whatever the aggregate size. Coalescing is already paying")
            print("           for itself; the only lever left is the physical rate")
            print("           (MCS, streams, width) -- not a bigger per-submit ceiling.")
        elif ms_spread < 1.2:
            print("\n  VERDICT: ms per submit is flat while bytes per submit are not.")
            print("           The cost is one round trip per submit, so bigger")
            print("           aggregates are close to free: raise the per-submit byte")
            print("           ceiling (driver build). Changing the rate will not help.")
        else:
            print("\n  VERDICT: mixed. Throughput moved %.1fx for a %.1fx change in" %
                  (air_spread, ratio_b))
            print("           aggregate size, so part of the cost is the round trip and")
            print("           part is the air. Worth one more sweep with a wider range.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
