package dev.hryshyn.remanence.core.model

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * G2 boundary tests: fake providers only (no renderer algorithms),
 * plain JVM + coroutines-test. Existing assertions are preserved
 * verbatim in behavior (runTest wrapping only); acceptance tests are
 * named per the review handoffs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
        override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
    fun repeatabilityAcrossIdenticalRequests() = runTest {
        val providers = listOf(FakeDeterministic("fake.b"), FakeDeterministic("fake.a"))
        val first = GeneratorDiscovery.run(request(), providers)
        val second = GeneratorDiscovery.run(request(), providers)
        assertEquals(first.accepted.map { it.candidateId }, second.accepted.map { it.candidateId })
        assertEquals(first, second)
        assertEquals(first.inputHash, second.inputHash)
        assertEquals(GeneratorExpression.canonicalHash(input()), first.inputHash)
        assertEquals(42L, first.seed)
        assertEquals("gen-1", first.generationId)
    }

    @Test
    fun discoveryOrderIgnoresRegistrationOrder() = runTest {
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
    fun duplicateIdsRejectedFirstWins() = runTest {
        val dup = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("dup", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
        assertEquals(
            GeneratorDiscovery.RejectionCause.DUPLICATE_ID,
            result.rejected.single().causeCode,
        )
        assertEquals("same", result.accepted.single().candidateId)
    }

    @Test
    fun invalidExpressionsRejectedWithReasons() = runTest {
        val bad = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("bad", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome {
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
    fun nonSequentialOrdinalsRejectWholeSet() = runTest {
        val shuffled = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("shuffled", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
    fun failuresIsolatedUnsupportedRouted() = runTest {
        val throwing = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("boom", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome =
                throw IllegalStateException("provider exploded")
        }
        val unsupported = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("nope", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
        // B5 redaction: class name recorded, message text never persisted.
        val boomDetail = byId.getValue("boom").detail
        assertTrue(boomDetail.contains("IllegalStateException"))
        assertTrue(!boomDetail.contains("provider exploded"))
    }

    @Test
    fun fatalErrorPassthroughUncaught() = runTest {
        val fatal = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("fatal", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome =
                throw AssertionError("fatal provider bug")
        }
        assertFailsWith<AssertionError> {
            GeneratorDiscovery.run(request(), listOf(fatal))
        }
    }

    @Test
    fun emptySetIsFailureNotSuccess() = runTest {
        val empty = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("empty", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(emptyList())
        }
        val result = GeneratorDiscovery.run(request(), listOf(empty))
        assertTrue(result.accepted.isEmpty())
        assertEquals("failed", result.providers.single().outcome)
        assertEquals("empty candidate set", result.providers.single().detail)
    }

    @Test
    fun truncationIsDeterministicAndCounted() = runTest {
        val a = FakeDeterministic("a", count = 2)
        val b = FakeDeterministic("b", count = 2)
        val result = GeneratorDiscovery.run(request(max = 3), listOf(b, a))
        assertEquals(listOf("a#gen-1#0", "a#gen-1#1", "b#gen-1#0"), result.accepted.map { it.candidateId })
        assertEquals(1, result.truncated)
    }

    @Test
    fun invalidRequestsRejected() = runTest {
        assertTrue(GeneratorDiscovery.validateRequest(request(id = "  ")).isNotEmpty())
        assertTrue(GeneratorDiscovery.validateRequest(request(max = 0)).isNotEmpty())
        val badInput = input().copy(photos = emptyList())
        assertTrue(GeneratorDiscovery.validateRequest(request().copy(input = badInput)).isNotEmpty())
        assertTrue(GeneratorDiscovery.validateRequest(request()).isEmpty())
        val blankRef = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("x")
        }
        assertFailsWith<IllegalArgumentException> {
            GeneratorDiscovery.run(request(), listOf(blankRef))
        }
        val negRef = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("neg", -1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("x")
        }
        assertFailsWith<IllegalArgumentException> {
            GeneratorDiscovery.run(request(), listOf(negRef))
        }
    }

    @Test
    fun lyingProviderIdentityIsStampedByDispatcher() = runTest {
        // N1: dispatcher-owned attribution, recorded — never laundered.
        // Rewritten to pin record-not-silence (old version pinned silence).
        val liar = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("liar", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
        assertEquals("attribution normalized: 1", result.providers.single().detail)
    }

    @Test
    fun invalidFirstNeverSuppressesLaterValid() = runTest {
        val evil = fakeExpression(input()).copy(
            placements = listOf(
                GeneratorExpression.Placement("p1", 300, 600, 120, 213, null, null),
                GeneratorExpression.Placement("p2", 0, 0, 120, 213, null, null),
                GeneratorExpression.Placement("p3", 0, 0, 120, 213, null, null),
            ),
        )
        val first = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("first", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(GeneratorDiscovery.Candidate("shared", 0, ref, evil)),
                )
        }
        val second = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("second", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
    fun cancellationRethrownNotConverted() = runTest {
        val cancelling = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("cancel", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome =
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
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
    fun oversizedInputsRejectedOrBounded() = runTest {
        assertTrue(GeneratorDiscovery.validateRequest(request(id = "x".repeat(257))).isNotEmpty())
        assertTrue(
            GeneratorDiscovery.validateRequest(request(max = GeneratorDiscovery.MAX_CANDIDATES + 1)).isNotEmpty(),
        )
        val badProvider = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("x".repeat(300), 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Unsupported("r".repeat(2500))
        }
        val loudResult = GeneratorDiscovery.run(request(), listOf(loud))
        val detail = loudResult.providers.single().detail
        assertTrue(detail.endsWith("…[truncated]"))
        assertEquals(GeneratorDiscovery.MAX_REASON_CHARS + "…[truncated]".length, detail.length)
    }

    @Test
    fun fullResultDeterministicAcrossRegistrationOrders() = runTest {
        val throwing = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("boom", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome =
                throw IllegalStateException("fixed failure")
        }
        val unsupported = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("nope", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
    fun resultSnapshotsAreDetachedCopies() = runTest {
        val dup = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("dup", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
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
    fun seedChangesProvenanceButNotFakeShape() = runTest {
        val first = GeneratorDiscovery.run(request(seed = 1L), listOf(FakeDeterministic("s")))
        val second = GeneratorDiscovery.run(request(seed = 2L), listOf(FakeDeterministic("s")))
        assertEquals(1L, first.seed)
        assertEquals(2L, second.seed)
        assertEquals(first.accepted.map { it.candidateId }, second.accepted.map { it.candidateId })
        assertEquals(first.inputHash, second.inputHash)
    }

    // ---- acceptance tests (review handoffs, exact names) ----

    @Test
    fun foreignInputCandidatesRejectedAsInputMismatch() = runTest {
        val foreign = input().copy(ownerId = "someone-else", note = "foreign")
        val evil = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("foreign", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate(
                            "f0", 0, ref, fakeExpression(foreign),
                        ),
                    ),
                )
        }
        val result = GeneratorDiscovery.run(request(), listOf(evil))
        assertEquals(0, result.accepted.size)
        assertEquals(1, result.rejected.size)
        assertEquals("input mismatch", result.rejected.single().cause)
        assertEquals(
            GeneratorDiscovery.RejectionCause.INPUT_MISMATCH,
            result.rejected.single().causeCode,
        )
    }

    @Test
    fun cancellationPropagatesInsteadOfFailedRecord() = runTest {
        val started = AtomicBoolean(false)
        val slow = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("slow", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest): GeneratorDiscovery.ProviderOutcome {
                started.set(true)
                delay(60_000)
                return GeneratorDiscovery.ProviderOutcome.Unsupported("too slow")
            }
        }
        val deferred = async { GeneratorDiscovery.run(request(), listOf(slow)) }
        repeat(10) { runCurrent() }
        assertTrue(started.get())
        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }
    }

    @Test
    fun overLimitInputsRejectedAtBoundary() = runTest {
        assertFailsWith<IllegalArgumentException> {
            GeneratorDiscovery.run(
                request(max = GeneratorDiscovery.MAX_CANDIDATES + 1),
                listOf(FakeDeterministic("s")),
            )
        }
        val many = (0 until GeneratorDiscovery.MAX_PROVIDERS + 1).map { FakeDeterministic("p$it", count = 1) }
        assertFailsWith<IllegalArgumentException> {
            GeneratorDiscovery.run(request(), many)
        }
        val noisy = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("noisy", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate(
                            "n0", 0, ref,
                            fakeExpression(request.input).copy(
                                diagnostics = (0 until GeneratorDiscovery.MAX_DIAGNOSTICS + 8).map { "d$it" },
                            ),
                        ),
                    ),
                )
        }
        val result = GeneratorDiscovery.run(request(), listOf(noisy))
        assertEquals(1, result.accepted.size)
        assertEquals(
            GeneratorDiscovery.MAX_DIAGNOSTICS,
            result.accepted.single().expression.diagnostics.size,
        )
        assertTrue(result.providers.single().detail.contains("diagnostics truncated: dropped=8"))
    }

    @Test
    fun blankCandidateIdRejected() = runTest {
        val blank = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("blank", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate("", 0, ref, fakeExpression(request.input)),
                    ),
                )
        }
        val good = FakeDeterministic("good", count = 1)
        val result = GeneratorDiscovery.run(request(), listOf(blank, good))
        assertEquals(1, result.accepted.size)
        assertEquals("good#gen-1#0", result.accepted.single().candidateId)
        assertEquals(1, result.rejected.size)
        assertEquals("blank id", result.rejected.single().cause)
        assertEquals(
            GeneratorDiscovery.RejectionCause.BLANK_ID,
            result.rejected.single().causeCode,
        )
    }

    @Test
    fun negativeVersionRejection() = runTest {
        val base = fakeExpression(input())
        val bad = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("versions", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Candidates(
                    listOf(
                        GeneratorDiscovery.Candidate("v0", 0, ref, base.copy(grammarVersion = -1)),
                        GeneratorDiscovery.Candidate("v1", 1, ref, base.copy(fontVersion = -2)),
                        GeneratorDiscovery.Candidate("v2", 2, ref, base.copy(paletteVersion = -3)),
                    ),
                )
        }
        val result = GeneratorDiscovery.run(request(), listOf(bad))
        assertTrue(result.accepted.isEmpty())
        assertEquals(3, result.rejected.size)
        assertTrue(result.rejected.all { it.cause == "negative version" })
        assertTrue(
            result.rejected.all { it.causeCode == GeneratorDiscovery.RejectionCause.NEGATIVE_VERSION },
        )
    }

    @Test
    fun structuredTaxonomyRoutesElsewhereOrStops() = runTest {
        val skip = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("skip", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.NotApplicable("no music branch")
        }
        val stop = object : GeneratorDiscovery.GeneratorProvider {
            override val ref = GeneratorDiscovery.ProviderRef("halt", 1)
            override suspend fun generate(request: GeneratorDiscovery.GenerationRequest) =
                GeneratorDiscovery.ProviderOutcome.Incompatible("input class excluded")
        }
        val result = GeneratorDiscovery.run(request(), listOf(skip, stop))
        assertTrue(result.accepted.isEmpty())
        val byId = result.providers.associateBy { it.provider.providerId }
        assertEquals("not-applicable", byId.getValue("skip").outcome)
        assertEquals("incompatible", byId.getValue("halt").outcome)
    }
}
