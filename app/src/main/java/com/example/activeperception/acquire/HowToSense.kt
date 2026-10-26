package com.example.activeperception.acquire

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How-to-Sense: the next base cell by computation, in place of Duel's gain rule and shutter
 * moves (top-down proposed, Actdiff revision/PROPOSED_TOPDOWN_2026-10-26.md; study s137z).
 * From this frame's base picture, every lattice cell's brightness, clipping and blur is
 * computed on a 16 x 12 grid, the detector's tolerance curve turns them into a chance of
 * detection, and the object map weights the grid. The base moves only when the best cell
 * beats the current one by more than the curve's standard error. Pure Kotlin so the JVM
 * tests run exactly the phone's code.
 *
 * Picture = the detector input without its letterbox rows: [W] x [H], 8-bit sRGB. Lattice
 * cell c = gain index * 5 + shutter index over [Duel.ISOS] x [Duel.SHUTTERS_US].
 */
object HowToSense {
    const val GX = 16
    const val GY = 12
    /** Grid cell side, picture px. */
    const val CS = 40
    const val W = GX * CS
    const val H = GY * CS
    const val N = GX * GY
    /** Shutter denominators of [Duel.SHUTTERS_US] (1/240 ... 1/15 s), exact. */
    val DENS = intArrayOf(240, 120, 60, 30, 15)
    /** Linear level from which a pixel counts as clipped. */
    const val CLIP = 0.98
    /** Typical car size before the map holds any detection, px. */
    const val FIRST_SIZE = 60.0

