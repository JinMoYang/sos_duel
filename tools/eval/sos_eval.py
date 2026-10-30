"""Label-free scoring of a lap session (the runs one press of a Main / Check button records).

Per frame, each run's OUTPUT vehicle boxes (COCO classes 1, 2, 3, 5, 7):
  - proposed / addl_* runs: the fused clusters in dets.jsonl ("out" flag, else evidence >= .25);
  - baselines: their dets.jsonl line, conf >= .25, NMS .3.
A box is CONFIRMED when >= K = 3 other runs of the session (any method) also output a box at
the same track position: lap phase within +-1 of 60 bins (~0.2 s at 13 s a lap) and IoU >= .3.
The cars are parked and every run passes each spot several times, so a real car recurs; a
false alarm does not. A box no other run saw (support 0) is the false-detection proxy.
Lap phase comes from the lap session's line crossings (elapsed ns, the sensor clock); only
settled laps count (run_lap >= 1: the first lap of a run is start-up).

Methods are compared per frame with a quadratic trend in session time removed (light,
battery and Limo speed drift over a session), plus round wins (each round runs every method
once, in a rotated order).
"""
import bisect
import collections
import csv
import glob
import json
import os
import statistics as st

import numpy as np

VEH = {1, 2, 3, 5, 7}
NB = 60
K = 3

# Run-name substrings -> method labels, per session kind. The first match wins, so a name that
# contains another (addl_3_merge_every contains addl_3_merge) comes first.
PRESETS = {
    "par_main": [("addl_3_sep_every", "proposed"), ("par_ae_phone", "AE-Phone"),
                 ("par_proposed_v2_p5", "Sense-well"), ("par_physsweep_fast", "PhysSweep fast"),
                 ("par_shin_nm", "ShinNM"), ("par_nae", "Neural-AE"),
                 ("par_fixed_1600_240", "fixed 1600/240")],
    "addl": [("addl_0", "key only"), ("addl_1_merge", "+1 merged"), ("addl_1_sep", "+1 separate"),
             ("addl_3_merge", "+3 merged"), ("addl_3_sep", "+3 separate"),
             ("addl_5_merge", "+5 merged"), ("addl_5_sep", "+5 separate")],
    "addl_every": [("addl_3_merge_every", "+3 merged, every"), ("addl_3_sep_every", "+3 separate, every"),
                   ("addl_3_merge", "+3 merged, trace"), ("addl_3_sep", "+3 separate, trace")],
}


def iou(a, b):
    x1, y1, x2, y2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def nms(d, thr=0.3):
    keep = []
    for c, b in sorted(d, key=lambda x: -x[0]):
        if all(iou(b, kb) < thr for _, kb in keep):
            keep.append((c, b))
    return keep


def method_of(name, names):
    for k, v in names:
        if k in name:
            return v
    return None


def is_fused(name):
    """Runs whose dets.jsonl holds fused clusters (the proposed method and its checks)."""
    return "addl_" in name


