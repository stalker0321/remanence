package dev.hryshyn.remanence.core.recognition

import java.util.Locale

/** Safe phase for recognition telemetry; candidate rows are never identified. */
enum class MatchDiagnosticPhase {
    INDEX,
    CANDIDATE_EVALUATED,
    RESULT,
}

/** Outcome label emitted at index, matcher, or verification decision points. */
enum class MatchDiagnosticOutcome {
    INDEX_UNAVAILABLE,
    GRANT,
    AMBIGUOUS,
    RECAPTURE,
    NO_MATCH,
}

/**
 * Recognition telemetry containing only bounded counts, profile-independent
 * enums, and numeric scores. It intentionally has no capsule IDs, descriptors,
 * keypoint coordinates, handles, chooser text, or exception text.
 */
data class MatchDiagnosticEvent(
    val phase: MatchDiagnosticPhase,
    val outcome: MatchDiagnosticOutcome? = null,
    val origin: CandidateOrigin? = null,
    val candidateCount: Int = 0,
    val score: Double? = null,
    val margin: Double? = null,
    val ratioMutualMatches: Int? = null,
    val ransacInliers: Int? = null,
    val coverage: Double? = null,
    val rawCandidateCount: Int? = null,
    val validCandidateCount: Int? = null,
    val profileSkippedCandidateCount: Int? = null,
    val invalidCandidateCount: Int? = null,
    val matcherFailure: SiftRootSiftMatchFailure? = null,
    val weakGatePassed: Boolean? = null,
    val strongGatePassed: Boolean? = null,
) {
    init {
        require(candidateCount >= 0)
        require(ratioMutualMatches == null || ratioMutualMatches >= 0)
        require(ransacInliers == null || ransacInliers >= 0)
        require(rawCandidateCount == null || rawCandidateCount >= 0)
        require(validCandidateCount == null || validCandidateCount >= 0)
        require(profileSkippedCandidateCount == null || profileSkippedCandidateCount >= 0)
        require(invalidCandidateCount == null || invalidCandidateCount >= 0)
    }

    /** Stable redacted representation suitable for a DEBUG log line. */
    fun safeSummary(): String = buildString {
        append("phase=").append(phase.name)
        append(" outcome=").append(outcome?.name ?: "n/a")
        append(" origin=").append(origin?.name ?: "n/a")
        append(" candidates=").append(candidateCount)
        append(" score=").append(decimal(score))
        append(" margin=").append(decimal(margin))
        append(" ratioMutual=").append(ratioMutualMatches?.toString() ?: "n/a")
        append(" inliers=").append(ransacInliers?.toString() ?: "n/a")
        append(" coverage=").append(decimal(coverage))
        append(" raw=").append(rawCandidateCount?.toString() ?: "n/a")
        append(" valid=").append(validCandidateCount?.toString() ?: "n/a")
        append(" profileSkipped=").append(profileSkippedCandidateCount?.toString() ?: "n/a")
        append(" invalid=").append(invalidCandidateCount?.toString() ?: "n/a")
        append(" matcherFailure=").append(matcherFailure?.name ?: "n/a")
        append(" weakGate=").append(weakGatePassed?.toString() ?: "n/a")
        append(" strongGate=").append(strongGatePassed?.toString() ?: "n/a")
    }

    private fun decimal(value: Double?): String =
        value?.let { String.format(Locale.US, "%.4f", it) } ?: "n/a"

    companion object {
        fun result(
            outcome: MatchDiagnosticOutcome,
            origin: CandidateOrigin? = null,
        ): MatchDiagnosticEvent = MatchDiagnosticEvent(
            phase = MatchDiagnosticPhase.RESULT,
            outcome = outcome,
            origin = origin,
        )
    }
}