    /** Inverse sRGB of each 8-bit level. */
    private val LIN = DoubleArray(256) {
        val x = it / 255.0
        if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    fun cell(g: Int, s: Int) = g * 5 + s

    /** Cell [c]'s exposure, ISO over shutter denominator; ratios of it scale brightness. */
    fun exposure(c: Int) = Duel.ISOS[c / 5].toDouble() / DENS[c % 5]

    /** Linear picture: the channel mean of the inverse sRGB of [argb] ([W] x [H], packed ARGB). */
    fun picture(argb: IntArray): DoubleArray {
        require(argb.size == W * H)
        return DoubleArray(W * H) { p ->
            val v = argb[p]
            (LIN[(v shr 16) and 0xFF] + LIN[(v shr 8) and 0xFF] + LIN[v and 0xFF]) / 3.0
        }
    }

    /** Per lattice cell and grid cell: mean of min(L r, 1), and the share of pixels with
     *  L r >= [CLIP], where r = the lattice cell's exposure over the base's. */
    class Stats(val bright: Array<DoubleArray>, val clip: Array<DoubleArray>)

    fun stats(pic: DoubleArray, base: Int): Stats {
        val byRatio = HashMap<Double, Array<DoubleArray>>()
        val per = Array(25) { c -> byRatio.getOrPut(exposure(c) / exposure(base)) { gridOf(pic, exposure(c) / exposure(base)) } }
        return Stats(Array(25) { per[it][0] }, Array(25) { per[it][1] })
    }

    private fun gridOf(pic: DoubleArray, r: Double): Array<DoubleArray> {
        val b = DoubleArray(N)
        val s = DoubleArray(N)
        for (y in 0 until H) {
            val row = (y / CS) * GX
            for (x in 0 until W) {
                val v = pic[y * W + x] * r
                val k = row + x / CS
                b[k] += min(v, 1.0)
                if (v >= CLIP) s[k] += 1.0
            }
        }
        val n = (CS * CS).toDouble()
        for (k in 0 until N) { b[k] /= n; s[k] /= n }
        return arrayOf(b, s)
    }

    /**
     * Speed per grid cell, picture px per second, between the previous base picture [prev] and
     * this one [cur] taken [dtSec] apart. [prev] is first scaled by [gain] (this base's exposure
     * over the previous one's) and clipped at 1, so both show the same brightness; both are then
     * halved (2 x 2 means) and divided by the mean of [cur]. In each grid cell up to [pts]
     * corners are tracked (pyramidal LK) and the median displacement is taken. Only cells with
     * [need] are measured (the others stay NaN); a needed cell with no tracked point takes the
     * median of the measured cells (0 if none). Returns the speeds and how many were measured.
     */
    fun speed(prev: DoubleArray, gain: Double, cur: DoubleArray, dtSec: Double,
              need: BooleanArray, pts: Int = 4): Pair<DoubleArray, Int> {
        val hw = W / 2; val hh = H / 2
        fun half(p: DoubleArray, g: Double): FloatArray = FloatArray(hw * hh) { i ->
            val x = 2 * (i % hw); val y = 2 * (i / hw)
            (0.25 * (min(p[y * W + x] * g, 1.0) + min(p[y * W + x + 1] * g, 1.0) +
                min(p[(y + 1) * W + x] * g, 1.0) + min(p[(y + 1) * W + x + 1] * g, 1.0))).toFloat()
        }
        val a = half(prev, gain); val b = half(cur, 1.0)
        val m = b.average().toFloat().coerceAtLeast(1e-6f)
        for (i in a.indices) { a[i] /= m; b[i] /= m }
        val pa = SparseFlow.pyramid(SparseFlow.Img(hw, hh, a))
        val pb = SparseFlow.pyramid(SparseFlow.Img(hw, hh, b))
        val v = DoubleArray(N) { Double.NaN }
        val hc = CS / 2
        var measured = 0
        for (k in 0 until N) {
            if (!need[k]) continue
            val x0 = (k % GX) * hc; val y0 = (k / GX) * hc
            val p = SparseFlow.corners(pb[0], x0.toDouble(), y0.toDouble(), (x0 + hc - 1).toDouble(), (y0 + hc - 1).toDouble(), pts)
            val mags = SparseFlow.track(pa, pb, p).filterNotNull().map { 2.0 * hypot(it[0], it[1]) }
            if (mags.isEmpty()) continue
            v[k] = median(mags) / dtSec
            measured++
        }
        val fill = if (measured > 0) median((0 until N).filter { need[it] && !v[it].isNaN() }.map { v[it] }) else 0.0
        for (k in 0 until N) if (need[k] && v[k].isNaN()) v[k] = fill
        return v to measured
    }

    /** Where cars were detected so far: per grid cell the covered area (in cell units) of every
     *  detection added, and the long sides of the boxes that touch it. */
    class ObjectMap {
        private val cover = DoubleArray(N)
        private val sides = Array(N) { ArrayList<Double>() }
        private val all = ArrayList<Double>()

        val total: Double get() = cover.sum()

        /** One detection, [x0, y0, x1, y1] in picture px. */
        fun add(b: DoubleArray) {
            val side = max(b[2] - b[0], b[3] - b[1])
            all.add(side)
            for (y in max(floor(b[1] / CS).toInt(), 0) until min(floor(b[3] / CS).toInt() + 1, GY)) {
                for (x in max(floor(b[0] / CS).toInt(), 0) until min(floor(b[2] / CS).toInt() + 1, GX)) {
                    val k = y * GX + x
                    cover[k] += max(0.0, min(b[2], (x + 1.0) * CS) - max(b[0], x * CS.toDouble())) *
                        max(0.0, min(b[3], (y + 1.0) * CS) - max(b[1], y * CS.toDouble())) / (CS * CS)
                    sides[k].add(side)
                }
            }
        }

        /** Grid weights summing to 1; uniform while the map is empty. */
        fun weights(): DoubleArray {
            val s = cover.sum()
            return if (s > 0) DoubleArray(N) { cover[it] / s } else DoubleArray(N) { 1.0 / N }
        }

        /** Typical car size per grid cell: median long side of the boxes touching it, else of
         *  all boxes, else [FIRST_SIZE]. */
        fun sizes(): DoubleArray {
            val med = if (all.isEmpty()) FIRST_SIZE else median(all)
            return DoubleArray(N) { if (sides[it].isEmpty()) med else median(sides[it]) }
        }
    }

    /**
     * The detector's tolerance curve (study s137l / s137z, measured without labels): chance of
     * detection per blur bin x brightness bin for boxes with little clipping, its variance, and
     * a factor per clipped-share bin. Each axis is given by its bins' lower edges; a value falls
     * in the last bin whose lower edge is <= it (the first bin below the first edge).
     *
     * Text format, one key per line followed by its numbers; '#' starts a comment line:
     *   blur_lo      NB lower edges (blur = speed x shutter / car size)
     *   bright_lo    ND lower edges (mean of min(L r, 1))
     *   clip_lo      NS lower edges (share of pixels with L r >= 0.98)
     *   p            NB x ND chances, blur-major
     *   var          NB x ND variances of p, blur-major
     *   clip_factor  NS factors
     */
    class Curve(
        val blurLo: DoubleArray, val brightLo: DoubleArray, val clipLo: DoubleArray,
        val p: DoubleArray, val v: DoubleArray, val clipFactor: DoubleArray, val note: String
    ) {
        init {
            require(p.size == blurLo.size * brightLo.size && v.size == p.size)
            require(clipFactor.size == clipLo.size)
        }

        val bins get() = p.size

        /** Flat index (blur-major) of the curve bin for [blur] and [bright]. */
        fun bin(blur: Double, bright: Double) = binOf(blurLo, blur) * brightLo.size + binOf(brightLo, bright)

        fun factor(clip: Double) = clipFactor[binOf(clipLo, clip)]

        companion object {
            fun binOf(lo: DoubleArray, x: Double): Int = max(lo.count { it <= x } - 1, 0)

            fun parse(text: String): Curve {
                val kv = HashMap<String, DoubleArray>()
                val notes = ArrayList<String>()
                for (line in text.lines()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    if (t.startsWith("#")) { notes += t.removePrefix("#").trim(); continue }
                    val parts = t.split(Regex("\\s+"))
                    kv[parts[0]] = DoubleArray(parts.size - 1) { parts[it + 1].toDouble() }
                }
                fun need(k: String) = requireNotNull(kv[k]) { "tolerance curve: '$k' missing" }
                return Curve(need("blur_lo"), need("bright_lo"), need("clip_lo"), need("p"),
                    need("var"), need("clip_factor"), notes.joinToString(" "))
            }
        }
    }

    /** E per lattice cell, the best cell, the move threshold, and the base for the next frame. */
    class Choice(val e: DoubleArray, val best: Int, val threshold: Double, val next: Int)

    /**
     * E_c = sum over grid cells of weight x curve(blur, brightness) x clip factor, with
     * blur = speed x shutter_c / car size. The base moves to the best cell only when E_best -
     * E_cur exceeds sqrt(sum over curve bins of (Wb_best - Wb_cur)^2 var), where Wb_c sums
     * weight x clip factor per curve bin. Grid cells of zero weight add nothing and are skipped.
     */
    fun choose(curve: Curve, st: Stats, speed: DoubleArray, w: DoubleArray, size: DoubleArray, cur: Int): Choice {
        val e = DoubleArray(25)
        val wb = Array(25) { DoubleArray(curve.bins) }
        for (c in 0 until 25) {
            val sh = 1.0 / DENS[c % 5]
            for (k in 0 until N) {
                if (w[k] == 0.0) continue
                val i = curve.bin(speed[k] * sh / size[k], st.bright[c][k])
                val f = curve.factor(st.clip[c][k])
                e[c] += w[k] * curve.p[i] * f
                wb[c][i] += w[k] * f
            }
        }
        var best = 0
        for (c in 1 until 25) if (e[c] > e[best]) best = c
        var t = 0.0
        for (i in 0 until curve.bins) { val d = wb[best][i] - wb[cur][i]; t += d * d * curve.v[i] }
        val thr = sqrt(t)
        return Choice(e, best, thr, if (best != cur && e[best] - e[cur] > thr) best else cur)
    }

    fun median(v: List<Double>): Double {
        val s = v.sorted(); val m = s.size
        return if (m % 2 == 1) s[m / 2] else 0.5 * (s[m / 2 - 1] + s[m / 2])
    }
}
