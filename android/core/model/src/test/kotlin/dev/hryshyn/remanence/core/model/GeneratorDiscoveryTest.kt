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
    fun lyingProviderIdentityIsStampedByDispatcher() {
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
        assertEquals(1, result.accepted.size)
        assertEquals(GeneratorDiscovery.ProviderRef("liar", 1), result.accepted.single().provider)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun invalidFirstNeverSuppressesLaterValid() {
        val evil = fakeExpression(input()).copy(
            placements = listOf(
                GeneratorExpression.Placement("p1", 300, 600, 120, 213, null, null),
                GeneratorExpression.Placement("p2", 0, 0, 120, 213, null, null),
                GeneratorExpression.Placement("p3", 0, 0, 120, 213, null, null),
            ),
        )
        val first = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("first", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(GeneratorDiscovery.Candidate("shared", 0, ref, evil)),
                )
        }
        val second = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("second", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(GeneratorDiscovery.Candidate("shared", 0, ref, fakeExpression(request.input))),
                )
        }
        val result = GeneratorDiscovery.run(request(), listOf(first, second))
        assertEquals(1, result.accepted.size)
        assertEquals("shared", result.accepted.single().candidateId)
        assertEquals(GeneratorDiscovery.ProviderRef("second", 1), result.accepted.single().provider)
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().cause.startsWith("invalid expression: "))
    }

    @Test
    fun cancellationRethrownNotConverted() {
        val cancelling = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("cancel", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome =
                throw java.util.concurrent.CancellationException("stop")
        }
        var threw = false
        try {
            GeneratorDiscovery.run(request(), listOf(cancelling))
        } catch (e: java.util.concurrent.CancellationException) {
            threw = true
        }
        assertTrue(threw, "provider CancellationException must propagate, not convert")

        var ownThrew = false
        try {
            GeneratorDiscovery.run(request(), listOf(FakeDeterministic("s")), shouldCancel = { true })
        } catch (e: GeneratorDiscovery.GenerationCancelledException) {
            ownThrew = true
        }
        assertTrue(ownThrew, "observed cancellation must throw GenerationCancelledException")

        var calls = 0
        val counting = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("counting", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("never").also { calls++ }
        }
        try {
            GeneratorDiscovery.run(request(), listOf(counting), shouldCancel = { calls >= 1 })
        } catch (e: GeneratorDiscovery.GenerationCancelledException) {
            // expected: cancel observed between providers
        }
        assertEquals(1, calls)
    }

    @Test
    fun oversizedInputsRejectedOrBounded() {
        assertTrue(GeneratorDiscovery.validateRequest(request(id = "x".repeat(257))).isNotEmpty())
        assertTrue(
            GeneratorDiscovery.validateRequest(request(max = GeneratorDiscovery.MAX_CANDIDATES + 1)).isNotEmpty(),
        )
        val badProvider = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("x".repeat(300), 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("no")
        }
        var refThrew = false
        try {
            GeneratorDiscovery.run(request(), listOf(badProvider))
        } catch (e: IllegalArgumentException) {
            refThrew = true
        }
        assertTrue(refThrew, "oversized providerId must fail fast")

        val longId = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("long", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate(
                            "y".repeat(300), 0, ref, fakeExpression(request.input),
                        ),
                    ),
                )
        }
        val longResult = GeneratorDiscovery.run(request(), listOf(longId))
        assertTrue(longResult.accepted.isEmpty())
        assertEquals("bad id", longResult.rejected.single().cause)

        val loud = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("loud", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("r".repeat(2500))
        }
        val loudResult = GeneratorDiscovery.run(request(), listOf(loud))
        val detail = loudResult.providers.single().detail
        assertTrue(detail.endsWith("…[truncated]"))
        assertEquals(GeneratorDiscovery.MAX_REASON_CHARS + "…[truncated]".length, detail.length)
    }

    @Test
    fun fullResultDeterministicAcrossRegistrationOrders() {
        val throwing = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("boom", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome =
                throw IllegalStateException("fixed failure")
        }
        val unsupported = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("nope", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("music input")
        }
        val good = FakeDeterministic("good", count = 1)
        val orders = listOf(
            listOf(throwing, unsupported, good),
            listOf(good, throwing, unsupported),
            listOf(unsupported, good, throwing),
        )
        val results = orders.map { GeneratorDiscovery.run(request(), it) }
        assertEquals(results[0], results[1])
        assertEquals(results[0], results[2])
    }

    @Test
    fun resultSnapshotsAreDetachedCopies() {
        val dup = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("dup", 1)
            override fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate("d", 0, ref, fakeExpression(request.input)),
                        GeneratorDiscovery.Candidate("d", 1, ref, fakeExpression(request.input)),
                    ),
                )
        }
        val providers = listOf(dup, FakeDeterministic("s", count = 1))
        val first = GeneratorDiscovery.run(request(), providers)
        val second = GeneratorDiscovery.run(request(), providers)
        assertEquals(first, second)
        assertEquals(2, first.accepted.size)
        assertEquals(1, first.rejected.size)
        assertTrue(!sameInstance(first.accepted, second.accepted))
        assertTrue(!sameInstance(first.rejected, second.rejected))
        assertTrue(!sameInstance(first.providers, second.providers))
    }

    private fun sameInstance(a: Any, b: Any): Boolean = a === b

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
