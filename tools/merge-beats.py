#!/usr/bin/env python3
"""Merge Android beats.json and core core_beats.json into a unified beats.json."""
import json
import sys


def main():
    if len(sys.argv) != 4:
        print("Usage: merge-beats.py <android_beats.json> <core_beats.json> <output.json>",
              file=sys.stderr)
        sys.exit(2)

    with open(sys.argv[1]) as f:
        android = json.load(f)
    with open(sys.argv[2]) as f:
        core = json.load(f)

    sample_rate = android.get("sampleRate", 44100)
    a_beats = android.get("beats", [])
    c_beats = core.get("beats", [])

    # Match beats by proximity (within ~11ms = 500 samples)
    max_dist = 500
    matched = set()

    for ab in a_beats:
        anchor = ab.get("refined", ab["sample"])
        best_j, best_d = None, max_dist
        for j, cb in enumerate(c_beats):
            if j in matched:
                continue
            d = abs(cb["sample"] - anchor)
            if d < best_d:
                best_d = d
                best_j = j
        if best_j is not None:
            matched.add(best_j)
            cb = c_beats[best_j]
            ab["coreSample"] = cb["sample"]
            ab["coreDetectorScore"] = cb.get("detectorScore")
            ab["coreCorrelationScore"] = cb.get("correlationScore")

    # Add core-only beats
    for j, cb in enumerate(c_beats):
        if j not in matched:
            a_beats.append({
                "sample": round(cb["sample"]),
                "time": cb["time"],
                "isTick": cb["isTick"],
                "seqIndex": cb.get("seqIndex", 0),
                "index": -1,
                "sincePrev": 0,
                "avgPeriod": 0,
                "coreSample": cb["sample"],
                "coreDetectorScore": cb.get("detectorScore"),
                "coreCorrelationScore": cb.get("correlationScore"),
                "coreOnly": True,
            })

    a_beats.sort(key=lambda b: b.get("refined", b["sample"]))

    result = {
        "sampleRate": sample_rate,
        "frameSamples": android.get("frameSamples", 220),
        "beats": a_beats,
    }

    with open(sys.argv[3], "w") as f:
        json.dump(result, f)

    n_matched = len(matched)
    n_core_only = len(c_beats) - n_matched
    n_android_only = sum(1 for b in a_beats if "coreSample" not in b)
    print(f"Merged: {n_matched} matched, {n_android_only} android-only, "
          f"{n_core_only} core-only -> {len(a_beats)} total")


if __name__ == "__main__":
    main()
