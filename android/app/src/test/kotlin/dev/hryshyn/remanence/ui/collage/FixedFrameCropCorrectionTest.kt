package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.clampTransform
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.correct
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.correctionKey
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.normalizedCrop
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.panDelta
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.panLimitPercent
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.sourceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T157 fixed-frame crop math and safety evidence.
 *
 * Fixtures mirror `Envelope50CropSolverTest` (constant 48x48 fields plus
 * injected boxes) so the approved base windows are the frozen solver's own
 * outputs. Every admitted window is re-checked against the frozen classifier
 * seam; no acceptance is ever derived from a missing diagnostic reason alone.
 */
class FixedFrameCropCorrectionTest {

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

    /** 1.0 inside the central quarter of cells, 0.0 outside. */
    private fun centralMass(): DoubleArray {
        val low = grid / 4
        val high = grid - grid / 4
        return DoubleArray(grid * grid) { index ->
            val y = index / grid
            val x = index % grid
            if (x in low until high && y in low until high) 1.0 else 0.0
        }
    }

    /**
     * Analysis with every detector mass inside the central quarter of the grid.
     * The frozen coverage floors are mass fractions of the whole source, so a
     * constant field makes any zoom-in fail merely because the window area
     * shrank; concentrating the mass keeps those floors meaningful and lets a
     * zoomed/panned window be judged on centre retention instead.
     */
    private fun centredMassAnalysis(
        width: Int = 1000,
        height: Int = 1000,
        faces: List<AnalysisFace> = emptyList(),
        objects: List<AnalysisObject> = emptyList(),
        protectedRegions: List<AnalysisProtectedRegion> = emptyList(),
    ) = CollageAnalysis(
        width = width,
        height = height,
        faces = faces,
        objects = objects,
        protectedRegions = protectedRegions,
        importance = centralMass(),
        baselineField = centralMass(),
        objectField = DoubleArray(grid * grid),
        saliency = centralMass(),
        edgeEnergy = centralMass(),
        structureField = centralMass(),
    )

    private val slotLandscape = Envelope50CropSolver.Slot(340.0, 248.0, zooms = listOf(1.05, 1.0))
    private val slotPortrait = Envelope50CropSolver.Slot(167.0, 248.0, zooms = listOf(1.05, 1.0))

    private val identity = CropTransform(1.0, 0.0, 0.0)

    private fun approved(
        photo: CollageAnalysis = analysis(),
        slot: Envelope50CropSolver.Slot = slotLandscape,
    ): NormalizedCrop = requireNotNull(Envelope50CropSolver.solve(photo, slot)) {
        "fixture must produce an approved solver crop"
    }

    private fun frozenReason(
        photo: CollageAnalysis,
        slot: Envelope50CropSolver.Slot,
        window: NormalizedCrop,
    ): CropRejectReason? = Envelope50CropSolver.rejectionReason(
        analysis = photo,
        slot = slot,
        rect = NormBox(window.x, window.y, window.width, window.height),
        shape = slot.shape,
    )

    private fun assertContained(inner: NormalizedCrop, outer: NormalizedCrop, label: String) {
        val eps = 1e-9
        assertTrue("$label left", inner.x >= outer.x - eps)
        assertTrue("$label top", inner.y >= outer.y - eps)
        assertTrue("$label right", inner.x + inner.width <= outer.x + outer.width + eps)
        assertTrue("$label bottom", inner.y + inner.height <= outer.y + outer.height + eps)
    }

    private fun centreX(window: NormalizedCrop) = window.x + window.width / 2
    private fun centreY(window: NormalizedCrop) = window.y + window.height / 2

    // --- clamp parity with the frozen JS clampCrop -------------------------

