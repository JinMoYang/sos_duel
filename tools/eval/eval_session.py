"""Scores one lap session: confirmed cars per frame by method (trend removed), round wins, the
support tiers (false-detection proxy) and frame timing.

usage: python eval_session.py SESSION_DIR [--kind par_main|addl|addl_every] [--key-only]
  SESSION_DIR  a folder with the session's lapsession_*.csv and its run_* folders, as pulled from
               /sdcard/Documents/sos (each run needs frames.csv, dets.jsonl, manifest.json;
               proposed / addl runs also candidate_dets.jsonl)
  --kind       which run names to read (sos_eval.PRESETS); default par_main (the Main buttons)
  --key-only   also score the proposed method's image 0 alone (the key exposure at the curve's
               setting, no additional exposures) as its own row: the curve's choice by itself
"""
import argparse
import csv
import statistics as st

import sos_eval as E


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session")
    ap.add_argument("--kind", default="par_main", choices=sorted(E.PRESETS))
    ap.add_argument("--key-only", action="store_true")
    a = ap.parse_args()
    names = E.PRESETS[a.kind]
    runs = E.load(a.session, names)
    if not runs:
        raise SystemExit(f"no {a.kind} runs under {a.session}")
    by_bin = E.pool(runs)
    t0 = min(r["t"] for r in runs)
    rows, tiers = [], {}
    print(f"{a.session}: {len(runs)} runs\n")
    print(f"{'min':>5}  {'method':18s} {'confirmed':>9} {'sup 1-2':>8} {'sup 0':>6}  frames")
    for ri, run in sorted(enumerate(runs), key=lambda x: x[1]["t"]):
        c, w, z, n = E.score(runs, by_bin, ri)
        t = (run["t"] - t0) / 60000
        rows.append((run["method"], t, c))
        tiers.setdefault(run["method"], []).append((c, w, z))
        print(f"{t:5.1f}  {run['method']:18s} {c:9.2f} {w:8.2f} {z:6.2f}  {n}")
        if a.key_only and run["method"] == "proposed":
            rows.append(("key only", t, E.score(runs, by_bin, ri, view="base")[0]))
    methods = [m for _, m in names] + (["key only"] if a.key_only else [])
    methods = [m for m in methods if any(r[0] == m for r in rows)]
    level, se = E.trend_removed(rows, methods)
    print(f"\nconfirmed cars / frame, quadratic session-time trend removed (se of a difference {se:.2f}):")
    for m, v in sorted(level.items(), key=lambda x: -x[1]):
        print(f"  {m:18s} {v:5.2f}")
    print("\nround wins (row beats column):")
    plain = [r for r in rows if r[0] != "key only"]
    wins = E.round_wins(plain, [m for m in methods if m != "key only"])
    for m in methods:
        if m == "key only":
            continue
        print(f"  {m:18s} " + "  ".join(f"{b[:10]} {w}/{n}" for (x, b), (w, n) in wins.items() if x == m))
    print("\nper frame, mean over runs: confirmed / support 1-2 / support 0 (no other run saw it)")
    for m in [m for m in methods if m in tiers]:
        v = tiers[m]
        print(f"  {m:18s} {E.mean([x[0] for x in v]):5.2f} / {E.mean([x[1] for x in v]):4.2f} / {E.mean([x[2] for x in v]):4.2f}")
    print("\nframe timing (from frames.csv): output interval mean / 95th percentile, exposure end -> logged")
    timing = {}
    for run in runs:
        fr = list(csv.DictReader(open(f"{run['dir']}/frames.csv")))
        ts = [int(r["ts"]) for r in fr]
        iv = sorted((b - x) / 1e6 for x, b in zip(ts, ts[1:]))
        lat = [(int(r["t_logged_ns"]) - int(r["t_last_exposure_end_ns"])) / 1e6 for r in fr
               if r.get("t_logged_ns") and r.get("t_last_exposure_end_ns")]
        if iv:
            timing.setdefault(run["method"], []).append((st.mean(iv), iv[int(0.95 * (len(iv) - 1))],
                                                         st.median(lat) if lat else float("nan")))
    for m in [m for m in methods if m in timing]:
        v = timing[m]
        print(f"  {m:18s} {E.mean([x[0] for x in v]):5.0f} / {E.mean([x[1] for x in v]):4.0f} ms, "
              f"{E.mean([x[2] for x in v]):4.0f} ms")


if __name__ == "__main__":
    main()