def load(root, names):
    """Every run of the session under [root] whose name matches [names]: per frame its lap,
    lap-phase bin and views ("out" = output boxes; fused runs also "base" = image 0 alone)."""
    ev = list(csv.DictReader(open(glob.glob(f"{root}/lapsession_*.csv")[0])))
    cross = sorted(set([int(e["elapsed_ns"]) for e in ev if e["event"] == "lap"] +
                       [int(e["elapsed_ns"]) for e in ev if e["event"] == "block_start"][:1]))
    runs = []
    for d in sorted(glob.glob(f"{root}/run_*")):
        name = os.path.basename(d)
        method = method_of(name, names)
        if method is None:
            continue
        frames = {int(r["idx"]): r for r in csv.DictReader(open(f"{d}/frames.csv"))}
        views = collections.defaultdict(dict)
        for line in open(f"{d}/dets.jsonl"):
            j = json.loads(line)
            if is_fused(name):
                k = [x for x in j["dets"] if int(x["class"]) in VEH]
                views["out"][j["frame"]] = [x["xyxy"] for x in k if x.get("out", x["evidence"] >= 0.25)]
            else:
                if j.get("method") == "cand":
                    continue
                v = [(x["conf"], x["xyxy"]) for x in j["dets"] if int(x["cls"]) in VEH and x["conf"] >= 0.25]
                views["out"][j["frame"]] = [b for _, b in nms(v)]
        if is_fused(name):
            for line in open(f"{d}/candidate_dets.jsonl"):
                j = json.loads(line)
                if j["role"] != "base":
                    continue
                v = [(x["conf"], x["xyxy"]) for x in j["dets"] if int(x["class"]) in VEH and x["conf"] >= 0.25]
                views["base"][j["frame"]] = [b for _, b in nms(v)]
        recs = []
        for f, r in sorted(frames.items()):
            ts = int(r["ts"])
            i = bisect.bisect_right(cross, ts) - 1
            if i < 0 or i + 1 >= len(cross):
                continue
            phase = (ts - cross[i]) / (cross[i + 1] - cross[i])
            recs.append({"frame": f, "lap": int(r["run_lap"]) if r.get("run_lap") not in (None, "") else -9,
                         "bin": int(phase * NB) % NB, "views": {k: v.get(f, []) for k, v in views.items()}})
        runs.append({"name": name, "dir": d, "method": method, "recs": recs, "t": int(name.split("_")[-1])})
    return runs


def pool(runs):
    """Every run's output boxes by lap-phase bin: what confirmation looks up."""
    by_bin = collections.defaultdict(list)
    for ri, run in enumerate(runs):
        for r in run["recs"]:
            for b in r["views"]["out"]:
                by_bin[r["bin"]].append((ri, b))
    return by_bin


def support(by_bin, ri, rec, box):
    """The other runs that output a box at this spot."""
    s = set()
    for db in (-1, 0, 1):
        for rj, ob in by_bin[(rec["bin"] + db) % NB]:
            if rj != ri and iou(box, ob) >= 0.3:
                s.add(rj)
    return s


def score(runs, by_bin, ri, view="out"):
    """Per settled frame: confirmed boxes, boxes with support 1-2, boxes with support 0."""
    conf = weak = none = n = 0
    for r in runs[ri]["recs"]:
        if r["lap"] < 1 or view not in r["views"]:
            continue
        n += 1
        for b in r["views"][view]:
            k = len(support(by_bin, ri, r, b))
            if k >= K:
                conf += 1
            elif k:
                weak += 1
            else:
                none += 1
    return (conf / n, weak / n, none / n, n) if n else (float("nan"),) * 3 + (0,)


def trend_removed(rows, methods):
    """Per-method level with a quadratic trend in session time removed; rows: (method, t_min,
    value). Returns ({method: level}, standard error of a difference between two methods)."""
    X = np.array([[1.0 if m == mm else 0.0 for mm in methods] + [t, t * t] for m, t, _ in rows])
    y = np.array([v for _, _, v in rows])
    beta, *_ = np.linalg.lstsq(X, y, rcond=None)
    resid = y - X @ beta
    dof = max(len(y) - X.shape[1], 1)
    se = float(np.sqrt((resid ** 2).sum() / dof)) * np.sqrt(2 / 3)
    return dict(zip(methods, beta[:len(methods)])), se


def round_wins(rows, methods):
    """rows in session order, one round = len(methods) runs: {(a, b): rounds a beat b}."""
    k = len(methods)
    rounds = [rows[i:i + k] for i in range(0, len(rows), k)]
    wins = {}
    for a in methods:
        for b in methods:
            if a == b:
                continue
            w = 0
            for rd in rounds:
                va = [v for m, _, v in rd if m == a]
                vb = [v for m, _, v in rd if m == b]
                if va and vb and va[0] > vb[0]:
                    w += 1
            wins[(a, b)] = (w, len(rounds))
    return wins


def mean(v):
    return st.mean(v) if v else float("nan")