    @Test
    fun clampMatchesFrozenJsClampCrop() {
        // validation/checks.mjs:47 — zoom=1.25, pan {200,-200} → {12.5,-12.5}
        assertEquals(CropTransform(1.25, 12.5, -12.5), clampTransform(1.25, 200.0, -200.0))
        // z=1 locks pan
        assertEquals(CropTransform(1.0, 0.0, 0.0), clampTransform(1.0, 5.0, -5.0))
        // out-of-range slider values clamp into [1, 1.25] before the pan limit
        assertEquals(CropTransform(1.0, 0.0, 0.0), clampTransform(0.5, 0.2, -0.2))
        assertEquals(CropTransform(1.25, 0.0, 0.0), clampTransform(2.0, 0.0, 0.0))
        // limit is exactly (z-1)*50
        assertEquals(0.0, panLimitPercent(1.0), 0.0)
        assertEquals(12.5, panLimitPercent(1.25), 0.0)
        assertEquals(CropTransform(1.125, 6.25, -6.25), clampTransform(1.125, 6.25, -6.3))

        val atLimit = clampTransform(1.25, 12.5, -12.5)
        assertNotNull(atLimit)
        assertEquals(CropTransform(1.25, 12.5, -12.5), atLimit)

        val beyond = clampTransform(1.1, -100.0, 100.0)!!
        assertEquals(1.1, beyond.z, 0.0)
        assertEquals(-panLimitPercent(1.1), beyond.xPercent, 0.0)
        assertEquals(panLimitPercent(1.1), beyond.yPercent, 0.0)
    }

    @Test
    fun nonFiniteInputIsRejectedInsteadOfPropagated() {
        assertNull(clampTransform(Double.NaN, 0.0, 0.0))
        assertNull(clampTransform(1.1, Double.NaN, 0.0))
        assertNull(clampTransform(1.1, 0.0, Double.POSITIVE_INFINITY))

        val photo = analysis()
        val base = approved(photo)
        assertNull(sourceWindow(base, CropTransform(Double.NaN, 0.0, 0.0)))

        val result = correct(base, slotLandscape, 1000, 1000, CropTransform(Double.NaN, 0.0, 0.0), photo)
        assertFalse(result.accepted)
        assertNull(result.window)
        assertEquals(GeometryReject.NONFINITE_VALUE, result.geometryReject)
        assertNull(result.safetyReject)
        assertFalse(result.classifierEvaluated)
    }

    // --- direction/sign mapping -------------------------------------------

    @Test
    fun panDeltaMatchesTheFourDirectionButtons() {
        assertEquals(PanDelta(-1.0, 0.0), panDelta(PanDirection.LEFT))
        assertEquals(PanDelta(1.0, 0.0), panDelta(PanDirection.RIGHT))
        assertEquals(PanDelta(0.0, -1.0), panDelta(PanDirection.UP))
        assertEquals(PanDelta(0.0, 1.0), panDelta(PanDirection.DOWN))
    }

    @Test
    fun panSignMapsPhotoMovementToSourceWindowMovement() {
        val base = approved()
        val atIdentity = sourceWindow(base, identity)!!
        assertEquals(base, atIdentity)

        val photoRight = sourceWindow(base, CropTransform(1.25, 5.0, 0.0))!!
        val photoLeft = sourceWindow(base, CropTransform(1.25, -5.0, 0.0))!!
        val photoDown = sourceWindow(base, CropTransform(1.25, 0.0, 5.0))!!

        // photo moved right ⇒ window centre moves left in the source, by (t/z)*w
        assertTrue(centreX(photoRight) < centreX(atIdentity))
        assertEquals(base.x + base.width * 0.46, centreX(photoRight), 1e-12)
        assertTrue(centreX(photoLeft) > centreX(atIdentity))
        assertEquals(base.x + base.width * 0.54, centreX(photoLeft), 1e-12)
        // photo moved down ⇒ window centre moves up in the source
        assertTrue(centreY(photoDown) < centreY(atIdentity))
        assertEquals(base.y + base.height * 0.46, centreY(photoDown), 1e-12)

        // a LEFT button press therefore moves the window right
        val delta = panDelta(PanDirection.LEFT)
        val afterLeft = sourceWindow(base, CropTransform(1.25, delta.xPercent, delta.yPercent))!!
        assertTrue(centreX(afterLeft) > centreX(atIdentity))
        val deltaRight = panDelta(PanDirection.RIGHT)
        val afterRight = sourceWindow(base, CropTransform(1.25, deltaRight.xPercent, deltaRight.yPercent))!!
        assertTrue(centreX(afterRight) < centreX(atIdentity))
    }

    // --- identity, reproducibility, no drift -------------------------------

