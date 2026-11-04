package com.example.activeperception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationPhaseTrackerTest {
    @Test fun confirmsOnlyRealDirectionReversals() {
        val tracker = OscillationPhaseTracker(
            moveThresholdRadS = 0.10,
            reversalConfirmMs = 100,
            minHalfSweepDeg = 15.0)

        assertNull(tracker.update(0, 0.0, 0.5))
        assertNull(tracker.update(100, 20.0, 0.5))
        // One opposite sample is only a pending reversal.
        assertNull(tracker.update(200, 40.0, -0.8))
        val right = tracker.update(350, 35.0, -0.8)!!
        assertEquals(-1, right.newDirection)
        assertEquals(35.0, right.sweepDeg, 0.001)

        assertNull(tracker.update(450, 10.0, -0.6))
        assertNull(tracker.update(550, 0.0, 0.8))
        val left = tracker.update(700, 5.0, 0.8)!!
        assertEquals(1, left.newDirection)
        assertEquals(30.0, left.sweepDeg, 0.001)
    }

    @Test fun ignoresSmallJitterAroundAnEndpoint() {
        val tracker = OscillationPhaseTracker(
            moveThresholdRadS = 0.10,
            reversalConfirmMs = 100,
            minHalfSweepDeg = 15.0)
        tracker.update(0, 0.0, 0.5)
        tracker.update(100, 5.0, -0.7)
        assertNull(tracker.update(250, 4.0, -0.7))
        assertEquals(1, tracker.direction)
    }
}
