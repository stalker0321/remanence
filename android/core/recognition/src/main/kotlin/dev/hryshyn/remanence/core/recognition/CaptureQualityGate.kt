package dev.hryshyn.remanence.core.recognition

/** Documented capture-quality reason codes (docs/recognition.md section 4). */
enum class QualityReason {
    CARD_TOO_SMALL,
    CROP_UNCERTAIN,
    ANGLE_UNCERTAIN,
    RESOLUTION_INSUFFICIENT,
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
        if (!input.detectedAreaRatio.isFinite() || input.detectedAreaRatio < 0.0) {
            reasons += QualityReason.CROP_UNCERTAIN
        } else if (input.detectedAreaRatio < gates.minCardAreaRatio) {
            reasons += QualityReason.CARD_TOO_SMALL
        }
        if (!input.rectangularity.isFinite() || input.rectangularity < 0.0) {
            reasons += QualityReason.CROP_UNCERTAIN
        } else if (input.rectangularity < gates.minRectangularity) {
            reasons += QualityReason.ANGLE_UNCERTAIN
        }
        input.cropAspectRatio?.let { ratio ->
            if (!ratio.isFinite() || ratio <= 0.0) {
                reasons += QualityReason.CROP_UNCERTAIN
            } else {
                val orientationNormalizedRatio = if (ratio < 1.0) 1.0 / ratio else ratio
                if (!orientationNormalizedRatio.isFinite()) {
                    reasons += QualityReason.CROP_UNCERTAIN
                } else if (orientationNormalizedRatio !in gates.aspectRatioMin..gates.aspectRatioMax) {
                    reasons += QualityReason.ANGLE_UNCERTAIN
                }
            }
        }
        input.croppedShortEdgePx?.let { shortEdge ->
            if (shortEdge <= 0) {
                reasons += QualityReason.CROP_UNCERTAIN
            } else if (shortEdge < gates.minShortEdgeAfterWarpPx) {
                reasons += QualityReason.RESOLUTION_INSUFFICIENT
            }
        }
        return reasons
    }
}
