package com.example.activeperception.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exposure grid and image formation (no Android types; run on the JVM). */
class AcquireCoreTest {

    @Test fun gridRatiosAndBurst() {
        val g = Grid(intArrayOf(100, 200, 400, 800), intArrayOf(4000, 8000, 16000, 32000))
        assertEquals(4, g.nGain); assertEquals(4, g.nShutter)
        assertEquals(listOf(1.0, 2.0, 4.0, 8.0), (0 until 4).map { g.gainRatio(it) })
        assertEquals(listOf(1, 2, 4, 8), (0 until 4).map { g.burstN(it) })
        assertEquals(8, g.maxBurst)
        for (gi in 0 until g.nGain) for (sj in 0 until g.nShutter)
            assertEquals(gi to sj, g.indices(g.cell(gi, sj)))
    }

    @Test fun srgbEncoding() {
        assertEquals(0, Formation.srgbU8(0.0, 1023.0))
        assertEquals(255, Formation.srgbU8(1023.0, 1023.0))
        val expMid = 1.055 * Math.pow(0.5, 1.0 / 2.4) - 0.055
        assertTrue(Math.abs(Formation.linearToSrgb(0.5) - expMid) < 1e-9)
    }

    @Test fun demosaicAndPack() {
        val w = 4; val h = 4
        val bayer = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when { y % 2 == 0 && x % 2 == 0 -> 400; y % 2 == 1 && x % 2 == 1 -> 100; else -> 200 }
        }
        val (r, gg, b) = Formation.demosaic2x2(bayer, w, h, "RGGB").let { Triple(it[0], it[1], it[2]) }
        assertTrue(r.all { it == 400 } && gg.all { it == 200 } && b.all { it == 100 })
        val argb = Formation.packCandidateArgb(r, gg, b, 1.0, 1023.0)
        assertEquals(0xFF, (argb[0] ushr 24) and 0xFF)
        assertTrue(((argb[0] ushr 16) and 0xFF) > ((argb[0] ushr 8) and 0xFF))
    }

    @Test fun sensorOrientationRotatesPixelsAndDimensions() {
        // Source rows: [1 2 3] / [4 5 6]. Clockwise 90 => [4 1] / [5 2] / [6 3].
        val rotated = Formation.orientArgb(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, 90)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), rotated.pixels.toList())
    }
}
