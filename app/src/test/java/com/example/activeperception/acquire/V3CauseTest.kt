package com.example.activeperception.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/** Behaviour of the proposed_v3cause rules, checked case by case against Appendix A. */
class V3CauseTest {
    private fun b(x0: Double, y0: Double, x1: Double, y1: Double) = doubleArrayOf(x0, y0, x1, y1)
    private fun d(c: Double, box: DoubleArray, cls: Int = 2) = V3Cause.D(c, box, cls)

    @Test fun iouAndContain() {
        assertEquals(1.0, V3Cause.iou(b(0.0, 0.0, 10.0, 10.0), b(0.0, 0.0, 10.0, 10.0)), 1e-12)
        assertEquals(0.0, V3Cause.iou(b(0.0, 0.0, 10.0, 10.0), b(10.0, 0.0, 20.0, 10.0)), 1e-12)
        assertEquals(25.0 / 175.0, V3Cause.iou(b(0.0, 0.0, 10.0, 10.0), b(5.0, 5.0, 15.0, 15.0)), 1e-12)
        assertEquals(1.0, V3Cause.contain(b(2.0, 2.0, 4.0, 4.0), b(0.0, 0.0, 10.0, 10.0)), 1e-12)
        assertEquals(0.0, V3Cause.contain(b(1.0, 1.0, 1.0, 5.0), b(0.0, 0.0, 10.0, 10.0)), 1e-12)
    }

    @Test fun nmsKeepsHighestAndStableTies() {
        val a = d(0.5, b(0.0, 0.0, 10.0, 10.0))
        val c = d(0.9, b(1.0, 1.0, 11.0, 11.0))
        val e = d(0.5, b(50.0, 50.0, 60.0, 60.0))
        val kept = V3Cause.nms(listOf(a, c, e))
        assertEquals(listOf(c, e), kept)
        // Equal confidences keep the input order (Python's stable sort).
        val t1 = d(0.4, b(0.0, 0.0, 10.0, 10.0)); val t2 = d(0.4, b(0.0, 0.0, 10.0, 10.0))
        assertEquals(listOf(t1), V3Cause.nms(listOf(t1, t2)))
    }

    @Test fun perImageSplitsOutputsAndWeak() {
        val raw = listOf(
            d(0.80, b(0.0, 0.0, 10.0, 10.0)),
            d(0.10, b(1.0, 1.0, 10.0, 10.0)),      // overlaps the output -> suppressed in low
            d(0.12, b(40.0, 40.0, 50.0, 50.0)),    // weak
            d(0.04, b(80.0, 80.0, 90.0, 90.0)),    // below 0.05
            d(0.30, b(100.0, 100.0, 110.0, 110.0), cls = 0)  // person: ignored
        )
        val p = V3Cause.perImage(raw)
        assertEquals(1, p.out.size)
        assertEquals(2, p.low.size)
        assertEquals(1, p.weak.size)
        assertEquals(0.12, p.weak[0].conf, 1e-12)
    }

    @Test fun pythonRoundIsHalfToEven() {
        assertEquals(0, V3Cause.pyRound(0.5)); assertEquals(2, V3Cause.pyRound(1.5))
        assertEquals(2, V3Cause.pyRound(2.5)); assertEquals(1, V3Cause.pyRound(0.6))
    }

    @Test fun fuseWeightsByUnsaturatedEvidence() {
        val box = b(0.0, 0.0, 10.0, 10.0)
        val base = V3Cause.FuseImage(listOf(d(0.9, box)), listOf(d(0.9, box))) { 0.0 }
        val extra = V3Cause.FuseImage(emptyList(), listOf(d(0.3, box))) { 0.5 }
        val r = V3Cause.fuse(listOf(base, extra))
        assertEquals(1, r.size)
        assertEquals("kept", r[0].status)
        assertEquals((1.0 * 0.9 + 0.5 * 0.3) / 1.5, r[0].evidence, 1e-12)
        assertEquals(2, r[0].nPic)                 // 0.9 and 0.3 are both >= 0.25
    }

    @Test fun fuseDropsSmallPiecesInsideKeptBoxes() {
        val big = b(0.0, 0.0, 100.0, 100.0)
        val small = b(10.0, 10.0, 30.0, 30.0)
        val im = V3Cause.FuseImage(listOf(d(0.9, big), d(0.6, small)),
            listOf(d(0.9, big), d(0.6, small))) { 0.0 }
        val r = V3Cause.fuse(listOf(im))
        assertEquals(listOf("kept", "contained"), r.map { it.status })
    }

