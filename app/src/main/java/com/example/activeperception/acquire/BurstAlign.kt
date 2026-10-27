package com.example.activeperception.acquire

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tracking sum of a burst (2026-10-28 experiment): before the frames of a burst are summed,
 * every later frame is moved back onto the first one, grid tile by grid tile, by the motion
 * measured between consecutive frames, so a passing car is summed onto itself instead of
 * into a double image. Frames of one burst share their exposure, so plain LK applies.
 *
 * Works on decoded Bayer sites (the [RawFrame] layout, w x h sites, 2 x 2 sites = one quad =
 * one pixel of the 1020-wide oriented image). Motion is measured on quad luma at half the
 * quad resolution; frames are moved by whole quads (two sites), so the CFA pattern is kept.
 * All in sensor coordinates: no orientation is involved. Pure Kotlin for the JVM tests.
 */
object BurstAlign {
    /** Tiles across and down (the How-to-Sense grid's shape). */
    const val TX = 16
    const val TY = 12
    /** Corners tracked per tile. */
    const val PTS = 4
    /** Pyramid levels for one frame step (16.7 ms; up to ~10 half-quad px on the Limo). */
    const val LEVELS = 3
    /** A tile whose step differs from its 3 x 3 neighbours' median by more than this
     *  (half-quad px) takes that median. */
    const val OUTLIER_PX = 6.0

