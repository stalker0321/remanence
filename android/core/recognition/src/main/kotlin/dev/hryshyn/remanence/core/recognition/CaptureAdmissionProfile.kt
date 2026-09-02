package dev.hryshyn.remanence.core.recognition

/**
 * Local capture-only admission thresholds.
 *
 * These values deliberately do not belong to [RecognitionProfile]: changing
 * them must not change the persisted `mvp-orb-v1` identity, fingerprint wire
 * format, or matching compatibility. Front artwork and mostly-white postcard
 * backs have materially different Laplacian distributions, so applying one
 * blur threshold to both sides rejects sharp backs while admitting blurred
 * fronts.
 */
data class CaptureAdmissionProfile(
    val frontMinLaplacianVariance: Double,
    val backMinLaplacianVariance: Double,
) {
    init {
        require(frontMinLaplacianVariance.isFinite() && frontMinLaplacianVariance in 0.0..MAX_LAPLACIAN_VARIANCE)
        require(backMinLaplacianVariance.isFinite() && backMinLaplacianVariance in 0.0..MAX_LAPLACIAN_VARIANCE)
    }

    fun minLaplacianVariance(side: FingerprintSide): Double = when (side) {
        FingerprintSide.FRONT -> frontMinLaplacianVariance
        FingerprintSide.BACK -> backMinLaplacianVariance
    }

    companion object {
        private const val MAX_LAPLACIAN_VARIANCE: Double = 10_000.0

        /** Calibrated on the locked capture corpus; local admission only. */
        fun calibratedM2(): CaptureAdmissionProfile = CaptureAdmissionProfile(
            frontMinLaplacianVariance = 110.0,
            backMinLaplacianVariance = 55.0,
        )
    }
}
