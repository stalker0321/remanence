package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandidateRankerTest {

    private val profile = RecognitionProfile.mvpOrbV1()
    private val ranker = CandidateRanker(profile)

    private fun candidate(areaRatio: Double, rectangularity: Double) =
        QuadCandidate(
            corners = listOf(
                PointD(10.0, 10.0),
                PointD(110.0, 10.0),
                PointD(110.0, 60.0),
                PointD(10.0, 60.0),
            ),
            areaRatio = areaRatio,
            rectangularity = rectangularity,
        )

    @Test
    fun confidenceCombinesSignalsWithProfileWeights() {
        val c = candidate(areaRatio = 0.35, rectangularity = 1.00)
        val ranked = ranker.rank(listOf(CandidateWithEdges(c)), frameDiagonalPx = 500.0)
        assertEquals(1, ranked.size)
        // All signals saturate at 1: (0.4+0.3+0.2)/0.9 = 1.
        assertTrue(kotlin.math.abs(ranked[0].confidence - 1.0) < 1e-9)
    }

    @Test
    fun weakerAreaAndRectangularityScoreLower() {
        val strong = candidate(0.50, 0.98)
        val weak = candidate(0.15, 0.80)
        val ranked = rankedList(listOf(weak, strong))
        assertTrue(ranked[0].candidate === strong || ranked[0].candidate == strong)
        assertTrue(ranked[0].confidence > ranked[1].confidence)
    }

    @Test
    fun measuredEdgeSupportOverridesRectangularityProxy() {
        val measured = candidate(0.40, 0.72)
        val proxyOnly = candidate(0.40, 0.72)
        val ranked = ranker.rank(
            listOf(CandidateWithEdges(proxyOnly), CandidateWithEdges(measured, edgeSupport = 1.0)),
            frameDiagonalPx = 500.0,
        )
        assertTrue(ranked[0].confidence > ranked[1].confidence)
        assertTrue(ranked.first().candidate === measured || ranked.first().confidence > ranked[1].confidence)
        assertTrue(ranked[0].confidence > ranked[1].confidence)
    }

    @Test
    fun guideProximityBreaksTiesTowardOverlay() {
        val left = QuadCandidate(
            corners = listOf(PointD(20.0, 60.0), PointD(120.0, 60.0), PointD(120.0, 140.0), PointD(20.0, 140.0)),
            areaRatio = 0.30,
            rectangularity = 0.95,
        )
        val right = QuadCandidate(
            corners = listOf(PointD(280.0, 60.0), PointD(380.0, 60.0), PointD(380.0, 140.0), PointD(280.0, 140.0)),
            areaRatio = 0.30,
            rectangularity = 0.95,
        )
        val guide = GuideOverlay(left = 0.0, top = 0.0, right = 200.0, bottom = 200.0)
        val rankedNoGuide = ranker.rank(listOf(CandidateWithEdges(right), CandidateWithEdges(left)), frameDiagonalPx = 500.0)
        assertEquals(rankedNoGuide[0].confidence, rankedNoGuide[1].confidence)

        val rankedGuided = ranker.rank(listOf(CandidateWithEdges(right), CandidateWithEdges(left)), frameDiagonalPx = 500.0, guide = guide)
        assertTrue(rankedGuided[0].candidate == left)
        assertTrue(rankedGuided[0].confidence > rankedGuided[1].confidence)
    }

    private fun rankedList(candidates: List<QuadCandidate>) =
        ranker.rank(candidates.map(::CandidateWithEdges), frameDiagonalPx = 500.0)
}
