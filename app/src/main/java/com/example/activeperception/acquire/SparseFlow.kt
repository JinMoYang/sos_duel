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
            if (x0 >= 0 && y0 >= 0 && x0 < w - 1 && y0 < h - 1) {
                // Inside: the same four pixels without the edge clamps.
                val i = y0 * w + x0
                val a = px[i]; val b = px[i + 1]; val c = px[i + w]; val d = px[i + w + 1]
                return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            }
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
            val src = p.px; val pw = p.w
            if (2 * w <= p.w && 2 * h <= p.h) {
                // Every 2 x 2 block lies inside, so no clamping is needed.
                for (y in 0 until h) {
                    var i = 2 * y * pw
                    for (x in 0 until w) {
                        q[y * w + x] = (src[i] + src[i + 1] + src[i + pw] + src[i + pw + 1]) * 0.25f
                        i += 2
                    }
                }
            } else for (y in 0 until h) for (x in 0 until w) {
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
                if (x >= 3 && y >= 3 && x + 3 < img.w && y + 3 < img.h) {
                    val px = img.px; val w = img.w
                    for (dy in -2..2) for (dx in -2..2) {
                        val i = (y + dy) * w + x + dx
                        val gx = (px[i + 1] - px[i - 1]) * 0.5
                        val gy = (px[i + w] - px[i - w]) * 0.5
                        sxx += gx * gx; syy += gy * gy; sxy += gx * gy
                    }
                } else for (dy in -2..2) for (dx in -2..2) {
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
     *
     * Written for the phone (2026-10-26: 32 cells of How-to-Sense's speed took 130-260 ms):
     * [cur] is sampled once per level on the window plus a 1-px border (bit-exact: point
     * coordinates are integers, so p x 2^-level plus a whole offset is exact in binary), and
     * inside the image [prev] is sampled with bilinear weights computed once per iteration.
     * That reorders the rounding only: the displacements match the per-pixel version to
     * ~1e-12 px (checked on the phone, androidTest FlowBenchTest).
     */
    fun track(
        prev: List<Img>, cur: List<Img>, pts: List<DoubleArray>,
        halfWin: Int = 4, iters: Int = 10
    ): List<DoubleArray?> {
        val side = 2 * halfWin + 1
        val n = side * side
        val ps = side + 2
        val patch = DoubleArray(ps * ps)
        val ix = DoubleArray(n); val iy = DoubleArray(n); val iv = DoubleArray(n)
        return pts.map { p -> trackOne(prev, cur, p, halfWin, iters, ps, patch, ix, iy, iv) }
    }

    private fun trackOne(
        prev: List<Img>, cur: List<Img>, p: DoubleArray, halfWin: Int, iters: Int,
        ps: Int, patch: DoubleArray, ix: DoubleArray, iy: DoubleArray, iv: DoubleArray
    ): DoubleArray? {
        var gx = 0.0; var gy = 0.0            // guess carried down the pyramid
        var ok = true
        for (lvl in cur.indices.reversed()) {
            val I = cur[lvl]; val J = prev[lvl]
            val s = 1.0 / (1 shl lvl)
            val px = p[0] * s; val py = p[1] * s
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            val bx0 = floor(px).toInt() - halfWin - 1; val by0 = floor(py).toInt() - halfWin - 1
            if (bx0 >= 0 && by0 >= 0 && bx0 + ps < I.w && by0 + ps < I.h) {
                // Inside: every sample has px's own fraction (exact, as above).
                val fx = px - floor(px); val fy = py - floor(py)
                val ip = I.px; val iw = I.w
                for (j in 0 until ps) {
                    var q = (by0 + j) * iw + bx0
                    for (i in 0 until ps) {
                        val a = ip[q]; val b = ip[q + 1]; val c = ip[q + iw]; val d = ip[q + iw + 1]
                        patch[j * ps + i] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
                        q++
                    }
                }
            } else for (j in 0 until ps) for (i in 0 until ps) {
                patch[j * ps + i] = I.bilinear(px + (i - halfWin - 1), py + (j - halfWin - 1))
            }
            var k = 0
            for (wy in -halfWin..halfWin) for (wx in -halfWin..halfWin) {
                val c = (wy + halfWin + 1) * ps + wx + halfWin + 1
                val dx = (patch[c + 1] - patch[c - 1]) * 0.5
                val dy = (patch[c + ps] - patch[c - ps]) * 0.5
                ix[k] = dx; iy[k] = dy; iv[k] = patch[c]
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy
                k++
            }
            val det = sxx * syy - sxy * sxy
            if (abs(det) < 1e-9) { ok = false; break }
            var vx = 0.0; var vy = 0.0
            val jw = J.w; val jh = J.h; val jp = J.px
            for (it in 0 until iters) {
                var bx = 0.0; var by = 0.0
                k = 0
                // Every window pixel shares the window's fractional offset, so the bilinear
                // weights are computed once; only windows touching the border sample one by one.
                val cx = px + gx + vx; val cy = py + gy + vy
                val x0 = floor(cx).toInt(); val y0 = floor(cy).toInt()
                if (x0 - halfWin >= 0 && y0 - halfWin >= 0 && x0 + halfWin < jw - 1 && y0 + halfWin < jh - 1) {
                    val fx = cx - x0; val fy = cy - y0
                    val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy)
                    val w01 = (1 - fx) * fy; val w11 = fx * fy
                    for (wy in -halfWin..halfWin) {
                        var i = (y0 + wy) * jw + x0 - halfWin
                        for (wx in -halfWin..halfWin) {
                            val jv = jp[i] * w00 + jp[i + 1] * w10 + jp[i + jw] * w01 + jp[i + jw + 1] * w11
                            val diff = iv[k] - jv
                            bx += diff * ix[k]; by += diff * iy[k]
                            k++; i++
                        }
                    }
                } else for (wy in -halfWin..halfWin) for (wx in -halfWin..halfWin) {
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
        return if (!ok || gx.isNaN() || gy.isNaN()) null else doubleArrayOf(gx, gy)
    }

}
