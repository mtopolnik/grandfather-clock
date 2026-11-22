#!/usr/bin/env python3
"""
Computes offline reference values for clock recordings, for use by
TickDetectorEvaluationTest.

For each WAV it finds the clock's period without any prior, tracks every
beat across the whole file, times each beat precisely (onset plus template
cross-correlation), and fits a straight line per beat type with a shared
period. The full-length fit is the reference the real-time detector is
judged against.

Usage:
    tools/reference.py [--root DIR] [--out FILE] [WAV ...]

With no WAV arguments it scans DIR (default ~/Desktop/grandfather-clock)
recursively. Results are merged into FILE (default tools/eval/references.json),
keyed by the WAV's path relative to DIR.

Requires numpy and scipy.
"""
import argparse
import json
import os
import sys

import numpy as np
from scipy import signal

SR = 44100
HOP = 44                       # ~1 ms envelope hop
SKIP_NAMES = {"clock_idealized.wav"}
MAX_SCAN_BYTES = 1 << 30        # ~3.4 hours of 44.1 kHz mono 16-bit


def load(path):
    return np.fromfile(path, dtype="<i2", offset=44).astype(np.float64)


def bandpass(x, lo=2000, hi=10000):
    sos = signal.butter(4, [lo, hi], "bp", fs=SR, output="sos")
    return signal.sosfiltfilt(sos, x)


def envelope(y, n):
    return np.sqrt(np.convolve(y * y, np.ones(n) / n, mode="same"))


def find_period(env_hop):
    """Returns (full period in samples, phase of beat type 0, phase of beat type 1, confidence)."""
    ons = np.maximum(0.0, np.diff(np.log(env_hop + 1.0)))
    ons = np.convolve(ons, np.ones(3), "same")
    o = ons - ons.mean()
    n = len(o)
    f = np.fft.rfft(o, 2 * n)
    ac = np.fft.irfft(f * np.conj(f))[:n]
    ac /= ac[0]
    lo, hi = int(0.6 * SR / HOP), int(min(3.0, n * HOP / SR / 3) * SR / HOP)
    L = lo + int(np.argmax(ac[lo:hi]))
    conf = float(ac[L])
    y0, y1, y2 = ac[L - 1], ac[L], ac[L + 1]
    L = L + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2)

    # Folding needs the period to be accurate to a fraction of a bin over the
    # folded span, so only the first ~20 s are folded.
    span = min(n, int(20 * SR / HOP))

    def fold_peaks(period_hops):
        nbins = int(round(period_hops / 2))           # 2 ms bins
        idx = (np.arange(span) % period_hops) / period_hops * nbins
        h = np.bincount(idx.astype(int) % nbins, weights=ons[:span], minlength=nbins)
        h = np.convolve(np.concatenate([h[-3:], h, h[:3]]), np.ones(5) / 5, "same")[3:-3]
        pk, _ = signal.find_peaks(np.concatenate([h, h]), distance=max(1, int(0.15 * nbins)))
        pk = np.unique(pk % nbins)
        # A peak counts as a beat when it stands well clear of the profile's own
        # noise, so a quiet tock still counts next to a loud tick.
        med = np.median(h)
        sigma = 1.4826 * np.median(np.abs(h - med)) + 1e-12
        strong = [p for p in pk if h[p] > med + max(6 * sigma, 0.15 * (h.max() - med))]
        return strong, h, nbins

    # The autocorrelation peak may sit at the full period, at twice it, or (for a
    # clock in beat, where tick and tock are evenly spaced) at half of it. The
    # full period is the shortest candidate whose fold shows exactly two beats
    # roughly half a period apart.
    period = float(L)
    for cand in (L / 2, L, 2 * L):
        strong, h, nbins = fold_peaks(cand)
        if len(strong) == 2:
            sep = abs(strong[1] - strong[0]) / nbins
            if 0.25 <= sep <= 0.75:
                period = cand
                break
    strong, h, nbins = fold_peaks(period)
    strong = sorted(strong, key=lambda p: -h[p])[:2]
    phases = sorted(p / nbins * period * HOP for p in strong)
    return period * HOP, phases[0], phases[-1], conf


