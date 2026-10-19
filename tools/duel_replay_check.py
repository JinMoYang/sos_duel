"""Replay check for a Duel run (spec 5.6): recompute every decision from the run's own
records and compare with what the app logged.

    python duel_replay_check.py <run_dir> [<run_dir> ...]

Reads manifest.json, decisions.jsonl, candidate_dets.jsonl and dets.jsonl. Recomputed per
frame: the plan (reason, neighbour direction; Thompson picks from the logged draws), the base
setting, the per-image outputs and weak traces, discord (fused boxes moved by the logged
to_nb shifts), the votes, the move and line search, and the next ISO (from the logged
clip_by_G, which is RAW arithmetic the JPEGs cannot redo). Fusion itself is not redone:
the kept boxes are taken from dets.jsonl. No dependencies beyond the standard library.
"""
import json
import math
import sys
from collections import Counter, defaultdict

VEH = {1, 2, 3, 5, 7}
OUT_THR, WEAK_LO, TAU, CONF_MOVE, SEEN_IOU = 0.25, 0.05, 0.7, 0.95, 0.3
ISOS = [100, 200, 400, 800, 1600]
SHUTTERS = [4167, 8333, 16667, 33333, 66667]
DIRS = {"short": -1, "long": +1}


def iou(a, b):
    x1, y1, x2, y2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def nms(dets, thr=0.3):
    keep = []
    for c, b in sorted(dets, key=lambda d: -d[0]):
        if all(iou(b, kb) < thr for _, kb in keep):
            keep.append((c, b))
    return keep


def per_image(raw):
    v = [(c, b) for c, b, k in raw if k in VEH]
    low = nms([d for d in v if d[0] >= WEAK_LO])
    out = nms([d for d in v if d[0] >= OUT_THR])
    weak = [(c, b) for c, b in low if c < OUT_THR and all(iou(b, ob) < 0.3 for _, ob in out)]
    return out, low, weak


def p_better(w, l):
    n = w + l + 1
    return sum(math.comb(n, k) for k in range(w + 1)) / 2 ** n


def load_jsonl(path):
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def check(run):
    man = json.load(open(f"{run}/manifest.json", encoding="utf-8"))
    mp = man.get("method_params", {})
    every = mp.get("mode") == "duel_every"
    g = ISOS.index(mp["start"]["iso"])
    s = SHUTTERS.index(mp["start"]["shutter_us"])
    dec = sorted(load_jsonl(f"{run}/decisions.jsonl"), key=lambda r: r["frame"])
    cand = defaultdict(dict)
    for r in load_jsonl(f"{run}/candidate_dets.jsonl"):
        cand[r["frame"]][r["role"]] = [(d["conf"], d["xyxy"], d["class"]) for d in r["dets"]]
    dets = {r["frame"]: r for r in load_jsonl(f"{run}/dets.jsonl")}

    w = {"short": 0, "long": 0}
    l = {"short": 0, "long": 0}
    line, prev_trace = None, False
    bad = Counter()
    examples = defaultdict(list)

    def mismatch(field, frame, got, want):
        bad[field] += 1
        if len(examples[field]) < 3:
            examples[field].append((frame, got, want))

    moves = 0
    for r in dec:
        f = r["frame"]
        # ---- plan
        dirs = [d for d, st in DIRS.items() if 0 <= s + st < len(SHUTTERS)]
        draws = r["plan"]["thompson_draws"]
        if line in dirs:
            want = ("line", line)
        elif every or prev_trace:
            reason = "always" if every else "trace"
            pick = max(dirs, key=lambda d: draws[d]) if draws else None
            if draws is None or sorted(draws) != sorted(dirs):
                mismatch("thompson_dirs", f, draws and sorted(draws), dirs)
            want = (reason, pick)
        else:
            want = ("none", None)
        got = (r["plan"]["reason"], r["plan"]["nb_dir"])
        if got != want:
            mismatch("plan", f, got, want)
        nb = r["plan"]["nb_dir"]
        # ---- base setting
        if r["base"]["g_req"] != ISOS[g] or r["base"]["s_req"] != SHUTTERS[s]:
            mismatch("base", f, (r["base"]["g_req"], r["base"]["s_req"]), (ISOS[g], SHUTTERS[s]))
        # ---- per image
        b_out, _, b_weak = per_image(cand[f].get("base", []))
        trace = bool(b_weak)
        if trace != r["trace"]:
            mismatch("trace", f, r["trace"], trace)
        if len(b_out) != r["n_cars"]:
            mismatch("n_cars", f, r["n_cars"], len(b_out))
        # ---- discord, votes, move, line
        if nb is not None:
            n_out, _, _ = per_image(cand[f].get("neighbor", []))
            wt = lt = 0
            for k in dets[f]["dets"]:
                b = k["xyxy"]
                sh = k.get("to_nb", [0.0, 0.0, 0])
                bn = [b[0] + sh[0], b[1] + sh[1], b[2] + sh[0], b[3] + sh[1]]
                hb = any(iou(b, o) >= SEEN_IOU for _, o in b_out)
                hn = any(iou(bn, o) >= SEEN_IOU for _, o in n_out)
                wt += hn and not hb
                lt += hb and not hn
            logged = r["discord"]
            if logged is None or (logged["w_t"], logged["l_t"]) != (wt, lt):
                mismatch("discord", f, logged, (wt, lt))
            w[nb] += wt
            l[nb] += lt
            moved = None
            if p_better(w[nb], l[nb]) >= CONF_MOVE:
                s += DIRS[nb]
                w = {"short": 0, "long": 0}
                l = {"short": 0, "long": 0}
                line = nb
                moved = SHUTTERS[s]
                moves += 1
            elif line and lt > wt:
                line = None
            got_mv = r["move"]["to_s"] if r["move"] else None
            if got_mv != moved:
                mismatch("move", f, got_mv, moved)
        votes = {d: [w[d], l[d]] for d in DIRS}
        if r["votes_after"] != votes:
            mismatch("votes", f, r["votes_after"], votes)
        if r["line_after"] != line:
            mismatch("line", f, r["line_after"], line)
        prev_trace = trace
        # ---- next ISO (decided from this frame, logged on the next frame's row)
        nxt = next((x for x in dec if x["frame"] > f), None)
        if nxt is not None and nxt["gain"].get("from_frame") == f:
            clip = nxt["gain"]["clip_by_G"]
            if clip is None:
                want_g = g
            else:
                ok = [i for i, iso in enumerate(ISOS) if clip[str(iso)] < TAU]
                want_g = max(ok) if ok else 0
            if nxt["gain"]["chosen"] != ISOS[want_g]:
                mismatch("gain", f, nxt["gain"]["chosen"], ISOS[want_g])
            g = want_g
        nothing = not b_out and not b_weak
        if nothing != r["nothing"]:
            mismatch("nothing", f, r["nothing"], nothing)

    print(f"{run}: {len(dec)} frames, mode {mp.get('mode')}, {moves} moves, "
          f"{'all decisions match' if not bad else 'MISMATCHES ' + dict(bad).__repr__()}")
    for field, ex in examples.items():
        for frame, got, want in ex:
            print(f"   {field} frame {frame}: app {got} vs replay {want}")
    return not bad


if __name__ == "__main__":
    ok = all([check(run) for run in sys.argv[1:]])
    sys.exit(0 if ok else 1)
