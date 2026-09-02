package dev.hryshyn.remanence.core.recognition

/**
 * Local capture-only admission thresholds — FRONT-only production contract
 * (ADR-012). These values deliberately do not belong to [RecognitionProfile]:
 * changing them must not change the persisted `mvp-orb-v1` identity,
 * fingerprint wire format, or matching compatibility. BACK is not a production
 * capture; its former independent threshold is deleted and any BACK request
 * fails closed.
 */
data class CaptureAdmissionProfile(
    val frontMinLaplacianVariance: Double,
) {
    init {
        require(frontMinLaplacianVariance.isFinite() && frontMinLaplacianVariance in 0.0..MAX_LAPLACIAN_VARIANCE)
    }

    fun minLaplacianVariance(side: FingerprintSide): Double = when (side) {
        FingerprintSide.FRONT -> frontMinLaplacianVariance
        FingerprintSide.BACK ->
            throw IllegalArgumentException("BACK capture is not supported in FRONT-only contract")
    }

    companion object {
        private const val MAX_LAPLACIAN_VARIANCE: Double = 10_000.0

        /** Calibrated on the locked capture corpus; local admission only. */
        fun calibratedM2(): CaptureAdmissionProfile = CaptureAdmissionProfile(
            frontMinLaplacianVariance = 110.0,
        )
    }
}
