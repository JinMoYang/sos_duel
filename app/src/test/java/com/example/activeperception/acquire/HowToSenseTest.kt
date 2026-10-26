package com.example.activeperception.acquire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** How-to-Sense: hand-checked cases, then the study's own frames (s137z) when they are bundled. */
class HowToSenseTest {
    private val n = HowToSense.N
    private fun flat(level: Int) = IntArray(HowToSense.W * HowToSense.H) { (0xFF shl 24) or (level shl 16) or (level shl 8) or level }
    private fun lin(level: Int): Double {
        val x = level / 255.0
        return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    }

    @Test fun statsScaleTheBasePictureByTheExposureRatio() {
        val pic = HowToSense.picture(flat(128))
        val base = HowToSense.cell(2, 2)                          // ISO 400, 1/60
        val st = HowToSense.stats(pic, base)
        assertEquals(lin(128), st.bright[base][0], 1e-12)
        assertEquals(0.0, st.clip[base][0], 1e-12)
        val half = HowToSense.cell(1, 2)                          // ISO 200: half the light
        assertEquals(lin(128) / 2, st.bright[half][n - 1], 1e-12)
        val top = HowToSense.cell(4, 4)                           // 16x: clips everywhere
        assertEquals(1.0, st.bright[top][7], 1e-12)
        assertEquals(1.0, st.clip[top][7], 1e-12)
    }

    @Test fun mapWeightsCoveredAreaAndSizesTouchedCells() {
        val m = HowToSense.ObjectMap()
        assertEquals(1.0 / n, m.weights()[5], 1e-15)              // empty: uniform
        assertEquals(HowToSense.FIRST_SIZE, m.sizes()[5], 1e-15)
        m.add(doubleArrayOf(0.0, 0.0, 40.0, 20.0))                 // half of grid cell (0, 0)
        m.add(doubleArrayOf(40.0, 0.0, 80.0, 40.0))                // all of grid cell (0, 1)
        val w = m.weights()
        assertEquals(1.0 / 3, w[0], 1e-12)
        assertEquals(2.0 / 3, w[1], 1e-12)
        assertEquals(0.0, w[HowToSense.GX + 1], 1e-12)             // touched at its edge only
        val s = m.sizes()
        assertEquals(40.0, s[0], 1e-12)
        assertEquals(40.0, s[HowToSense.GX + 1], 1e-12)            // edge-touched cells keep the side
        assertEquals(40.0, s[n - 1], 1e-12)                        // untouched: median of all boxes
    }

    @Test fun binsTakeTheLastLowerEdgeAtOrBelow() {
        val lo = doubleArrayOf(0.0, 0.01, 0.02)
        assertEquals(0, HowToSense.Curve.binOf(lo, -1.0))
        assertEquals(0, HowToSense.Curve.binOf(lo, 0.0099))
        assertEquals(1, HowToSense.Curve.binOf(lo, 0.01))
        assertEquals(2, HowToSense.Curve.binOf(lo, 5.0))
    }

    private fun twoBinCurve(v: Double) = HowToSense.Curve.parse("""
        # test curve
        blur_lo 0
        bright_lo 0 0.5
        clip_lo 0
        p 0.2 0.8
        var $v $v
        clip_factor 1
    """.trimIndent())

    @Test fun chooseMovesToTheBestCellOnlyBeyondTheStandardError() {
        val pic = HowToSense.picture(flat(100))                   // L = .127
        val base = HowToSense.cell(0, 0)
        val st = HowToSense.stats(pic, base)
        val still = DoubleArray(n)
        val w = DoubleArray(n) { 1.0 / n }
        val size = DoubleArray(n) { 60.0 }
        // Brightness >= .5 needs 4x the base's exposure; the first such cell is ISO 100, 1/60.
        val sure = HowToSense.choose(twoBinCurve(0.0), st, still, w, size, base)
        assertEquals(HowToSense.cell(0, 2), sure.best)
        assertEquals(sure.best, sure.next)
        assertEquals(0.6, sure.e[sure.best] - sure.e[base], 1e-12)
        // Threshold = sqrt(1^2 var + 1^2 var) = 1 for var .5: the gain .6 does not move the base.
        val unsure = HowToSense.choose(twoBinCurve(0.5), st, still, w, size, base)
        assertEquals(1.0, unsure.threshold, 1e-12)
        assertEquals(base, unsure.next)
    }

    /** A non-periodic scene of soft blobs: a purely periodic texture can trap the coarse pyramid
     *  levels on the wrong period, which no real scene did (see [HowToSense.FLOW_LEVELS]). */
    private val blobs = java.util.Random(3).let { r ->
        List(140) { doubleArrayOf(r.nextDouble() * 760 - 60, r.nextDouble() * 480, 6 + r.nextDouble() * 10, r.nextDouble()) }
    }
    private fun blobScene(x: Double, y: Double): Double {
        var s = 0.2
        for (b in blobs) { val dx = x - b[0]; val dy = y - b[1]; s += 0.25 * b[3] * exp(-(dx * dx + dy * dy) / (2 * b[2] * b[2])) }
        return s
    }

