package com.example.activeperception.acquire

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sparse optical flow for proposed_v3cause's blur test: track a few corner points inside
 * one box between the previous and the current base image (pyramidal Lucas-Kanade,
 * Bouguet's formulation) and report the median displacement. Only the box's points are
 * tracked; no dense field is ever computed. Pure Kotlin so it is unit-testable on the JVM.
 */
object SparseFlow {
    class Img(val w: Int, val h: Int, val px: FloatArray) {
        operator fun get(x: Int, y: Int): Float = px[min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)]
        fun bilinear(x: Double, y: Double): Double {
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val fx = x - x0; val fy = y - y0
            val a = this[x0, y0]; val b = this[x0 + 1, y0]
            val c = this[x0, y0 + 1]; val d = this[x0 + 1, y0 + 1]
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        }
    }

    /** Level 0 is [base]; each next level halves both sides by 2x2 averaging. */
    fun pyramid(base: Img, levels: Int = 3): List<Img> {
        val out = arrayListOf(base)
        while (out.size < levels) {
            val p = out.last()
            val w = max(1, p.w / 2); val h = max(1, p.h / 2)
            val q = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                q[y * w + x] = (p[2 * x, 2 * y] + p[2 * x + 1, 2 * y] +
                    p[2 * x, 2 * y + 1] + p[2 * x + 1, 2 * y + 1]) * 0.25f
            }
            out.add(Img(w, h, q))
        }
        return out
    }

    /** Up to [maxPts] Shi-Tomasi corners inside [x0,y0,x1,y1] (level-0 px), picked on a
     *  grid so they spread over the box; falls back to the grid itself on flat boxes. */
    fun corners(img: Img, x0: Double, y0: Double, x1: Double, y1: Double, maxPts: Int = 8): List<DoubleArray> {
        val xa = max(2, x0.toInt()); val xb = min(img.w - 3, x1.toInt())
        val ya = max(2, y0.toInt()); val yb = min(img.h - 3, y1.toInt())
        if (xb <= xa || yb <= ya) return emptyList()
        val step = max(1, min(xb - xa, yb - ya) / 6)
        val cand = ArrayList<Pair<Double, DoubleArray>>()
        var y = ya
        while (y <= yb) {
            var x = xa
            while (x <= xb) {
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for (dy in -2..2) for (dx in -2..2) {
                    val gx = (img[x + dx + 1, y + dy] - img[x + dx - 1, y + dy]) * 0.5
                    val gy = (img[x + dx, y + dy + 1] - img[x + dx, y + dy - 1]) * 0.5
                    sxx += gx * gx; syy += gy * gy; sxy += gx * gy
                }
                val tr = sxx + syy
                val minEig = 0.5 * (tr - sqrt(max(0.0, (sxx - syy) * (sxx - syy) + 4 * sxy * sxy)))
                cand.add(minEig to doubleArrayOf(x.toDouble(), y.toDouble()))
                x += step
            }
            y += step
        }
        return cand.sortedByDescending { it.first }.take(maxPts).map { it.second }
    }

    /**
     * Pyramidal LK: for each point p in [cur] (level 0), find where the same content sits in
     * [prev]. Returns the displacement prev - cur per point, or null when the 2x2 gradient
     * matrix is singular or the solution leaves the image.
     */
    fun track(
        prev: List<Img>, cur: List<Img>, pts: List<DoubleArray>,
        halfWin: Int = 4, iters: Int = 10
    ): List<DoubleArray?> = pts.map { p ->
        var gx = 0.0; var gy = 0.0            // guess carried down the pyramid
        var ok = true
        for (lvl in cur.indices.reversed()) {
            val I = cur[lvl]; val J = prev[lvl]
            val s = 1.0 / (1 shl lvl)
            val px = p[0] * s; val py = p[1] * s
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            val n = (2 * halfWin + 1) * (2 * halfWin + 1)
            val ix = DoubleArray(n); val iy = DoubleArray(n); val iv = DoubleArray(n)
            var k = 0
            for (wy in -halfWin..halfWin) for (wx in -halfWin..halfWin) {
                val x = px + wx; val y = py + wy
                val dx = (I.bilinear(x + 1, y) - I.bilinear(x - 1, y)) * 0.5
                val dy = (I.bilinear(x, y + 1) - I.bilinear(x, y - 1)) * 0.5
                ix[k] = dx; iy[k] = dy; iv[k] = I.bilinear(x, y)
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy
                k++
            }
            val det = sxx * syy - sxy * sxy
            if (abs(det) < 1e-9) { ok = false; break }
            var vx = 0.0; var vy = 0.0
            for (it in 0 until iters) {
                var bx = 0.0; var by = 0.0
                k = 0
                for (wy in -halfWin..halfWin) for (wx in -halfWin..halfWin) {
                    val diff = iv[k] - J.bilinear(px + wx + gx + vx, py + wy + gy + vy)
                    bx += diff * ix[k]; by += diff * iy[k]
                    k++
                }
                val ux = (syy * bx - sxy * by) / det
                val uy = (sxx * by - sxy * bx) / det
                vx += ux; vy += uy
                if (abs(ux) < 0.01 && abs(uy) < 0.01) break
            }
            gx += vx; gy += vy
            if (lvl > 0) { gx *= 2; gy *= 2 }
        }
        if (!ok || gx.isNaN() || gy.isNaN()) null else doubleArrayOf(gx, gy)
    }

    /** Median displacement magnitude (level-0 px) of the box's tracked points; NaN if none. */
    fun boxMotion(prev: List<Img>, cur: List<Img>, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val pts = corners(cur[0], x0, y0, x1, y1)
        if (pts.isEmpty()) return Double.NaN
        val mags = track(prev, cur, pts).filterNotNull().map { hypot(it[0], it[1]) }.sorted()
        if (mags.isEmpty()) return Double.NaN
        val m = mags.size
        return if (m % 2 == 1) mags[m / 2] else 0.5 * (mags[m / 2 - 1] + mags[m / 2])
    }
}
