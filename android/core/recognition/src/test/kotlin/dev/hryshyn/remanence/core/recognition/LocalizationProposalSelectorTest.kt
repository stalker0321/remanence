package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizationProposalSelectorTest {
    private val profile = RecognitionProfile.mvpOrbV1()
    private val selector = LocalizationProposalSelector(PostcardCropSelector(profile))

    @Test
    fun crediblePrimaryProposalIsTriedBeforeLegacy() {
        val primary = rect(180.0, 150.0, 1420.0, 1050.0, area = 0.58, support = 0.92)
        val legacy = rect(260.0, 220.0, 1340.0, 940.0, area = 0.45, support = null)

        val attempts = selector.orderedAttempts(listOf(primary), listOf(legacy), 1600, 1200)

        assertEquals(2, attempts.size)
        assertFalse(attempts[0].usedGuideFallback)
        assertEquals(LocalizationProposalSource.V2_LINE, attempts[0].proposalSource)
        assertEquals(primary.corners, attempts[0].candidate.corners)
        assertEquals(LocalizationProposalSource.LEGACY_CONTOUR, attempts[1].proposalSource)
        assertEquals(legacy.corners, attempts[1].candidate.corners)
    }

    @Test
    fun primaryWithoutCredibleProposalFallsBackToLegacyBeforeGuide() {
        val invalidPrimary = rect(20.0, 20.0, 120.0, 100.0, area = 0.01, support = 0.95)
        val legacy = rect(240.0, 180.0, 1360.0, 1020.0, area = 0.49, support = null)

        val attempts = selector.orderedAttempts(listOf(invalidPrimary), listOf(legacy), 1600, 1200)

        assertEquals(1, attempts.size)
        assertFalse(attempts.single().usedGuideFallback)
        assertEquals(LocalizationProposalSource.LEGACY_CONTOUR, attempts.single().proposalSource)
        assertEquals(legacy.corners, attempts.single().candidate.corners)
    }

    @Test
    fun noPrimaryAndNoLegacyProposalUsesExistingGuide() {
        val attempts = selector.orderedAttempts(emptyList(), emptyList(), 1600, 1200)

        assertEquals(1, attempts.size)
        assertTrue(attempts.single().usedGuideFallback)
        assertEquals(LocalizationProposalSource.GUIDE_FALLBACK, attempts.single().proposalSource)
    }

    private fun rect(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        area: Double,
        support: Double?,
    ) = QuadCandidate(
        corners = listOf(
            PointD(left, top),
            PointD(right, top),
            PointD(right, bottom),
            PointD(left, bottom),
        ),
        areaRatio = area,
        rectangularity = 0.95,
        edgeSupport = support,
    )
}