    @Test fun speedFollowsAShiftWhateverTheExposure() {
        fun scene(x: Int, y: Int) = blobScene(x.toDouble(), y.toDouble())
        val w = HowToSense.W
        // Content moves 6 px right in 0.1 s; the previous picture had half the exposure.
        val prev = DoubleArray(w * HowToSense.H) { 0.5 * scene(it % w, it / w) }
        val cur = DoubleArray(w * HowToSense.H) { scene(it % w - 6, it / w) }
        val (v, measured) = HowToSense.speed(prev, 2.0, cur, 0.1, BooleanArray(n) { true })
        assertTrue(measured >= n - 5)                              // a few cells are flat between blobs
        assertEquals(60.0, HowToSense.median(v.toList()), 3.0)
        val (only, m1) = HowToSense.speed(prev, 2.0, cur, 0.1, BooleanArray(n) { it == 50 })
        assertEquals(1, m1)
        assertTrue(only[0].isNaN())
    }

    @Test fun flowCellsSampleTheEmptyMapAndCapTheFullOne() {
        val empty = HowToSense.flowCells(DoubleArray(n) { 1.0 / n })
        assertEquals(16, empty.count { it })
        val w = DoubleArray(n) { if (it < 100) (it + 1).toDouble() else 0.0 }
        val top = HowToSense.flowCells(w)
        assertEquals(HowToSense.MAX_FLOW_CELLS, top.count { it })
        assertTrue(top[99] && !top[0] && !top[150])             // the heaviest cells, none unweighted
    }

    @Test fun speedForMapFillsTheUntrackedWeightedCells() {
        val w = HowToSense.W
        val prev = DoubleArray(w * HowToSense.H) { blobScene((it % w).toDouble(), (it / w).toDouble()) }
        val cur = DoubleArray(w * HowToSense.H) { blobScene((it % w).toDouble() - 6, (it / w).toDouble()) }
        val wt = DoubleArray(n) { 1.0 / n }
        val (v, _, tried) = HowToSense.speedForMap(prev, 1.0, cur, 0.1, wt)
        assertEquals(16, tried)
        assertTrue(v.none { it.isNaN() })                        // every weighted cell has a speed
        assertEquals(60.0, HowToSense.median(v.toList()), 3.0)
    }

    @Test fun speedsMeasuredAheadGiveTheSerialResult() {
        // Duel2 measures the flow cells of the map before a frame's detections while the
        // detector runs, then the cells those detections add: the same speeds as measuring
        // the final map's cells at once.
        val w = HowToSense.W
        val prev = DoubleArray(w * HowToSense.H) { blobScene((it % w).toDouble(), (it / w).toDouble()) }
        val cur = DoubleArray(w * HowToSense.H) { blobScene((it % w).toDouble() - 6, (it / w).toDouble()) }
        val before = DoubleArray(n) { if (it in 70 until 102) 1.0 else 0.0 }
        val after = DoubleArray(n) { if (it in 60 until 110) (it - 50).toDouble() else 0.0 }
        val serial = HowToSense.speedForMap(prev, 1.0, cur, 0.1, after)
        val fl = HowToSense.Flow(prev, 1.0, cur, 0.1)
        val tried = HowToSense.flowCells(before)
        val raw = DoubleArray(n) { if (tried[it]) fl.cellSpeed(it) else Double.NaN }
        val sel = HowToSense.flowCells(after)
        val late = (0 until n).filter { sel[it] && !tried[it] }
        assertTrue(late.isNotEmpty() && late.size < sel.count { it })
        for (k in late) raw[k] = fl.cellSpeed(k)
        val ahead = HowToSense.speedForMap(raw, after)
        assertArrayEquals(serial.first, ahead.first, 0.0)
        assertEquals(serial.second, ahead.second)
        assertEquals(serial.third, ahead.third)
    }