    @Test fun pickDeltaSweepsThenExploits() {
        val n = IntArray(6); val s = DoubleArray(6)
        for (want in 1..5) {
            val (dd, mode) = V3Cause.pickDelta(n, s)
            assertEquals(want, dd); assertEquals("sweep", mode)
            n[dd] += 1; s[dd] += if (dd == 3) 2.0 else 1.0
        }
        assertEquals(3 to "exploit", V3Cause.pickDelta(n, s))
        s[1] = 2.0     // tie between 1 and 3 -> the smaller delta
        assertEquals(1 to "exploit", V3Cause.pickDelta(n, s))
    }

    @Test fun hysteresisNeedsTenOnOneSide() {
        assertEquals(400.0 to "hold", V3Cause.hysteresis(400.0, List(9) { 100.0 }))
        assertEquals(200.0 to "all_lower", V3Cause.hysteresis(400.0, List(9) { 100.0 } + 200.0))
        assertEquals(800.0 to "all_higher", V3Cause.hysteresis(400.0, List(9) { 1600.0 } + 800.0))
        assertEquals(400.0 to "hold", V3Cause.hysteresis(400.0, List(9) { 100.0 } + 400.0))
    }

    @Test fun baseTargetPicksLargestSafeScale() {
        val box = b(0.0, 0.0, 10.0, 10.0)
        // 5% of the box saturates at x2, 20% at x4: largest j with < 0.1 is 1.
        val sat = { _: DoubleArray, s: Double -> if (s >= 4) 0.2 else if (s >= 2) 0.05 else 0.0 }
        val t = V3Cause.baseTarget(listOf(d(0.9, box)), sat, 100.0, 50.0, 1200.0)!!
        assertEquals(1, t.j); assertEquals(200.0, t.target, 1e-9)
        assertNull(V3Cause.baseTarget(emptyList(), sat, 100.0, 50.0, 1200.0))
        val clamped = V3Cause.baseTarget(listOf(d(0.9, box)), { _, _ -> 0.0 }, 400.0, 50.0, 1200.0)!!
        assertEquals(4, clamped.j); assertEquals(1200.0, clamped.target, 1e-9)
    }

    @Test fun causeOrder() {
        val box = b(0.0, 0.0, 100.0, 50.0)
        assertEquals("short_sat", V3Cause.cause(box, 0.6, 50.0, 1.0 / 60, 0.1, 0.0, 1.0, true))
        // 6 px/frame x (16.7 ms / 100 ms) / 100 px = 0.01 -> no blur; 20 px -> 0.033 blur.
        assertEquals("digital", V3Cause.cause(box, 0.0, 6.0, 1.0 / 60, 0.1, 100.0, 2.0, false))
        assertEquals("short_blur", V3Cause.cause(box, 0.0, 20.0, 1.0 / 60, 0.1, 100.0, 2.0, false))
        assertEquals("long", V3Cause.cause(box, 0.0, 0.0, 1.0 / 60, 0.1, 9.0, 2.0, false))
        assertEquals("long", V3Cause.cause(box, 0.0, 0.0, 1.0 / 60, 0.1, 100.0, 2.0, true))
        // No previous frame: no motion evidence.
        assertEquals("digital", V3Cause.cause(box, 0.0, 50.0, 1.0 / 60, Double.NaN, 100.0, 2.0, false))
    }

    @Test fun planNextCountsOnlyFlickeringPlaces() {
        val p1 = d(0.15, b(0.0, 0.0, 10.0, 10.0))
        val p2 = d(0.22, b(50.0, 50.0, 60.0, 60.0))
        val p3 = d(0.08, b(100.0, 100.0, 110.0, 110.0))
        val hist = listOf(listOf(d(0.9, b(0.0, 0.0, 10.0, 10.0))), listOf(d(0.9, b(51.0, 50.0, 61.0, 60.0))))
        val causes = mapOf(p1 to "long", p2 to "short_blur", p3 to "digital")
        val below = V3Cause.planNext(listOf(p1, p2, p3), hist, { causes[it]!! }, 0.30)
        assertEquals(0, below.k); assertEquals("below_theta", below.skipReason)
        val plan = V3Cause.planNext(listOf(p1, p2, p3), hist, { causes[it]!! }, 0.10)
        assertEquals(2, plan.k)                    // s = 0.22 >= 0.20
        assertEquals(2, plan.nPlaces)              // p3 never overlapped an output
        // one place each: ties broken cheapest first -> short_blur before long
        assertEquals(listOf("short_blur", "long"), plan.chosen)
        val none = V3Cause.planNext(listOf(p3), hist, { causes[it]!! }, 0.05)
        assertEquals("no_flicker_places", none.skipReason); assertTrue(none.chosen.isEmpty())
        val inf = V3Cause.planNext(listOf(p1, p2), hist, { causes[it]!! }, Double.POSITIVE_INFINITY)
        assertEquals(0, inf.k)
    }

