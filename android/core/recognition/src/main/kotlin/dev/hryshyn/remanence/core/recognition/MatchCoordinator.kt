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
 * FRONT candidates are scored in the recipient-preferred and sender-fallback
 * indexes, then deduplicated by capsule id (recipient baseline wins) and
 * classified once. N distinct plausibles are always Ambiguous; a unique
 * plausible may auto-accept through existing strong rules; zero recipient
 * plausibles may fall back to sender. A sender fallback never replaces the
 * recipient baseline for the same capsule — it reports
 * [SenderFallbackAccepted.suggestsBaselineImprovement] instead.
 */
sealed interface CoordinatorDecision {
    /** Stop immediately and verify crypto for this candidate. */
    data class AutoAccepted(
        val origin: CandidateOrigin,
        val candidateId: String,
    ) : CoordinatorDecision

    /** Chooser or guided-recapture surfaces over the origin-deduped union. */
    data class Ambiguous(
        val origin: CandidateOrigin,
        val classification: ScanClassification,
    ) : CoordinatorDecision

    /** Recipient had no plausible candidate; sender index decided the scan instead. */
    data class SenderFallbackAccepted(
        val candidateId: String,
        val suggestsBaselineImprovement: Boolean = true,
    ) : CoordinatorDecision

    /** No candidate anywhere; show recapture guidance. */
    data object NoMatchEverywhere : CoordinatorDecision
}

/**
 * Coordinates recipient-preferred and sender-fallback FRONT indexes by
 * merging scored candidates (same capsule keeps the recipient baseline) and
 * running one [ScanOutcomeClassifier] pass over the union.
 */
class MatchCoordinator(
    private val profile: RecognitionProfile,
) {

    private val classifier = ScanOutcomeClassifier(profile)
    private val ranker = FrontCandidateRanker(profile)
    private val acceptanceEvaluator = CompositeAcceptanceEvaluator(profile)

    fun coordinate(
        recipient: UniverseScanResult,
        sender: UniverseScanResult?,
    ): CoordinatorDecision {
        require(recipient.origin == CandidateOrigin.RECIPIENT_PREFERRED) {
            "first universe must be the recipient-preferred index"
        }
        if (sender != null) {
            require(sender.origin == CandidateOrigin.SENDER_FALLBACK) {
                "fallback universe must be the sender index"
            }
        }

        val union = unionPreferringRecipient(recipient, sender)
        val classification = classifier.classify(union.ranking, union.acceptance)
        return decisionFrom(classification, union)
    }

    private fun unionPreferringRecipient(
        recipient: UniverseScanResult,
        sender: UniverseScanResult?,
    ): UnionUniverse {
        val rows = LinkedHashMap<String, UnionRow>()
        absorb(rows, recipient)
        if (sender != null) absorb(rows, sender)

        val ranking = ranker.rank(rows.values.map { it.front })
        val composites = ranking.retained.map { retained ->
            val existing = rows[retained.candidateId]
            CompositeCandidate(
                candidateId = retained.candidateId,
                frontScore = existing?.scored?.frontScore ?: retained.sideScore,
                frontWeakPassed = existing?.scored?.frontWeakPassed ?: retained.weakGatePassed,
                frontStrongPassed = existing?.scored?.frontStrongPassed ?: false,
            )
        }
        val acceptance = if (composites.isEmpty()) {
            null
        } else {
            acceptanceEvaluator.evaluate(composites, ranking.duplicateFrontGroup)
        }
        val originById = rows.mapValues { it.value.origin }
        return UnionUniverse(ranking, acceptance, originById)
    }

    private fun absorb(rows: LinkedHashMap<String, UnionRow>, universe: UniverseScanResult) {
        val scoredById = universe.acceptance?.scored.orEmpty().associateBy { it.candidateId }
        for (front in universe.frontRanking.retained) {
            rows.putIfAbsent(
                front.candidateId,
                UnionRow(front, scoredById[front.candidateId], universe.origin),
            )
        }
        for (scored in universe.acceptance?.scored.orEmpty()) {
            rows.putIfAbsent(
                scored.candidateId,
                UnionRow(
                    FrontCandidate(scored.candidateId, scored.frontScore, scored.frontWeakPassed),
                    scored,
                    universe.origin,
                ),
            )
        }
    }

    private fun decisionFrom(
        classification: ScanClassification,
        union: UnionUniverse,
    ): CoordinatorDecision {
        val involvedIds = when {
            classification.accepted != null -> listOf(classification.accepted.candidateId)
            classification.chooserRows.isNotEmpty() -> classification.chooserRows.map { it.candidateId }
            else -> plausibleFrontCandidates(
                union.acceptance?.scored.orEmpty(),
                profile.ranking.chooserFrontMin,
            ).map { it.candidateId }
        }
        val origin = if (involvedIds.any { union.originById[it] == CandidateOrigin.RECIPIENT_PREFERRED }) {
            CandidateOrigin.RECIPIENT_PREFERRED
        } else {
            CandidateOrigin.SENDER_FALLBACK
        }
        return when (classification.outcome) {
            ScanOutcome.AUTO_ACCEPTED -> {
                val candidateId = classification.accepted!!.candidateId
                if (origin == CandidateOrigin.RECIPIENT_PREFERRED) {
                    CoordinatorDecision.AutoAccepted(origin, candidateId)
                } else {
                    CoordinatorDecision.SenderFallbackAccepted(candidateId)
                }
            }
            ScanOutcome.PLAUSIBLE_CHOOSER,
            ScanOutcome.SINGLE_CANDIDATE_RECAPTURE,
            -> CoordinatorDecision.Ambiguous(origin, classification)
            ScanOutcome.NO_MATCH -> CoordinatorDecision.NoMatchEverywhere
        }
    }

    private data class UnionRow(
        val front: FrontCandidate,
        val scored: ScoredComposite?,
        val origin: CandidateOrigin,
    )

    private data class UnionUniverse(
        val ranking: FrontRanking,
        val acceptance: CompositeAcceptanceReport?,
        val originById: Map<String, CandidateOrigin>,
    )
}
