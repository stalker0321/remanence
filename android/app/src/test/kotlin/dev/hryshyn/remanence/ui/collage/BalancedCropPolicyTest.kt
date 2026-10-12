package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic analyzed inputs, not the owner's postcard dataset or real detector output. */
class BalancedCropPolicyTest {
    private val n = CollageAnalysis.GRID_SIZE
    private val balanced = CropAdmissionPolicy.BALANCED
    private val strict = CropAdmissionPolicy.STRICT
    private fun field(value: Double = 1.0) = DoubleArray(n * n) { value }
    private fun analysis(
        width: Int = 4000, height: Int = 1000,
        faces: List<AnalysisFace> = emptyList(),
        objects: List<AnalysisObject> = emptyList(),
        regions: List<AnalysisProtectedRegion> = emptyList(),
        importance: DoubleArray = field(), edge: DoubleArray = field(), saliency: DoubleArray = field(),
        baseline: DoubleArray = field(),
    ) = CollageAnalysis(width, height, faces, objects, regions, importance, baseline, field(), saliency, edge, field())
    private val slot = Envelope50CropSolver.Slot(250.0, 250.0)

    @Test fun balancedRescuesRealSearchedAestheticRejectionAndIsDeterministic() {
        val photo = analysis()
        assertEquals(CropSolveResult.Rejected(CropRejectReason.PROTECTION_FIELD_LOW), Envelope50CropSolver.solveDetailed(photo, slot))
        val crop = requireNotNull(Envelope50CropSolver.solve(photo, slot, balanced))
        assertTrue(crop.width * crop.height >= .25)
        assertEquals(crop, Envelope50CropSolver.solve(photo, slot, balanced))
        assertNotNull(Envelope50CropSolver.baseMetrics(photo, slot, balanced))
        assertNull(Envelope50CropSolver.baseMetrics(photo, slot, strict))
    }

    @Test fun balancedKeepsStrictSuccessExactlyIncludingRejectedEnvelopeShift() {
        val photo = analysis(width = 1000)
        for (shape in listOf("rect", "round", "ellipse")) {
            val request = Envelope50CropSolver.Slot(340.0, 248.0, shape, listOf(1.05, 1.0))
            val original = Envelope50CropSolver.solveDetailed(photo, request, strict)
            assertTrue(original is CropSolveResult.Solved)
            assertEquals(original, Envelope50CropSolver.solveDetailed(photo, request, balanced))
            assertEquals(Envelope50CropSolver.baseMetrics(photo, request), Envelope50CropSolver.baseMetrics(photo, request, balanced))
        }
    }

    @Test fun relaxedEnvelopeUsesEffectivePolicyAndKeepsBaseIfFaceShiftWouldFail() {
        val objectPhoto = analysis(objects = listOf(AnalysisObject(NormBox(.75, .2, .1, .5), .9, 39)))
        val objectBase = requireNotNull(Envelope50CropSolver.baseMetrics(objectPhoto, slot, balanced)).rect
        val shifted = requireNotNull(Envelope50CropSolver.solve(objectPhoto, slot, balanced))
        assertNotEquals(objectBase.x, shifted.x) // the envelope must not reapply strict aesthetic vetoes
        assertEquals(CropRejectReason.PROTECTION_FIELD_LOW,
            Envelope50CropSolver.rejectionReason(objectPhoto, slot, NormBox(shifted.x, shifted.y, shifted.width, shifted.height)))
        val faces = listOf(
            AnalysisFace(NormBox(.3, .4, .04, .05), .9), // still protected, too small for anchor heuristic
            AnalysisFace(NormBox(.48, .4, .05, .1), .9),
        )
        val photo = analysis(faces = faces)
        val base = requireNotNull(Envelope50CropSolver.baseMetrics(photo, slot, balanced)).rect
        val retained = requireNotNull(Envelope50CropSolver.solve(photo, slot, balanced))
        assertEquals(NormalizedCrop(base.x, base.y, base.width, base.height), retained)
        val target = .505 - base.width / 2
        val badShift = NormBox(base.x + (target - base.x) * .5, base.y, base.width, base.height)
        assertEquals(CropRejectReason.FACE_CROSSES_CROP_OR_MASK,
            Envelope50CropSolver.rejectionReason(photo, slot, badShift, policy = balanced))
    }

