package dev.hryshyn.remanence.capture

import dev.hryshyn.remanence.core.recognition.PointD
import dev.hryshyn.remanence.core.recognition.QuadCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningQuadTest {
    private fun rect(l: Double, t: Double, r: Double, b: Double) =
        listOf(PointD(l, t), PointD(r, t), PointD(r, b), PointD(l, b))

    private fun candidate(corners: List<PointD>, area: Double = 0.2, rect: Double = 0.97, edge: Double? = 1.0) =
        QuadCandidate(corners, area, rect, edge)

    @Test fun strictCropIsUsedAsIs() {
        val card = rect(100.0, 100.0, 400.0, 310.0)
        assertEquals(card, OpeningQuad.select(candidate(card), usedGuideFallback = false, lineCandidates = null, frameWidth = 800, frameHeight = 600))
    }

    @Test fun guideFallbackRecoversASmallButClearPostcard() {
        val guide = rect(0.0, 0.0, 800.0, 600.0)
        val small = rect(300.0, 200.0, 440.0, 300.0) // 1.4 aspect
        assertEquals(small, OpeningQuad.select(candidate(guide), true, listOf(candidate(small, area = 0.09)), 800, 600))
    }

    @Test fun nonPostcardProportionsOrWeakEdgesAreRefused() {
        val guide = rect(0.0, 0.0, 800.0, 600.0)
        val half = rect(300.0, 200.0, 370.0, 300.0)
        val square = rect(300.0, 200.0, 400.0, 300.0)
        assertNull(OpeningQuad.select(candidate(guide), true, listOf(candidate(square)), 800, 600))
        assertNull(OpeningQuad.select(candidate(guide), true, listOf(candidate(half, edge = 0.80)), 800, 600))
        assertNull(OpeningQuad.select(candidate(guide), true, listOf(candidate(half, edge = null)), 800, 600))
        assertNull(OpeningQuad.select(candidate(guide), true, null, 800, 600))
    }

    @Test fun cornersAreOrderedTopLeftClockwise() {
        val shuffled = listOf(PointD(400.0, 310.0), PointD(100.0, 100.0), PointD(100.0, 310.0), PointD(400.0, 100.0))
        assertEquals(rect(100.0, 100.0, 400.0, 310.0), OpeningQuad.order(shuffled))
    }
}