    @Test
    fun identityReturnsTheApprovedCropExactly() {
        val photo = analysis()
        val base = approved(photo)
        val result = correct(base, slotLandscape, 1000, 1000, identity, photo)

        assertTrue(result.accepted)
        assertTrue(result.classifierEvaluated)
        assertNull(result.geometryReject)
        assertNull(result.safetyReject)
        assertEquals(base, result.window)
        val window = result.window!!
        assertEquals(base.x, window.x, 0.0)
        assertEquals(base.y, window.y, 0.0)
        assertEquals(base.width, window.width, 0.0)
        assertEquals(base.height, window.height, 0.0)
    }

    @Test
    fun sameTransformIsReproducible() {
        val photo = centredMassAnalysis()
        val base = approved(photo)
        val transform = CropTransform(1.17, -4.0, 3.0)
        val first = correct(base, slotLandscape, 1000, 1000, transform, photo)
        val second = correct(base, slotLandscape, 1000, 1000, transform, photo)
        assertEquals(first, second)
        assertEquals(first.window, second.window)
        assertTrue(first.accepted)
    }

    @Test
    fun sequentialPansDoNotAccumulateIntoTheWindow() {
        val photo = centredMassAnalysis()
        val base = approved(photo)

        var state = identity
        repeat(10) {
            val delta = panDelta(PanDirection.RIGHT)
            state = clampTransform(1.1, state.xPercent + delta.xPercent, state.yPercent + delta.yPercent)!!
        }
        // clamped at the z=1.1 limit, never beyond
        assertEquals(panLimitPercent(1.1), state.xPercent, 0.0)
        assertEquals(0.0, state.yPercent, 0.0)

        val stepped = correct(base, slotLandscape, 1000, 1000, state, photo)
        val oneShot = correct(
            base, slotLandscape, 1000, 1000,
            CropTransform(1.1, panLimitPercent(1.1), 0.0), photo,
        )
        assertTrue(stepped.accepted)
        assertEquals(stepped.window, oneShot.window)
        assertEquals(stepped, correct(base, slotLandscape, 1000, 1000, state, photo))

        // absolute derivation from the approved crop: one 1/z reduction, not ten
        val steppedWindow = stepped.window!!
        assertEquals(base.width / 1.1, steppedWindow.width, 1e-12)
        assertEquals(base.height / 1.1, steppedWindow.height, 1e-12)
    }

    // --- safety admission --------------------------------------------------

    @Test
    fun smallSafePanIsAdmittedAndStaysInsideTheApprovedCrop() {
        val photo = centredMassAnalysis()
        val base = approved(photo)
        val result = correct(base, slotLandscape, 1000, 1000, CropTransform(1.25, 2.0, -2.0), photo)

        assertTrue(result.accepted)
        assertTrue(result.classifierEvaluated)
        assertNull(result.geometryReject)
        assertNull(result.safetyReject)
        val window = result.window!!
        assertContained(window, base, "small pan")
        assertNull(frozenReason(photo, slotLandscape, window))
    }

    @Test
    fun faceCutOffIsRejectedWithTheFrozenReason() {
        val photo = analysis(faces = listOf(AnalysisFace(box(0.10, 0.20, 0.15, 0.15), 0.9)))
        val base = approved(photo)

        // the approved crop itself is safe…
        val unchanged = correct(base, slotLandscape, 1000, 1000, identity, photo)
        assertTrue(unchanged.accepted)

        // …and the pan that pushes the face across the frame edge is not
        val panned = correct(base, slotLandscape, 1000, 1000, CropTransform(1.25, -10.0, 0.0), photo)
        assertFalse(panned.accepted)
        assertNull(panned.window)
        assertNull(panned.geometryReject)
        assertTrue(panned.classifierEvaluated)
        assertEquals(CropRejectReason.FACE_CROSSES_CROP_OR_MASK, panned.safetyReject)
    }

    @Test
    fun contourCutOffIsRejectedWithTheFrozenReason() {
        val tip = AnalysisProtectedRegion(
            kind = "structure-tip",
            box = box(0.03, 0.30, 0.15, 0.25),
            confidence = 0.4,
            critical = true,
        )
        val photo = analysis(protectedRegions = listOf(tip))
        val base = approved(photo)

        assertTrue(correct(base, slotLandscape, 1000, 1000, identity, photo).accepted)

        val panned = correct(base, slotLandscape, 1000, 1000, CropTransform(1.25, -10.0, 0.0), photo)
        assertFalse(panned.accepted)
        assertNull(panned.geometryReject)
        assertTrue(panned.classifierEvaluated)
        assertEquals(CropRejectReason.CONTOUR_ENDPOINT_SEVERED, panned.safetyReject)
    }

