package dev.hryshyn.remanence.ui.collage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T151 production mounting — the actual all-family producer callpath.
 *
 * Uses a real [CollageCropMatrixSource]-shaped fake whose costs force a
 * non-source-order complete-safe assignment, plus a fake authoritative
 * [CollageCropSource] for the sealed placements, and proves the producer
 * enumerates **all 14 families**, matches with `complete-safe-matching`, orders
 * by the frozen first-valid resolve order and applies the DECISIONS cap.
 */
class AllFamilyCandidateProducerTest {

    private val grid = CollageAnalysis.GRID_SIZE

    private fun field(value: Double) = DoubleArray(grid * grid) { value }

    private fun face() = AnalysisFace(NormBox(0.4, 0.4, 0.2, 0.2), 0.9)

    private fun analysis(width: Int, height: Int) = CollageAnalysis(
        width = width,
        height = height,
        faces = listOf(face()),
        objects = emptyList(),
        protectedRegions = emptyList(),
        importance = field(0.7),
        baselineField = field(0.5),
        objectField = field(0.0),
        saliency = field(0.6),
        edgeEnergy = field(0.6),
        structureField = field(0.5),
    )

    private fun photos(): List<AllFamilyPhoto> = listOf(
        AllFamilyPhoto("c0", 1500, 1000, true, analysis(1500, 1000)),
        AllFamilyPhoto("c1", 1000, 1500, true, analysis(1000, 1500)),
        AllFamilyPhoto("c2", 1000, 1000, true, analysis(1000, 1000)),
    )

    private val preferred = mapOf("c0" to 1.5, "c1" to 1000.0 / 1500.0, "c2" to 1.0)

    /** Costs by source-aspect affinity; source-order is deliberately not cheapest. */
    private fun fakeMatrix() = CollageCropMatrixSource { _, requests ->
        requests.associateWith { request ->
            val pref = preferred[request.contentId] ?: 1.0
            CropMatrixCell(true, null, Math.abs(Math.log((request.slotWidth / request.slotHeight) / pref)))
        }
    }

    private fun input(sender: String = "Alice") = AllFamilyInput(
        id = "all-family",
        sender = sender,
        note = "a short note",
        music = true,
        artwork = true,
        photos = photos(),
    )

    @Test
    fun mountsAllFamiliesWithCompleteSafeMatchingAndCap() = runBlocking {
        val outcome = AllFamilyCandidateProducer(fakeMatrix()).produce(input())
        assertTrue("expected candidates", outcome is AllFamilyOutcome.Candidates)
        val result = outcome as AllFamilyOutcome.Candidates

        assertEquals(
            "all 14 frozen families must be enumerated for a 3-photo note+music+artwork+face input",
            FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS.toSet(),
            result.attempted,
        )
        assertTrue("producer must mount candidates", result.candidates.isNotEmpty())
        assertTrue("DECISIONS total cap", result.candidates.size <= FrozenCandidatePolicy.MAX_TOTAL)
        result.perGrammar.forEach { (gid, branches) ->
            assertTrue("max two layouts per grammar ($gid)", branches.size <= FrozenCandidatePolicy.MAX_PER_GRAMMAR)
        }
        assertTrue("candidates must span beyond Editorial Rows", result.perGrammar.keys.any { it != 5 })
        assertTrue(
            "assignment must be complete-safe (not a source-order fallback)",
            result.candidates.all { it.assignmentMode == "complete-safe-matching" },
        )
        val sourceOrder = photos().map { it.contentId }
        assertTrue(
            "complete-safe matching must permute at least one candidate vs source order",
            result.candidates.any { it.compositionOrder != sourceOrder },
        )
        assertTrue("placements must be mounted", result.candidates.all { it.placements.isNotEmpty() })
    }

    @Test
    fun unavailableWhenAuthoritativeAnalysisIsMissing() = runBlocking {
        val noAnalysis = CollageCropMatrixSource { _, _ -> null }
        val outcome = AllFamilyCandidateProducer(noAnalysis).produce(input())
        assertTrue("must fail closed", outcome is AllFamilyOutcome.Unavailable)
    }

    @Test
    fun blankSenderOmitsSenderMarks() = runBlocking {
        val outcome = AllFamilyCandidateProducer(fakeMatrix()).produce(input(sender = "   "))
        val result = outcome as AllFamilyOutcome.Candidates
        assertTrue(
            "blank sender must never fabricate a sender mark",
            result.candidates.all { candidate -> candidate.plan.provenance.none { it.role == "sender" } },
        )
    }

