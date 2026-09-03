package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Union-level 0/1/N proof for FRONT-only recipient-first / sender-fallback coordination (ADR-012). */
class MatchCoordinatorTest {

    private val coordinator = MatchCoordinator(RecognitionProfile.mvpOrbV1())

    private fun scored(
        candidateId: String,
        score: Double,
        frontWeak: Boolean = true,
        frontStrong: Boolean = true,
    ) = ScoredComposite(
        candidateId = candidateId,
        compositeScore = score,
        frontScore = score,
        frontWeakPassed = frontWeak,
        frontStrongPassed = frontStrong,
    )

    private fun universe(
        origin: CandidateOrigin,
        rows: List<ScoredComposite>,
        autoAccepted: ScoredComposite? = null,
        rejection: RejectionRule? = null,
    ): UniverseScanResult {
        val ranking = FrontRanking(
            retained = rows.filter { it.frontWeakPassed }.map {
                FrontCandidate(it.candidateId, it.frontScore, it.frontWeakPassed)
            },
            duplicateFrontGroup = false,
        )
        val acceptance = if (rows.isEmpty()) {
            null
        } else {
            CompositeAcceptanceReport(rows, autoAccepted, rejection)
        }
        return UniverseScanResult(origin, ranking, acceptance)
    }

    private fun emptyRecipient() = UniverseScanResult(
        CandidateOrigin.RECIPIENT_PREFERRED,
        FrontCandidateRanker(RecognitionProfile.mvpOrbV1()).rank(emptyList()),
        null,
    )

