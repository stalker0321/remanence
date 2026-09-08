package dev.hryshyn.remanence.capture

import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.LocalizationProposalSource
import dev.hryshyn.remanence.core.recognition.QualityReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLocalizationDiagnosticsTest {
    @Test
    fun summaryExposesFeatureSourceFallbackQualityAndCountOnly() {
        val summary = CaptureLocalizationEvent(
            side = FingerprintSide.FRONT,
            featureV2Enabled = true,
            source = LocalizationProposalSource.LEGACY_CONTOUR,
            stage = CaptureDiagnosticStage.FEATURES,
            outcome = CaptureLocalizationOutcome.ACCEPTED,
            fallbackReason = LocalizationFallbackReason.V2_WARP_INVALID,
            qualityReasons = setOf(QualityReason.TOO_DARK, QualityReason.CARD_TOO_SMALL),
            featureCount = 42,
        ).safeSummary()

        assertTrue(summary.contains("feature=v2-line:true"))
        assertTrue(summary.contains("source=LEGACY_CONTOUR"))
        assertTrue(summary.contains("fallback=V2_WARP_INVALID"))
        assertTrue(summary.contains("quality=CARD_TOO_SMALL|TOO_DARK"))
        assertTrue(summary.contains("features=42"))
        listOf("bitmap", "jpeg", "descriptor", "coordinate", "capsule", "handle").forEach {
            assertFalse("telemetry leaked $it", summary.contains(it, ignoreCase = true))
        }
    }
}
