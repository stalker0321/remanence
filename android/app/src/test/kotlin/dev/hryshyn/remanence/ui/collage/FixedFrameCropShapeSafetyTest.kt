package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 behavioural safety regression (peer-crop-integration): the accepted T157
 * [FixedFrameCropCorrection.correct] gate must re-classify a corrected window
 * under the slot's **aperture shape**, exactly as the frozen classifier does.
 *
 * A face parked in the extreme top-left corner of the source is inside the
 * rectangular aperture but outside the rounded/elliptic one
 * (`Envelope50CropSolver.maskPoint("round")` radius .12), so the same window
 * must be admitted for `rect` and rejected with the frozen
 * `FACE_CROSSES_CROP_OR_MASK` reason for `round`/`ellipse`. If the shape ever
 * stops reaching the classifier (the F1 defect: `Slot` defaulting to `rect`),
 * the round/ellipse arm of this test fails.
 *
 * Pure JVM: no Gradle-only helpers, no source edits.
 */
class FixedFrameCropShapeSafetyTest {

    private fun field(value: Double): DoubleArray =
        DoubleArray(CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE) { value }

    /** Constant fields clear every frozen floor; the only gate is the face. */
    private fun cornerFaceAnalysis() = CollageAnalysis(
        width = 1000,
        height = 1000,
        faces = listOf(AnalysisFace(NormBox(0.0, 0.0, 0.048, 0.048), 0.9)),
        objects = emptyList(),
        protectedRegions = emptyList(),
        importance = field(1.0),
        baselineField = field(1.0),
        objectField = field(0.0),
        saliency = field(1.0),
        edgeEnergy = field(1.0),
        structureField = field(1.0),
    )

    /** Full-source approved window; square slot keeps the frame aspect exact. */
    private val base = NormalizedCrop(0.0, 0.0, 1.0, 1.0)

    private fun correct(shape: String): CorrectedCropWindow = FixedFrameCropCorrection.correct(
        baseCrop = base,
        slot = Envelope50CropSolver.Slot(width = 300.0, height = 300.0, shape = shape),
        sourceWidth = 1000,
        sourceHeight = 1000,
        transform = CropTransform(1.0, 0.0, 0.0),
        analysis = cornerFaceAnalysis(),
    )

    @Test
    fun rectangularApertureKeepsTheCornerFace() {
        val outcome = correct("rect")
        assertTrue("rect must accept, got $outcome", outcome.accepted)
        assertTrue(outcome.classifierEvaluated)
        assertNull(outcome.geometryReject)
        assertNull(outcome.safetyReject)
        assertEquals(base, outcome.window)
    }

    @Test
    fun roundedApertureRejectsTheSameWindowWithTheFrozenFaceReason() {
        val outcome = correct("round")
        assertFalse("round must refuse the corner face", outcome.accepted)
        assertNull(outcome.window)
        assertNull(outcome.geometryReject)
        assertTrue("the classifier must have run", outcome.classifierEvaluated)
        assertEquals(CropRejectReason.FACE_CROSSES_CROP_OR_MASK, outcome.safetyReject)
    }

    @Test
    fun ellipticApertureRejectsTheSameWindowWithTheFrozenFaceReason() {
        val outcome = correct("ellipse")
        assertFalse("ellipse must refuse the corner face", outcome.accepted)
        assertNull(outcome.geometryReject)
        assertTrue(outcome.classifierEvaluated)
        assertEquals(CropRejectReason.FACE_CROSSES_CROP_OR_MASK, outcome.safetyReject)
    }

    @Test
    fun shapeChangesTheOutcomeNotTheGeometry() {
        // Same inputs, only the aperture differs: a shape-insensitive gate
        // would accept all three identically.
        val rect = correct("rect")
        val round = correct("round")
        assertTrue(rect.accepted)
        assertFalse(round.accepted)
        assertTrue("the round arm must reach the frozen classifier", round.classifierEvaluated)
    }
}
