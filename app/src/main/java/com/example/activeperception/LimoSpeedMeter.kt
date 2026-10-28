package com.example.activeperception

import kotlin.math.abs

/**
 * The Limo's speed over one run, from the gravity-axis gyro alone (2026-10-28). Records were
 * taken at two Limo speeds (12.7 and 20.8 s laps) with nothing in the run saying which, so
 * every run now measures it: laps = net yaw / 360 and the lap time is the run's length over
 * that; the turn rate is the median |yaw rate| of the samples turning faster than
 * [TURN_RAD_S], the straights' near-zero rate left out (it halves at the slower speed).
 *
 * [add] runs on the sensor thread, [start] and [result] on the run's.
 */
class LimoSpeedMeter {
    class Result(val seconds: Double, val laps: Double, val lapS: Double, val turnRateRadS: Double)

    private var yaw0 = 0.0
    private var t0Ms = 0L
    private val hist = IntArray(BINS)
    private var turning = 0

    @Synchronized fun start(totalYawDeg: Double, nowMs: Long) {
        yaw0 = totalYawDeg; t0Ms = nowMs; hist.fill(0); turning = 0
    }

    @Synchronized fun add(yawRateRadS: Double) {
        val a = abs(yawRateRadS)
        if (a.isNaN() || a < TURN_RAD_S) return
        hist[minOf(BINS - 1, (a / BIN_RAD_S).toInt())]++; turning++
    }

    /** NaN lap time under half a lap (or no yaw at all: gravity not locked). */
    @Synchronized fun result(totalYawDeg: Double, nowMs: Long): Result {
        val s = (nowMs - t0Ms) / 1000.0
        val laps = abs(totalYawDeg - yaw0) / 360.0
        var rate = Double.NaN
        if (turning > 0) {
            var seen = 0
            for (b in hist.indices) {
                seen += hist[b]
                if (2 * seen >= turning) { rate = (b + 0.5) * BIN_RAD_S; break }
            }
        }
        return Result(s, laps, if (laps >= 0.5) s / laps else Double.NaN, rate)
    }

    companion object {
        const val TURN_RAD_S = 0.15
        private const val BIN_RAD_S = 0.01
        private const val BINS = 500
    }
}
