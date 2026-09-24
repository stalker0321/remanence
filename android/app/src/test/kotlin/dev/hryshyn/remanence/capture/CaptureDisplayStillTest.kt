package dev.hryshyn.remanence.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bounds proof for the capture-once display frame: the subsample factor
 * keeps the longer side within the cap as a power of two, and invalid
 * inputs fail loudly instead of producing a zero-sized decode request.
 * Pure JVM math — no runner needed.
 */
class CaptureDisplayStillTest {

    @Test
    fun sampleSizeKeepsLongSideWithinCap() {
        assertEquals(16, captureDisplaySampleSize(4000, 3000, 480))
        assertEquals(2, captureDisplaySampleSize(800, 600, 480))
        assertEquals(1, captureDisplaySampleSize(480, 480, 480))
        assertEquals(1, captureDisplaySampleSize(100, 100, 480))
        assertEquals(4, captureDisplaySampleSize(1920, 1080, 480))
    }

    @Test
    fun sampleSizeRejectsNonPositiveInputs() {
        try {
            captureDisplaySampleSize(0, 10, 480)
            fail("zero width must fail")
        } catch (_: IllegalArgumentException) {
        }
        try {
            captureDisplaySampleSize(10, 10, 0)
            fail("zero cap must fail")
        } catch (_: IllegalArgumentException) {
        }
    }
}
