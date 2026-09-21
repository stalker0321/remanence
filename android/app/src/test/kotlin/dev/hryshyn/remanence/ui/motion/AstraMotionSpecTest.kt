package dev.hryshyn.remanence.ui.motion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Astra fidelity motion contract against the authoritative study
 * values (`design/motion/motion-original.mjs`, the continuous-Carry
 * revision `motion.mjs`, `design/research/motion.md`):
 * exact durations, exact easing control points, and the reduced-motion
 * policy (short 80 ms replacement / instant snap, never an infinite
 * pulse — pulseWait is skipped when reduced).
 *
 * Pure JVM: [AstraMotionSpec] has no Compose dependency. NOT run in this
 * tick (no Gradle execution per the task brief).
 */
class AstraMotionSpecTest {

    @Test
    fun routineContextDurationIsAuthored() {
        assertEquals(340, AstraMotionSpec.ROUTINE_CONTEXT_MS)
    }

    @Test
    fun imageTravelSitsInsideTheAuthoredRange() {
        assertEquals(420, AstraMotionSpec.IMAGE_TRAVEL_MIN_MS)
        assertEquals(480, AstraMotionSpec.IMAGE_TRAVEL_MAX_MS)
        val travel = AstraMotionSpec.IMAGE_TRAVEL_MS
        assertTrue(
            "image travel $travel outside 420..480",
            travel in AstraMotionSpec.IMAGE_TRAVEL_MIN_MS..AstraMotionSpec.IMAGE_TRAVEL_MAX_MS,
        )
    }

    @Test
    fun openingAndReducedDurationsAreAuthored() {
        assertEquals(680, AstraMotionSpec.OPENING_MS)
        assertEquals(80, AstraMotionSpec.REDUCED_CROSSFADE_MS)
    }

    @Test
    fun easingControlPointsAreAuthored() {
        assertArrayEquals(
            floatArrayOf(0.2f, 0.75f, 0.25f, 1.0f),
            AstraMotionSpec.EASING_GENTLE,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(0.22f, 0.64f, 0.2f, 1.0f),
            AstraMotionSpec.EASING_TRAVEL,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(0.32f, 0.0f, 0.28f, 1.0f),
            AstraMotionSpec.EASING_DISSOLVE,
            0f,
        )
    }

    @Test
    fun focusSelectDepthIsAuthored() {
        assertEquals(0.987f, AstraMotionSpec.FOCUS_SELECT_SCALE_FROM, 0f)
    }

    @Test
    fun animatorScaleZeroResolvesToInstant() {
        assertEquals(AstraMotionSpec.Resolved.INSTANT, AstraMotionSpec.resolve(0f))
        assertEquals(AstraMotionSpec.Resolved.INSTANT, AstraMotionSpec.resolve(-1f))
    }

    @Test
    fun shortenedScaleResolvesToShortReplacement() {
        assertEquals(AstraMotionSpec.Resolved.REDUCED_SHORT, AstraMotionSpec.resolve(0.5f))
    }

    @Test
    fun fullScaleResolvesToFull() {
        assertEquals(AstraMotionSpec.Resolved.FULL, AstraMotionSpec.resolve(1f))
        assertEquals(AstraMotionSpec.Resolved.FULL, AstraMotionSpec.resolve(2f))
    }

    @Test
    fun fullRegimeKeepsAuthoredDurations() {
        assertEquals(340, AstraMotionSpec.routineMs(AstraMotionSpec.Resolved.FULL))
        assertEquals(450, AstraMotionSpec.imageTravelMs(AstraMotionSpec.Resolved.FULL))
        assertEquals(680, AstraMotionSpec.openingMs(AstraMotionSpec.Resolved.FULL))
    }

    @Test
    fun reducedRegimeCollapsesToShortCrossfade() {
        assertEquals(80, AstraMotionSpec.routineMs(AstraMotionSpec.Resolved.REDUCED_SHORT))
        assertEquals(80, AstraMotionSpec.imageTravelMs(AstraMotionSpec.Resolved.REDUCED_SHORT))
        assertEquals(80, AstraMotionSpec.openingMs(AstraMotionSpec.Resolved.REDUCED_SHORT))
    }

    @Test
    fun instantRegimeIsZeroDuration() {
        assertEquals(0, AstraMotionSpec.routineMs(AstraMotionSpec.Resolved.INSTANT))
        assertEquals(0, AstraMotionSpec.imageTravelMs(AstraMotionSpec.Resolved.INSTANT))
        assertEquals(0, AstraMotionSpec.openingMs(AstraMotionSpec.Resolved.INSTANT))
    }
}