    @Test
    fun resolutionShortfallIsRejectedWithTheFrozenReason() {
        val photo = analysis()
        val oversizedSlot = Envelope50CropSolver.Slot(2000.0, 2000.0, zooms = listOf(1.0))
        val base = NormalizedCrop(0.0, 0.0, 1.0, 1.0)

        val result = correct(base, oversizedSlot, 1000, 1000, identity, photo)
        assertFalse(result.accepted)
        assertNull(result.geometryReject)
        assertTrue(result.classifierEvaluated)
        assertEquals(CropRejectReason.RESOLUTION_TOO_LOW, result.safetyReject)
    }

    @Test
    fun missingAnalysisIsRejectedNeverAccepted() {
        val base = approved()
        val result = correct(base, slotLandscape, 1000, 1000, identity, null)

        assertFalse(result.accepted)
        assertNull(result.window)
        assertEquals(GeometryReject.MISSING_ANALYSIS, result.geometryReject)
        assertNull(result.safetyReject)
        assertFalse(result.classifierEvaluated)
    }

    @Test
    fun analysisMustDescribeTheSameSource() {
        val photo = analysis(width = 1600, height = 1200)
        val base = approved(photo, slotLandscape)

        val result = correct(base, slotLandscape, 3200, 2400, identity, photo)
        assertFalse(result.accepted)
        assertEquals(GeometryReject.ANALYSIS_DIMENSION_MISMATCH, result.geometryReject)
        assertFalse(result.classifierEvaluated)
    }

    @Test
    fun outOfSourceAndInvalidGeometryAreRejectedBeforeTheClassifier() {
        val photo = centredMassAnalysis()
        val base = approved(photo)

        val beyondRight = correct(NormalizedCrop(0.6, 0.0, 0.5, 1.0), slotLandscape, 1000, 1000, identity, photo)
        assertEquals(GeometryReject.BASE_CROP_OUT_OF_SOURCE, beyondRight.geometryReject)
        assertFalse(beyondRight.classifierEvaluated)

        val negativeSize = correct(NormalizedCrop(0.1, 0.1, -0.2, 0.5), slotLandscape, 1000, 1000, identity, photo)
        assertEquals(GeometryReject.BASE_CROP_OUT_OF_SOURCE, negativeSize.geometryReject)

        val negativeOrigin = correct(NormalizedCrop(-0.05, 0.0, 0.5, 0.5), slotLandscape, 1000, 1000, identity, photo)
        assertEquals(GeometryReject.BASE_CROP_OUT_OF_SOURCE, negativeOrigin.geometryReject)

        val noSource = correct(base, slotLandscape, 0, 1000, identity, photo)
        assertEquals(GeometryReject.INVALID_SOURCE_DIMENSIONS, noSource.geometryReject)

        val badSlot = correct(base, Envelope50CropSolver.Slot(0.0, 248.0), 1000, 1000, identity, photo)
        assertEquals(GeometryReject.INVALID_SLOT, badSlot.geometryReject)

        val tooWide = correct(base, slotLandscape, 1000, 1000, CropTransform(1.3, 0.0, 0.0), photo)
        assertEquals(GeometryReject.ZOOM_OUT_OF_DOMAIN, tooWide.geometryReject)

        val tooTight = correct(base, slotLandscape, 1000, 1000, CropTransform(0.9, 0.0, 0.0), photo)
        assertEquals(GeometryReject.ZOOM_OUT_OF_DOMAIN, tooTight.geometryReject)

        // pan is locked at z=1 and rejected beyond the limit, never re-clamped
        val locked = correct(base, slotLandscape, 1000, 1000, CropTransform(1.0, 1.0, 0.0), photo)
        assertEquals(GeometryReject.PAN_BEYOND_LIMIT, locked.geometryReject)
        assertNull(locked.window)
        assertFalse(locked.classifierEvaluated)

        val beyondLimit = correct(base, slotLandscape, 1000, 1000, CropTransform(1.25, 13.0, 0.0), photo)
        assertEquals(GeometryReject.PAN_BEYOND_LIMIT, beyondLimit.geometryReject)

        // clamping first is what makes the same inputs admissible
        val clamped = clampTransform(1.25, 13.0, 0.0)!!
        assertTrue(correct(base, slotLandscape, 1000, 1000, clamped, photo).accepted)
    }

