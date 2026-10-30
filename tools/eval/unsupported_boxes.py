"""The proposed method's boxes no other run saw: how many of its images saw each (n_pic), and a
replay of an output rule that keeps only boxes seen by >= 2 images (other runs unchanged).
The replay needs no new capture: it only removes boxes from the logged output.

usage: python unsupported_boxes.py SESSION_DIR   (a par_main session, see eval_session.py)
"""
import collections
import json
import sys

import sos_eval as E


def main(root):
    names = E.PRESETS["par_main"]
    runs = E.load(root, names)
    key = lambda b: tuple(round(v, 1) for v in b)
    npic = {}
    for ri, run in enumerate(runs):
        if run["method"] != "proposed":
            continue
        for line in open(f"{run['dir']}/dets.jsonl"):
            j = json.loads(line)
            for x in j["dets"]:
                npic[(ri, j["frame"], key(x["xyxy"]))] = x.get("n_pic", 1)

    def tally(keep):
        by_bin = collections.defaultdict(list)
        for ri, run in enumerate(runs):
            for r in run["recs"]:
                for b in r["views"]["out"]:
                    if keep(ri, r["frame"], b):
                        by_bin[r["bin"]].append((ri, b))
        per = collections.defaultdict(lambda: collections.Counter())
        tiers = collections.Counter()
        frames = collections.Counter()
        for ri, run in enumerate(runs):
            for r in run["recs"]:
                if r["lap"] < 1:
                    continue
                frames[run["method"]] += 1
                for b in r["views"]["out"]:
                    if not keep(ri, r["frame"], b):
                        continue
                    k = len(E.support(by_bin, ri, r, b))
                    t = "confirmed" if k >= E.K else "support 1-2" if k else "support 0"
                    per[run["method"]][t] += 1
                    if run["method"] == "proposed":
                        tiers[(t, npic.get((ri, r["frame"], key(b)), 0))] += 1
        return {m: {t: c / frames[m] for t, c in v.items()} for m, v in per.items()}, tiers

    base, tiers = tally(lambda ri, f, b: True)
    print(f"{root}\nproposed boxes by support x images that saw them (n_pic):")
    for t in ("confirmed", "support 1-2", "support 0"):
        row = {k: v for (tt, k), v in tiers.items() if tt == t}
        tot = sum(row.values())
        if tot:
            print(f"  {t:12s} {tot:5d}  " + "  ".join(f"n_pic {k}: {v / tot:.0%}" for k, v in sorted(row.items())))
    alt, _ = tally(lambda ri, f, b: runs[ri]["method"] != "proposed" or npic.get((ri, f, key(b)), 1) >= 2)
    print("replay: proposed outputs only boxes seen by >= 2 images; per frame")
    for m in ("proposed", "Sense-well", "fixed 1600/240"):
        if m in base:
            print(f"  {m:15s} confirmed {base[m].get('confirmed', 0):.2f} -> {alt[m].get('confirmed', 0):.2f}"
                  f"   support 0 {base[m].get('support 0', 0):.2f} -> {alt[m].get('support 0', 0):.2f}")


if __name__ == "__main__":
    main(sys.argv[1])
