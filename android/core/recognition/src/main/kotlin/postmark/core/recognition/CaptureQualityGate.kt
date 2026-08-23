package postmark.core.recognition

/** Documented capture-quality reason codes (docs/recognition.md section 4). */
enum class QualityReason {
    CARD_TOO_SMALL,
    CROP_UNCERTAIN,
    TOO_BLURRY,
    TOO_DARK,
    GLARE_EXCESSIVE,
}

data class CaptureQualityInput(
    val signals: CaptureQualitySignals,
    val detectedAreaRatio: Double,
    val rectangularity: Double,
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
        return reasons
    }
}