    @Test
    fun frameAspectMismatchIsRejectedBeforeTheClassifier() {
        val photo = analysis()
        val portraitBase = approved(photo, slotPortrait)

        val result = correct(portraitBase, slotLandscape, 1000, 1000, identity, photo)
        assertFalse(result.accepted)
        assertNull(result.window)
        assertEquals(GeometryReject.FRAME_ASPECT_MISMATCH, result.geometryReject)
        assertFalse(result.classifierEvaluated)
        assertNull(result.safetyReject)
    }

    // --- non-square source and non-square frame ----------------------------

    @Test
    fun nonSquareSourceKeepsTheFrameAspectThroughTheCorrection() {
        val photo = centredMassAnalysis(width = 1600, height = 1200)
        val base = approved(photo, slotPortrait)
        assertTrue(base.width < base.height)
        assertTrue(photo.width > photo.height)

        val identityResult = correct(base, slotPortrait, 1600, 1200, identity, photo)
        assertTrue(identityResult.accepted)
        assertEquals(base, identityResult.window)

        val result = correct(base, slotPortrait, 1600, 1200, CropTransform(1.25, 5.0, -5.0), photo)
        assertTrue(result.accepted)
        val window = result.window!!
        assertContained(window, base, "non-square pan")

        val expectedAspect = 167.0 / 248.0
        assertEquals(expectedAspect, (window.width * 1600) / (window.height * 1200), 1e-9)
        assertEquals(
            expectedAspect,
            (window.width * 1600) / (window.height * 1200),
            FixedFrameCropCorrection.DEFAULT_ASPECT_TOLERANCE,
        )
        assertNull(frozenReason(photo, slotPortrait, window))
    }

    @Test
    fun quantizedSealedWindowRoundTripsIntoTheGate() {
        val photo = analysis()
        val base = approved(photo)

        val full = normalizedCrop(GeneratorExpression.CropWindow(0, 0, 10000, 10000))!!
        assertEquals(0.0, full.x, 0.0)
        assertEquals(0.0, full.y, 0.0)
        assertEquals(1.0, full.width, 0.0)
        assertEquals(1.0, full.height, 0.0)
        assertNull(normalizedCrop(GeneratorExpression.CropWindow(0, 0, 0, 10000)))
        assertNull(normalizedCrop(GeneratorExpression.CropWindow(0, 0, 10000, 10000), 0))

        val roundTrip = normalizedCrop(base.toCropWindow())!!
        assertEquals(base.x, roundTrip.x, 1e-4)
        assertEquals(base.y, roundTrip.y, 1e-4)
        assertEquals(base.width, roundTrip.width, 1e-4)
        assertEquals(base.height, roundTrip.height, 1e-4)

        val result = correct(roundTrip, slotLandscape, 1000, 1000, identity, photo)
        assertTrue(result.accepted)
        assertNull(frozenReason(photo, slotLandscape, result.window!!))
    }

    // --- zoom is relative to the approved crop, not a wider search ---------

    @Test
    fun solverZoomFamiliesAreNotConsultedByTheCorrection() {
        val photo = centredMassAnalysis()
        val base = approved(photo)
        val transform = CropTransform(1.25, 5.0, 0.0)

        val frozenFamilies = correct(
            base, Envelope50CropSolver.Slot(340.0, 248.0, zooms = listOf(1.05, 1.0)),
            1000, 1000, transform, photo,
        )
        val unrelatedFamilies = correct(
            base, Envelope50CropSolver.Slot(340.0, 248.0, zooms = listOf(3.0, 4.0)),
            1000, 1000, transform, photo,
        )
        assertEquals(frozenFamilies, unrelatedFamilies)
        // zoom-in narrows the window inside the approved crop; it never re-searches
        assertTrue(unrelatedFamilies.window!!.width < base.width)
        assertContained(unrelatedFamilies.window!!, base, "zoom families ignored")
    }

    // --- every admitted window still meets the frozen classifier -----------

