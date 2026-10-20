package com.example.activeperception.acquire

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Statistics of one decoded RAW frame inside a box given in detector-input pixels (the
 * oriented image of 2x2 quads that every formed image and every logged box lives in). The
 * box is mapped back through the sensor orientation to the decoded Bayer sites.
 *
 * Decoded sites are black-subtracted and signed (see RawFrame), so the mean is the
 * spec's "box mean RAW - black" in DN, and a site is saturated at digital scale s when
 * value x s >= white - black of its CFA channel.
 */
class RawBoxSampler(
    private val frame: RawFrame,
    /** Black level per CFA position (row * 2 + col), as in RawMeta.blackLevels. */
    black: IntArray,
    whiteDn: Double,
    /** Further frames of the same burst summed site by site with [frame] — the statistics
     *  of a burst-sum image (Duel's synthesized longer exposure). Empty: [frame] alone. */
    private val sumWith: List<RawFrame> = emptyList()
) {
    private val qw = frame.width / 2
    private val qh = frame.height / 2
    private val deg = ((frame.sensorOrientation % 360) + 360) % 360
    val orientedW = if (deg == 90 || deg == 270) qh else qw
    val orientedH = if (deg == 90 || deg == 270) qw else qh
    private val satLevel = DoubleArray(4) { whiteDn - black.getOrElse(it) { 0 } }

    class Stats(val n: Int, val meanDn: Double, val satFrac: DoubleArray)

    /** Oriented quad (ox, oy) -> sensor quad (sx, sy); same mapping as the native fill. */
    private fun toSensor(ox: Int, oy: Int): Pair<Int, Int> = when (deg) {
        0 -> ox to oy
        90 -> oy to (qh - 1 - ox)
        180 -> (qw - 1 - ox) to (qh - 1 - oy)
        else -> (qw - 1 - oy) to ox
    }

    /** Mean signal and, for every scale, the saturated fraction inside [box]. Large boxes are
     *  sampled on a regular quad stride so at most about [maxSites] sites are read. */
    fun stats(box: DoubleArray, scales: DoubleArray, maxSites: Int = 40_000): Stats {
        val ox0 = floor(box[0]).toInt().coerceIn(0, orientedW - 1)
        val ox1 = (ceil(box[2]).toInt() - 1).coerceIn(ox0, orientedW - 1)
        val oy0 = floor(box[1]).toInt().coerceIn(0, orientedH - 1)
        val oy1 = (ceil(box[3]).toInt() - 1).coerceIn(oy0, orientedH - 1)
        val (ax, ay) = toSensor(ox0, oy0)
        val (bx, by) = toSensor(ox1, oy1)
        val sx0 = min(ax, bx); val sx1 = max(ax, bx)
        val sy0 = min(ay, by); val sy1 = max(ay, by)
        val quads = (sx1 - sx0 + 1).toLong() * (sy1 - sy0 + 1)
        val stride = max(1, ceil(sqrt(quads * 4.0 / maxSites)).toInt())
        val sat = IntArray(scales.size)
        var sum = 0.0
        var n = 0
        val bw = frame.width
        var sy = sy0
        while (sy <= sy1) {
            var sx = sx0
            while (sx <= sx1) {
                for (dy in 0..1) for (dx in 0..1) {
                    val idx = (2 * sy + dy) * bw + 2 * sx + dx
                    var v = frame.bayer[idx].toDouble()
                    for (o in sumWith) v += o.bayer[idx]
                    sum += v; n++
                    val level = satLevel[dy * 2 + dx]
                    for (k in scales.indices) if (v * scales[k] >= level) sat[k]++
                }
                sx += stride
            }
            sy += stride
        }
        return Stats(n, if (n > 0) sum / n else 0.0,
            DoubleArray(scales.size) { if (n > 0) sat[it].toDouble() / n else 0.0 })
    }

    fun satFrac(box: DoubleArray, scale: Double = 1.0): Double =
        stats(box, doubleArrayOf(scale)).satFrac[0]
}
