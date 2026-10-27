package com.example.activeperception

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.activeperception.acquire.BurstAlign
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.exp

/**
 * On-device cost of one tracking-update step (Duel2 duel2t: two reads, 32 tracked tiles) on
 * full-size decoded frames (2040 x 1530 sites), the work on one thread vs split over four.
 * Run: adb shell am instrument -w -e class com.example.activeperception.AlignBenchTest
 *      com.sos.integrate.test/androidx.test.runner.AndroidJUnitRunner ; adb logcat -d -s AlignBench
 */
@RunWith(AndroidJUnit4::class)
class AlignBenchTest {
    private val w = 2040; private val h = 1530
    private val blobs = java.util.Random(5).let { r ->
        List(900) { doubleArrayOf(r.nextDouble() * 1080 - 30, r.nextDouble() * 800 - 20, 3 + r.nextDouble() * 8, r.nextDouble()) }
    }
    private fun frame(mqx: Int): IntArray {
        // A coarse blob field on the quad grid, filled cell by cell (fast enough for a bench).
        val qw = w / 2; val qh = h / 2
        val q = DoubleArray(qw * qh) { 60.0 }
        for (b in blobs) {
            val r = (3 * b[2]).toInt()
            for (y in maxOf(0, b[1].toInt() - r) until minOf(qh, b[1].toInt() + r))
                for (x in maxOf(0, b[0].toInt() + mqx - r) until minOf(qw, b[0].toInt() + mqx + r)) {
                    val dx = x - b[0] - mqx; val dy = y - b[1]
                    q[y * qw + x] += 300 * b[3] * exp(-(dx * dx + dy * dy) / (2 * b[2] * b[2]))
                }
        }
        return IntArray(w * h) { i -> q[(i / w / 2) * qw + (i % w) / 2].toInt() }
    }

    private fun ms(reps: Int, f: () -> Unit): Double {
        val t = LongArray(reps)
        for (i in 0 until reps) { val t0 = System.nanoTime(); f(); t[i] = System.nanoTime() - t0 }
        t.sort(); return t[reps / 2] / 1e6
    }

    @Test fun oneStepCost() {
        val f0 = frame(0); val f1 = frame(12)
        val tiles = BooleanArray(BurstAlign.TX * BurstAlign.TY) { (it / BurstAlign.TX) in 5..6 }   // 32 tiles
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            repeat(2) { BurstAlign.pyramid(f0, w, h); BurstAlign.pyramid(f0, w, h, pool) }
            val lumaSerial = ms(7) { BurstAlign.pyramid(f0, w, h) }
            val lumaSplit = ms(7) { BurstAlign.pyramid(f0, w, h, pool) }
            val p0 = BurstAlign.pyramid(f0, w, h, pool); val p1 = BurstAlign.pyramid(f1, w, h, pool)
            val stepMs = ms(7) { BurstAlign.step(p0, p1, pool, tiles) }
            val m = BurstAlign.accumulate(listOf(BurstAlign.step(p0, p1, pool, tiles)))[0]
            val moveSerial = ms(7) { BurstAlign.moveBack(f1, w, h, m) }
            val moveSplit = ms(7) { BurstAlign.moveBack(f1, w, h, m, pool) }
            Log.i("AlignBench", "luma+pyramid a frame: one thread %.1f ms, four %.1f ms".format(lumaSerial, lumaSplit))
            Log.i("AlignBench", "LK step on 32 tiles (four threads): %.1f ms".format(stepMs))
            Log.i("AlignBench", "move back a frame: one thread %.1f ms, four %.1f ms".format(moveSerial, moveSplit))
            Log.i("AlignBench", "one step, two reads: before ~%.1f ms (2 lumas on 2 threads + move on 1), now ~%.1f ms"
                .format(lumaSerial + stepMs + moveSerial, 2 * lumaSplit + stepMs + moveSplit))
            assertTrue(BurstAlign.median(BurstAlign.step(p0, p1, pool, tiles).filter { it[2] > 0 }.map { it[0] }) in 5.0..7.0)
        } finally { pool.shutdownNow() }
    }
}
