package postmark.core.recognition

/**
 * Raw geometric signals for one query-vs-reference side, after matching,
 * RANSAC, coverage measurement, and plausibility gating (docs/recognition.md
 * section 7). The median error is the estimator's normalized value.
 */
data class SideMatchSignals(
    val ratioMutualMatches: Int,
    val ransacInliers: Int,
    val inlierRatio: Double,
    val spatialCoverage: Double,
    val occupiedGridCells: Int,
    val medianInlierErrorNormalized: Double,
    val homographyPlausible: Boolean,
)

/**
 * M1-M05 result (docs/recognition.md section 8): the four weighted sub-scores,
 * the combined side score, and both evidence-gate outcomes. UI only ever sees
 * classification-level outcomes; raw signals stay here for tests and logs.
 */
data class SideScoreReport(
    val countScore: Double,
    val ratioScore: Double,
    val coverageScore: Double,
    val reprojectionScore: Double,
    val sideScore: Double,
    val weakGatePassed: Boolean,
    val strongGatePassed: Boolean,
)

/**
 * Converts raw side signals into the documented weighted side score and
 * evaluates the weak and strong evidence gates using ONLY profile fields —
 * no scattered literals. All inputs come from earlier M1-M0x stages.
 */
class SideScorer(
    private val profile: RecognitionProfile,
) {

    fun score(signals: SideMatchSignals): SideScoreReport {
        val match = profile.match
        val longEdge = profile.capture.canonicalLongEdgePx.toDouble()

        val countScore = clamp(signals.ransacInliers / match.countScoreInliersDivisor)
        val ratioScore = clamp(
            (signals.inlierRatio - match.ratioScoreOffset) / match.ratioScoreSpan,
        )
        val coverageScore = clamp(signals.spatialCoverage / match.coverageScoreTarget)
        val medianErrorPx = signals.medianInlierErrorNormalized * longEdge
        val reprojectionScore =
            ONE - clamp(medianErrorPx / match.reprojectionScoreMaxMedianErrorPx)
        val sideScore = COUNT_WEIGHT * countScore +
            RATIO_WEIGHT * ratioScore +
            COVERAGE_WEIGHT * coverageScore +
            REPROJECTION_WEIGHT * reprojectionScore

        val weakGatePassed = signals.homographyPlausible &&
            signals.ratioMutualMatches >= match.weakMinRatioMatches &&
            signals.ransacInliers >= match.weakMinInliers &&
            signals.inlierRatio >= match.weakMinInlierRatio &&
            signals.spatialCoverage >= match.weakMinCoverage &&
            signals.occupiedGridCells >= match.weakMinGridCells

        val strongGatePassed = signals.homographyPlausible &&
            signals.ratioMutualMatches >= match.strongMinRatioMatches &&
            signals.ransacInliers >= match.strongMinInliers &&
            signals.inlierRatio >= match.strongMinInlierRatio &&
            signals.spatialCoverage >= match.strongMinCoverage &&
            medianErrorPx <= match.strongMaxMedianErrorPx

        return SideScoreReport(
            countScore = countScore,
            ratioScore = ratioScore,
            coverageScore = coverageScore,
            reprojectionScore = reprojectionScore,
            sideScore = sideScore,
            weakGatePassed = weakGatePassed,
            strongGatePassed = strongGatePassed,
        )
    }

    private fun clamp(value: Double): Double = value.coerceIn(ZERO, ONE)

    internal companion object {
        /** docs/recognition.md section 8 weights. */
        const val COUNT_WEIGHT = 0.35
        const val RATIO_WEIGHT = 0.25
        const val COVERAGE_WEIGHT = 0.25
        const val REPROJECTION_WEIGHT = 0.15
        const val ONE = 1.0
        const val ZERO = 0.0
    }
}
