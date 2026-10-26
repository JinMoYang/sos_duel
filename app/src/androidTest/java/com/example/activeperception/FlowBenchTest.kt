package com.example.activeperception

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.activeperception.acquire.HowToSense
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * On-device cost of Duel2's choice step on real bright-session base pictures (assets/flowbench:
 * three consecutive pairs from run_car_duel2_every_1792986967104, straight / turn / between, with
 * the map weights at that moment). Times the app's speed flow against [RefFlow] (the code before
 * the speed-up), interleaved so heat falls on both alike, and checks the speeds agree.
 * Run: adb shell am instrument -w -e class com.example.activeperception.FlowBenchTest
 *      com.sos.integrate.test/androidx.test.runner.AndroidJUnitRunner ; adb logcat -d -s FlowBench
 */
@RunWith(AndroidJUnit4::class)
class FlowBenchTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    /** The 640 x 640 detector-input JPEG without its letterbox rows (80 above and below). */
    private fun picture(name: String): DoubleArray {
        val bmp = assets.open("flowbench/$name").use { BitmapFactory.decodeStream(it) }
        val px = IntArray(HowToSense.W * HowToSense.H)
        bmp.getPixels(px, 0, HowToSense.W, 0, (bmp.height - HowToSense.H) / 2, HowToSense.W, HowToSense.H)
        return HowToSense.picture(px)
    }

    private fun ms(reps: Int, f: () -> Unit): Double {
        val t = LongArray(reps)
        for (i in 0 until reps) { val t0 = System.nanoTime(); f(); t[i] = System.nanoTime() - t0 }
        t.sort(); return t[reps / 2] / 1e6
    }

    @Test fun speedFlowCostAndAgreement() {
        val rows = assets.open("flowbench/bench.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        var worst = 0.0
        for (row in rows) {
            val t = row.split(Regex("\\s+"))
            val k = t[0].toInt(); val dt = t[1].toDouble(); val gain = t[2].toDouble()
            val w = DoubleArray(HowToSense.N) { t[3 + it].toDouble() }
            val a = picture("pair_${k}_a.jpg"); val b = picture("pair_${k}_b.jpg")
            for (cap in listOf(32, 16)) {
                val sel = HowToSense.flowCells(w, cap)
                repeat(3) { RefFlow.speed(a, gain, b, dt, sel); HowToSense.speed(a, gain, b, dt, sel) }
                var refMs = 0.0; var newMs = 0.0
                repeat(4) {
                    refMs += ms(3) { RefFlow.speed(a, gain, b, dt, sel) }
                    newMs += ms(3) { HowToSense.speed(a, gain, b, dt, sel) }
                }
                val ref = RefFlow.speed(a, gain, b, dt, sel); val now = HowToSense.speed(a, gain, b, dt, sel)
                var d = 0.0
                for (c in 0 until HowToSense.N) if (sel[c]) d = max(d, abs(ref.first[c] - now.first[c]))
                worst = max(worst, d)
                val med = HowToSense.median((0 until HowToSense.N).filter { sel[it] }.map { now.first[it] })
                Log.i("FlowBench", "pair %d cells %d: before %.1f ms, now %.1f ms, max |speed diff| %.2e px/s, median speed %.0f px/s, measured %d/%d"
                    .format(k, sel.count { it }, refMs / 4, newMs / 4, d, med, now.second, ref.second))
            }
            val st = HowToSense.cell(4, 0)
            val cells = BooleanArray(HowToSense.N) { w[it] > 0.0 }
            val statsMs = ms(9) { HowToSense.stats(b, st, cells) }
            val picMs = ms(9) { HowToSense.picture(IntArray(HowToSense.W * HowToSense.H)) }
            Log.i("FlowBench", "pair %d: stats %.1f ms (%d cells), picture %.1f ms".format(k, statsMs, cells.count { it }, picMs))
        }
        Log.i("FlowBench", "worst speed difference %.2e px/s".format(worst))
        assertTrue("speeds differ by $worst px/s", worst < 1e-3)
    }
}