    @Test fun relaxedFacesUseActualBoxesButNeverCutThem() {
        val photo = analysis(width = 1000, faces = listOf(AnalysisFace(NormBox(.1, .2, .8, .2), .9)))
        val frame = Envelope50CropSolver.Slot(800.0, 1000.0)
        assertNull(Envelope50CropSolver.solve(photo, frame, strict))
        val crop = requireNotNull(Envelope50CropSolver.solve(photo, frame, balanced))
        assertTrue(crop.x <= .1 + 1e-5 && crop.x + crop.width >= .9 - 1e-5)
        val cutting = analysis(width = 1000, faces = listOf(AnalysisFace(NormBox(.05, .1, .9, .8), .9)))
        assertNull(Envelope50CropSolver.solve(cutting, frame, balanced))
        assertEquals(CropRejectReason.FACE_CROSSES_CROP_OR_MASK,
            Envelope50CropSolver.rejectionReason(cutting, frame, NormBox(0.0, 0.0, 1.0, .5), policy = balanced))
    }

    @Test fun relaxedFaceCannotCrossEllipseOrRoundedMask() {
        val photo = analysis(width = 1000, faces = listOf(AnalysisFace(NormBox(.01, .01, .1, .1), .9)))
        for (shape in listOf("round", "ellipse")) {
            assertEquals(CropRejectReason.FACE_CROSSES_CROP_OR_MASK,
                Envelope50CropSolver.rejectionReason(photo, slot.copy(shape = shape), NormBox(0.0, 0.0, 1.0, 1.0), shape, balanced))
        }
    }

    @Test fun aestheticsArePreferencesNotVetoesInRelaxedEvaluation() {
        val region = NormBox(0.0, 0.0, 1.0, .5)
        val bottom = DoubleArray(n * n) { if (it / n >= n / 2) 1.0 else 0.0 }
        val photos = listOf(
            analysis(width = 1000, importance = bottom),
            analysis(width = 1000, edge = bottom),
            analysis(width = 1000, saliency = bottom),
            analysis(width = 1000, baseline = bottom),
            analysis(width = 1000, regions = listOf(AnalysisProtectedRegion("structure-tip", NormBox(.4, .7, .1, .1), .9, true))),
            analysis(width = 1000, objects = listOf(AnalysisObject(NormBox(.1, .1, .8, .8), .9, 39))),
        )
        photos.forEach {
            assertNotNull(Envelope50CropSolver.rejectionReason(it, slot, region))
            assertNull(Envelope50CropSolver.rejectionReason(it, slot, region, policy = balanced))
        }
    }

    @Test fun balancedPreservesAreaResolutionAndGeometryGuards() {
        assertNull(Envelope50CropSolver.solve(analysis(width = 5000), slot, balanced)) // <25%
        assertNull(Envelope50CropSolver.solve(analysis(100, 100), slot, balanced)) // source pixels
        for (bad in listOf(slot.copy(width = 0.0), slot.copy(width = Double.NaN), slot.copy(shape = "triangle"), slot.copy(zooms = listOf(.5)))) {
            assertNull(Envelope50CropSolver.solve(analysis(), bad, balanced))
        }
        assertEquals(CropRejectReason.NO_CROP_CANDIDATE,
            Envelope50CropSolver.rejectionReason(analysis(), slot, NormBox(-.1, 0.0, 1.0, 1.0), policy = balanced))
        assertNull(Envelope50CropSolver.solve(analysis(importance = field(Double.NaN)), slot, balanced))
        assertNull(Envelope50CropSolver.solve(analysis(faces = listOf(AnalysisFace(NormBox(.9, .2, .2, .1), .9))), slot, balanced))
    }

    private fun familyInput(music: Boolean = false, artwork: Boolean = false): AllFamilyInput {
        val face = AnalysisFace(NormBox(.45, .4, .1, .1), .9)
        return AllFamilyInput("balanced-synthetic", "alice", "a short note", music, artwork,
            (0..2).map { AllFamilyPhoto("p$it", 4000, 1000, true, analysis(faces = listOf(face))) })
    }
    private suspend fun produce(input: AllFamilyInput, matrix: CropAdmissionPolicy, final: CropAdmissionPolicy): AllFamilyOutcome.Candidates =
        AllFamilyCandidateProducer(AndroidCollageCropMatrixSource(matrix), cropPolicy = final).produce(input) as AllFamilyOutcome.Candidates