    @Test fun pictureRowsFromFloatsMatchThePackedPath() {
        val px = HowToSense.W * HowToSense.H
        val rgb = FloatArray(px * 3) { ((it * 37L) % 1001).toFloat() / 1000f - 0.0005f }
        fun q(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
        val argb = IntArray(px) { p -> (0xFF shl 24) or (q(rgb[3 * p]) shl 16) or (q(rgb[3 * p + 1]) shl 8) or q(rgb[3 * p + 2]) }
        val rows = DoubleArray(px)
        HowToSense.pictureRows(rgb, rows, 0 until 200)
        HowToSense.pictureRows(rgb, rows, 200 until HowToSense.H)
        assertArrayEquals(HowToSense.picture(argb), rows, 0.0)
    }

    @Test fun statsOnEveryCellMatchTheWeightedOnes() {
        val pic = HowToSense.picture(IntArray(HowToSense.W * HowToSense.H) { (0xFF shl 24) or ((it * 7919) and 0xFFFFFF) })
        val cells = BooleanArray(n) { it % 5 == 2 }
        val all = HowToSense.stats(pic, HowToSense.cell(3, 1))
        val some = HowToSense.stats(pic, HowToSense.cell(3, 1), cells)
        for (c in 0 until 25) for (k in 0 until n) if (cells[k]) {
            assertEquals(some.bright[c][k], all.bright[c][k], 0.0)
            assertEquals(some.clip[c][k], all.clip[c][k], 0.0)
        }
    }

    @Test fun speedFollowsATurnSizedShift() {
        // A Limo turn: ~450 px/s, so ~60 px between base pictures 0.13 s apart.
        fun scene(x: Double, y: Double) = blobScene(x, y)
        val w = HowToSense.W
        val prev = DoubleArray(w * HowToSense.H) { scene((it % w).toDouble(), (it / w).toDouble()) }
        val cur = DoubleArray(w * HowToSense.H) { scene((it % w).toDouble() - 60, (it / w).toDouble()) }
        // Only the cells away from the left edge, where the content entering the picture is new.
        val need = BooleanArray(n) { it % HowToSense.GX >= 3 }
        val (v, measured) = HowToSense.speed(prev, 1.0, cur, 0.13, need)
        assertTrue(measured > need.count { it } / 2)
        assertEquals(60 / 0.13, HowToSense.median(v.filter { !it.isNaN() }), 0.1 * 60 / 0.13)
    }

    /**
     * The study's frames, bundled under src/test/resources/howtosense/: curve.txt (the curve
     * they were computed with) and, per frame k, case_k.png (the 640 x 480 base picture) with
     * case_k.txt holding, one key per line, base (cell), speed (192, px/s), boxes (4 per map
     * box so far, picture px), and the expected bright and clip (25 x 192, cell-major), e (25),
     * best, threshold and next, all computed in float64.
     */
    /** [HowToSense.W] x [HowToSense.H] packed ARGB of a PNG. javax.imageio is not on the Android
     *  unit-test compile classpath (android.jar stands in for the JDK there) but is in the JDK
     *  that runs the tests, so it is reached by reflection. */
    private fun readArgb(url: java.net.URL): IntArray {
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", java.net.URL::class.java)
            .invoke(null, url)!!
        val ip = Int::class.javaPrimitiveType
        return img.javaClass.getMethod("getRGB", ip, ip, ip, ip, IntArray::class.java, ip, ip)
            .invoke(img, 0, 0, HowToSense.W, HowToSense.H, null, 0, HowToSense.W) as IntArray
    }

    @Test fun studyFramesGiveTheSameChoice() {
        val curveText = javaClass.getResource("/howtosense/curve.txt")?.readText()
        assumeTrue("study check values not bundled yet", curveText != null)
        val curve = HowToSense.Curve.parse(curveText!!)
        var k = 0
        while (true) {
            val txt = javaClass.getResource("/howtosense/case_$k.txt")?.readText() ?: break
            val kv = txt.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { l -> l.split(Regex("\\s+")).let { p -> p[0] to DoubleArray(p.size - 1) { p[it + 1].toDouble() } } }
            val pic = HowToSense.picture(readArgb(javaClass.getResource("/howtosense/case_$k.png")!!))
            val base = kv.getValue("base")[0].toInt()
            val st = HowToSense.stats(pic, base)
            assertArrayEquals("case $k bright", kv.getValue("bright"), DoubleArray(25 * n) { st.bright[it / n][it % n] }, 1e-9)
            assertArrayEquals("case $k clip", kv.getValue("clip"), DoubleArray(25 * n) { st.clip[it / n][it % n] }, 1e-9)
            val map = HowToSense.ObjectMap()
            val bx = kv["boxes"] ?: DoubleArray(0)
            for (i in 0 until bx.size / 4) map.add(bx.copyOfRange(4 * i, 4 * i + 4))
            val ch = HowToSense.choose(curve, st, kv.getValue("speed"), map.weights(), map.sizes(), base)
            assertArrayEquals("case $k e", kv.getValue("e"), ch.e, 1e-9)
            assertEquals("case $k best", kv.getValue("best")[0].toInt(), ch.best)
            assertEquals("case $k threshold", kv.getValue("threshold")[0], ch.threshold, 1e-9)
            assertEquals("case $k next", kv.getValue("next")[0].toInt(), ch.next)
            k++
        }
        assertTrue("no case_0.txt next to curve.txt", k > 0)
    }
}
