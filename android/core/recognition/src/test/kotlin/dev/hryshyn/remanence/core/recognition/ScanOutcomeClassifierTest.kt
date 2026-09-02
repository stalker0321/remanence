package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fail-safe matrix proof for FRONT-only scan outcome classification (ADR-012, M2-F0-01). */
class ScanOutcomeClassifierTest {

    private val classifier = ScanOutcomeClassifier(RecognitionProfile.mvpOrbV1())
    private val notMatchFront = FrontCandidateRanker(RecognitionProfile.mvpOrbV1())
        .rank(emptyList())

    private fun composite(
        id: String,
        score: Double,
        frontWeak: Boolean = true,
    ) = ScoredComposite(
        candidateId = id,
        compositeScore = score,
        frontScore = score,
        frontWeakPassed = frontWeak,
        frontStrongPassed = false,
    )

    private fun report(candidates: List<ScoredComposite>, accepted: ScoredComposite? = null) =
        CompositeAcceptanceReport(candidates, accepted, null)

    @Test
    fun autoAcceptedWinsOverEverythingElse() {
        val leader = composite("leader", 0.85)
        val others = listOf(composite("p2", 0.55), composite("p3", 0.45))

        val classification = classifier.classify(
            FrontRanking(listOf(FrontCandidate("leader", 0.85, weakGatePassed = true)), false),
            report(listOf(leader) + others, accepted = leader),
        )

        assertEquals(ScanOutcome.AUTO_ACCEPTED, classification.outcome)
        assertEquals("leader", classification.accepted?.candidateId)
        assertTrue(classification.chooserRows.isEmpty())
    }

    @Test
    fun noMatchFrontForcesNoMatchEvenWithStaleComposites() {
        val classification = classifier.classify(
            notMatchFront,
            report(listOf(composite("ghost", 0.9), composite("ghost2", 0.8))),
        )

        assertEquals(ScanOutcome.NO_MATCH, classification.outcome)
        assertTrue(classification.chooserRows.isEmpty())
    }

    @Test
    fun twoToFivePlausibleCandidatesBecomeAScoreSortedChooser() {
        val rows = listOf(
            composite("top", 0.60),
            composite("mid", 0.50),
            composite("low", 0.42),
        )

        val classification = classifier.classify(
            FrontRanking(rows.map { FrontCandidate(it.candidateId, it.compositeScore, weakGatePassed = true) }, false),
            report(rows),
        )

        assertEquals(ScanOutcome.PLAUSIBLE_CHOOSER, classification.outcome)
        assertEquals(listOf("top", "mid", "low"), classification.chooserRows.map { it.candidateId })
    }

    @Test
    fun chooserCapsAtFiveRows() {
        val rows = (0 until 7).map { index -> composite("c$index", 0.60 - index * 0.01) }

        val classification = classifier.classify(
            FrontRanking(rows.map { FrontCandidate(it.candidateId, it.compositeScore, weakGatePassed = true) }, false),
            report(rows),
        )

        assertEquals(ScanOutcome.PLAUSIBLE_CHOOSER, classification.outcome)
        assertEquals(5, classification.chooserRows.size)
    }

    @Test
    fun implausibleCandidatesNeverEnterTheChooser() {
        val plausible = composite("plausible", 0.45)
        val belowCompositeFloor = composite("floor", 0.39) // under chooserCompositeMin 0.40
        val noSideWeak = composite("blind", 0.90, frontWeak = false)

        val classification = classifier.classify(
            FrontRanking(
                listOf(FrontCandidate("plausible", 0.45, weakGatePassed = true), FrontCandidate("blind", 0.90, weakGatePassed = true)),
                false,
            ),
            report(listOf(plausible, belowCompositeFloor, noSideWeak)),
        )

        // Only ONE plausible candidate remains -> guided recapture, not a chooser.
        assertEquals(ScanOutcome.SINGLE_CANDIDATE_RECAPTURE, classification.outcome)
        assertTrue(classification.chooserRows.isEmpty())
    }

    @Test
    fun exactlyOnePlausibleCandidateRequestsGuidedRecapture() {
        val classification = classifier.classify(
            FrontRanking(listOf(FrontCandidate("single", 0.5, weakGatePassed = true), FrontCandidate("dud", 0.2, weakGatePassed = true)), false),
            report(
                listOf(
                    composite("single", 0.5),
                    composite("dud", 0.20, frontWeak = false),
                ),
            ),
        )

        assertEquals(ScanOutcome.SINGLE_CANDIDATE_RECAPTURE, classification.outcome)
    }

    @Test
    fun nothingPlausibleIsNoMatch() {
        val classification = classifier.classify(
            FrontRanking(listOf(FrontCandidate("weak-only", 0.3, weakGatePassed = true), FrontCandidate("also-weak", 0.25, weakGatePassed = true)), false),
            report(
                listOf(
                    composite("weak-only", 0.30, frontWeak = false),
                    composite("also-weak", 0.25, frontWeak = false),
                ),
            ),
        )

        assertEquals(ScanOutcome.NO_MATCH, classification.outcome)
        assertTrue(classification.chooserRows.isEmpty())
    }

    @Test
    fun nullAcceptanceReportWithRetainedFrontsIsNoMatch() {
        val classification = classifier.classify(
            FrontRanking(listOf(FrontCandidate("front", 0.6, weakGatePassed = true)), false),
            null,
        )

        assertEquals(ScanOutcome.NO_MATCH, classification.outcome)
    }
}
