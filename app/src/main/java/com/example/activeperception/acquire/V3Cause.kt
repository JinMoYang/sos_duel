package com.example.activeperception.acquire

/**
 * proposed_v3cause decision rules: a line-for-line port of the device spec's Appendix A
 * (Python, no dependencies). Pure Kotlin with no Android types, so the JVM unit tests and an
 * off-device replay run exactly the code the phone runs.
 *
 * Boxes are [x0, y0, x1, y1] in detector-input pixels; one detection is (conf, box).
 * Ties keep the input order, as Python's stable sort does.
 */
object V3Cause {
    /** COCO: bicycle, car, motorcycle, bus, truck. */
    val VEH = setOf(1, 2, 3, 5, 7)
    const val OUT_THR = 0.25
    const val WEAK_LO = 0.05
    const val PLACE_IOU = 0.3
    const val TWO_SHOT_S = 0.20
    const val FLICKER_WINDOW = 10
    const val HYST_WINDOW = 10
    const val SAT_PLACE = 0.5
    const val BLUR_RATIO = 0.03
    const val BASE_SAT = 0.1
    /** Cheapest first: the order that breaks equal place counts. */
    val ORDER = listOf("digital", "short_sat", "short_blur", "long")

    class D(val conf: Double, val box: DoubleArray, val cls: Int = -1)

