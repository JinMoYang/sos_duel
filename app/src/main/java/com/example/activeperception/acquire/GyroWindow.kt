package com.example.activeperception.acquire

import kotlin.math.abs

/**
 * The last [CAP] gyro samples (rad/s, phone axes, sensor-event time in ns, the camera's
 * SENSOR_TIMESTAMP base), for How-to-Sense's "gyro" speed source (2026-10-28). [mean] is the
 * record builder's (ToleranceCurveSosCf): the mean over [t0, t1] padded by [PAD_NS] on each
 * side, else the sample nearest the middle. [add] runs on the sensor thread, [mean] on the run's.
 */
class GyroWindow {
    private val ts = LongArray(CAP)
    private val w = Array(CAP) { DoubleArray(3) }
    private var next = 0
    private var size = 0

    @Synchronized fun add(tNs: Long, x: Double, y: Double, z: Double) {
        ts[next] = tNs; w[next][0] = x; w[next][1] = y; w[next][2] = z
        next = (next + 1) % CAP; size = minOf(size + 1, CAP)
    }

    @Synchronized fun clear() { next = 0; size = 0 }

    /** Mean rotation over [t0, t1] ns and the samples it used (0: the nearest one; -1: none). */
    @Synchronized fun mean(t0: Long, t1: Long): Pair<DoubleArray, Int> {
        val m = DoubleArray(3); var n = 0
        var best = -1; var bestD = Long.MAX_VALUE
        val c = (t0 + t1) / 2
        for (j in 0 until size) {
            val i = (next - 1 - j + CAP) % CAP
            if (ts[i] >= t0 - PAD_NS && ts[i] <= t1 + PAD_NS) { for (k in 0..2) m[k] += w[i][k]; n++ }
            val d = abs(ts[i] - c)
            if (d < bestD) { bestD = d; best = i }
        }
        if (n > 0) return DoubleArray(3) { m[it] / n } to n
        if (best < 0) return DoubleArray(3) to -1
        return w[best].copyOf() to 0
    }

    companion object {
        /** ~6 s of the ~40 Hz gyro the app registers (the rate the records were built at). */
        const val CAP = 256
        const val PAD_NS = 13_000_000L
    }
}
