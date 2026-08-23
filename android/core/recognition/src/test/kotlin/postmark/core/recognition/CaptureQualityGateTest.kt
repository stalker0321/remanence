package postmark.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureQualityGateTest {

    private val gate = CaptureQualityGate(RecognitionProfile.mvpOrbV1())

    private fun passingSignals() = CaptureQualitySignals(
        laplacianVariance = 500.0,
        nearBlackFraction = 0.01,
        clippedWhiteFraction = 0.01,
        largestGlareFraction = 0.01,
    )

    private fun passingInput() = CaptureQualityInput(
        signals = passingSignals(),
        detectedAreaRatio = 0.60,
        rectangularity = 0.95,
    )

    @Test
    fun cleanCapturePassesWithNoReasons() {
        assertTrue(gate.evaluate(passingInput()).isEmpty())
    }

    @Test
    fun blurBoundaryFlipsExactlyAtProfileThreshold() {
        val below = passingInput().copy(
            signals = passingSignals().copy(laplacianVariance = 80.0 - 1e-6),
        )
        val atOrAbove = passingInput().copy(
            signals = passingSignals().copy(laplacianVariance = 80.0),
        )
        assertEquals(setOf(QualityReason.TOO_BLURRY), gate.evaluate(below))
        assertTrue(gate.evaluate(atOrAbove).isEmpty())
    }

    @Test
    fun darknessBoundaryFlipsExactlyAtProfileThreshold() {
        val above = passingInput().copy(
            signals = passingSignals().copy(nearBlackFraction = 0.25 + 1e-6),
        )
        val atOrBelow = passingInput().copy(
            signals = passingSignals().copy(nearBlackFraction = 0.25),
        )
        assertEquals(setOf(QualityReason.TOO_DARK), gate.evaluate(above))
        assertTrue(gate.evaluate(atOrBelow).isEmpty())
    }

    @Test
    fun clippedWhiteTriggersGlareExcessive() {
        val above = passingInput().copy(
            signals = passingSignals().copy(clippedWhiteFraction = 0.20 + 1e-6),
        )
        assertEquals(setOf(QualityReason.GLARE_EXCESSIVE), gate.evaluate(above))
    }

    @Test
    fun contiguousGlareRegionTriggersGlareExcessive() {
        val above = passingInput().copy(
            signals = passingSignals().copy(largestGlareFraction = 0.12 + 1e-6),
        )
        assertEquals(setOf(QualityReason.GLARE_EXCESSIVE), gate.evaluate(above))
    }

    @Test
    fun smallCardFlaggedTooSmall() {
        val below = passingInput().copy(detectedAreaRatio = 0.35 - 1e-6)
        assertEquals(setOf(QualityReason.CARD_TOO_SMALL), gate.evaluate(below))
    }

    @Test
    fun lowRectangularityFlagsCropUncertain() {
        val below = passingInput().copy(rectangularity = 0.80 - 1e-6)
        assertEquals(setOf(QualityReason.CROP_UNCERTAIN), gate.evaluate(below))
    }

    @Test
    fun multipleReasonsReportedTogether() {
        val bad = passingInput().copy(
            signals = CaptureQualitySignals(
                laplacianVariance = 5.0,
                nearBlackFraction = 0.9,
                clippedWhiteFraction = 0.5,
                largestGlareFraction = 0.5,
            ),
            detectedAreaRatio = 0.05,
            rectangularity = 0.3,
        )
        assertEquals(
            setOf(
                QualityReason.TOO_BLURRY,
                QualityReason.TOO_DARK,
                QualityReason.GLARE_EXCESSIVE,
                QualityReason.CARD_TOO_SMALL,
                QualityReason.CROP_UNCERTAIN,
            ),
            gate.evaluate(bad),
        )
    }
}
