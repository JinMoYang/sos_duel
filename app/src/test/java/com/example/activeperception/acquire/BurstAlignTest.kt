package com.example.activeperception.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class BurstAlignTest {
    private val w = 640; private val h = 480                 // sites: 320 x 240 quads, 160 x 120 half-quad px
    private val blobs = java.util.Random(7).let { r ->
        List(220) { doubleArrayOf(r.nextDouble() * 360 - 20, r.nextDouble() * 260 - 10, 3 + r.nextDouble() * 6, r.nextDouble()) }
    }
    /** Scene at quad (qx, qy); each CFA site of the quad gets it times its channel's factor. */
    private fun scene(qx: Double, qy: Double): Double {
        var s = 200.0
        for (b in blobs) { val dx = qx - b[0]; val dy = qy - b[1]; s += 600 * b[3] * exp(-(dx * dx + dy * dy) / (2 * b[2] * b[2])) }
        return s
    }
    private val cfa = doubleArrayOf(0.6, 1.0, 1.0, 0.8)
    /** A frame whose content has moved by (mqx, mqy) quads. */
    private fun frame(mqx: Int, mqy: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        (scene((x / 2 - mqx).toDouble(), (y / 2 - mqy).toDouble()) * cfa[(y and 1) * 2 + (x and 1)]).toInt()
    }

    @Test fun moveBackShiftsWholeQuadsAndKeepsTheCfaParity() {
        val f = IntArray(w * h) { it }
        // 1.0 x 0.5 half-quad px = 2 x 1 quads = 4 x 2 sites.
        val out = BurstAlign.moveBack(f, w, h, Array(BurstAlign.TX * BurstAlign.TY) { doubleArrayOf(1.0, 0.5) })
        for (y in listOf(10, 200, 400)) for (x in listOf(3, 300, 600))
            assertEquals(f[(y + 2) * w + x + 4], out[y * w + x])
        // Past the right and bottom edges: the border site of the same CFA position.
        for (y in listOf(h - 1, h - 2)) for (x in listOf(w - 1, w - 2)) {
            val src = out[y * w + x]
            assertEquals(x and 1, (src % w) and 1)
            assertEquals(y and 1, (src / w) and 1)
        }
    }

    @Test fun stepFindsAKnownMotionAndMovingBackRealignsTheFrame() {
        val f0 = frame(0, 0); val f1 = frame(6, 2)           // 6 x 2 quads = 3 x 1 half-quad px
        val s = BurstAlign.step(BurstAlign.pyramid(f0, w, h), BurstAlign.pyramid(f1, w, h))
        val dx = BurstAlign.median(s.map { it[0] }); val dy = BurstAlign.median(s.map { it[1] })
        assertEquals(3.0, dx, 0.25); assertEquals(1.0, dy, 0.25)
        val back = BurstAlign.moveBack(f1, w, h, BurstAlign.accumulate(listOf(s))[0])
        // Away from the borders the moved-back frame is frame 0, and the sum is twice frame 0.
        var err = 0.0; var naive = 0.0; var n = 0
        for (y in 40 until h - 40) for (x in 40 until w - 40) {
            val i = y * w + x
            err += abs(back[i] - f0[i]); naive += abs(f1[i] - f0[i]); n++
        }
        assertTrue("moved back differs by ${err / n} DN (unmoved ${naive / n})", err / n < 0.1 * naive / n)
    }

    @Test fun accumulatedStepsAddUpOverTheBurst() {
        val f = listOf(frame(0, 0), frame(4, 0), frame(8, 2), frame(12, 2))
        val pyr = f.map { BurstAlign.pyramid(it, w, h) }
        val steps = (1 until f.size).map { BurstAlign.step(pyr[it - 1], pyr[it]) }
        val acc = BurstAlign.accumulate(steps)
        assertEquals(6.0, BurstAlign.median(acc[2].map { it[0] }), 0.4)   // 12 quads
        assertEquals(1.0, BurstAlign.median(acc[2].map { it[1] }), 0.4)   // 2 quads
    }

    @Test fun stepOnSomeTilesFillsTheRestWithTheirMotion() {
        val f0 = frame(0, 0); val f1 = frame(6, 2)
        val tiles = BooleanArray(BurstAlign.TX * BurstAlign.TY) { it % 7 == 3 }
        val s = BurstAlign.step(BurstAlign.pyramid(f0, w, h), BurstAlign.pyramid(f1, w, h), tiles = tiles)
        assertTrue(s.withIndex().all { (t, v) -> tiles[t] || v[2] == 0.0 })      // untracked ones marked
        assertEquals(3.0, BurstAlign.median(s.map { it[0] }), 0.25)
        assertEquals(1.0, BurstAlign.median(s.map { it[1] }), 0.25)
    }

    @Test fun splittingTheWorkOverThreadsChangesNothing() {
        val f1 = frame(6, 2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val serial = BurstAlign.lumaHalf(f1, w, h); val split = BurstAlign.lumaHalf(f1, w, h, pool)
            assertTrue(serial.px.contentEquals(split.px))
            val m = Array(BurstAlign.TX * BurstAlign.TY) { doubleArrayOf((it % 5) - 2.0, (it % 3) - 1.0) }
            assertTrue(BurstAlign.moveBack(f1, w, h, m).contentEquals(BurstAlign.moveBack(f1, w, h, m, pool)))
        } finally { pool.shutdownNow() }
    }

    @Test fun cleanFillsUntrackedTilesAndOutliersFromTheirNeighbours() {
        val raw = Array(BurstAlign.TX * BurstAlign.TY) { doubleArrayOf(2.0, 1.0, 4.0) }
        raw[20] = doubleArrayOf(Double.NaN, Double.NaN, 0.0)
        raw[50] = doubleArrayOf(40.0, -30.0, 4.0)
        val c = BurstAlign.clean(raw)
        assertEquals(2.0, c[20][0], 1e-12); assertEquals(1.0, c[20][1], 1e-12)
        assertEquals(2.0, c[50][0], 1e-12); assertEquals(1.0, c[50][1], 1e-12)
        assertTrue(c[50][2] < 0)                                           // marked as replaced
    }
}
