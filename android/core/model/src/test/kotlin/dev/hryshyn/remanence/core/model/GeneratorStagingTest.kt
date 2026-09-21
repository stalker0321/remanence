package dev.hryshyn.remanence.core.model

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G3 staging tests: in-memory fake store + fixed clock only.
 * No Android, no filesystem, no renderer, no publish path.
 */
class GeneratorStagingTest {

    companion object {
        const val OWNER_A = "0198f0a0-0000-7000-8000-00000000a001"
        const val OWNER_B = "0198f0a0-0000-7000-8000-00000000b001"
    }

    private class FakeStore(
        var throwOnPut: Boolean = false,
        var throwOnGet: Boolean = false,
        var throwOnDeleteKey: String? = null,
    ) : GeneratorStaging.BlobStore {
        val data = mutableMapOf<String, ByteArray>()
        val deleted = mutableListOf<String>()
        override fun put(key: String, bytes: ByteArray) {
            if (throwOnPut) throw IllegalStateException("store down")
            data[key] = bytes.copyOf()
        }
        override fun get(key: String): ByteArray? {
            if (throwOnGet) throw IllegalStateException("store down")
            return data[key]?.copyOf()
        }
        override fun delete(key: String): Boolean {
            if (key == throwOnDeleteKey) throw IllegalStateException("delete failed")
            deleted += key
            return data.remove(key) != null
        }
        override fun keys(): Set<String> = data.keys.toSet()
        fun tamper(key: String, bytes: ByteArray) {
            data[key] = bytes.copyOf()
        }
    }

    private var now = 1_000L
    private lateinit var store: FakeStore
    private lateinit var manager: GeneratorStaging.Manager

    private fun setup() {
        store = FakeStore()
        manager = GeneratorStaging.Manager(store, nowMillis = { now })
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(seed: Byte) = ByteArray(64) { (seed + it).toByte() }

    private fun photo(id: String, ordinal: Int, bytes: ByteArray, w: Int = 100, h: Int = 200) =
        GeneratorExpression.PhotoRef(id, ordinal, w, h, sha(bytes))

    private fun input(
        owner: String = OWNER_A,
        b1: ByteArray = bytes(1),
        b2: ByteArray = bytes(2),
        b3: ByteArray = bytes(3),
        note: String? = "n",
    ) = GeneratorExpression.GeneratorInput(
        ownerId = owner,
        epoch = 7L,
        photos = listOf(photo("p1", 0, b1), photo("p2", 1, b2), photo("p3", 2, b3)),
        note = note,
        music = null,
    )

    private fun openOk(
        input: GeneratorExpression.GeneratorInput = input(),
        sender: GeneratorStaging.SenderSnapshot? = null,
        revision: Long = 0L,
        ttl: Long = GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS,
    ): GeneratorStaging.StagingSession {
        val opened = manager.openFor(input, sender, revision, ttl)
        assertIs<GeneratorStaging.OpenResult.Opened>(opened)
        return opened.session
    }

    private fun stageAll(
        session: GeneratorStaging.StagingSession,
        blobs: List<ByteArray> = listOf(bytes(1), bytes(2), bytes(3)),
    ): List<GeneratorStaging.Lease> = blobs.mapIndexed { index, bytes ->
        val staged = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch,
            session.contentRevision, index, 100, 200, bytes,
        )
        assertIs<GeneratorStaging.StageResult.Staged>(staged)
        assertEquals(index, staged.lease.ordinal)
        staged.lease
    }

