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

}
