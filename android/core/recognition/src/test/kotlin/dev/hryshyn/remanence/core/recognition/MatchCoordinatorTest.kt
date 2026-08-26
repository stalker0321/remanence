package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ordering proof for M1-M09 recipient-first / sender-fallback coordination. */
class MatchCoordinatorTest {

    private val coordinator = MatchCoordinator(RecognitionProfile.mvpOrbV1())

    private fun acceptedReport(candidateId: String, score: Double = 0.85) = run {
        val leader = ScoredComposite(
            candidateId = candidateId,
            compositeScore = score,
            frontScore = score,
            backScore = score,
            frontWeakPassed = true,
            frontStrongPassed = true,
            backWeakPassed = true,
            backStrongPassed = true,
        )
        CompositeAcceptanceReport(listOf(leader), leader, null)
    }

    private fun rankingWithRetained(count: Int) = FrontRanking(
        retained = (0 until count).map { index ->
            FrontCandidate("front-$index", 0.5 - index * 0.01, weakGatePassed = true)
        },
        duplicateFrontGroup = false,
    )

    private fun universe(
        origin: CandidateOrigin,
        retained: Int,
        acceptance: CompositeAcceptanceReport? = null,
    ) = UniverseScanResult(origin, rankingWithRetained(retained), acceptance)

    @Test
    fun recipientAutoAcceptanceStopsBeforeSenderIsConsulted() {
        val decision = coordinator.coordinate(
            universe(CandidateOrigin.RECIPIENT_PREFERRED, 1, acceptedReport("rec-1")),
            universe(CandidateOrigin.SENDER_FALLBACK, 3, acceptedReport("snd-1", 0.95)),
        )

        assertEquals(
            CoordinatorDecision.AutoAccepted(CandidateOrigin.RECIPIENT_PREFERRED, "rec-1"),
            decision,
        )
    }

    @Test
    fun recipientAmbiguityNeverFallsBackToSender() {
        val rows = listOf(
            ScoredComposite(
                "r1", 0.55, 0.55, 0.55, true, false, true, false,
            ),
            ScoredComposite(
                "r2", 0.45, 0.45, 0.45, true, false, true, false,
            ),
        )
        val chooserReport = CompositeAcceptanceReport(rows, null, RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL)
        val decision = coordinator.coordinate(
            universe(CandidateOrigin.RECIPIENT_PREFERRED, 2, chooserReport),
            universe(CandidateOrigin.SENDER_FALLBACK, 3, acceptedReport("snd-1")),
        )

        val ambiguous = decision as CoordinatorDecision.Ambiguous
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, ambiguous.origin)
        assertEquals(ScanOutcome.PLAUSIBLE_CHOOSER, ambiguous.classification.outcome)
        assertEquals(listOf("r1", "r2"), ambiguous.classification.chooserRows.map { it.candidateId })
    }

    @Test
    fun recipientSingleRecaptureAlsoStaysPut() {
        val single = listOf(
            ScoredComposite("only", 0.50, 0.50, 0.50, true, false, true, false),
        )
        val report = CompositeAcceptanceReport(single, null, RejectionRule.COMPOSITE_BELOW_MINIMUM)
        val decision = coordinator.coordinate(
            universe(CandidateOrigin.RECIPIENT_PREFERRED, 1, report),
            universe(CandidateOrigin.SENDER_FALLBACK, 2),
        )

        assertEquals(
            CoordinatorDecision.Ambiguous(
                CandidateOrigin.RECIPIENT_PREFERRED,
                ScanClassification(ScanOutcome.SINGLE_CANDIDATE_RECAPTURE, null, emptyList()),
            ),
            decision,
        )
    }

    @Test
    fun senderFallbackOnlyWhenRecipientHasNoWeakEvidence() {
        val emptyRecipient = UniverseScanResult(
            CandidateOrigin.RECIPIENT_PREFERRED,
            FrontCandidateRanker(RecognitionProfile.mvpOrbV1()).rank(emptyList()),
            null,
        )
        val decision = coordinator.coordinate(
            emptyRecipient,
            universe(CandidateOrigin.SENDER_FALLBACK, 1, acceptedReport("snd-old")),
        )

        val fallback = decision as CoordinatorDecision.SenderFallbackAccepted
        assertEquals("snd-old", fallback.candidateId)
        assertTrue(fallback.suggestsBaselineImprovement)
    }

    @Test
    fun weakButImplausibleRecipientsDoNotTriggerSilentFallback() {
        // Two retained fronts (weak passed) whose composites never became
        // plausible: the aged-card identity still exists; no silent re-search.
        val implausible = listOf(
            ScoredComposite("aged-1", 0.30, 0.30, 0.30, true, false, true, false),
            ScoredComposite("aged-2", 0.20, 0.20, 0.20, true, false, false, false),
        )
        val report = CompositeAcceptanceReport(implausible, null, null)
        val decision = coordinator.coordinate(
            universe(CandidateOrigin.RECIPIENT_PREFERRED, 2, report),
            universe(CandidateOrigin.SENDER_FALLBACK, 4, acceptedReport("snd", 0.9)),
        )

        assertEquals(CoordinatorDecision.NoMatchEverywhere, decision)
    }

    @Test
    fun nothingAnywhereYieldsNoMatch() {
        val emptyRecipient = UniverseScanResult(
            CandidateOrigin.RECIPIENT_PREFERRED,
            FrontCandidateRanker(RecognitionProfile.mvpOrbV1()).rank(emptyList()),
            null,
        )
        assertEquals(
            CoordinatorDecision.NoMatchEverywhere,
            coordinator.coordinate(emptyRecipient, null),
        )
        assertEquals(
            CoordinatorDecision.NoMatchEverywhere,
            coordinator.coordinate(
                emptyRecipient,
                universe(CandidateOrigin.SENDER_FALLBACK, 0),
            ),
        )
    }

    @Test
    fun universeOrderingAndOriginsAreEnforced() {
        assertFailsWith<IllegalArgumentException> {
            coordinator.coordinate(
                universe(CandidateOrigin.SENDER_FALLBACK, 1),
                null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.coordinate(
                universe(CandidateOrigin.RECIPIENT_PREFERRED, 0),
                universe(CandidateOrigin.RECIPIENT_PREFERRED, 1),
            )
        }
        assertFalse(false)
    }
}