    @Test
    fun rejectedPlacementCropOmitsTheBranchNotTheWholeRun() = runBlocking {
        // The fake assignment matrix says every cell is feasible, but the real
        // solver rejects the slot on a zero-protection analysis: the branch must
        // be omitted while the run stays a candidate outcome.
        val rejecting = AllFamilyInput(
            id = "all-family",
            sender = "Alice",
            note = "a short note",
            music = true,
            artwork = true,
            photos = listOf(
                AllFamilyPhoto("c0", 50, 50, true, analysis(50, 50)),
                AllFamilyPhoto("c1", 50, 50, true, analysis(50, 50)),
                AllFamilyPhoto("c2", 50, 50, true, analysis(50, 50)),
            ),
        )
        val outcome = AllFamilyCandidateProducer(fakeMatrix()).produce(rejecting)
        assertTrue(
            "a rejected per-slot crop must not abort the whole run",
            outcome is AllFamilyOutcome.Candidates,
        )
        val result = outcome as AllFamilyOutcome.Candidates
        assertTrue("every candidate lacked a safe placement crop", result.candidates.isEmpty())
        assertTrue("the enumerated families still report their valid layouts", result.perGrammar.isNotEmpty())
        assertTrue("omitted branches must carry a typed cause", result.omissions.isNotEmpty())
        assertTrue(
            "at least one omitted crop branch must record the frozen reason",
            result.omissions.any { it.kind == OmissionKind.PLACEMENT_CROP_REJECTED && it.cropReason != null },
        )
        assertTrue(
            "omitted crop branches must name the slot/content",
            result.omissions.filter { it.kind == OmissionKind.PLACEMENT_CROP_REJECTED }
                .all { it.slotIndex != null && it.contentId != null },
        )
    }

    @Test
    fun loadPhotosRejectsMismatchedAnalysisDimensions() = runBlocking {
        val source = CollageAnalysisSource { analysis(900, 900) }
        assertNull(
            "an analysis whose dimensions differ from the verified photo must fail closed",
            AllFamilyCandidateProducer.loadPhotos(listOf(PhotoDescriptor("c0", 1000, 1000, true)), source),
        )
    }

    @Test
    fun sharedAnalysisSourceIsFetchedOncePerPhotoAcrossMatrixAndPlacements() = runBlocking {
        val calls = LinkedHashMap<String, Int>()
        val byId = mapOf(
            "c0" to analysis(1500, 1000),
            "c1" to analysis(1000, 1500),
            "c2" to analysis(1000, 1000),
        )
        val source = CollageAnalysisSource { contentId ->
            calls[contentId] = (calls[contentId] ?: 0) + 1
            byId[contentId]
        }
        val descriptors = listOf(
            PhotoDescriptor("c0", 1500, 1000, true),
            PhotoDescriptor("c1", 1000, 1500, true),
            PhotoDescriptor("c2", 1000, 1000, true),
        )
        val loaded = requireNotNull(AllFamilyCandidateProducer.loadPhotos(descriptors, source))
        val outcome = AllFamilyCandidateProducer(fakeMatrix()).produce(
            AllFamilyInput("cache", "Alice", "a short note", true, true, loaded),
        )
        assertTrue(outcome is AllFamilyOutcome.Candidates)
        assertEquals(setOf("c0", "c1", "c2"), calls.keys)
        assertTrue("exactly one analysis fetch per photo, got $calls", calls.values.all { it == 1 })
    }

    @Test
    fun loadPhotosUsesTheSharedAnalysisSourceAndFailsClosed() = runBlocking {
        val analysed = analysis(1000, 1000)
        val source = CollageAnalysisSource { contentId -> if (contentId == "c0") analysed else null }
        val loaded = AllFamilyCandidateProducer.loadPhotos(
            listOf(PhotoDescriptor("c0", 1000, 1000, true)),
            source,
        )
        assertEquals(1, loaded!!.size)
        assertEquals("c0", loaded[0].contentId)
        assertEquals(analysed, loaded[0].analysis)
        assertNull(
            "missing analysis must fail closed",
            AllFamilyCandidateProducer.loadPhotos(listOf(PhotoDescriptor("gone", 1, 1, false)), source),
        )
    }
}