    /** Quad luma at half the quad resolution: each pixel the mean of a 4 x 4 block of sites,
     *  divided by the frame mean so the threshold-free LK sees the same scale in every light. */
    fun lumaHalf(bayer: IntArray, w: Int, h: Int): SparseFlow.Img {
        val hw = w / 4; val hh = h / 4
        val out = FloatArray(hw * hh)
        var total = 0.0
        for (y in 0 until hh) {
            val sy = 4 * y
            for (x in 0 until hw) {
                val sx = 4 * x
                var s = 0L
                for (dy in 0 until 4) {
                    val row = (sy + dy) * w + sx
                    s += bayer[row] + bayer[row + 1] + bayer[row + 2] + bayer[row + 3]
                }
                val v = s / 16f
                out[y * hw + x] = v; total += v
            }
        }
        val m = (total / out.size).toFloat().coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] /= m
        return SparseFlow.Img(hw, hh, out)
    }

    fun pyramid(bayer: IntArray, w: Int, h: Int) = SparseFlow.pyramid(lumaHalf(bayer, w, h), LEVELS)

    /** Tile [t]'s bounds in half-quad px of an image [w] x [h]: x0, y0, x1, y1 (inclusive). */
    fun tileBounds(t: Int, w: Int, h: Int): IntArray {
        val tx = t % TX; val ty = t / TX
        return intArrayOf(tx * w / TX, ty * h / TY, (tx + 1) * w / TX - 1, (ty + 1) * h / TY - 1)
    }

    /**
     * Per tile, where [next]'s content sits relative to [prev] (half-quad px): d such that
     * next(p + d) ~ prev(p). Tiles with no tracked point, and outliers against their
     * neighbours, take the median of their 3 x 3 neighbourhood (the frame median if that is
     * empty too). Returns TX x TY rows of (dx, dy, points tracked).
     */
    fun step(prev: List<SparseFlow.Img>, next: List<SparseFlow.Img>,
             exec: java.util.concurrent.ExecutorService? = null): Array<DoubleArray> {
        val w = prev[0].w; val h = prev[0].h
        fun one(t: Int): DoubleArray {
            val b = tileBounds(t, w, h)
            val p = SparseFlow.corners(prev[0], b[0].toDouble(), b[1].toDouble(), b[2].toDouble(), b[3].toDouble(), PTS)
            val tr = SparseFlow.track(next, prev, p).filterNotNull()
            if (tr.isEmpty()) return doubleArrayOf(Double.NaN, Double.NaN, 0.0)
            return doubleArrayOf(median(tr.map { it[0] }), median(tr.map { it[1] }), tr.size.toDouble())
        }
        val raw = if (exec == null) Array(TX * TY) { one(it) }
            else (0 until TX * TY).map { t -> exec.submit(java.util.concurrent.Callable { one(t) }) }
                .map { it.get() }.toTypedArray()
        return clean(raw)
    }

    /** Fills untracked tiles and replaces outliers by their 3 x 3 neighbourhood median. */
    fun clean(raw: Array<DoubleArray>): Array<DoubleArray> {
        val good = raw.filter { !it[0].isNaN() }
        val gx = if (good.isEmpty()) 0.0 else median(good.map { it[0] })
        val gy = if (good.isEmpty()) 0.0 else median(good.map { it[1] })
        return Array(raw.size) { t ->
            val tx = t % TX; val ty = t / TX
            val nb = ArrayList<DoubleArray>()
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = tx + dx; val y = ty + dy
                if (x in 0 until TX && y in 0 until TY && !raw[y * TX + x][0].isNaN()) nb += raw[y * TX + x]
            }
            val mx = if (nb.isEmpty()) gx else median(nb.map { it[0] })
            val my = if (nb.isEmpty()) gy else median(nb.map { it[1] })
            val r = raw[t]
            when {
                r[0].isNaN() -> doubleArrayOf(mx, my, 0.0)
                abs(r[0] - mx) > OUTLIER_PX || abs(r[1] - my) > OUTLIER_PX -> doubleArrayOf(mx, my, -r[2])
                else -> r
            }
        }
    }

    /** Cumulative motion of frames 1..n-1 against frame 0 from the consecutive [steps]. */
    fun accumulate(steps: List<Array<DoubleArray>>): List<Array<DoubleArray>> {
        val out = ArrayList<Array<DoubleArray>>()
        var acc = Array(TX * TY) { doubleArrayOf(0.0, 0.0) }
        for (s in steps) {
            acc = Array(TX * TY) { t -> doubleArrayOf(acc[t][0] + s[t][0], acc[t][1] + s[t][1]) }
            out += acc
        }
        return out
    }

    /**
     * [bayer] moved back by [motion] (half-quad px per tile, from [accumulate]): within each
     * tile the output site p takes the input site p + d, d rounded to whole quads (2 sites
     * per quad, 4 sites per half-quad px), so the CFA pattern is kept. Sources outside the
     * frame are clamped to the border site of the same CFA parity.
     */
    fun moveBack(bayer: IntArray, w: Int, h: Int, motion: Array<DoubleArray>): IntArray {
        val out = IntArray(bayer.size)
        val hw = w / 4; val hh = h / 4
        for (t in 0 until TX * TY) {
            val b = tileBounds(t, hw, hh)
            // Site bounds of the tile; the last row and column of tiles reach the frame edge.
            val sx0 = 4 * b[0]; val sy0 = 4 * b[1]
            val sx1 = if (t % TX == TX - 1) w else 4 * (b[2] + 1)
            val sy1 = if (t / TX == TY - 1) h else 4 * (b[3] + 1)
            val qx = (motion[t][0] * 2).roundToInt(); val qy = (motion[t][1] * 2).roundToInt()
            val ox = 2 * qx; val oy = 2 * qy
            for (y in sy0 until sy1) {
                var syy = y + oy
                if (syy < 0) syy = y and 1 else if (syy >= h) syy = h - 2 + (y and 1)
                val dst = y * w; val src = syy * w
                for (x in sx0 until sx1) {
                    var sxx = x + ox
                    if (sxx < 0) sxx = x and 1 else if (sxx >= w) sxx = w - 2 + (x and 1)
                    out[dst + x] = bayer[src + sxx]
                }
            }
        }
        return out
    }

    fun median(v: List<Double>): Double {
        val s = v.sorted(); val m = s.size
        return if (m % 2 == 1) s[m / 2] else 0.5 * (s[m / 2 - 1] + s[m / 2])
    }
}
