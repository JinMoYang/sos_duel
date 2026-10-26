package com.example.activeperception.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour of the Duel rules, checked case by case against the spec's Appendix A. */
class DuelTest {
    private fun b(x0: Double, y0: Double, x1: Double, y1: Double) = doubleArrayOf(x0, y0, x1, y1)
    private fun d(c: Double, box: DoubleArray) = V3Cause.D(c, box, 2)

    @Test fun pBetterMatchesTheSpecValues() {
        assertEquals(31.0 / 32.0, Duel.pBetter(4, 0), 1e-12)     // spec: 0.969
        assertEquals(120.0 / 128.0, Duel.pBetter(5, 1), 1e-12)   // spec: 0.938
        assertEquals(247.0 / 256.0, Duel.pBetter(6, 1), 1e-12)   // spec: 0.965
        assertEquals(0.5, Duel.pBetter(0, 0), 1e-12)
        // Stays finite for long stalemates.
        val p = Duel.pBetter(1500, 1500)
        assertTrue(p > 0.4 && p < 0.6)
    }

    @Test fun discordCountsCarsSeenInOnlyOneImage() {
        val car1 = b(0.0, 0.0, 10.0, 10.0)
        val car2 = b(50.0, 50.0, 60.0, 60.0)
        val car3 = b(100.0, 0.0, 110.0, 10.0)
        val fused = listOf(car1, car2, car3)
        // car1 in both, car2 only in the neighbour, car3 only in the base.
        val base = listOf(d(0.9, car1), d(0.8, car3))
        // The neighbour sees everything shifted 2 px right.
        fun shift(x: DoubleArray) = b(x[0] + 2, x[1], x[2] + 2, x[3])
        val nb = listOf(d(0.9, shift(car1)), d(0.7, shift(car2)))
        val wl = Duel.discord(fused, base, nb, ::shift)
        assertEquals(1, wl[0]); assertEquals(1, wl[1])
        // Without moving the boxes to the neighbour's time a large shift breaks the match.
        val far = listOf(d(0.9, b(8.0, 0.0, 18.0, 10.0)))
        assertEquals(0, Duel.discord(listOf(car1), listOf(d(0.9, car1)), far) { it }[0])
        assertEquals(1, Duel.discord(listOf(car1), listOf(d(0.9, car1)), far) { it }[1])
    }

    @Test fun betaVariateHasTheRightMean() {
        val rng = java.util.Random(7)
        val n = 20000
        var s = 0.0
        repeat(n) { s += Duel.betaVariate(5.0, 1.0, rng) }
        assertEquals(5.0 / 6.0, s / n, 0.01)
    }

    @Test fun l0ComparesOnlyAfterATrace() {
        val p = Duel.Policy(every = false, baseG = 4, baseS = 2, seed = 1)
        assertEquals("none", p.plan().reason)
        assertNull(p.plan().nb)
        p.prevTrace = true
        val pl = p.plan()
        assertEquals("trace", pl.reason)
        assertEquals(2, pl.draws!!.size)
        val l1 = Duel.Policy(every = true, baseG = 4, baseS = 2, seed = 1)
        assertEquals("always", l1.plan().reason)
    }

    @Test fun theLatticeEdgeBlocksTheDirection() {
        val p = Duel.Policy(every = true, baseG = 4, baseS = 0, seed = 1)   // 1/240: no shorter rung
        assertEquals(listOf(Duel.Dir.LONG), p.dirs())
        val pl = p.plan()
        assertEquals("always", pl.reason)
        assertEquals(Duel.Dir.LONG, pl.nb)
        assertEquals(1, pl.draws!!.size)
    }

    @Test fun burstSumSamplerClipsOnTheSum() {
        // Two frames of 8x6 sites at 40 DN (white 100, black 0): each alone is far from
        // white; their sum (80 DN, Duel's longer image) clips once scaled by 1.333.
        val w = 8; val h = 6
        val f0 = RawFrame(IntArray(w * h) { 40 }, w, h, "RGGB", 100.0, 0)
        val f1 = RawFrame(IntArray(w * h) { 40 }, w, h, "RGGB", 100.0, 0)
        val one = RawBoxSampler(f0, IntArray(4), 100.0)
        val sum = RawBoxSampler(f0, IntArray(4), 100.0, listOf(f1))
        val all = b(0.0, 0.0, 4.0, 3.0)
        assertEquals(0.0, one.satFrac(all, 1.333), 1e-12)
        assertEquals(1.0, sum.satFrac(all, 1.333), 1e-12)
        assertEquals(80.0, sum.stats(all, doubleArrayOf(1.0)).meanDn, 1e-9)
    }

    @Test fun votesOnlyAccumulateAndNeverMoveTheBase() {
        val p = Duel.Policy(every = true, baseG = 4, baseS = 2, seed = 1)
        p.afterCompare(Duel.Dir.LONG, 2, 0)
        p.afterCompare(Duel.Dir.LONG, 3, 0)
        p.afterCompare(Duel.Dir.SHORT, 0, 1)
        p.afterCompare(null, 9, 9)
        assertEquals(2, p.baseS)                                   // the base is HowToSense's to move
        assertEquals(5, p.w[Duel.Dir.LONG.ordinal]); assertEquals(0, p.l[Duel.Dir.LONG.ordinal])
        assertEquals(0, p.w[Duel.Dir.SHORT.ordinal]); assertEquals(1, p.l[Duel.Dir.SHORT.ordinal])
        // The votes survive a base move made by the caller.
        p.baseS = 3
        assertEquals(5, p.w[Duel.Dir.LONG.ordinal])
    }
}
