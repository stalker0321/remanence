package dev.hryshyn.remanence.core.recognition

/** Documented capture-quality reason codes (docs/recognition.md section 4). */
enum class QualityReason {
    CARD_TOO_SMALL,
    CROP_UNCERTAIN,
    TOO_BLURRY,
    TOO_DARK,
    GLARE_EXCESSIVE,
    FEATURES_INSUFFICIENT,
}

data class CaptureQualityInput(
    val signals: CaptureQualitySignals,
    val detectedAreaRatio: Double,
    val rectangularity: Double,
    /** Aspect ratio of the chosen crop, normalized so landscape/portrait agree. */
    val cropAspectRatio: Double? = null,
    /** Short edge after the perspective warp, when the crop has been warped. */
    val croppedShortEdgePx: Int? = null,
)

/**
 * Classifies measured capture signals into reason codes using ONLY profile
 * thresholds; an empty set means the capture passes all quality gates.
 */
class CaptureQualityGate(private val profile: RecognitionProfile) {

    fun evaluate(input: CaptureQualityInput): Set<QualityReason> {
        val reasons = sortedSetOf<QualityReason>()
        val gates = profile.capture
        with(input.signals) {
            if (laplacianVariance < gates.minLaplacianVariance) reasons += QualityReason.TOO_BLURRY
            if (nearBlackFraction > gates.maxNearBlackFraction) reasons += QualityReason.TOO_DARK
            if (clippedWhiteFraction > gates.maxClippedWhiteFraction ||
                largestGlareFraction > gates.maxGlareRegionFraction
            ) {
                reasons += QualityReason.GLARE_EXCESSIVE
            }
        }
        if (input.detectedAreaRatio < gates.minCardAreaRatio) reasons += QualityReason.CARD_TOO_SMALL
        if (input.rectangularity < gates.minRectangularity) reasons += QualityReason.CROP_UNCERTAIN
        input.cropAspectRatio?.let { ratio ->
            if (ratio !in gates.aspectRatioMin..gates.aspectRatioMax) {
                reasons += QualityReason.CROP_UNCERTAIN
            }
        }
        input.croppedShortEdgePx?.let { shortEdge ->
            if (shortEdge < gates.minShortEdgeAfterWarpPx) reasons += QualityReason.CROP_UNCERTAIN
        }
        return reasons
    }
}
