package dev.hryshyn.remanence.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * G2 boundary tests: fake providers only (no renderer algorithms),
 * plain JVM. Every test pins determinism, taxonomy, or isolation —
 * never implementation policy (no grammar v1–v7 rules here).
 */
class GeneratorDiscoveryTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "c".repeat(64)

    private fun input() = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = listOf(
            GeneratorExpression.PhotoRef("p1", 0, 1080, 1920, hashA),
            GeneratorExpression.PhotoRef("p2", 1, 1920, 1080, hashB),
            GeneratorExpression.PhotoRef("p3", 2, 1080, 1080, hashC),
        ),
        note = "hello",
        music = null,
    )

    private fun request(
        seed: Long = 42L,
        max: Int = 10,
        id: String = "gen-1",
    ) = GeneratorDiscovery.GenerationRequest(input(), id, seed, max)

    private fun fakeExpression(
        input: GeneratorExpression.GeneratorInput,
        grammar: String = "fake-g",
    ) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = grammar,
        grammarVersion = 1,
        branchId = "fake-b",
        input = input,
        placements = input.photos.map { photo ->
            GeneratorExpression.Placement(photo.contentId, 0, 0, 120, 213, null, null)
        },
        noteTreatment = "verbatim",
        diagnostics = emptyList(),
        fontVersion = 1,
        paletteVersion = 1,
    )

    private open inner class FakeDeterministic(
        id: String,
        version: Int = 1,
        private val count: Int = 2,
    ) : GeneratorDiscovery.GeneratorProvider {
        override val ref = GeneratorDiscovery.ProviderRef(id, version)
        override fun generate(request: GeneratorDiscovery.GenerationRequest) =
            GeneratorDiscovery.ProviderOutcome.Candidates(
                (0 until count).map { ordinal ->
                    GeneratorDiscovery.Candidate(
                        candidateId = "${ref.providerId}#${request.generationId}#$ordinal",
                        ordinal = ordinal,
                        provider = ref,
                        expression = fakeExpression(request.input),
                    )
                },
            )
    }

    @Test
    fun repeatabilityAcrossIdenticalRequests() {
        val providers = listOf(FakeDeterministic("fake.b"), FakeDeterministic("fake.a"))
        val first = GeneratorDiscovery.run(request(), providers)
        val second = GeneratorDiscovery.run(request(), providers)
        assertEquals(first.accepted.map { it.candidateId }, second.accepted.map { it.candidateId })
        assertEquals(first.inputHash, second.inputHash)
        assertEquals(GeneratorExpression.canonicalHash(input()), first.inputHash)
        assertEquals(42L, first.seed)
        assertEquals("gen-1", first.generationId)
    }

    @Test
    fun discoveryOrderIgnoresRegistrationOrder() {
        val scrambled = listOf(
            FakeDeterministic("zeta", version = 1, count = 1),
            FakeDeterministic("alpha", version = 2, count = 1),
            FakeDeterministic("alpha", version = 1, count = 1),
        )
        val result = GeneratorDiscovery.run(request(), scrambled)
        assertEquals(
            listOf(
                GeneratorDiscovery.ProviderRef("alpha", 2),
                GeneratorDiscovery.ProviderRef("alpha", 1),
                GeneratorDiscovery.ProviderRef("zeta", 1),
            ),
            result.providers.map { it.provider },
        )
        assertEquals(
            listOf("alpha#gen-1#0", "alpha#gen-1#0", "zeta#gen-1#0").distinct().size + 1,
            result.accepted.size + result.rejected.size,
        )
    }

    @Test
    fun duplicateIdsRejectedFirstWins() {
        val dup = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("dup", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate("same", 0, ref, fakeExpression(request.input)),
                        GeneratorDiscovery.Candidate("same", 1, ref, fakeExpression(request.input)),
                    ),
                )
        }
        val result = GeneratorDiscovery.run(request(), listOf(dup))
        assertEquals(1, result.accepted.size)
        assertEquals(1, result.rejected.size)
        assertEquals("duplicate id", result.rejected.single().cause)
        assertEquals("same", result.accepted.single().candidateId)
    }

    @Test
    fun invalidExpressionsRejectedWithReasons() {
        val bad = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("bad", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome {
                val evil = fakeExpression(request.input).copy(
                    placements = listOf(
                        GeneratorExpression.Placement("p1", 300, 600, 120, 213, null, null),
                        GeneratorExpression.Placement("p2", 0, 0, 120, 213, null, null),
                        GeneratorExpression.Placement("p3", 0, 0, 120, 213, null, null),
                    ),
                )
                return GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(GeneratorDiscovery.Candidate("evil", 0, ref, evil)),
                )
            }
        }
        val result = GeneratorDiscovery.run(request(), listOf(bad))
        assertTrue(result.accepted.isEmpty())
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().cause.startsWith("invalid expression: "))
    }

    @Test
    fun nonSequentialOrdinalsRejectWholeSet() {
        val shuffled = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("shuffled", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate("s1", 1, ref, fakeExpression(request.input)),
                        GeneratorDiscovery.Candidate("s0", 0, ref, fakeExpression(request.input)),
                    ),
                )
        }
        val result = GeneratorDiscovery.run(request(), listOf(shuffled))
        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.rejected.size)
        assertEquals("failed", result.providers.single().outcome)
    }

    @Test
    fun failuresIsolatedUnsupportedRouted() {
        val throwing = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("boom", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome =
                throw IllegalStateException("provider exploded")
        }
        val unsupported = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("nope", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("music input")
        }
        val good = FakeDeterministic("good", count = 1)
        val result = GeneratorDiscovery.run(request(), listOf(throwing, unsupported, good))
        assertEquals(1, result.accepted.size)
        assertEquals("good#gen-1#0", result.accepted.single().candidateId)
        val byId = result.providers.associateBy { it.provider.providerId }
        assertEquals("failed", byId.getValue("boom").outcome)
        assertEquals("unsupported", byId.getValue("nope").outcome)
        assertEquals("candidates", byId.getValue("good").outcome)
    }

    @Test
    fun emptySetIsFailureNotSuccess() {
        val empty = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("empty", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(emptyList())
        }
        val result = GeneratorDiscovery.run(request(), listOf(empty))
        assertTrue(result.accepted.isEmpty())
        assertEquals("failed", result.providers.single().outcome)
        assertEquals("empty candidate set", result.providers.single().detail)
    }

    @Test
    fun truncationIsDeterministicAndCounted() {
        val a = FakeDeterministic("a", count = 2)
        val b = FakeDeterministic("b", count = 2)
        val result = GeneratorDiscovery.run(request(max = 3), listOf(b, a))
        assertEquals(listOf("a#gen-1#0", "a#gen-1#1", "b#gen-1#0"), result.accepted.map { it.candidateId })
        assertEquals(1, result.truncated)
    }

    @Test
    fun invalidRequestsRejected() {
        assertTrue(GeneratorDiscovery.validateRequest(request(id = "  ")).isNotEmpty())
        assertTrue(GeneratorDiscovery.validateRequest(request(max = 0)).isNotEmpty())
        val badInput = input().copy(photos = emptyList())
        assertTrue(GeneratorDiscovery.validateRequest(request().copy(input = badInput)).isNotEmpty())
        assertTrue(GeneratorDiscovery.validateRequest(request()).isEmpty())
    }

    @Test
    fun providerMismatchRejected() {
        val liar = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("liar", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate(
                            "x", 0,
                            GeneratorDiscovery.ProviderRef("someone-else", 9),
                            fakeExpression(request.input),
                        ),
                    ),
                )
        }
        val result = GeneratorDiscovery.run(request(), listOf(liar))
        assertTrue(result.accepted.isEmpty())
        assertEquals("provider mismatch", result.rejected.single().cause)
    }

    @Test
    fun seedChangesProvenanceButNotFakeShape() {
        val first = GeneratorDiscovery.run(request(seed = 1L), listOf(FakeDeterministic("s")))
        val second = GeneratorDiscovery.run(request(seed = 2L), listOf(FakeDeterministic("s")))
        assertEquals(1L, first.seed)
        assertEquals(2L, second.seed)
        assertEquals(first.accepted.map { it.candidateId }, second.accepted.map { it.candidateId })
        assertEquals(first.inputHash, second.inputHash)
    }
}