    private fun useOk(lease: GeneratorStaging.Lease, session: GeneratorStaging.StagingSession): ByteArray {
        val used = manager.use(lease, session.ownerId, session.epoch, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Bytes>(used)
        return used.bytes
    }

    @Test
    fun lifecycleOpenStageUseClose() {
        setup()
        val session = openOk()
        val leases = stageAll(session)
        for (lease in leases) useOk(lease, session)
        val rebuilt = manager.toInput(session.sessionId, session.ownerId, session.epoch, session.contentRevision)!!
        assertEquals(input(), rebuilt)
        assertEquals(
            GeneratorExpression.canonicalHash(input()),
            GeneratorExpression.canonicalHash(rebuilt),
        )
        assertEquals(3, manager.close(session.sessionId, session.ownerId, session.epoch, session.contentRevision))
        val after = manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(after)
    }

    @Test
    fun doubleReleaseIsIdempotent() {
        setup()
        val session = openOk()
        val leases = stageAll(session)
        assertEquals(3, manager.close(session.sessionId, session.ownerId, session.epoch, session.contentRevision))
        assertEquals(
            0,
            manager.close(session.sessionId, session.ownerId, session.epoch, session.contentRevision),
        )
        assertEquals(
            0,
            manager.revoke(session.sessionId, session.ownerId, session.epoch, session.contentRevision),
        )
        assertTrue(store.keys().none { it.startsWith(session.sessionId) })
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision),
        )
    }

    @Test
    fun ownerMismatchRejectedEverywhere() {
        setup()
        val session = openOk()
        val bad = manager.stagePhoto(
            session.sessionId, OWNER_B, session.epoch, session.contentRevision, 0, 100, 200, bytes(1),
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(bad)
        val leases = stageAll(session)
        val used = manager.use(leases[0], OWNER_B, session.epoch, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(used)
        assertNull(manager.toInput(session.sessionId, OWNER_B, session.epoch, session.contentRevision))
        assertEquals(
            0,
            manager.revoke(session.sessionId, OWNER_B, session.epoch, session.contentRevision),
        )
    }

    @Test
    fun staleEpochAndRevisionRejected() {
        setup()
        val session = openOk()
        val leases = stageAll(session)
        val staleEpoch = manager.use(leases[0], session.ownerId, 8L, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(staleEpoch)
        val staleRevision = manager.use(leases[0], session.ownerId, session.epoch, 1L)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(staleRevision)
        assertNull(manager.toInput(session.sessionId, session.ownerId, 8L, session.contentRevision))
    }

    @Test
    fun sessionTtlControlsExpiryAndRejectsInvalidTtl() {
        setup()
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(input(), null, 0L, 0L))
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(input(), null, 0L, -5L))
        val session = openOk(ttl = 100L)
        val leases = stageAll(session)
        now = 1_000L + 100L
        // Exact boundary: now == lastUse + ttl stays live, and use renews.
        assertIs<GeneratorStaging.LeaseUse.Bytes>(
            manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision),
        )
        now += 101L
        val expired = manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(expired)
        assertEquals(1, manager.sweep(now))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun forgedLeaseAndWrongContentCannotRead() {
        setup()
        val session = openOk()
        val leases = stageAll(session)
        val other = openOk(input(owner = OWNER_B))
        val otherLeases = stageAll(other)
        val forged = leases[0].copy(handleId = "forged")
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(forged, session.ownerId, session.epoch, session.contentRevision),
        )
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(otherLeases[0], session.ownerId, session.epoch, session.contentRevision),
        )
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(leases[0].copy(ordinal = 1), session.ownerId, session.epoch, session.contentRevision),
        )
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(leases[0].copy(contentId = "pX"), session.ownerId, session.epoch, session.contentRevision),
        )
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(
                leases[0].copy(contentHash = "f".repeat(64)),
                session.ownerId, session.epoch, session.contentRevision,
            ),
        )
        assertIs<GeneratorStaging.LeaseUse.Bytes>(
            manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision),
        )
    }

    @Test
    fun originalSourceBudgetAndHashBinding() {
        setup()
        val big = ByteArray(9 * 1024 * 1024) { it.toByte() }
        val tagged = input(b1 = big)
        val session = openOk(tagged)
        val staged = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 100, 200, big,
        )
        assertIs<GeneratorStaging.StageResult.Staged>(staged)
        val mismatch = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 1, 100, 200, bytes(9),
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(mismatch)
    }

    @Test
    fun storeFailureRollsBackInMemoryAndBytes() {
        setup()
        store.throwOnPut = true
        val session = openOk()
        val failed = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 100, 200, bytes(1),
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(failed)
        assertTrue(store.keys().isEmpty())
        store.throwOnPut = false
        // No phantom entry: ordinal 0 stages cleanly after the failure.
        val leases = stageAll(session)
        assertEquals(3, leases.size)
        assertEquals(3, store.keys().size)
    }

    @Test
    fun throwingStoreMapsToRejection() {
        setup()
        store.throwOnPut = true
        store.throwOnGet = true
        val session = openOk()
        assertIs<GeneratorStaging.StageResult.Rejected>(
            manager.stagePhoto(
                session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 100, 200, bytes(1),
            ),
        )
        store.throwOnPut = false
        val leases = stageAll(session)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision),
        )
    }

    @Test
    fun deleteBestEffortAcrossKeys() {
        setup()
        val session = openOk()
        stageAll(session)
        store.throwOnDeleteKey = "${session.sessionId}/1"
        assertEquals(2, manager.revoke(session.sessionId, session.ownerId, session.epoch, session.contentRevision))
        assertTrue(store.keys().none { it == "${session.sessionId}/0" || it == "${session.sessionId}/2" })
        store.throwOnDeleteKey = null
    }

    @Test
    fun terminalSessionsEvicted() {
        setup()
        val session = openOk()
        stageAll(session)
        manager.close(session.sessionId, session.ownerId, session.epoch, session.contentRevision)
        // Evicted: reopening the same input yields a fresh live record.
        val reopened = openOk()
        assertEquals(session.sessionId, reopened.sessionId)
        val leases = stageAll(reopened)
        assertEquals(3, leases.size)
    }

    @Test
    fun analysesBoundedAndEvicted() {
        setup()
        for (index in 0 until GeneratorStaging.MAX_ANALYSES + 10) {
            assertTrue(manager.rememberAnalysis("h$index", OWNER_A, 7L, 0L, "v$index"))
        }
        assertNull(manager.lookupAnalysis("h0", OWNER_A, 7L, 0L))
        assertEquals(
            "v${GeneratorStaging.MAX_ANALYSES + 9}",
            manager.lookupAnalysis("h${GeneratorStaging.MAX_ANALYSES + 9}", OWNER_A, 7L, 0L),
        )
        assertTrue(!manager.rememberAnalysis("big", OWNER_A, 7L, 0L, "x".repeat(4097)))
        assertNull(manager.lookupAnalysis("big", OWNER_A, 7L, 0L))
    }

    @Test
    fun revokeClearsAnalyses() {
        setup()
        val session = openOk()
        assertTrue(manager.rememberAnalysis("h1", OWNER_A, 7L, 0L, "v1"))
        manager.revoke(session.sessionId, session.ownerId, session.epoch, session.contentRevision)
        assertNull(manager.lookupAnalysis("h1", OWNER_A, 7L, 0L))
    }

    @Test
    fun rejoinConflictingSenderRejected() {
        setup()
        val senderA = GeneratorStaging.SenderSnapshot("sender-a", "A")
        val senderB = GeneratorStaging.SenderSnapshot("sender-b", "B")
        openOk(sender = senderA)
        val conflict = manager.openFor(input(), senderB)
        assertIs<GeneratorStaging.OpenResult.Rejected>(conflict)
        val same = manager.openFor(input(), senderA)
        assertIs<GeneratorStaging.OpenResult.Opened>(same)
    }

    @Test
    fun rejoinConflictingSenderRejectedReverse() {
        setup()
        val senderA = GeneratorStaging.SenderSnapshot("sender-a", "A")
        val senderB = GeneratorStaging.SenderSnapshot("sender-b", "B")
        openOk(sender = senderB)
        val conflict = manager.openFor(input(), senderA)
        assertIs<GeneratorStaging.OpenResult.Rejected>(conflict)
    }

    @Test
    fun nonCanonicalOwnerAndOversizedContentIdRejected() {
        setup()
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(input(owner = "owner-1"), null))
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(input(owner = "NOT-A-UUID"), null))
        val longId = "p".repeat(129)
        val bad = input().copy(
            photos = listOf(
                photo(longId, 0, bytes(1)),
                photo("p2", 1, bytes(2)),
                photo("p3", 2, bytes(3)),
            ),
        )
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(bad, null))
        openOk()
    }

    @Test
    fun senderFieldsAreBoundedAndRedacted() {
        setup()
        val blankLabel = GeneratorStaging.SenderSnapshot("sender-1", "  ")
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(input(), blankLabel))
        val hugeLabel = GeneratorStaging.SenderSnapshot("sender-1", "x".repeat(257))
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(input(), hugeLabel))
        val sender = GeneratorStaging.SenderSnapshot("sender-9", "Mykola")
        val text = sender.toString()
        assertTrue(text.contains("(<redacted>)"))
        assertTrue(!text.contains("sender-9"))
        assertTrue(!text.contains("Mykola"))
        openOk(sender = sender)
    }

    @Test
    fun hashKeyedReuseWithinPolicyOnly() {
        setup()
        var computes = 0
        fun analyze(hash: String, owner: String, epoch: Long, revision: Long): String {
            manager.lookupAnalysis(hash, owner, epoch, revision)?.let { return it }
            computes++
            val value = "analysis-$hash"
            manager.rememberAnalysis(hash, owner, epoch, revision, value)
            return value
        }
        val hash = sha(bytes(1))
        assertEquals("analysis-$hash", analyze(hash, OWNER_A, 7L, 0L))
        assertEquals("analysis-$hash", analyze(hash, OWNER_A, 7L, 0L))
        assertEquals(1, computes)
        analyze(hash, OWNER_B, 7L, 0L)
        analyze(hash, OWNER_A, 8L, 0L)
        analyze(hash, OWNER_A, 7L, 1L)
        assertEquals(4, computes)
    }

    @Test
    fun revokeDeletesStagedBytesAndCancels() {
        setup()
        val session = openOk()
        stageAll(session)
        assertEquals(3, store.keys().size)
        assertEquals(3, manager.revoke(session.sessionId, session.ownerId, session.epoch, session.contentRevision))
        assertTrue(store.keys().isEmpty())
        assertEquals(3, store.deleted.size)
        val leases = listOf(
            GeneratorStaging.Lease(
                "${session.sessionId}#0", session.sessionId, "p1", 0, sha(bytes(1)), 64L,
            ),
        )
        val after = manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(after)
    }

    @Test
    fun sweepExpiresIdleSessionsOnly() {
        setup()
        val old = openOk()
        stageAll(old)
        now += GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS + 1
        val freshInput = input(owner = OWNER_B)
        val freshOpened = manager.openFor(freshInput, null)
        assertIs<GeneratorStaging.OpenResult.Opened>(freshOpened)
        assertEquals(1, manager.sweep(now))
        assertTrue(store.keys().none { it.startsWith(old.sessionId) })
        assertEquals(0, manager.sweep(now))
    }

    @Test
    fun bridgeToG1StableAndEditSensitive() {
        setup()
        val session = openOk()
        stageAll(session)
        val rebuilt = manager.toInput(session.sessionId, session.ownerId, session.epoch, session.contentRevision)!!
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validate(rebuilt))
        val expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "stage-check",
            grammarVersion = 1,
            branchId = "stage-check",
            input = rebuilt,
            placements = rebuilt.photos.map { photo ->
                GeneratorExpression.Placement(photo.contentId, 0, 0, 120, 213, null, null)
            },
            noteTreatment = "verbatim",
            diagnostics = emptyList(),
            fontVersion = 1,
            paletteVersion = 1,
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(expression))
        val frozen = GeneratorExpression.freeze(expression, 1L, 7L)
        val edited = manager.toInput(session.sessionId, session.ownerId, session.epoch, session.contentRevision)!!
            .copy(note = "changed")
        assertNotEquals(frozen.expressionHash, GeneratorExpression.canonicalHash(edited))
        assertNotEquals(rebuilt, edited)
    }

    @Test
    fun exactIdAndOrderCoverage() {
        setup()
        val session = openOk()
        val early = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 1, 100, 200, bytes(2),
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(early)
        val wrongHash = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 100, 200, bytes(9),
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(wrongHash)
        val wrongDims = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 0, 200, bytes(1),
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(wrongDims)
        val dupInput = input().copy(
            photos = listOf(photo("p1", 0, bytes(1)), photo("p1", 1, bytes(2)), photo("p3", 2, bytes(3))),
        )
        assertIs<GeneratorStaging.OpenResult.Rejected>(manager.openFor(dupInput, null))
        stageAll(session)
        val rebuilt = manager.toInput(session.sessionId, session.ownerId, session.epoch, session.contentRevision)!!
        assertEquals(listOf("p1", "p2", "p3"), rebuilt.photos.map { it.contentId })
    }

    @Test
    fun toctouTamperRejectedAtUse() {
        setup()
        val session = openOk()
        val leases = stageAll(session)
        store.tamper("${session.sessionId}/1", bytes(9))
        val used = manager.use(leases[1], session.ownerId, session.epoch, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(used)
        val clean = manager.use(leases[0], session.ownerId, session.epoch, session.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Bytes>(clean)
    }

    @Test
    fun boundsEnforced() {
        setup()
        val session = openOk()
        val huge = ByteArray(GeneratorStaging.RAW_SOURCE_MAX_BYTES + 1)
        val over = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 100, 200, huge,
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(over)
        var last: GeneratorStaging.OpenResult = manager.openFor(input(), null)
        for (index in 2..GeneratorStaging.MAX_SESSIONS + 2) {
            last = manager.openFor(input(owner = "0198f0a0-0000-7000-8000-00000000${"%04d".format(index)}"), null)
        }
        assertIs<GeneratorStaging.OpenResult.Rejected>(last)
    }

    @Test
    fun senderIsolatedFromIdentity() {
        setup()
        val sender = GeneratorStaging.SenderSnapshot("sender-9", "Mykola")
        val session = openOk(sender = sender)
        stageAll(session)
        val rebuilt = manager.toInput(session.sessionId, session.ownerId, session.epoch, session.contentRevision)!!
        assertEquals(GeneratorExpression.canonicalHash(input()), GeneratorExpression.canonicalHash(rebuilt))
        assertEquals(
            sender,
            manager.senderOf(session.sessionId, session.ownerId, session.epoch, session.contentRevision),
        )
    }

    @Test
    fun idempotentOpenAndOwnerRevocation() {
        setup()
        val first = openOk()
        val second = openOk()
        assertEquals(first.sessionId, second.sessionId)
        stageAll(first)
        assertEquals(3, manager.revokeOwner(OWNER_A))
        assertTrue(store.keys().isEmpty())
        val leases = listOf(
            GeneratorStaging.Lease(
                "${first.sessionId}#0", first.sessionId, "p1", 0, sha(bytes(1)), 64L,
            ),
        )
        val after = manager.use(leases[0], first.ownerId, first.epoch, first.contentRevision)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(after)
    }

    @Test
    fun concurrentStageRevokeInvariant() {
        setup()
        val session = openOk()
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val revoker = Thread {
            try {
                repeat(20) {
                    manager.revoke(session.sessionId, session.ownerId, session.epoch, session.contentRevision)
                }
            } catch (e: Throwable) {
                errors += e
            }
        }
        val stagers = (0 until 4).map {
            Thread {
                try {
                    repeat(20) {
                        manager.stagePhoto(
                            session.sessionId, session.ownerId, session.epoch,
                            session.contentRevision, 0, 100, 200, bytes(1),
                        )
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        (stagers + revoker).forEach { it.start() }
        (stagers + revoker).forEach { it.join(10_000) }
        assertTrue(errors.isEmpty(), "no throwable may escape sealed taxonomy: $errors")
        // Deterministic end state: the single session is always evicted
        // (stages cannot recreate it), every written key was deleted by
        // some revoke, and post-revoke reads are rejected.
        assertTrue(store.keys().isEmpty(), store.keys().toString())
        val probe = GeneratorStaging.Lease(
            "${session.sessionId}#0", session.sessionId, "p1", 0, sha(bytes(0)), 64L,
        )
        assertIs<GeneratorStaging.LeaseUse.Rejected>(
            manager.use(probe, session.ownerId, session.epoch, session.contentRevision),
        )
    }
}
