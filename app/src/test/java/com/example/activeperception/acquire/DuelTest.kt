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

    @Test fun gainRuleTakesTheHighestUnclippedIso() {
        assertNull(Duel.gainRule(null))
        assertEquals(4, Duel.gainRule(doubleArrayOf(0.0, 0.0, 0.1, 0.3, 0.69)))
        assertEquals(2, Duel.gainRule(doubleArrayOf(0.0, 0.2, 0.5, 0.7, 0.9)))
        assertEquals(0, Duel.gainRule(doubleArrayOf(0.8, 0.9, 1.0, 1.0, 1.0)))
    }

    @Test fun betaVariateHasTheRightMean() {
        val rng = java.util.Random(7)
        val n = 20000
        var s = 0.0
        repeat(n) { s += Duel.betaVariate(5.0, 1.0, rng) }
        assertEquals(5.0 / 6.0, s / n, 0.01)
    }

    @Test fun l0ComparesOnlyAfterATraceOrDuringALine() {
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

    @Test fun moveAfterEnoughWinsThenLineSearchUntilTheNeighbourLoses() {
        val p = Duel.Policy(every = true, baseG = 4, baseS = 3, seed = 1)
        // Four cars seen only in the shorter neighbour, in one frame: 4:0 -> 0.969 -> move.
        assertEquals(2, p.afterCompare(Duel.Dir.SHORT, 4, 0))
        assertEquals(Duel.Dir.SHORT, p.line)
        assertEquals(0, p.w.sum() + p.l.sum())
        // Line search: the next plan compares short again, without drawing.
        val pl = p.plan()
        assertEquals(Duel.Dir.SHORT, pl.nb); assertEquals("line", pl.reason)
        // A draw keeps the line ...
        assertNull(p.afterCompare(Duel.Dir.SHORT, 1, 1))
        assertEquals(Duel.Dir.SHORT, p.line)
        // ... a loss ends it.
        assertNull(p.afterCompare(Duel.Dir.SHORT, 0, 1))
        assertNull(p.line)
    }

    @Test fun theLatticeEdgeBlocksTheDirection() {
        val p = Duel.Policy(every = true, baseG = 4, baseS = 1, seed = 1)
        assertEquals(0, p.afterCompare(Duel.Dir.SHORT, 5, 0))   // reaches 1/240
        assertEquals(listOf(Duel.Dir.LONG), p.dirs())
        val pl = p.plan()                                        // line blocked: falls through
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

    @Test fun votesAccumulateAcrossFrames() {
        val p = Duel.Policy(every = true, baseG = 4, baseS = 2, seed = 1)
        assertNull(p.afterCompare(Duel.Dir.LONG, 2, 0))
        assertNull(p.afterCompare(Duel.Dir.LONG, 1, 0))
        assertEquals(15.0 / 16.0, p.pBetterNow(Duel.Dir.LONG), 1e-12)   // 3:0, not yet 0.95
        assertEquals(3, p.afterCompare(Duel.Dir.LONG, 1, 0))      // 4:0 -> move
    }
}
