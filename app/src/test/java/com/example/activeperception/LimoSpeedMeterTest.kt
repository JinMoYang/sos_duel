package com.example.activeperception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimoSpeedMeterTest {
    @Test fun lapTimeAndTurnRateOfASteadyTrack() {
        // 12.7 s laps: 6 s of straight (0.02 rad/s) and 6.7 s of turn at 2 pi / 6.7 rad/s.
        val m = LimoSpeedMeter()
        m.start(totalYawDeg = 1000.0, nowMs = 0L)
        val turn = 2 * Math.PI / 6.7
        repeat(3) {
            repeat(600) { m.add(0.02) }
            repeat(670) { m.add(-turn) }
        }
        val r = m.result(totalYawDeg = 1000.0 - 3 * 360.0, nowMs = 38_100L)
        assertEquals(3.0, r.laps, 1e-9)
        assertEquals(12.7, r.lapS, 1e-9)
        assertEquals(turn, r.turnRateRadS, 0.006)
    }

    @Test fun noLapTimeUnderHalfALap() {
        val m = LimoSpeedMeter()
        m.start(0.0, 0L)
        val r = m.result(100.0, 10_000L)
        assertTrue(r.lapS.isNaN())
        assertTrue(r.turnRateRadS.isNaN())
    }
}
