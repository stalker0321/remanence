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
 * Fail-safe 0/1/N classifier over a scored candidate universe
 * (docs/recognition.md section 9 "Ambiguity and retry"):
 *
 * - a candidate is PLAUSIBLE when FRONT passed weak evidence AND its
 *   composite reached [RecognitionProfile.RankingThresholds.chooserFrontMin];
 * - a NO_MATCH_FRONT ranking forces NO_MATCH regardless of any composite data;
 * - N≥2 distinct plausibles are always a score-sorted chooser (capped at the
 *   same five-row limit as front retention), even if a leader would pass
 *   automatic acceptance or a score margin;
 * - a unique automatic acceptance may open only when N≤1;
 * - exactly one plausible below auto rules requests one guided recapture;
 * - everything else is NO_MATCH.
 */
class ScanOutcomeClassifier(
    private val profile: RecognitionProfile,
) {

    fun classify(
        frontRanking: FrontRanking,
        acceptance: CompositeAcceptanceReport?,
    ): ScanClassification {
        if (frontRanking.noMatchFront) {
            return ScanClassification(ScanOutcome.NO_MATCH, null, emptyList())
        }

        val scored = acceptance?.scored.orEmpty()
        val plausible = plausibleFrontCandidates(scored, profile.ranking.chooserFrontMin)
            .sortedByDescending { it.compositeScore }

        if (plausible.size >= MIN_CHOOSER_ROWS) {
            return ScanClassification(
                outcome = ScanOutcome.PLAUSIBLE_CHOOSER,
                accepted = null,
                chooserRows = plausible.take(MAX_CHOOSER_ROWS),
            )
        }

        val accepted = acceptance?.autoAccepted
        if (accepted != null) {
            return ScanClassification(ScanOutcome.AUTO_ACCEPTED, accepted, emptyList())
        }

        return when {
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

/** Shared plausibility predicate so ranking, acceptance, and classification stay aligned. */
internal fun plausibleFrontCandidates(
    scored: List<ScoredComposite>,
    chooserFrontMin: Double,
): List<ScoredComposite> =
    scored.filter { candidate ->
        candidate.frontWeakPassed && candidate.compositeScore >= chooserFrontMin
    }