    @Test
    fun everyAdmittedWindowOfTheTransformGridMeetsTheFrozenClassifier() {
        val photo = centredMassAnalysis()
        val base = approved(photo)
        val zooms = listOf(1.0, 1.05, 1.125, 1.25)
        var admitted = 0
        var evaluated = 0

        for (z in zooms) {
            val limit = panLimitPercent(z)
            for (x in listOf(-limit, -limit / 2, 0.0, limit / 2, limit)) {
                for (y in listOf(-limit, -limit / 2, 0.0, limit / 2, limit)) {
                    val result = correct(base, slotLandscape, 1000, 1000, CropTransform(z, x, y), photo)
                    if (result.accepted) {
                        assertNull("geometry reason for z=$z x=$x y=$y", result.geometryReject)
                        assertNull("safety reason for z=$z x=$x y=$y", result.safetyReject)
                        assertTrue("classifier must run for z=$z x=$x y=$y", result.classifierEvaluated)
                        assertContained(result.window!!, base, "grid z=$z x=$x y=$y")
                        assertNull(frozenReason(photo, slotLandscape, result.window!!))
                        admitted++
                    } else {
                        assertNull(
                            "in-domain geometry must not fail for z=$z x=$x y=$y",
                            result.geometryReject,
                        )
                        assertNotNull(
                            "a rejected window must carry a frozen reason for z=$z x=$x y=$y",
                            result.safetyReject,
                        )
                        assertTrue("classifier must run for z=$z x=$x y=$y", result.classifierEvaluated)
                    }
                    evaluated++
                }
            }
        }
        assertEquals(zooms.size * 25, evaluated)
        // identity (z=1 row) is always admitted; zoomed admissions must exist too
        assertTrue("identity row must be admitted, measured $admitted", admitted >= 25)
        assertTrue("zoomed admissions must exist, measured $admitted", admitted >= 50)
    }

    @Test
    fun unsafeGridWindowsAreRejectedAndSafeOnesAdmittedWithoutOverlap() {
        val photo = centredMassAnalysis(faces = listOf(AnalysisFace(box(0.10, 0.20, 0.15, 0.15), 0.9)))
        val base = approved(photo)
        var admitted = 0
        var rejected = 0

        for (z in listOf(1.0, 1.125, 1.25)) {
            val limit = panLimitPercent(z)
            for (x in listOf(-limit, -limit / 2, 0.0, limit / 2, limit)) {
                for (y in listOf(-limit, 0.0, limit)) {
                    val result = correct(base, slotLandscape, 1000, 1000, CropTransform(z, x, y), photo)
                    if (result.accepted) {
                        assertNull(result.geometryReject)
                        assertNull(result.safetyReject)
                        assertTrue(result.classifierEvaluated)
                        assertContained(result.window!!, base, "face grid z=$z x=$x y=$y")
                        assertNull(frozenReason(photo, slotLandscape, result.window!!))
                        admitted++
                    } else {
                        assertNull("geometry reason must not coexist with a frozen reason", result.geometryReject)
                        assertNotNull("a rejection must carry a frozen reason", result.safetyReject)
                        assertTrue(result.classifierEvaluated)
                        rejected++
                    }
                }
            }
        }
        assertTrue("the sweep must contain admissions", admitted > 0)
        assertTrue("the sweep must contain frozen rejections", rejected > 0)
    }

    // --- correction state key ---------------------------------------------

    @Test
    fun correctionKeyIsScopedByExpressionAndSlotWithAContentIdGuard() {
        val key = correctionKey("candidate-a", 0, "content-1")
        assertNotNull(key)
        assertEquals(CorrectionKey("candidate-a", 0, "content-1"), key)
        assertTrue(key!!.matches("candidate-a", 0, "content-1"))

        // photo replaced in the same slot ⇒ guard fails ⇒ correction discarded
        assertFalse(key.matches("candidate-a", 0, "content-2"))
        // another slot with the same photo is a different correction
        assertFalse(key.matches("candidate-a", 1, "content-1"))
        // another candidate identity with the same photo and slot differs too
        assertFalse(key.matches("candidate-b", 0, "content-1"))

        val otherSlot = correctionKey("candidate-a", 1, "content-1")
        assertFalse(key == otherSlot)
        val sameContentOtherSlot = correctionKey("candidate-a", 1, "content-1")
        assertEquals(otherSlot, sameContentOtherSlot)
        // not global per photo: the same content id under another identity differs
        assertFalse(key == correctionKey("candidate-b", 0, "content-1"))

        assertNull(correctionKey("", 0, "content-1"))
        assertNull(correctionKey("candidate-a", -1, "content-1"))
        assertNull(correctionKey("candidate-a", 0, ""))
    }
}
