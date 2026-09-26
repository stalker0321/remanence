package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parity fixtures generated from the authoritative V5 freeze with no downloads:
 * a Node harness loads `runtime/crop.js` (`V4_CROP.crop`, the safe baseline),
 * `runtime/safety.js` (`V5_SAFETY.crop`) and `runtime/crop-experiment.js`
 * (`V5_CROP.predict(..., 'envelope-50')`), runs the documented call chain
 * `baseline = V4_CROP.crop(photo, tile); rect = V5_CROP.predict(photo, tile,
 * baseline.rect, 'envelope-50').rect`, and prints the normalized rects pinned
 * below. The analyses are synthetic: constant 48x48 fields plus injected
 * face/object boxes. Quantized integers are asserted exactly; normalized values
 * with 1e-9 tolerance.
 *
 * Scenarios cover: two slot aspects for the same photo, face-envelope shift,
 * object-envelope shift, a rejected shift falling back to the safe base, and a
 * fail-closed impossible slot.
 */
class Envelope50CropSolverTest {

    private val grid = CollageAnalysis.GRID_SIZE

    private fun field(value: Double) = DoubleArray(grid * grid) { value }

    private fun analysis(
        width: Int = 1000,
        height: Int = 1000,
        faces: List<AnalysisFace> = emptyList(),
        objects: List<AnalysisObject> = emptyList(),
        protectedRegions: List<AnalysisProtectedRegion> = emptyList(),
        importance: Double = 0.7,
        saliency: Double = 0.6,
        edge: Double = 0.6,
        structure: Double = 0.5,
        baseline: Double = 0.5,
        objectField: Double = 0.0,
    ) = CollageAnalysis(
        width = width,
        height = height,
        faces = faces,
        objects = objects,
        protectedRegions = protectedRegions,
        importance = field(importance),
        baselineField = field(baseline),
        objectField = field(objectField),
        saliency = field(saliency),
        edgeEnergy = field(edge),
        structureField = field(structure),
    )

    private fun box(x: Double, y: Double, w: Double, h: Double) = NormBox(x, y, w, h)

    private val slotLandscape = Envelope50CropSolver.Slot(340.0, 248.0, zooms = listOf(1.05, 1.0))
    private val slotPortrait = Envelope50CropSolver.Slot(167.0, 248.0, zooms = listOf(1.05, 1.0))

    private fun assertCrop(expected: GeneratorExpression.CropWindow, crop: NormalizedCrop?) {
        requireNotNull(crop) { "expected a crop, got null" }
        assertEquals(expected, crop.toCropWindow())
    }

    @Test
    fun uniformLandscapeMatchesOracle() {
        assertCrop(
            GeneratorExpression.CropWindow(238, 1527, 9524, 6947),
            Envelope50CropSolver.solve(analysis(), slotLandscape),
        )
    }

    @Test
    fun samePhotoDifferentSlotAspectsProduceDifferentCrops() {
        val photo = analysis()
        val landscape = Envelope50CropSolver.solve(photo, slotLandscape)!!
        val portrait = Envelope50CropSolver.solve(photo, slotPortrait)!!
        assertCrop(GeneratorExpression.CropWindow(238, 1527, 9524, 6947), landscape)
        assertCrop(GeneratorExpression.CropWindow(2242, 298, 6413, 9524), portrait)
        assertEquals(0.9523809523809523, landscape.width, 1e-9)
        assertEquals(0.6413210445468509, portrait.width, 1e-9)
    }

    @Test
    fun faceEnvelopeShiftsTheWindow() {
        val photo = analysis(faces = listOf(AnalysisFace(box(0.10, 0.20, 0.15, 0.15), 0.9)))
        val crop = Envelope50CropSolver.solve(photo, slotLandscape)!!
        assertCrop(GeneratorExpression.CropWindow(119, 763, 9524, 6947), crop)
        assertEquals(0.011904761904761918, crop.x, 1e-9)
        assertEquals(0.07633053221288513, crop.y, 1e-9)
    }

    @Test
    fun objectProtectionMovesTheSafeBase() {
        // The base window shifts right to retain the object; the subsequent
        // envelope shift is rejected ("A separate important region would
        // disappear"), so the result is the object-preserving baseline.
        val photo = analysis(objects = listOf(AnalysisObject(box(0.70, 0.30, 0.25, 0.30), 0.80, 0)))
        val crop = Envelope50CropSolver.solve(photo, slotPortrait)!!
        assertCrop(GeneratorExpression.CropWindow(3138, 417, 6413, 9524), crop)
        assertEquals(0.3138440860215055, crop.x, 1e-9)
        assertEquals(0.04166666666666671, crop.y, 1e-9)
    }

    @Test
    fun rejectedEnvelopeShiftKeepsTheSafeBaseline() {
        // Oracle `applied=false`: the envelope shift fails the protection-field
        // mass check, so the already-safe baseline is kept.
        assertCrop(
            GeneratorExpression.CropWindow(2242, 298, 6413, 9524),
            Envelope50CropSolver.solve(analysis(), slotPortrait),
        )
    }

    @Test
    fun edgeClampAndFaceEnvelopeShift() {
        // The baseline already drops to contain the bottom face (edge clamp);
        // the envelope shift then moves it slightly right, still safe.
        val photo = analysis(faces = listOf(AnalysisFace(box(0.85, 0.85, 0.10, 0.10), 0.9)))
        val crop = Envelope50CropSolver.solve(photo, slotLandscape)!!
        assertCrop(GeneratorExpression.CropWindow(357, 3053, 9524, 6947), crop)
        assertEquals(0.035714285714285754, crop.x, 1e-9)
        assertEquals(0.30532212885154053, crop.y, 1e-9)
    }

    @Test
    fun impossibleSlotIsFailClosedNull() {
        val impossible = Envelope50CropSolver.Slot(3400.0, 248.0, zooms = listOf(1.05, 1.0))
        assertNull(Envelope50CropSolver.solve(analysis(), impossible))
    }

    @Test
    fun solveIsDeterministicAcrossRepeatedCalls() {
        val photo = analysis(faces = listOf(AnalysisFace(box(0.10, 0.20, 0.15, 0.15), 0.9)))
        val first = Envelope50CropSolver.solve(photo, slotLandscape)
        val second = Envelope50CropSolver.solve(photo, slotLandscape)
        assertEquals(first, second)
    }
}