    @Test fun remedySettings() {
        val t0 = 1.0 / 60
        assertEquals(4.0, V3Cause.remedySetting("digital", t0, 400.0, 2, 1200.0).dgain, 1e-12)
        assertEquals(16.0, V3Cause.remedySetting("digital", t0, 400.0, 5, 1200.0).dgain, 1e-12)
        assertEquals(t0 / 4, V3Cause.remedySetting("short_sat", t0, 400.0, 1, 1200.0).exposureS, 1e-12)
        val sb = V3Cause.remedySetting("short_blur", t0, 800.0, 1, 1200.0)
        assertEquals(t0 / 2, sb.exposureS, 1e-12); assertEquals(1200.0, sb.again, 1e-12)
        assertEquals(t0 * 8, V3Cause.remedySetting("long", t0, 400.0, 3, 1200.0).exposureS, 1e-12)
    }

    @Test fun rawBoxSamplerFollowsTheSensorRotation() {
        // 8x6 Bayer sites = 4x3 quads; rotated 90 deg the oriented image is 3 wide, 4 tall.
        val w = 8; val h = 6
        val bayer = IntArray(w * h) { 10 }
        // Sensor quad (sx=3, sy=0) saturated (white 100, black 0 -> level 100).
        for (dy in 0..1) for (dx in 0..1) bayer[(0 + dy) * w + 6 + dx] = 100
        val s = RawBoxSampler(RawFrame(bayer, w, h, "RGGB", 100.0, 90), IntArray(4), 100.0)
        assertEquals(3, s.orientedW); assertEquals(4, s.orientedH)
        // deg 90: sx = oy, sy = qh-1-ox -> sensor (3,0) is oriented (ox=2, oy=3).
        assertEquals(1.0, s.satFrac(b(2.0, 3.0, 3.0, 4.0)), 1e-12)
        assertEquals(0.0, s.satFrac(b(0.0, 0.0, 1.0, 1.0)), 1e-12)
        // x10 brings the 10 DN sites to the 100 DN level.
        assertEquals(1.0, s.satFrac(b(0.0, 0.0, 1.0, 1.0), 10.0), 1e-12)
        val all = s.stats(b(0.0, 0.0, 3.0, 4.0), doubleArrayOf(1.0))
        assertEquals(48, all.n); assertEquals((44 * 10 + 4 * 100) / 48.0, all.meanDn, 1e-9)
        assertEquals(4.0 / 48, all.satFrac[0], 1e-12)
    }

    @Test fun flowRecoversASubpixelShift() {
        val w = 120; val h = 100
        fun scene(sx: Double, sy: Double) = SparseFlow.Img(w, h, FloatArray(w * h) { i ->
            val x = (i % w) - sx; val y = (i / w) - sy
            (0.5 + 0.25 * sin(x / 3.1) * sin(y / 4.3) + 0.2 * exp(-((x - 60) * (x - 60) + (y - 50) * (y - 50)) / 200.0)).toFloat()
        })
        val prev = SparseFlow.pyramid(scene(0.0, 0.0))
        val cur = SparseFlow.pyramid(scene(2.5, -1.5))
        val pts = SparseFlow.corners(cur[0], 40.0, 30.0, 80.0, 70.0)
        assertTrue(pts.isNotEmpty())
        for (v in SparseFlow.track(prev, cur, pts)) {
            requireNotNull(v)
            assertEquals(-2.5, v[0], 0.1); assertEquals(1.5, v[1], 0.1)
        }
        val m = SparseFlow.boxMotion(prev, cur, 40.0, 30.0, 80.0, 70.0)
        assertEquals(Math.hypot(2.5, 1.5), m, 0.1)
        val still = SparseFlow.boxMotion(prev, prev, 40.0, 30.0, 80.0, 70.0)
        assertEquals(0.0, still, 1e-6)
    }
}
