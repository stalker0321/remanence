package postmark.core.recognition

/**
 * Axis-aligned capture guide rectangle in image coordinates (the fixed
 * overlay shown to the user before capture).
 */
data class GuideOverlay(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
}

/** A detection candidate with its ranking confidence in [0, 1]. */
data class RankedQuad(
    val candidate: QuadCandidate,
    val confidence: Double,
)

/** One ranking input: a detection plus its optional measured edge support in [0,1]. */
data class CandidateWithEdges(
    val candidate: QuadCandidate,
    val edgeSupport: Double? = null,
)

/**
 * Ranks contour candidates by area, rectangularity, optional edge support,
 * and distance from the guide overlay, using profile-defined weights
 * (docs/recognition.md section 4). Output is sorted by descending confidence.
 */
class CandidateRanker(private val profile: RecognitionProfile) {

    fun rank(
        inputs: List<CandidateWithEdges>,
        frameDiagonalPx: Double,
        guide: GuideOverlay? = null,
    ): List<RankedQuad> {
        require(frameDiagonalPx > 0.0) { "frame diagonal must be positive" }
        val weights = profile.ranking
        return inputs
            .map { input ->
                val candidate = input.candidate
                val areaScore = kotlin.math.min(candidate.areaRatio / profile.capture.minCardAreaRatio, 1.0)
                val rectScore = clamp((candidate.rectangularity - 0.70) / 0.30)
                // When unmeasured, rectangularity stands in as the best proxy.
                val edge = input.edgeSupport?.coerceIn(0.0, 1.0) ?: candidate.rectangularity.coerceIn(0.0, 1.0)
                val guideScore = guide?.let { guideProximity(candidate, it, frameDiagonalPx) } ?: 0.0

                // Without a guide the missing weight mass would deflate every
                // confidence equally; renormalize over present signals.
                val guideWeight = if (guide == null) 0.0 else weights.confidenceGuideProximityWeight
                val denominator = 1.0 - weights.confidenceGuideProximityWeight + guideWeight

                val confidence = clamp(
                    (
                        weights.confidenceAreaWeight * areaScore +
                            weights.confidenceRectangularityWeight * rectScore +
                            weights.confidenceEdgeSupportWeight * clamp(edge) +
                            guideWeight * guideScore
                        ) / denominator,
                )
                RankedQuad(candidate, confidence)
            }
            .sortedByDescending { it.confidence }
    }

    /** 1 at the guide center, linearly falling to 0 at [maxGuideDistance] away. */
    private fun guideProximity(candidate: QuadCandidate, guide: GuideOverlay, frameDiagonalPx: Double): Double {
        val centroidX = candidate.corners.sumOf { it.x } / 4.0
        val centroidY = candidate.corners.sumOf { it.y } / 4.0
        val dx = centroidX - guide.centerX
        val dy = centroidY - guide.centerY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        return clamp(1.0 - distance / (frameDiagonalPx * MAX_GUIDE_DISTANCE_FRACTION))
    }

    private fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)

    private companion object {
        const val MAX_GUIDE_DISTANCE_FRACTION = 0.25
    }
}