    @Test fun completeAssignmentAndFinalSolveBothNeedTheSameBalancedPolicyThenSealPrepare() = runBlocking {
        val input = familyInput()
        val before = produce(input, strict, strict)
        val wrongAssignment = produce(input, strict, balanced)
        val wrongFinal = produce(input, balanced, strict)
        val after = produce(input, balanced, balanced)
        assertTrue(before.candidates.isEmpty())
        assertTrue(wrongAssignment.candidates.isEmpty())
        assertTrue(wrongFinal.candidates.isEmpty())
        assertTrue(after.candidates.isNotEmpty())
        assertEquals(before.attempted, after.attempted) // unchanged applicability, not every family needs silent input
        assertTrue(after.candidates.size <= FrozenCandidatePolicy.MAX_TOTAL)
        assertTrue(after.perGrammar.values.all { it.size <= FrozenCandidatePolicy.MAX_PER_GRAMMAR })
        assertEquals(after, produce(input, balanced, balanced))
        val authored = GeneratorExpression.GeneratorInput(input.id, 1L,
            input.photos.mapIndexed { index, p -> GeneratorExpression.PhotoRef(p.contentId, index, p.widthPx, p.heightPx, ('a' + index).toString().repeat(64)) }, input.note, null)
        var sealedCount = 0
        var readyCount = 0
        for (candidate in after.candidates) {
            val style = AllFamilyV4Adapter.styleForGrammar(candidate.grammarId) ?: continue
            val result = AllFamilyV4Adapter.seal(candidate, authored, input.sender, style)
            if (result !is AllFamilyV4Adapter.Outcome.Sealed) continue
            sealedCount++
            assertEquals(authored, result.expression.input)
            assertTrue(GeneratorExpression.validateResolved(result.expression) is GeneratorExpression.InputValidation.Valid)
            val prepared = CollageRenderer.prepare(result.expression, input.note, null,
                input.photos.associate { it.contentId to CollageRenderer.SourcePhoto(it.widthPx, it.heightPx) },
                CollageRenderer.TextMeasurer { request -> CollageRenderer.TextMeasurement(request.maxWidth, request.line, false) })
            if (prepared is CollageRenderer.SceneResult.Ready) readyCount++
        }
        assertTrue("Balanced candidates must actually seal", sealedCount > 0)
        assertTrue("Sealed balanced candidates must pass the unmodified receiver prepare gate", readyCount > 0)
        println("T180 synthetic panorama fixture: strict=${before.candidates.size}; balanced=${after.candidates.size}; sealed=$sealedCount; prepared=$readyCount; attempted=${after.attempted.size}")
    }

    @Test fun balancedMatrixStillRejectsMissingAndDimensionMismatchedAnalysis() = runBlocking {
        val matrix = AndroidCollageCropMatrixSource(balanced)
        val request = CropMatrixRequest("p", 250.0, 250.0)
        assertNull(matrix.matrix(listOf(CropMatrixPhoto("p", 4000, 1000, null)), listOf(request)))
        assertNull(matrix.matrix(listOf(CropMatrixPhoto("p", 3000, 1000, analysis())), listOf(request)))
        assertNull(AllFamilyCandidateProducer.loadPhotos(listOf(PhotoDescriptor("p", 3000, 1000, true)), CollageAnalysisSource { analysis() }))
    }

    @Test fun allFourteenApplicableFamiliesKeepCapsAndGainBalancedCandidates() = runBlocking {
        val input = familyInput(music = true, artwork = true)
        val before = produce(input, strict, strict)
        val after = produce(input, balanced, balanced)
        assertEquals(FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS.toSet(), after.attempted)
        assertEquals(before.attempted, after.attempted)
        assertTrue(before.candidates.isEmpty())
        assertTrue(after.candidates.isNotEmpty())
        assertTrue(after.candidates.size <= FrozenCandidatePolicy.MAX_TOTAL)
        assertTrue(after.perGrammar.values.all { it.size <= FrozenCandidatePolicy.MAX_PER_GRAMMAR })
        println("T180 synthetic panorama+music+art fixture: strict=${before.candidates.size}; balanced=${after.candidates.size}; attempted=${after.attempted.size}")
    }

    @Test fun balancedKeepsHumanRoleAndFacePixelFloor() = runBlocking {
        val matrix = AndroidCollageCropMatrixSource(balanced)
        val request = CropMatrixRequest("p", 250.0, 250.0, assignmentRole = "human-anchor")
        val cells = requireNotNull(matrix.matrix(listOf(CropMatrixPhoto("p", 4000, 1000, analysis())), listOf(request)))
        assertFalse(cells.getValue(request).feasible)
        val tinyFace = analysis(width = 4000, height = 4000, faces = listOf(AnalysisFace(NormBox(.45, .45, .06, .06), .9)))
        val tiny = request.copy(slotWidth = 100.0, slotHeight = 100.0)
        assertFalse(requireNotNull(matrix.matrix(listOf(CropMatrixPhoto("p", 4000, 4000, tinyFace)), listOf(tiny))).getValue(tiny).feasible)
    }
}
