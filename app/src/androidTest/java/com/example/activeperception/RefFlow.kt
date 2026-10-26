package com.example.activeperception

import com.example.activeperception.acquire.HowToSense
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SparseFlow and HowToSense.speed as they were before the 2026-10-26 speed-up (commit 9cdbde2),
 * copied verbatim: the yardstick the on-device bench compares the app's flow against.
 */
object RefFlow {
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

    fun track(
        prev: List<Img>, cur: List<Img>, pts: List<DoubleArray>,
        halfWin: Int = 4, iters: Int = 10
    ): List<DoubleArray?> = pts.map { p ->
        var gx = 0.0; var gy = 0.0
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

    /** HowToSense.speed on the code above. */
    fun speed(prev: DoubleArray, gain: Double, cur: DoubleArray, dtSec: Double,
              need: BooleanArray, pts: Int = 4, levels: Int = HowToSense.FLOW_LEVELS): Pair<DoubleArray, Int> {
        val W = HowToSense.W; val H = HowToSense.H; val N = HowToSense.N; val GX = HowToSense.GX
        val hw = W / 2; val hh = H / 2
        fun half(p: DoubleArray, g: Double): FloatArray = FloatArray(hw * hh) { i ->
            val x = 2 * (i % hw); val y = 2 * (i / hw)
            (0.25 * (min(p[y * W + x] * g, 1.0) + min(p[y * W + x + 1] * g, 1.0) +
                min(p[(y + 1) * W + x] * g, 1.0) + min(p[(y + 1) * W + x + 1] * g, 1.0))).toFloat()
        }
        val a = half(prev, gain); val b = half(cur, 1.0)
        val m = b.average().toFloat().coerceAtLeast(1e-6f)
        for (i in a.indices) { a[i] /= m; b[i] /= m }
        val pa = pyramid(Img(hw, hh, a), levels)
        val pb = pyramid(Img(hw, hh, b), levels)
        val v = DoubleArray(N) { Double.NaN }
        val hc = HowToSense.CS / 2
        var measured = 0
        for (k in 0 until N) {
            if (!need[k]) continue
            val x0 = (k % GX) * hc; val y0 = (k / GX) * hc
            val p = corners(pb[0], x0.toDouble(), y0.toDouble(), (x0 + hc - 1).toDouble(), (y0 + hc - 1).toDouble(), pts)
            val mags = track(pa, pb, p).filterNotNull().map { 2.0 * hypot(it[0], it[1]) }
            if (mags.isEmpty()) continue
            v[k] = HowToSense.median(mags) / dtSec
            measured++
        }
        val fill = if (measured > 0) HowToSense.median((0 until N).filter { need[it] && !v[it].isNaN() }.map { v[it] }) else 0.0
        for (k in 0 until N) if (need[k] && v[k].isNaN()) v[k] = fill
        return v to measured
    }
}
