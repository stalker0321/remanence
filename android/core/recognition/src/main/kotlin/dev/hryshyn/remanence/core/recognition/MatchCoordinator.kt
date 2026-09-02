package dev.hryshyn.remanence.core.recognition

/** Which fingerprint index a scan result came from (docs section 2, 11). */
enum class CandidateOrigin { RECIPIENT_PREFERRED, SENDER_FALLBACK }

/** Stage results for one candidate universe (preferred or fallback). */
data class UniverseScanResult(
    val origin: CandidateOrigin,
    val frontRanking: FrontRanking,
    val acceptance: CompositeAcceptanceReport?,
)

/**
 * M1-M09 decision over both universes (docs/recognition.md section 11):
 * recipient pairs are searched first; automatic acceptance there stops the
 * scan; plausible or ambiguous recipient results use the normal chooser rules;
 * ONLY an empty weak-evidence universe falls back to sender pairs. A sender
 * fallback never replaces the recipient baseline - it reports
 * [SenderFallbackAccepted.suggestsBaselineImprovement] instead.
 */
sealed interface CoordinatorDecision {
    /** Stop immediately and verify crypto for this candidate. */
    data class AutoAccepted(
        val origin: CandidateOrigin,
        val candidateId: String,
    ) : CoordinatorDecision

    /** Chooser or guided-recapture surfaces, scoped to one universe. */
    data class Ambiguous(
        val origin: CandidateOrigin,
        val classification: ScanClassification,
    ) : CoordinatorDecision

    /** Recipient had nothing weak; sender index decided the scan instead. */
    data class SenderFallbackAccepted(
        val candidateId: String,
        val suggestsBaselineImprovement: Boolean = true,
    ) : CoordinatorDecision

    /** No candidate anywhere; show recapture guidance. */
    data object NoMatchEverywhere : CoordinatorDecision
}

/**
 * Coordinates the recipient-first then sender-fallback strategy using the
 * M1-M08 classifier per universe. The sender universe is consulted ONLY when
 * the recipient universe retained no weak-evidence candidates at all; every
 * other recipient outcome terminates the scan.
 */
class MatchCoordinator(
    private val profile: RecognitionProfile,
) {

    private val classifier = ScanOutcomeClassifier(profile)

    fun coordinate(
        recipient: UniverseScanResult,
        sender: UniverseScanResult?,
    ): CoordinatorDecision {
        require(recipient.origin == CandidateOrigin.RECIPIENT_PREFERRED) {
            "first universe must be the recipient-preferred index"
        }
        val recipientDecision = classifier.classify(recipient.frontRanking, recipient.acceptance)

        return when {
            recipientDecision.accepted != null ->
                CoordinatorDecision.AutoAccepted(
                    CandidateOrigin.RECIPIENT_PREFERRED,
                    recipientDecision.accepted.candidateId,
                )
            recipientDecision.outcome == ScanOutcome.PLAUSIBLE_CHOOSER ||
                recipientDecision.outcome == ScanOutcome.SINGLE_CANDIDATE_RECAPTURE ->
                CoordinatorDecision.Ambiguous(CandidateOrigin.RECIPIENT_PREFERRED, recipientDecision)
            recipient.frontRanking.retained.isNotEmpty() ->
                // Weak evidence existed but nothing reached plausibility; the
                // aged-card identity still beat the fallback threshold, so we
                // do not silently re-search against sender baselines.
                CoordinatorDecision.NoMatchEverywhere
            else -> fallbackToSender(sender)
        }
    }

    private fun fallbackToSender(sender: UniverseScanResult?): CoordinatorDecision {
        if (sender == null || sender.frontRanking.retained.isEmpty()) {
            return CoordinatorDecision.NoMatchEverywhere
        }
        require(sender.origin == CandidateOrigin.SENDER_FALLBACK) {
            "fallback universe must be the sender index"
        }
        val senderDecision = classifier.classify(sender.frontRanking, sender.acceptance)
        return when {
            senderDecision.accepted != null ->
                CoordinatorDecision.SenderFallbackAccepted(senderDecision.accepted.candidateId)
            senderDecision.outcome == ScanOutcome.PLAUSIBLE_CHOOSER ||
                senderDecision.outcome == ScanOutcome.SINGLE_CANDIDATE_RECAPTURE ->
                CoordinatorDecision.Ambiguous(CandidateOrigin.SENDER_FALLBACK, senderDecision)
            else -> CoordinatorDecision.NoMatchEverywhere
        }
    }
}
