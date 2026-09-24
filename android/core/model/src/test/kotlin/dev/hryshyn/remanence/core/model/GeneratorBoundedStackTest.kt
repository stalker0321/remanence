package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.core.model.GeneratorDiscovery.GenerationRequest
import dev.hryshyn.remanence.core.model.GeneratorDiscovery.GenerationTerminalOutcome
import dev.hryshyn.remanence.core.model.GeneratorDiscovery.ProviderOutcome
import dev.hryshyn.remanence.core.model.GeneratorDiscovery.ProviderOutcomeRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Stage1-A bounded-stack provider tests: real model hashing/validation only
 * (no fixtures, no fake candidates — every expression comes from
 * [GeneratorBoundedStack.resolve] or the provider itself). Plain JVM +
 * coroutines-test, following [GeneratorDiscoveryTest] conventions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorBoundedStackTest {

    private val provider = GeneratorBoundedStack.BoundedStackProvider()

    private fun photo(id: String, ordinal: Int, w: Int = 1080, h: Int = 1920) =
        GeneratorExpression.PhotoRef(
            contentId = id,
            ordinal = ordinal,
            widthPx = w,
            heightPx = h,
            // Distinct lowercase-hex content per id (G1 requires unique hashes).
            contentHash = id.map { it.code.toString(16).padStart(2, '0') }
                .joinToString("").padEnd(64, '0').take(64),
        )

    private fun input(
        ids: List<String> = listOf("s1", "s2", "s3"),
        note: String? = "hello",
        music: GeneratorExpression.MusicRef? = null,
        dims: (String) -> Pair<Int, Int> = { 1080 to 1920 },
    ) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = ids.mapIndexed { i, id -> photo(id, i, dims(id).first, dims(id).second) },
        note = note,
        music = music,
    )

    private fun request(
        input: GeneratorExpression.GeneratorInput = input(),
        id: String = "gen-bs-1",
        seed: Long = 42L,
        max: Int = 10,
    ) = GenerationRequest(input, id, seed, max)

    private suspend fun candidatesOf(req: GenerationRequest) =
        when (val outcome = provider.generate(req)) {
            is ProviderOutcome.Candidates -> outcome.candidates
            else -> error("expected Candidates, got $outcome")
        }

    @Test
    fun eligibleInputYieldsOneValidCandidate() = runTest {
        val req = request()
        val candidates = candidatesOf(req)
        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(0, candidate.ordinal)
        assertEquals(provider.ref, candidate.provider)
        assertEquals(req.input, candidate.expression.input)
        assertTrue(
            GeneratorExpression.validateResolved(candidate.expression)
                is GeneratorExpression.InputValidation.Valid,
        )
    }

    @Test
    fun authoredOrderPreservedAndBandsTileCanvasExactly() = runTest {
        val ids = listOf("a1", "a2", "a3", "a4", "a5")
        val candidates = candidatesOf(request(input(ids)))
        val placements = candidates.single().expression.placements
        assertEquals(ids, placements.map { it.contentId })
        assertEquals(0, placements.first().y)
        placements.forEach { assertEquals(GeneratorExpression.CANVAS_WIDTH_UNITS, it.width) }
        placements.zipWithNext { upper, lower ->
            assertEquals(upper.y + upper.height, lower.y)
        }
        val last = placements.last()
        assertEquals(GeneratorExpression.CANVAS_HEIGHT_UNITS, last.y + last.height)
    }

    @Test
    fun extremeAspectsStayWithinCanvasAndCropBounds() = runTest {
        val req = request(
            input(
                ids = listOf("wide", "tall", "square"),
                dims = {
                    when (it) {
                        "wide" -> 2000 to 100
                        "tall" -> 100 to 2000
                        else -> 1000 to 1000
                    }
                },
            ),
        )
        val expression = candidatesOf(req).single().expression
        assertTrue(
            GeneratorExpression.validateResolved(expression)
                is GeneratorExpression.InputValidation.Valid,
        )
        expression.placements.forEach { placement ->
            assertTrue(placement.x >= 0 && placement.y >= 0)
            assertTrue(placement.x + placement.width <= GeneratorExpression.CANVAS_WIDTH_UNITS)
            assertTrue(placement.y + placement.height <= GeneratorExpression.CANVAS_HEIGHT_UNITS)
            // Stage1-A emits full originals: no crop windows to bound-check.
            assertEquals(null, placement.crop)
            assertEquals(null, placement.maskId)
        }
    }

    @Test
    fun absentAndEmptyNotesAreDistinct() = runTest {
        val absent = candidatesOf(request(input(note = null))).single().expression
        val empty = candidatesOf(request(input(note = ""))).single().expression
        assertTrue(absent.noteTreatment != empty.noteTreatment)
        assertTrue(GeneratorExpression.canonicalHash(absent) != GeneratorExpression.canonicalHash(empty))
        assertTrue(
            GeneratorExpression.validateResolved(absent) is GeneratorExpression.InputValidation.Valid,
        )
        assertTrue(
            GeneratorExpression.validateResolved(empty) is GeneratorExpression.InputValidation.Valid,
        )
    }

    @Test
    fun noteAtWireCeilingPassesAndOneByteOverFailsClosed() = runTest {
        val limit = "n".repeat(ProtocolV1Limits.NOTE_MAX_UTF8_BYTES)
        assertEquals(1, candidatesOf(request(input(note = limit))).size)
        val over = request(input(note = "n".repeat(ProtocolV1Limits.NOTE_MAX_UTF8_BYTES + 1)))
        val outcome = provider.generate(over)
        assertTrue(outcome is ProviderOutcome.Failed, "expected Failed, got $outcome")
        assertFailsWith<IllegalArgumentException> {
            GeneratorDiscovery.run(over, listOf(provider))
        }
    }

    @Test
    fun repeatabilitySameInputSameCandidateAndHash() = runTest {
        val req = request()
        val first = candidatesOf(req).single()
        val second = candidatesOf(request()).single()
        assertEquals(first, second)
        assertEquals(
            GeneratorExpression.canonicalHash(first.expression),
            GeneratorExpression.canonicalHash(second.expression),
        )
        val runA = GeneratorDiscovery.run(req, listOf(provider))
        val runB = GeneratorDiscovery.run(req, listOf(provider))
        assertEquals(runA, runB)
        assertEquals(GeneratorExpression.canonicalHash(req.input), runA.inputHash)
        assertEquals(GenerationTerminalOutcome.SUCCESS, runA.terminal)
    }

    @Test
    fun musicRoutesElsewhereNeverIncompatible() = runTest {
        val musical = request(
            input(music = GeneratorExpression.MusicRef(trackId = "t-1", artworkId = null)),
        )
        val outcome = provider.generate(musical)
        assertTrue(outcome is ProviderOutcome.NotApplicable, "expected NotApplicable, got $outcome")
        val result = GeneratorDiscovery.run(musical, listOf(provider))
        assertTrue(result.accepted.isEmpty())
        assertEquals(GenerationTerminalOutcome.EMPTY, result.terminal)
        assertEquals(ProviderOutcomeRecord.NOT_APPLICABLE, result.providers.single().outcome)
    }

    @Test
    fun invalidInputsFailClosed() = runTest {
        // Fewer than 3 photos.
        val two = provider.generate(request(input(ids = listOf("s1", "s2"))))
        assertTrue(two is ProviderOutcome.Failed, "expected Failed, got $two")
        // Duplicate content ids.
        val dup = provider.generate(request(input(ids = listOf("s1", "s1", "s2"))))
        assertTrue(dup is ProviderOutcome.Failed, "expected Failed, got $dup")
        // Non-sequential ordinals.
        val off = input().copy(photos = input().photos.mapIndexed { i, p -> p.copy(ordinal = i + 1) })
        val ordinal = provider.generate(request(off))
        assertTrue(ordinal is ProviderOutcome.Failed, "expected Failed, got $ordinal")
    }

    @Test
    fun expressionHashIsStableHexAndFreezeConsistent() = runTest {
        val expression = candidatesOf(request()).single().expression
        val hash = GeneratorExpression.canonicalHash(expression)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")), "unexpected hash $hash")
        val frozen = GeneratorExpression.freeze(expression, contentRevision = 3L, frozenAtEpoch = 7L)
        assertEquals(hash, frozen.expressionHash)
        assertEquals(expression, frozen.expression)
    }

    @Test
    fun singleCandidateRespectsMaxCandidates() = runTest {
        val result = GeneratorDiscovery.run(request(max = 1), listOf(provider))
        assertEquals(1, result.accepted.size)
        assertEquals(0, result.truncated)
        assertEquals(GenerationTerminalOutcome.SUCCESS, result.terminal)
    }

    @Test
    fun provisionalContractVersionsAreStrictlyPositive() = runTest {
        val expression = candidatesOf(request()).single().expression
        assertEquals(GeneratorBoundedStack.GRAMMAR_ID, expression.grammarId)
        assertEquals(GeneratorBoundedStack.BRANCH_ID, expression.branchId)
        assertTrue(expression.canvasVersion > 0)
        assertTrue(expression.grammarVersion > 0)
        assertTrue(expression.fontVersion > 0)
        assertTrue(expression.paletteVersion > 0)
    }

    @Test
    fun resolveRejectsInvalidInputBeforeGeometry() {
        val empty = input().copy(photos = emptyList())
        val noPhotos = assertFailsWith<IllegalArgumentException> { GeneratorBoundedStack.resolve(empty) }
        assertTrue(noPhotos.message!!.contains("photos.size"))
        val overNote = input().copy(note = "n".repeat(ProtocolV1Limits.NOTE_MAX_UTF8_BYTES + 1))
        val tooLong = assertFailsWith<IllegalArgumentException> { GeneratorBoundedStack.resolve(overNote) }
        assertTrue(tooLong.message!!.contains("note must be"))
    }

    @Test
    fun candidateIdDerivesFromExpressionIdentity() = runTest {
        val first = candidatesOf(request()).single()
        val expected = "bounded-stack-${GeneratorExpression.canonicalHash(first.expression).take(16)}-0"
        assertEquals(expected, first.candidateId)
        val second = candidatesOf(request()).single()
        assertEquals(first.candidateId, second.candidateId)
    }
}