    @Test
    fun uniqueRecipientAcceptanceProceedsWhenSenderIsNotPlausible() {
        val leader = scored("rec-1", 0.85)
        val decision = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                listOf(leader),
                autoAccepted = leader,
            ),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(scored("snd-1", 0.20, frontWeak = false, frontStrong = false)),
            ),
        )

        assertEquals(
            CoordinatorDecision.AutoAccepted(CandidateOrigin.RECIPIENT_PREFERRED, "rec-1"),
            decision,
        )
    }

    @Test
    fun recipientAutoAcceptanceWithDistinctSenderPlausibleIsAmbiguous() {
        val recipientLeader = scored("rec-1", 0.85)
        val senderLeader = scored("snd-1", 0.95)
        val decision = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                listOf(recipientLeader),
                autoAccepted = recipientLeader,
            ),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(senderLeader),
                autoAccepted = senderLeader,
            ),
        )

        val ambiguous = decision as CoordinatorDecision.Ambiguous
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, ambiguous.origin)
        assertEquals(ScanOutcome.PLAUSIBLE_CHOOSER, ambiguous.classification.outcome)
        assertEquals(listOf("snd-1", "rec-1"), ambiguous.classification.chooserRows.map { it.candidateId })
    }

    @Test
    fun recipientAmbiguityNeverFallsBackToSenderGrant() {
        val rows = listOf(scored("r1", 0.55, frontStrong = false), scored("r2", 0.45, frontStrong = false))
        val decision = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                rows,
                rejection = RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL,
            ),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(scored("snd-1", 0.20, frontWeak = false, frontStrong = false)),
            ),
        )

        val ambiguous = decision as CoordinatorDecision.Ambiguous
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, ambiguous.origin)
        assertEquals(ScanOutcome.PLAUSIBLE_CHOOSER, ambiguous.classification.outcome)
        assertEquals(listOf("r1", "r2"), ambiguous.classification.chooserRows.map { it.candidateId })
    }

    @Test
    fun recipientSingleRecaptureStaysWhenSenderIsNotPlausible() {
        val only = scored("only", 0.50, frontStrong = false)
        val decision = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                listOf(only),
                rejection = RejectionRule.FRONT_BELOW_AUTO_MIN,
            ),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(scored("snd", 0.20, frontWeak = false, frontStrong = false)),
            ),
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
    fun senderFallbackOnlyWhenRecipientHasNoPlausible() {
        val senderLeader = scored("snd-old", 0.85)
        val decision = coordinator.coordinate(
            emptyRecipient(),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(senderLeader),
                autoAccepted = senderLeader,
            ),
        )

        val fallback = decision as CoordinatorDecision.SenderFallbackAccepted
        assertEquals("snd-old", fallback.candidateId)
        assertTrue(fallback.suggestsBaselineImprovement)
    }

    @Test
    fun zeroRecipientPlausiblePermitsSenderFallback() {
        val implausible = listOf(
            scored("aged-1", 0.30, frontStrong = false),
            scored("aged-2", 0.20, frontStrong = false),
        )
        val senderLeader = scored("snd", 0.9)
        val decision = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                implausible,
            ),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(senderLeader),
                autoAccepted = senderLeader,
            ),
        )

        val fallback = decision as CoordinatorDecision.SenderFallbackAccepted
        assertEquals("snd", fallback.candidateId)
    }

    @Test
    fun nothingAnywhereYieldsNoMatch() {
        assertEquals(
            CoordinatorDecision.NoMatchEverywhere,
            coordinator.coordinate(emptyRecipient(), null),
        )
        assertEquals(
            CoordinatorDecision.NoMatchEverywhere,
            coordinator.coordinate(
                emptyRecipient(),
                universe(CandidateOrigin.SENDER_FALLBACK, emptyList()),
            ),
        )
    }

    @Test
    fun universeOrderingAndOriginsAreEnforced() {
        assertFailsWith<IllegalArgumentException> {
            coordinator.coordinate(
                universe(CandidateOrigin.SENDER_FALLBACK, listOf(scored("x", 0.8))),
                null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.coordinate(
                emptyRecipient(),
                universe(CandidateOrigin.RECIPIENT_PREFERRED, listOf(scored("x", 0.8))),
            )
        }
        assertFalse(false)
    }

    @Test
    fun sameCapsuleCrossOriginDedupsAndPrefersRecipientBaseline() {
        val recipient = scored("same", 0.80)
        val senderHigher = scored("same", 0.95)
        val decision = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                listOf(recipient),
                autoAccepted = recipient,
            ),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(senderHigher),
                autoAccepted = senderHigher,
            ),
        )

        assertEquals(
            CoordinatorDecision.AutoAccepted(CandidateOrigin.RECIPIENT_PREFERRED, "same"),
            decision,
        )
    }

    @Test
    fun genuinelyScoreSeparatedDistinctPlausiblesStillAmbiguous() {
        val high = scored("high", 0.85)
        val low = scored("low", 0.45)
        assertTrue(high.compositeScore != low.compositeScore)

        val sameOrigin = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                listOf(high, low),
                autoAccepted = high,
            ),
            null,
        )
        val sameAmbiguous = sameOrigin as CoordinatorDecision.Ambiguous
        assertEquals(ScanOutcome.PLAUSIBLE_CHOOSER, sameAmbiguous.classification.outcome)
        val sameScores = sameAmbiguous.classification.chooserRows.map { it.compositeScore }
        assertEquals(2, sameScores.size)
        assertTrue(sameScores[0] != sameScores[1], "same-origin plausible scores must differ")
        assertEquals(0.85, sameScores[0], 1e-12)
        assertEquals(0.45, sameScores[1], 1e-12)

        val crossOrigin = coordinator.coordinate(
            universe(
                CandidateOrigin.RECIPIENT_PREFERRED,
                listOf(high),
                autoAccepted = high,
            ),
            universe(
                CandidateOrigin.SENDER_FALLBACK,
                listOf(low),
            ),
        )
        val crossAmbiguous = crossOrigin as CoordinatorDecision.Ambiguous
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, crossAmbiguous.origin)
        assertEquals(ScanOutcome.PLAUSIBLE_CHOOSER, crossAmbiguous.classification.outcome)
        val crossScores = crossAmbiguous.classification.chooserRows.map { it.compositeScore }
        assertEquals(2, crossScores.size)
        assertTrue(crossScores[0] != crossScores[1], "cross-origin plausible scores must differ")
        assertEquals(0.85, crossScores[0], 1e-12)
        assertEquals(0.45, crossScores[1], 1e-12)
        assertEquals(null, crossAmbiguous.classification.accepted)
    }
}
