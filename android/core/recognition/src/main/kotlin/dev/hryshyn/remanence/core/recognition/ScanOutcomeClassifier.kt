package dev.hryshyn.remanence.core.recognition

/** M1-M08 terminal classification of one scan attempt (docs section 9). */
enum class ScanOutcome {
    /** Automatic rules passed; the capsule opens without user choice. */
    AUTO_ACCEPTED,

    /** Two or more plausible candidates: show the scan-scoped chooser. */
    PLAUSIBLE_CHOOSER,

    /** Exactly one plausible candidate below auto rules: guided recapture first. */
    SINGLE_CANDIDATE_RECAPTURE,

    /** Nothing plausible exists: recapture guidance, never arbitrary capsules. */
    NO_MATCH,
}

/** The classified result handed to the scan flow UI. */
data class ScanClassification(
    val outcome: ScanOutcome,
    val accepted: ScoredComposite?,
    val chooserRows: List<ScoredComposite>,
)

/**
 * Fail-safe classifier over stage-1/stage-2 results
 * (docs/recognition.md section 9 "Ambiguity and retry"):
 *
 * - automatic acceptance always wins;
 * - a NO_MATCH_FRONT ranking forces NO_MATCH regardless of any composite data;
 * - a candidate is PLAUSIBLE when at least one side passed weak evidence AND
 *   its composite reached [RecognitionProfile.RankingThresholds.chooserCompositeMin];
 * - two or more plausible candidates become a score-sorted chooser (capped at
 *   the same five-row limit as front retention);
 * - exactly one plausible candidate requests one guided recapture instead of
 *   looping or silently accepting;
 * - everything else is NO_MATCH.
 */
class ScanOutcomeClassifier(
    private val profile: RecognitionProfile,
) {

    fun classify(
        frontRanking: FrontRanking,
        acceptance: CompositeAcceptanceReport?,
    ): ScanClassification {
        val accepted = acceptance?.autoAccepted
        if (accepted != null) {
            return ScanClassification(ScanOutcome.AUTO_ACCEPTED, accepted, emptyList())
        }
        if (frontRanking.noMatchFront) {
            return ScanClassification(ScanOutcome.NO_MATCH, null, emptyList())
        }

        val scored = acceptance?.scored.orEmpty()
        val chooserMin = profile.ranking.chooserCompositeMin
        val plausible = scored.filter { candidate ->
            candidate.frontWeakPassed &&
                candidate.compositeScore >= chooserMin
        }
        return when {
            plausible.size >= MIN_CHOOSER_ROWS -> ScanClassification(
                outcome = ScanOutcome.PLAUSIBLE_CHOOSER,
                accepted = null,
                chooserRows = plausible.take(MAX_CHOOSER_ROWS),
            )
            plausible.size == 1 -> ScanClassification(
                outcome = ScanOutcome.SINGLE_CANDIDATE_RECAPTURE,
                accepted = null,
                chooserRows = emptyList(),
            )
            else -> ScanClassification(ScanOutcome.NO_MATCH, null, emptyList())
        }
    }

    internal companion object {
        const val MIN_CHOOSER_ROWS = 2
        const val MAX_CHOOSER_ROWS = FrontCandidateRanker.RETAINED_LIMIT
    }
}