    fun iou(a: DoubleArray, b: DoubleArray): Double {
        val x1 = maxOf(a[0], b[0]); val y1 = maxOf(a[1], b[1])
        val x2 = minOf(a[2], b[2]); val y2 = minOf(a[3], b[3])
        if (x2 <= x1 || y2 <= y1) return 0.0
        val inter = (x2 - x1) * (y2 - y1)
        return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)
    }

    /** Fraction of a's area inside b. */
    fun contain(a: DoubleArray, b: DoubleArray): Double {
        val ix = maxOf(0.0, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0.0, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val aa = (a[2] - a[0]) * (a[3] - a[1])
        return if (aa > 0) ix * iy / aa else 0.0
    }

    /** Highest conf first; drop anything overlapping a kept box at IoU >= thr. */
    fun nms(dets: List<D>, thr: Double = 0.3): List<D> {
        val keep = ArrayList<D>()
        for (d in dets.sortedByDescending { it.conf }) {
            if (keep.all { iou(d.box, it.box) < thr }) keep.add(d)
        }
        return keep
    }

    class PerImage(val out: List<D>, val low: List<D>, val weak: List<D>)

    /** Detector output (conf >= 0.05 kept upstream or here) -> (outputs, low incl., weak). */
    fun perImage(raw: List<D>): PerImage {
        val v = raw.filter { it.cls in VEH }
        val low = nms(v.filter { it.conf >= WEAK_LO })
        val out = nms(v.filter { it.conf >= OUT_THR })
        val weak = low.filter { d -> d.conf < OUT_THR && out.all { iou(d.box, it.box) < PLACE_IOU } }
        return PerImage(out, low, weak)
    }

    /** One image entering fusion. [sat] = saturated fraction inside a box in THIS image. */
    class FuseImage(val out: List<D>, val low: List<D>, val sat: (DoubleArray) -> Double)

    /** One cluster with everything fusion decided about it, for the dets.jsonl record. */
    class Cluster(val box: DoubleArray, val peak: Double, val cls: Int) {
        var r: DoubleArray = DoubleArray(0)
        var w: DoubleArray = DoubleArray(0)
        var evidence = 0.0
        var nPic = 0
        /** "kept" | "nms" | "contained" */
        var status = ""
    }

    /** Clusters in the order fusion ranked them; the kept ones (status "kept") are the
     *  output, in output order. */
    fun fuse(images: List<FuseImage>): List<Cluster> {
        data class B(val c: Double, val box: DoubleArray, val cls: Int)
        val allb = images.flatMap { im -> im.out.map { B(it.conf, it.box, it.cls) } }
            .sortedByDescending { it.c }
        val cl = ArrayList<Cluster>()
        for (b in allb) {
            if (cl.none { iou(it.box, b.box) >= 0.5 }) cl.add(Cluster(b.box, b.c, b.cls))
        }
        if (cl.isEmpty()) return emptyList()
        val n = images.size
        val nmax = maxOf(1, pyRound(n / 5.0))
        for (g in cl) {
            g.r = DoubleArray(n) { a ->
                images[a].low.filter { iou(g.box, it.box) >= 0.5 }.maxOfOrNull { it.conf } ?: 0.0
            }
            g.w = DoubleArray(n) { a -> 1.0 - images[a].sat(g.box) }
            val sw = g.w.sum()
            var s = 0.0
            for (a in 0 until n) s += g.w[a] * g.r[a]
            g.evidence = s / maxOf(sw, 1e-9)
            g.nPic = g.r.count { it >= OUT_THR }
        }
        val ranked = cl.sortedWith(compareBy<Cluster>({ -it.evidence }, { -it.peak }))
        val keep = ArrayList<Cluster>()
        for (g in ranked) {
            if (keep.all { iou(g.box, it.box) < 0.3 }) keep.add(g) else g.status = "nms"
        }
        val out = ArrayList<Cluster>()
        for (g in keep) {
            if (g.nPic <= nmax && out.any { contain(g.box, it.box) >= 0.5 }) {
                g.status = "contained"; continue
            }
            g.status = "kept"; out.add(g)
        }
        return ranked
    }

    /** Python's round(): half to even. */
    fun pyRound(x: Double): Int = Math.rint(x).toInt()

    /** Reward for a delta: extra-image outputs not overlapping any base output, each weighted
     *  by 1 - its saturated fraction in that extra image. */
    fun addedBoxes(extraOut: List<D>, baseOut: List<D>, satExtra: (DoubleArray) -> Double): Double =
        extraOut.filter { d -> baseOut.all { iou(d.box, it.box) < 0.3 } }
            .sumOf { 1.0 - satExtra(it.box) }

    /** n[d], s[d] for d = 1..5 (index 0 unused). Deterministic. */
    fun pickDelta(n: IntArray, s: DoubleArray): Pair<Int, String> {
        for (d in 1..5) if (n[d] == 0) return d to "sweep"
        var best = 1
        for (d in 2..5) if (s[d] / n[d] > s[best] / n[best]) best = d
        return best to "exploit"
    }

    class BaseTarget(val target: Double, val j: Int)

    /** satAtScale(box, 2^j): fraction of the box's RAW sites with signal x 2^j >= white.
     *  Null when the base has no output box. */
    fun baseTarget(
        baseOut: List<D>, satAtScale: (DoubleArray, Double) -> Double,
        again: Double, againLo: Double, againHi: Double
    ): BaseTarget? {
        if (baseOut.isEmpty()) return null
        val ok = (-4..4).filter { j ->
            val s = Math.pow(2.0, j.toDouble())
            baseOut.all { satAtScale(it.box, s) < BASE_SAT }
        }
        val j = ok.maxOrNull() ?: -4
        return BaseTarget(minOf(maxOf(again * Math.pow(2.0, j.toDouble()), againLo), againHi), j)
    }

    /** window = the last 10 targets; acts only once 10 have accumulated. */
    fun hysteresis(cur: Double, window: List<Double>): Pair<Double, String> {
        if (window.size < HYST_WINDOW) return cur to "hold"
        if (window.all { it < cur }) return window.maxOrNull()!! to "all_lower"
        if (window.all { it > cur }) return window.minOrNull()!! to "all_higher"
        return cur to "hold"
    }

    /** blur ratio = motion px/frame x (T0 / frame interval) / box long side. */
    fun blurRatio(box: DoubleArray, motionPx: Double, t0S: Double, frameDtS: Double): Double {
        if (!(frameDtS > 0) || motionPx.isNaN()) return 0.0
        val side = maxOf(box[2] - box[0], box[3] - box[1], 1.0)
        return motionPx * (t0S / frameDtS) / side
    }

    /** -> "short_sat" | "short_blur" | "long" | "digital" */
    fun cause(
        box: DoubleArray, satFrac: Double, motionPx: Double, t0S: Double, frameDtS: Double,
        signalDn: Double, readNoiseDn: Double, againAtMax: Boolean, kappa: Double = 5.0
    ): String {
        if (satFrac >= SAT_PLACE) return "short_sat"
        if (blurRatio(box, motionPx, t0S, frameDtS) >= BLUR_RATIO) return "short_blur"
        if (signalDn < kappa * readNoiseDn || againAtMax) return "long"
        return "digital"
    }

    class Plan(
        val s: Double, val k: Int, val chosen: List<String>, val skipReason: String,
        val nWeak: Int, val nPlaces: Int?,
        val causeCounts: Map<String, Int>?, val ranked: List<Pair<String, Int>>?,
        /** The weak boxes that counted (flickered); empty unless k > 0. */
        val places: List<D>
    )

    /** weak: this frame's weak responses; outHist: base outputs of the previous frames
     *  (oldest first; only the last 10 are looked at); causeOf(box) -> that place's cause. */
    fun planNext(weak: List<D>, outHist: List<List<D>>, causeOf: (D) -> String, theta: Double): Plan {
        val s = weak.maxOfOrNull { it.conf } ?: -1.0
        val k = if (s < theta) 0 else if (s < TWO_SHOT_S) 1 else 2
        if (k == 0) return Plan(s, 0, emptyList(), "below_theta", weak.size, null, null, null, emptyList())
        val recent = outHist.takeLast(FLICKER_WINDOW)
        val places = weak.filter { d -> recent.any { h -> h.any { iou(d.box, it.box) >= PLACE_IOU } } }
        if (places.isEmpty())
            return Plan(s, k, emptyList(), "no_flicker_places", weak.size, 0, null, null, emptyList())
        val cnt = LinkedHashMap<String, Int>()
        for (p in places) { val x = causeOf(p); cnt[x] = (cnt[x] ?: 0) + 1 }
        val ranked = cnt.keys.sortedWith(compareBy<String>({ -cnt[it]!! }, { ORDER.indexOf(it) }))
        val chosen = ranked.take(k)
        return Plan(s, k, chosen, "none", weak.size, places.size, cnt,
            ranked.map { it to cnt[it]!! }, places)
    }

    /** Times a box in [hist] (the last 10 frames' outputs) overlapped [box] at IoU >= 0.3. */
    fun flickerCount(box: DoubleArray, hist: List<List<D>>): Int =
        hist.takeLast(FLICKER_WINDOW).count { h -> h.any { iou(box, it.box) >= PLACE_IOU } }

    class Setting(val exposureS: Double, val again: Double, val dgain: Double)

    /** digital reuses the base RAW (no new exposure). */
    fun remedySetting(
        remedy: String, t0S: Double, again: Double, delta: Int, againMax: Double,
        dgainMax: Double = 16.0
    ): Setting = when (remedy) {
        "digital" -> Setting(t0S, again, minOf(Math.pow(2.0, delta.toDouble()), dgainMax))
        "short_sat" -> Setting(t0S / 4, again, 1.0)
        "short_blur" -> Setting(t0S / 2, minOf(again * 2, againMax), 1.0)
        "long" -> Setting(t0S * Math.pow(2.0, delta.toDouble()), again, 1.0)
        else -> error("unknown remedy $remedy")
    }
}
