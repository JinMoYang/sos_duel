"""Replay check for a Duel run (spec 5.6): recompute every decision from the run's own
records and compare with what the app logged.

    python duel_replay_check.py <run_dir> [<run_dir> ...]

Reads manifest.json, decisions.jsonl, candidate_dets.jsonl and dets.jsonl. Recomputed per
frame: the plan (reason, neighbour direction; Thompson picks from the logged draws), the base
setting, the per-image outputs and weak traces, discord (fused boxes moved by the logged
to_nb shifts), the votes, and the next base from the logged How-to-Sense E values and
threshold (E itself needs the base picture before JPEG, so the JVM test checks it against
the study instead). Fusion itself is not redone: the kept boxes are taken from dets.jsonl.
No dependencies beyond the standard library.
"""
import json
import sys
from collections import Counter, defaultdict

VEH = {1, 2, 3, 5, 7}
OUT_THR, WEAK_LO, SEEN_IOU = 0.25, 0.05, 0.3
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


def load_jsonl(path):
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def check(run):
    man = json.load(open(f"{run}/manifest.json", encoding="utf-8"))
    mp = man.get("method_params", {})
    every = str(mp.get("mode", "")).endswith("_every")
    # duel2s / duel2t (2026-10-28): no Thompson; the extra cell is the previous frame's
    # extra_next (the curve's best away from the base shutter), else the shutter neighbour.
    new_rule = mp.get("base_mode") in ("single", "track")
    extra_next = None
    g = ISOS.index(mp["start"]["iso"])
    s = SHUTTERS.index(mp["start"]["shutter_us"])
    dec = sorted(load_jsonl(f"{run}/decisions.jsonl"), key=lambda r: r["frame"])
    cand = defaultdict(dict)
    for r in load_jsonl(f"{run}/candidate_dets.jsonl"):
        cand[r["frame"]][r["role"]] = [(d["conf"], d["xyxy"], d["class"]) for d in r["dets"]]
    dets = {r["frame"]: r for r in load_jsonl(f"{run}/dets.jsonl")}

    w = {"short": 0, "long": 0}
    l = {"short": 0, "long": 0}
    prev_trace = False
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
        if new_rule:
            reason = "always" if every else ("trace" if prev_trace else "none")
            want = (reason, None)
            if reason != "none":
                ex = extra_next if extra_next is not None and extra_next % 5 != s else g * 5 + (s + 1 if s + 1 < 5 else s - 1)
                if r["plan"].get("extra_cell") != ex:
                    mismatch("extra_cell", f, r["plan"].get("extra_cell"), ex)
            elif r["plan"].get("extra_cell") is not None:
                mismatch("extra_cell", f, r["plan"].get("extra_cell"), None)
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
        # ---- discord, votes
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
        votes = {d: [w[d], l[d]] for d in DIRS}
        if r["votes_after"] != votes:
            mismatch("votes", f, r["votes_after"], votes)
        prev_trace = trace
        # ---- next base: argmax E (first of equals), kept unless the gain beats the threshold
        bc = r["base_choice"]
        if "e" in bc:
            e, cur = bc["e"], g * 5 + s
            best = max(range(25), key=lambda c: (e[c], -c))
            nxt_c = best if best != cur and e[best] - e[cur] > bc["threshold"] else cur
            g, s = nxt_c // 5, nxt_c % 5
            moves += nxt_c != cur
        got_next = (r["next_base"]["iso"], r["next_base"]["shutter_us"])
        if got_next != (ISOS[g], SHUTTERS[s]):
            mismatch("next_base", f, got_next, (ISOS[g], SHUTTERS[s]))
        if new_rule:
            xn = bc.get("extra_next", {})
            if "e" in xn:
                want_x = max((c for c in range(25) if c % 5 != s), key=lambda c: (xn["e"][c], -c))
                if xn.get("cell") != want_x:
                    mismatch("extra_next", f, xn.get("cell"), want_x)
                extra_next = xn.get("cell")
            else:
                extra_next = None
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