def track(env, P, ph0, ph1, n_samples, win_s=0.060):
    """
    Visits beat slots in time order and picks the strongest envelope peak in a
    window around each predicted beat. Predictions come from a running fit of
    the beats found so far, so they follow the clock across the whole file.
    """
    W = int(win_s * SR)
    # Noise is judged locally (median over the preceding 1.5 s, decimated for
    # speed), so beats buried in a noisy stretch are skipped, not guessed.
    dec = env[::44]
    beats = []                                     # (k, parity, sample)
    model = [ph0, ph1, P]
    k = 0
    while True:
        for parity in (0, 1):
            c = int(model[parity] + k * model[2])
            if c + W >= n_samples:
                beats.sort(key=lambda b: b[2])
                return beats
            if c - W < 0:
                continue
            seg = env[c - W:c + W]
            rel = (np.arange(len(seg)) - W) / W
            j = int(np.argmax(seg * (1 - 0.5 * rel * rel)))
            lo = max(0, (c - int(1.5 * SR)) // 44)
            floor = np.median(dec[lo:max(lo + 10, (c - W) // 44)])
            if seg[j] > 3.5 * floor:
                beats.append((k, parity, c - W + j))
        if len(beats) >= 8:
            kk = np.array([b[0] for b in beats]); pp = np.array([b[1] for b in beats])
            tt = np.array([b[2] for b in beats], float)
            if (pp == 0).sum() >= 2 and (pp == 1).sum() >= 2:
                model = list(fit(kk, pp, tt)[0])
        k += 1


def fit(k, par, t):
    A = np.column_stack([par == 0, par == 1, k]).astype(float)
    keep = np.ones(len(t), bool)
    for _ in range(3):
        sol, *_ = np.linalg.lstsq(A[keep], t[keep], rcond=None)
        r = t - A @ sol
        mad = np.median(np.abs(r[keep] - np.median(r[keep]))) * 1.4826
        keep = np.abs(r) <= max(5 * mad, 0.002 * SR)
    sol, *_ = np.linalg.lstsq(A[keep], t[keep], rcond=None)
    r = t - A @ sol
    return sol, r, keep


def onsets(env_f, peaks, frac=0.2):
    out = []
    for p in peaks:
        a, b = p - int(0.03 * SR), p + int(0.01 * SR)
        seg = env_f[max(a, 0):b]
        a = max(a, 0)
        lo_n, hi_n = max(0, p - int(0.2 * SR)), max(1, p - int(0.05 * SR))
        noise = np.median(env_f[lo_n:hi_n])
        thr = noise + frac * (seg.max() - noise)
        i = max(1, int(np.argmax(seg > thr)))
        v0, v1 = seg[i - 1], seg[i]
        out.append(a + i - 1 + (thr - v0) / (v1 - v0 + 1e-12))
    return np.array(out)


def xcorr_refine(times, y, par, pre_ms=1, post_ms=6, search_ms=3, iters=3):
    pre, post = int(pre_ms * SR / 1000), int(post_ms * SR / 1000)
    srch = int(search_ms * SR / 1000)
    t = times.copy()
    for _ in range(iters):
        new = t.copy()
        for p in (0, 1):
            idx = np.where(par == p)[0]
            segs = []
            for i in idx:
                c = int(round(t[i]))
                s = y[c - pre:c + post]
                if len(s) == pre + post:
                    segs.append(s / (np.linalg.norm(s) + 1e-9))
            if not segs:
                continue
            tpl = np.mean(segs, axis=0)
            for i in idx:
                c = int(round(t[i]))
                w = y[c - pre - srch:c + post + srch]
                if len(w) != pre + post + 2 * srch:
                    continue
                cc = np.correlate(w, tpl, mode="valid")
                j = int(np.argmax(cc))
                d = 0.0
                if 0 < j < len(cc) - 1:
                    y0, y1, y2 = cc[j - 1], cc[j], cc[j + 1]
                    den = y0 - 2 * y1 + y2
                    d = 0.5 * (y0 - y2) / den if den != 0 else 0.0
                new[i] = c - srch + j + d
        t = new
        srch = max(int(0.5 * SR / 1000), srch // 2)
    return t


def analyze(path):
    x = load(path)
    y = bandpass(x)
    env2 = envelope(y, int(0.002 * SR))
    env_hop = envelope(y, HOP)[::HOP]
    P, ph0, ph1, conf = find_period(env_hop)
    beats = track(env2, P, ph0, ph1, len(x))
    # Second pass: predict from the first fit, so each beat type uses its own offset.
    k = np.array([b[0] for b in beats]); par = np.array([b[1] for b in beats])
    t = np.array([b[2] for b in beats], float)
    sol, _, _ = fit(k, par, t)
    beats = track(env2, sol[2], sol[0], sol[1], len(x), win_s=0.040)
    k = np.array([b[0] for b in beats]); par = np.array([b[1] for b in beats])
    pk = np.array([b[2] for b in beats])
    env_f = envelope(y, int(0.00025 * SR))
    t = xcorr_refine(onsets(env_f, pk), y, par)
    sol, r, keep = fit(k, par, t)
    period = sol[2]
    expected = int(np.floor((len(x) - sol[0]) / period)) + int(np.floor((len(x) - sol[1]) / period))
    to_us = 1e6 / SR
    return {
        "durationSeconds": round(len(x) / SR, 3),
        "periodMicros": round(period * to_us, 2),
        # Gap from beat type 0 to the following beat type 1, minus half the period.
        "imbalanceMicros": round(((sol[1] - sol[0]) - period / 2) * to_us, 1),
        "beatsFound": int(len(t)),
        "beatsInlier": int(keep.sum()),
        "beatsExpected": expected,
        "residualRmsMicros": round(float(r[keep].std()) * to_us, 1),
        "lockConfidence": round(conf, 3),
        "phase0Samples": round(float(sol[0]), 1),
        "phase1Samples": round(float(sol[1]), 1),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.expanduser("~/Desktop/grandfather-clock"))
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--out", default=os.path.join(here, "eval", "references.json"))
    ap.add_argument("wavs", nargs="*")
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    wavs = a.wavs
    if not wavs:
        wavs = []
        for path in sorted(os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs
                           if f.endswith(".wav") and f not in SKIP_NAMES):
            # The analysis holds a whole file in memory several times over, so
            # recordings of hours are left out of the scan.
            if os.path.getsize(path) > MAX_SCAN_BYTES:
                print("%-50s skipped: longer than the scan handles" % os.path.relpath(path, root), file=sys.stderr)
                continue
            wavs.append(path)
    refs = {}
    if os.path.exists(a.out):
        with open(a.out) as fh:
            refs = json.load(fh)
    for w in wavs:
        rel = os.path.relpath(os.path.abspath(w), root)
        try:
            res = analyze(w)
        except Exception as e:       # one bad file must not stop the batch
            print("%-50s FAILED: %s" % (rel, e), file=sys.stderr)
            continue
        refs[rel] = res
        print("%-50s P=%.2f µs  imb=%+.1f ms  beats %d/%d  rms %.1f ms  conf %.2f" % (
            rel, res["periodMicros"], res["imbalanceMicros"] / 1000, res["beatsInlier"],
            res["beatsExpected"], res["residualRmsMicros"] / 1000, res["lockConfidence"]))
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w") as fh:
        json.dump(dict(sorted(refs.items())), fh, indent=2)
        fh.write("\n")


if __name__ == "__main__":
    main()
