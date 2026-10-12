package dev.hryshyn.remanence.ui.motion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Opening Carry derived contract (freeze `motion.mjs:78–108`,
 * `guide.mjs` motion row "Verified front → whole capsule"): opening budget,
 * front-release/capsule keyframes, easing identity, inscribed scale, travel
 * endpoints and the reduced-motion ghost policy.
 *
 * Pure JVM like `AstraMotionSpecTest`: [AstraOpeningSpec] and the geometry
 * holders have no Android dependency.
 */
class AstraOpeningSpecTest {

    @Test
    fun openingBudgetMatchesTheFreeze() {
        assertEquals(820, AstraMotionSpec.openingMs(AstraMotionSpec.Resolved.FULL))
        assertEquals(80, AstraMotionSpec.openingMs(AstraMotionSpec.Resolved.REDUCED_SHORT))
        assertEquals(0, AstraMotionSpec.openingMs(AstraMotionSpec.Resolved.INSTANT))
    }

    @Test
    fun frontReleaseEndsAtSixtyPercent() {
        assertEquals(408, AstraOpeningSpec.frontReleaseMs(680))
    }

    @Test
    fun capsuleOpacitySamplesTheDissolveEasedKeyframes() {
        // Instants of the authored 0 / 0.12 / 0.72 / 1 opacity keyframes in the
        // *eased* domain. The dissolve option easing moves them off the raw
        // 81 ms / 490 ms that a raw-linear implementation would report.
        assertEquals(114, AstraOpeningSpec.capsuleFirstVisibleMs(680))
        assertEquals(324, AstraOpeningSpec.capsulePresentMs(680))
        assertNotEquals(81, AstraOpeningSpec.capsuleFirstVisibleMs(680))
        assertNotEquals(490, AstraOpeningSpec.capsulePresentMs(680))

        assertEquals(0f, AstraOpeningSpec.capsuleAlphaAt(0f), 0f)
        assertEquals(0f, AstraOpeningSpec.capsuleAlphaAt(114f / 680f), 1e-2f)
        assertEquals(1f, AstraOpeningSpec.capsuleAlphaAt(324f / 680f), 1e-2f)
        assertEquals(1f, AstraOpeningSpec.capsuleAlphaAt(1f), 0f)

        // Mid-flight alpha is the eased remap, not the raw-linear remap: pick a
        // raw fraction whose eased progress is strictly between the keyframes.
        val rawMid = cubicBezierInverse(AstraMotionSpec.EASING_DISSOLVE, 0.4f)
        assertEquals(0.4f, cubicBezierTransform(AstraMotionSpec.EASING_DISSOLVE, rawMid), 1e-3f)
        assertEquals(
            (0.4f - 0.12f) / 0.6f,
            AstraOpeningSpec.capsuleAlphaAt(rawMid),
            1e-3f,
        )
        assertNotEquals(
            (rawMid - 0.12f) / 0.6f,
            AstraOpeningSpec.capsuleAlphaAt(rawMid),
            1e-2f,
        )
    }

    @Test
    fun cubicBezierInverseRoundTripsTheDissolveCurve() {
        for (target in listOf(0.12f, 0.5f, 0.72f)) {
            val raw = cubicBezierInverse(AstraMotionSpec.EASING_DISSOLVE, target)
            assertEquals(target, cubicBezierTransform(AstraMotionSpec.EASING_DISSOLVE, raw), 1e-3f)
        }
    }

    @Test
    fun geometryUsesTravelAndOpacityUsesDissolve() {
        assertSame(AstraMotionSpec.EASING_TRAVEL, AstraOpeningSpec.GEOMETRY_EASING)
        assertSame(AstraMotionSpec.EASING_DISSOLVE, AstraOpeningSpec.OPACITY_EASING)
        assertArrayEquals(floatArrayOf(0.24f, 0.4f, 0.22f, 1f), AstraMotionSpec.EASING_TRAVEL, 0f)
        assertArrayEquals(floatArrayOf(0.32f, 0f, 0.28f, 1f), AstraMotionSpec.EASING_DISSOLVE, 0f)
    }

    @Test
    fun uniformScaleIsTheInscribedRatio() {
        val a = OpeningRect(0f, 0f, 90f, 120f)
        val b = OpeningRect(0f, 0f, 360f, 640f)
        assertEquals(minOf(a.width / b.width, a.height / b.height), AstraOpeningSpec.scaleOf(a, b), 0f)
        assertEquals(1f, AstraOpeningSpec.scaleOf(b, b), 0f)
    }

    @Test
    fun travelStartsAtFrontCenterEndsAtCapsuleCenter() {
        val a = OpeningRect(36f, 120f, 180f, 312f)
        val b = OpeningRect(0f, 0f, 360f, 640f)
        val start = AstraOpeningSpec.translateAt(a, b, 0f)
        assertEquals(a.centerX - b.centerX, start.x, 0f)
        assertEquals(a.centerY - b.centerY, start.y, 0f)
        val end = AstraOpeningSpec.translateAt(a, b, 1f)
        assertEquals(0f, end.x, 0f)
        assertEquals(0f, end.y, 0f)
    }

    @Test
    fun reducedRegimesHaveNoTravel() {
        assertFalse(AstraOpeningSpec.hasGhosts(AstraMotionSpec.Resolved.REDUCED_SHORT))
        assertFalse(AstraOpeningSpec.hasGhosts(AstraMotionSpec.Resolved.INSTANT))
        assertTrue(AstraOpeningSpec.hasGhosts(AstraMotionSpec.Resolved.FULL))
        val a = OpeningRect(36f, 120f, 180f, 312f)
        val settled = AstraOpeningSpec.translateAt(a, a, 1f)
        assertEquals(0f, settled.x, 0f)
        assertEquals(0f, settled.y, 0f)
        assertEquals(1f, AstraOpeningSpec.scaleOf(a, a), 0f)
    }

    @Test
    fun carryFrontConsumesOnceAndClearsOnLeave() {
        val holder = AstraCarryFront()
        assertNull(holder.consumeIf(true))
        val rect = OpeningRect(36f, 120f, 180f, 312f)
        holder.publish(rect)
        assertEquals(rect, holder.consumeIf(true))
        // Second read after the handoff sees nothing: no replay.
        assertNull(holder.consumeIf(true))
        // Inactive reads never expose the rect.
        holder.publish(rect)
        assertNull(holder.consumeIf(false))
        // Leaving to a neutral destination drops a stale rect.
        holder.clear()
        assertNull(holder.consumeIf(true))
    }
}
