package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * G4-C1 bridge tests: fakes only (memory sources, memory store).
 * No CreateViewModel, publisher, filesystem, or renderer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorCreateBridgeTest {

    companion object {
        const val OWNER_A = "0198f0a0-0000-7000-8000-00000000a001"
        const val OWNER_B = "0198f0a0-0000-7000-8000-00000000b001"
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakePhotoSource(
        private val bytes: ByteArray,
        val uriMarker: String = "content://media/fake/1",
    ) : PhotoSource {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        override fun openInputStream(): InputStream {
            opens.incrementAndGet()
            return object : ByteArrayInputStream(bytes) {
                override fun close() {
                    super.close()
                    closes.incrementAndGet()
                }
            }
        }
    }

    private class FakeDecoder(private val width: Int = 100, private val height: Int = 200) :
        GeneratorSourceBinding.PhotoDecoderPort {
        override suspend fun decodeUpright(jpeg: ByteArray) =
            GeneratorSourceBinding.UprightPhoto(width, height)
    }

    private class FakeNormalizer : PhotoNormalizerPort {
        override suspend fun normalize(inputJpeg: ByteArray) =
            NormalizedPhotoDto(ByteArray(32) { 5 }, 120, 213)
    }

    /** Normalizer parked on a gate so tests can stage deterministic bind races without sleeps. */
    private class GateNormalizer(val gate: CompletableDeferred<Unit>) : PhotoNormalizerPort {
        override suspend fun normalize(inputJpeg: ByteArray): NormalizedPhotoDto {
            gate.await()
            return NormalizedPhotoDto(ByteArray(32) { 5 }, 120, 213)
        }
    }

    /** Source whose stream throws CancellationException on read (M2 path). */
    private class CancellingSource : PhotoSource {
        override fun openInputStream(): InputStream = object : ByteArrayInputStream(ByteArray(8)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                throw CancellationException("test-cancel")
            }
        }
    }

    private class FakeStore : GeneratorStaging.BlobStore {
        val data = mutableMapOf<String, ByteArray>()
        override fun put(key: String, bytes: ByteArray) {
            data[key] = bytes.copyOf()
        }
        override fun get(key: String): ByteArray? = data[key]?.copyOf()
        override fun delete(key: String): Boolean = data.remove(key) != null
        override fun keys(): Set<String> = data.keys.toSet()
    }

    private var now = 1_000L
    private lateinit var store: FakeStore
    private lateinit var staging: GeneratorStaging.Manager
    private lateinit var bridge: GeneratorCreateBridge.Bridge

    private fun setup(normalizer: PhotoNormalizerPort = FakeNormalizer()) {
        store = FakeStore()
        staging = GeneratorStaging.Manager(store, nowMillis = { now })
        bridge = GeneratorCreateBridge.Bridge(
            staging,
            GeneratorSourceBinding.SourceBinder(normalizer, FakeDecoder()),
        )
    }

    private fun bytes(seed: Byte) = ByteArray(64) { (seed + it).toByte() }

    private fun input(
        owner: String = OWNER_A,
        epoch: Long = 7L,
        note: String? = "n",
    ) = GeneratorExpression.GeneratorInput(
        ownerId = owner,
        epoch = epoch,
        photos = listOf(
            GeneratorExpression.PhotoRef("p1", 0, 100, 200, sha(bytes(1))),
            GeneratorExpression.PhotoRef("p2", 1, 100, 200, sha(bytes(2))),
            GeneratorExpression.PhotoRef("p3", 2, 100, 200, sha(bytes(3))),
        ),
        note = note,
        music = null,
    )

    private fun sender() = GeneratorStaging.SenderSnapshot("sender-9", "@mykola")

    private fun begun(
        owner: String = OWNER_A,
        epoch: Long = 7L,
        generation: String = "gen-1",
        note: String? = "n",
    ): GeneratorCreateBridge.BegunSession {
        val started = bridge.begin(UserId.parseRest(owner), epoch, generation, input(owner, epoch, note), sender())
        assertTrue(started is GeneratorCreateBridge.BegunSession)
        return started as GeneratorCreateBridge.BegunSession
    }

    private suspend fun bindAll(
        begun: GeneratorCreateBridge.BegunSession,
        blobs: List<ByteArray> = listOf(bytes(1), bytes(2), bytes(3)),
    ): List<GeneratorCreateBridge.SlotResult> = blobs.mapIndexed { index, blob ->
        bridge.bindSlot(begun.context, begun.sessionId, index, FakePhotoSource(blob))
    }

    @Test
    fun photoInvalidationKillsOldContext() = runTest {
        setup()
        val first = begun()
        assertEquals(1L, first.context.contentRevision)
        assertTrue(bindAll(first)[0] is GeneratorCreateBridge.SlotResult.Bound)
        bridge.onPhotoEdit(first.context, first.sessionId)
        val stale = bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
        assertTrue(stale is GeneratorCreateBridge.SlotResult.Stale)
        val frozen = bridge.freeze(first.context, first.sessionId)
        assertTrue(frozen is GeneratorCreateBridge.FreezeResult.Rejected)
        val second = begun()
        assertEquals(2L, second.context.contentRevision)
        assertTrue(second.context.contentRevision != first.context.contentRevision)
    }

    @Test
    fun noteInvalidationKillsOldContext() = runTest {
        setup()
        val first = begun()
        assertTrue(bindAll(first)[0] is GeneratorCreateBridge.SlotResult.Bound)
        bridge.onNoteEdit(first.context, first.sessionId)
        val stale = bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
        assertTrue(stale is GeneratorCreateBridge.SlotResult.Stale)
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun ownerEpochChangesInvalidate() = runTest {
        setup()
        val first = begun()
        assertTrue(bindAll(first)[0] is GeneratorCreateBridge.SlotResult.Bound)
        bridge.onOwnerOrEpochChange(first.context, first.sessionId)
        val stale = bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
        assertTrue(stale is GeneratorCreateBridge.SlotResult.Stale)
        val second = begun(owner = OWNER_B, epoch = 9L)
        assertTrue(bindAll(second).all { it is GeneratorCreateBridge.SlotResult.Bound })
    }

    @Test
    fun staleCallbackRejected() = runTest {
        setup()
        val first = begun()
        assertTrue(
            bridge.bindSlot(first.context, "stg-0000000000000000", 0, FakePhotoSource(bytes(1)))
                is GeneratorCreateBridge.SlotResult.Stale,
        )
        val forged = first.context.copy(generationId = "gen-evil")
        assertTrue(
            bridge.bindSlot(forged, first.sessionId, 0, FakePhotoSource(bytes(1)))
                is GeneratorCreateBridge.SlotResult.Stale,
        )
        val wrongEpoch = first.context.copy(sessionEpoch = 99L)
        assertTrue(
            bridge.bindSlot(wrongEpoch, first.sessionId, 0, FakePhotoSource(bytes(1)))
                is GeneratorCreateBridge.SlotResult.Stale,
        )
    }

    @Test
    fun duplicateCallbackDoesNotReopenSource() = runTest {
        setup()
        val first = begun()
        val source = FakePhotoSource(bytes(1))
        assertTrue(bridge.bindSlot(first.context, first.sessionId, 0, source)
            is GeneratorCreateBridge.SlotResult.Bound)
        val again = FakePhotoSource(bytes(1))
        val duplicate = bridge.bindSlot(first.context, first.sessionId, 0, again)
        assertTrue(duplicate is GeneratorCreateBridge.SlotResult.Duplicate)
        assertEquals(0, again.opens.get())
    }

    @Test
    fun cancelLogoutCleanup() = runTest {
        setup()
        val first = begun()
        assertTrue(bindAll(first)[0] is GeneratorCreateBridge.SlotResult.Bound)
        bridge.cancel(first.context, first.sessionId)
        assertTrue(store.data.isEmpty())
        val stale = bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
        assertTrue(stale is GeneratorCreateBridge.SlotResult.Stale)
        val second = begun(owner = OWNER_B, epoch = 9L)
        assertTrue(bindAll(second)[0] is GeneratorCreateBridge.SlotResult.Bound)
        bridge.onLogout(UserId.parseRest(OWNER_B))
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun failureRollbackCleansSession() = runTest {
        setup()
        val first = begun()
        val firstSlot = bridge.bindSlot(first.context, first.sessionId, 0, FakePhotoSource(bytes(1)))
        assertTrue(firstSlot is GeneratorCreateBridge.SlotResult.Bound)
        val bad = bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(byteArrayOf(9)))
        assertTrue(bad is GeneratorCreateBridge.SlotResult.Failed)
        assertTrue(store.data.isEmpty())
        val stale = bridge.bindSlot(first.context, first.sessionId, 2, FakePhotoSource(bytes(3)))
        assertTrue(stale is GeneratorCreateBridge.SlotResult.Stale)
        assertTrue(
            bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Rejected,
        )
    }

    @Test
    fun frozenHandoffMatchesDirectInput() = runTest {
        setup()
        val first = begun()
        val results = bindAll(first)
        assertTrue(results.all { it is GeneratorCreateBridge.SlotResult.Bound })
        val frozen = bridge.freeze(first.context, first.sessionId)
        assertTrue(frozen is GeneratorCreateBridge.FreezeResult.Frozen)
        val handoff = (frozen as GeneratorCreateBridge.FreezeResult.Frozen).handoff
        assertEquals(input(), handoff.input)
        assertEquals(GeneratorExpression.canonicalHash(input()), handoff.inputHash)
        assertEquals(first.context, handoff.context)
    }

    @Test
    fun noSourceUriSenderPlaintextLeakage() = runTest {
        setup()
        val first = begun()
        assertTrue(bindAll(first).all { it is GeneratorCreateBridge.SlotResult.Bound })
        val frozen = bridge.freeze(first.context, first.sessionId)
        assertTrue(frozen is GeneratorCreateBridge.FreezeResult.Frozen)
        val handoff = (frozen as GeneratorCreateBridge.FreezeResult.Frozen).handoff
        val raw = String(GeneratorExpression.canonicalBytes(handoff.input), Charsets.UTF_8)
        assertTrue(!raw.contains("content://media/fake/1"))
        assertTrue(!raw.contains("@mykola"))
        assertTrue(!raw.contains("sender-9"))
    }

    @Test
    fun revisionMonotonicSeparateFromGeneration() = runTest {
        setup()
        val first = begun(generation = "gen-100")
        val second = begun(generation = "gen-101")
        val third = begun(generation = "gen-102")
        val revisions = listOf(first.context.contentRevision, second.context.contentRevision, third.context.contentRevision)
        assertEquals(listOf(1L, 2L, 3L), revisions)
        assertTrue(revisions.none { it == 100L || it == 101L || it == 102L })
    }

    @Test
    fun frozenHandoffOneShotDuplicateConsumeRejected() = runTest {
        setup()
        val first = begun()
        assertTrue(bindAll(first).all { it is GeneratorCreateBridge.SlotResult.Bound })
        val frozen = bridge.freeze(first.context, first.sessionId)
        assertTrue(frozen is GeneratorCreateBridge.FreezeResult.Frozen)
        val duplicate = bridge.freeze(first.context, first.sessionId)
        assertTrue(duplicate is GeneratorCreateBridge.FreezeResult.Rejected)
        val lateBind = bridge.bindSlot(first.context, first.sessionId, 0, FakePhotoSource(bytes(1)))
        assertTrue(
            lateBind is GeneratorCreateBridge.SlotResult.Stale ||
                lateBind is GeneratorCreateBridge.SlotResult.Duplicate,
        )
    }

    @Test
    fun logoutDropsBridgeRecords() = runTest {
        setup()
        val first = begun()
        assertTrue(bindAll(first)[0] is GeneratorCreateBridge.SlotResult.Bound)
        bridge.onLogout(UserId.parseRest(OWNER_A))
        assertTrue(store.data.isEmpty())
        val stale = bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
        assertTrue(stale is GeneratorCreateBridge.SlotResult.Stale)
        assertTrue(
            bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Rejected,
        )
    }

    @Test
    fun concurrentDuplicateSameOrdinalOpensOnceAndSurvives() = runTest {
        val gate = CompletableDeferred<Unit>()
        setup(GateNormalizer(gate))
        val first = begun()
        val firstSource = FakePhotoSource(bytes(1))
        val dupSource = FakePhotoSource(bytes(1))
        val firstBind = async { bridge.bindSlot(first.context, first.sessionId, 0, firstSource) }
        testScheduler.runCurrent()
        val dupBind = async { bridge.bindSlot(first.context, first.sessionId, 0, dupSource) }
        testScheduler.runCurrent()
        assertTrue(dupBind.await() is GeneratorCreateBridge.SlotResult.Duplicate)
        assertEquals(0, dupSource.opens.get())
        gate.complete(Unit)
        assertTrue(firstBind.await() is GeneratorCreateBridge.SlotResult.Bound)
        assertEquals(1, firstSource.opens.get())
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 2, FakePhotoSource(bytes(3)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        assertTrue(bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Frozen)
    }

    @Test
    fun outOfOrderBindOpensNoSource() = runTest {
        setup()
        val first = begun()
        val source = FakePhotoSource(bytes(2))
        val result = bridge.bindSlot(first.context, first.sessionId, 1, source)
        assertTrue(result is GeneratorCreateBridge.SlotResult.Failed)
        assertEquals(0, source.opens.get())
        assertTrue(store.data.isEmpty())
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 0, FakePhotoSource(bytes(1)))
                is GeneratorCreateBridge.SlotResult.Stale,
        )
        assertTrue(
            bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Rejected,
        )
    }

    @Test
    fun staleInvalidatorsPreserveLiveRecordAndData() = runTest {
        setup()
        val first = begun()
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 0, FakePhotoSource(bytes(1)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        assertEquals(1, store.data.size)
        bridge.onPhotoEdit(first.context.copy(generationId = "gen-evil"), first.sessionId)
        bridge.onNoteEdit(first.context.copy(sessionEpoch = 99L), first.sessionId)
        bridge.onOwnerOrEpochChange(first.context.copy(contentRevision = 999L), first.sessionId)
        bridge.cancel(first.context.copy(owner = UserId.parseRest(OWNER_B)), first.sessionId)
        bridge.onPhotoEdit(first.context, "stg-0000000000000000")
        assertEquals(1, store.data.size)
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 2, FakePhotoSource(bytes(3)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        assertTrue(bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Frozen)
    }

    @Test
    fun cancellationDuringReadRevokesAndRethrows() = runTest {
        setup()
        val first = begun()
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 0, FakePhotoSource(bytes(1)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        try {
            bridge.bindSlot(first.context, first.sessionId, 1, CancellingSource())
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // Expected: same cleanup guarantee as binder cancellation.
        }
        assertTrue(store.data.isEmpty())
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 2, FakePhotoSource(bytes(3)))
                is GeneratorCreateBridge.SlotResult.Stale,
        )
        assertTrue(
            bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Rejected,
        )
    }

    @Test
    fun invalidationDuringInflightBindLeavesNoOrphans() = runTest {
        val gate = CompletableDeferred<Unit>()
        setup(GateNormalizer(gate))
        val first = begun()
        val pending = async {
            bridge.bindSlot(first.context, first.sessionId, 0, FakePhotoSource(bytes(1)))
        }
        testScheduler.runCurrent()
        bridge.onPhotoEdit(first.context, first.sessionId)
        gate.complete(Unit)
        assertTrue(pending.await() is GeneratorCreateBridge.SlotResult.Stale)
        assertTrue(store.data.isEmpty())
        val second = begun()
        assertTrue(bindAll(second).all { it is GeneratorCreateBridge.SlotResult.Bound })
    }

    @Test
    fun freezeDuringInflightBindRejectsThenSucceeds() = runTest {
        val gate = CompletableDeferred<Unit>()
        setup(GateNormalizer(gate))
        val first = begun()
        val pending = async {
            bridge.bindSlot(first.context, first.sessionId, 0, FakePhotoSource(bytes(1)))
        }
        testScheduler.runCurrent()
        assertTrue(bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Rejected)
        gate.complete(Unit)
        assertTrue(pending.await() is GeneratorCreateBridge.SlotResult.Bound)
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 1, FakePhotoSource(bytes(2)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        assertTrue(
            bridge.bindSlot(first.context, first.sessionId, 2, FakePhotoSource(bytes(3)))
                is GeneratorCreateBridge.SlotResult.Bound,
        )
        assertTrue(bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Frozen)
        assertTrue(bridge.freeze(first.context, first.sessionId) is GeneratorCreateBridge.FreezeResult.Rejected)
    }

    @Test
    fun bindRefusalTokenNamesEachClassWithoutSecrets() {
        // The diagnostic seam must distinguish every refusal shape with a
        // fixed token carrying no private content (audited: all reason
        // strings in the seam are static literals).
        val bound = GeneratorCreateBridge.SlotResult.Bound(
            GeneratorStaging.Lease("s#0", "s", "c", 0, "h", 1L),
            GeneratorCreateBridge.NormalizedPhoto(ByteArray(2), 8, 8),
        )
        assertEquals("bound", dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(bound))
        assertEquals(
            "stale-unknown-session",
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Stale("unknown session"),
            ),
        )
        assertEquals(
            "duplicate",
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Duplicate(0),
            ),
        )
        assertEquals(
            "failed-hash-mismatch",
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Failed("hash mismatch"),
            ),
        )
        assertEquals(
            "failed-store-unavailable",
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Failed("store unavailable"),
            ),
        )
        assertEquals("thrown", dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(null))
        for (token in listOf(
            "bound",
            "stale-unknown-session",
            "duplicate",
            "failed-hash-mismatch",
            "failed-store-unavailable",
            "thrown",
        )) {
            assertTrue(
                "token must be a fixed allowlisted word",
                token.matches(Regex("[a-z0-9-]+")),
            )
        }
    }

    @Test
    fun bindRefusalTokenMapsEveryKnownLiteralDistinctly() {
        // Competing incident causes must produce distinguishable tokens.
        // "unknown session" is intentionally listed twice: the Stale class
        // (bridge records map) and the Failed class (staging passthrough)
        // are different layers with different meanings.
        val staleCases = mapOf(
            "unknown session" to "stale-unknown-session",
            "stale context" to "stale-context",
            "already consumed" to "stale-consumed",
        )
        val failedCases = mapOf(
            "bad content id" to "failed-bad-content-id",
            "bad ordinal" to "failed-bad-ordinal",
            "bad content hash" to "failed-bad-content-hash",
            "bad upright dims" to "failed-bad-upright-dims",
            "bind failed" to "failed-bind-failed",
            "bytes over bound" to "failed-bytes-over-bound",
            "decode failed" to "failed-decode-failed",
            "derived over budget" to "failed-derived-over-budget",
            "dimension mismatch" to "failed-dimension-mismatch",
            "empty bytes" to "failed-empty-bytes",
            "hash mismatch" to "failed-hash-mismatch",
            "hash mismatch at stage" to "failed-hash-mismatch-at-stage",
            "invalid input" to "failed-invalid-input",
            "non-positive dims" to "failed-non-positive-dims",
            "normalization failed" to "failed-normalization-failed",
            "out-of-order stage" to "failed-out-of-order-stage",
            "unknown slot" to "failed-unknown-slot",
            "unknown session" to "failed-session-missing",
            "session bytes over bound" to "failed-session-bytes-over-bound",
            "source failed" to "failed-source-failed",
            "source over 32MiB bound" to "failed-source-over-32mib-bound",
            "source over bound" to "failed-source-over-bound",
            "source unreadable" to "failed-source-unreadable",
            "store unavailable" to "failed-store-unavailable",
            "session not active" to "failed-session-not-active",
            "non-canonical owner" to "failed-non-canonical-owner",
            "owner mismatch" to "failed-owner-mismatch",
            "stale epoch" to "failed-stale-epoch",
            "stale revision" to "failed-stale-revision",
            "expired" to "failed-expired",
            "too many leases" to "failed-too-many-leases",
        )
        assertEquals("audited seam literal count", 3 + 31, staleCases.size + failedCases.size)
        val seen = mutableSetOf<String>()
        for ((reason, expected) in staleCases) {
            val token = dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Stale(reason),
            )
            assertEquals("literal '$reason'", expected, token)
            assertTrue("token must be unique: $token", seen.add(token))
        }
        for ((reason, expected) in failedCases) {
            val token = dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Failed(reason),
            )
            assertEquals("literal '$reason'", expected, token)
            assertTrue("token must be unique: $token", seen.add(token))
        }
        // The incident's competing causes stay distinguishable.
        val competing = setOf(
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Failed("hash mismatch"),
            ),
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Failed("dimension mismatch"),
            ),
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Failed("normalization failed"),
            ),
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                GeneratorCreateBridge.SlotResult.Failed("store unavailable"),
            ),
        )
        assertEquals(4, competing.size)
    }

    @Test
    fun bindRefusalTokenNeverEchoesAdversarialInput() {
        // Malicious reason strings carrying tokens, URIs, hashes and ids
        // must map to fixed fallbacks — raw input is never emitted.
        val hostile = listOf(
            "hash mismatch 8a49e364-2a65-4f76-bb66-1f684d697981",
            "failed-hash-mismatch",
            "content://media/external/images/media/42",
            "../../../etc/passwd",
            "thrown-security",
            "",
        )
        for (reason in hostile) {
            assertEquals(
                "hostile Failed must map fixed: '$reason'",
                "failed-other",
                dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                    GeneratorCreateBridge.SlotResult.Failed(reason),
                ),
            )
            assertEquals(
                "hostile Stale must map fixed: '$reason'",
                "stale-other",
                dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                    GeneratorCreateBridge.SlotResult.Stale(reason),
                ),
            )
        }
        // Exception categories never carry messages/stacks.
        assertEquals(
            "thrown-security",
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                null,
                SecurityException("secret-token abc123"),
            ),
        )
        assertEquals(
            "thrown-io",
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                null,
                java.io.IOException("content://private/blob"),
            ),
        )
        assertEquals(
            "thrown-other",
            dev.hryshyn.remanence.ui.create.bindSlotRefusalToken(
                null,
                IllegalStateException("hash 8a49e364"),
            ),
        )
    }
}
