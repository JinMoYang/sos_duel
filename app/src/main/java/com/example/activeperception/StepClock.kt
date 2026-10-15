package com.example.activeperception

import android.os.SystemClock

/**
 * One decision step's timeline, every mark on SystemClock.elapsedRealtimeNanos. On the S26U
 * the camera's SENSOR_TIMESTAMP (start of exposure) uses the same clock (timestamp source
 * REALTIME, recorded in each manifest), so the step can be measured end to end: from the
 * first photon of the images a decision used to the moment its detections were ready.
 *
 * Every method that logs through MeasurementController.logFrame writes these columns; a mark
 * a method never sets stays blank rather than borrowing a stale value from an earlier step.
 */
class StepClock {
    @Volatile var requestNs = 0L; private set
    @Volatile var firstExposureNs = 0L; private set
    @Volatile var lastExposureEndNs = 0L; private set
    @Volatile var capturedNs = 0L; private set
    @Volatile var formedNs = 0L; private set
    @Volatile var detectedNs = 0L; private set
    @Volatile var decidedNs = 0L; private set

    private fun now() = SystemClock.elapsedRealtimeNanos()

    /** Start of a step: the capture request is about to be issued. */
    fun begin() { reset(); requestNs = now() }

    /** Frames are in hand; their exposure windows come from the result metadata. */
    fun captured(metas: List<RawSensorCapturer.RawMeta>) {
        capturedNs = now()
        exposures(metas)
    }

    /** Exposure windows only, for callers that mark "captured" separately. */
    fun exposures(metas: List<RawSensorCapturer.RawMeta>) {
        var first = Long.MAX_VALUE
        var lastEnd = 0L
        for (m in metas) {
            if (m.timestamp <= 0L) continue
            first = minOf(first, m.timestamp)
            lastEnd = maxOf(lastEnd, m.timestamp + maxOf(0L, m.appliedExpUs) * 1_000L)
        }
        if (first != Long.MAX_VALUE) firstExposureNs = first
        if (lastEnd > 0L) lastExposureEndNs = lastEnd
    }

    fun formed() { formedNs = now() }
    fun detected() { detectedNs = now() }
    fun decided() { decidedNs = now() }

    fun reset() {
        requestNs = 0L; firstExposureNs = 0L; lastExposureEndNs = 0L
        capturedNs = 0L; formedNs = 0L; detectedNs = 0L; decidedNs = 0L
    }

    /** Values for [HEADERS], in order; unset marks are blank. [loggedNs] is taken by the caller. */
    fun columns(loggedNs: Long): List<Any> {
        fun c(v: Long): Any = if (v > 0L) v else ""
        return listOf(c(requestNs), c(firstExposureNs), c(lastExposureEndNs), c(capturedNs),
            c(formedNs), c(detectedNs), c(decidedNs), c(loggedNs))
    }

    companion object {
        val HEADERS = listOf("t_request_ns", "t_first_exposure_ns", "t_last_exposure_end_ns",
            "t_captured_ns", "t_formed_ns", "t_detected_ns", "t_decided_ns", "t_logged_ns")
        fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
    }
}
