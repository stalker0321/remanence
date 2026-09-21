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

    private class FakeStore : GeneratorStaging.BlobStore {
        val data = mutableMapOf<String, ByteArray>()
        val deleted = mutableListOf<String>()
        override fun put(key: String, bytes: ByteArray) {
            data[key] = bytes.copyOf()
        }
        override fun get(key: String): ByteArray? = data[key]?.copyOf()
        override fun delete(key: String): Boolean {
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
        b1: ByteArray = bytes(1),
        b2: ByteArray = bytes(2),
        b3: ByteArray = bytes(3),
        note: String? = "n",
    ) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = listOf(photo("p1", 0, b1), photo("p2", 1, b2), photo("p3", 2, b3)),
        note = note,
        music = null,
    )

    private fun openOk(
        input: GeneratorExpression.GeneratorInput = input(),
        sender: GeneratorStaging.SenderSnapshot? = null,
    ): GeneratorStaging.StagingSession {
        val opened = manager.openFor(input, sender)
        assertIs<GeneratorStaging.OpenResult.Opened>(opened)
        return opened.session
    }

    private fun stageAll(session: GeneratorStaging.StagingSession, vararg blobs: ByteArray) {
        val list = if (blobs.isEmpty()) listOf(bytes(1), bytes(2), bytes(3)) else blobs.toList()
        list.forEachIndexed { index, bytes ->
            val staged = manager.stagePhoto(
                session.sessionId, session.ownerId, session.epoch,
                session.contentRevision, index, 100, 200, bytes,
            )
            assertIs<GeneratorStaging.StageResult.Staged>(staged)
            assertEquals(index, staged.lease.ordinal)
        }
    }

    @Test
    fun lifecycleOpenStageUseClose() {
        setup()
        val session = openOk()
        stageAll(session)
        for (ordinal in 0..2) {
            val used = manager.use(session.sessionId, session.ownerId, session.epoch, session.contentRevision, ordinal)
            assertIs<GeneratorStaging.LeaseUse.Bytes>(used)
        }
        val rebuilt = manager.toInput(session.sessionId, session.ownerId, session.epoch, session.contentRevision)
        assertEquals(input(), rebuilt)
        assertEquals(
            GeneratorExpression.canonicalHash(input()),
            GeneratorExpression.canonicalHash(rebuilt!!),
        )
        assertEquals(3, manager.close(session.sessionId, session.ownerId, session.epoch, session.contentRevision))
        val after = manager.use(session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(after)
    }

    @Test
    fun doubleReleaseIsIdempotent() {
        setup()
        val session = openOk()
        stageAll(session)
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
    }

    @Test
    fun ownerMismatchRejectedEverywhere() {
        setup()
        val session = openOk()
        val bad = manager.stagePhoto(
            session.sessionId, "owner-2", session.epoch, session.contentRevision, 0, 100, 200, bytes(1),
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(bad)
        stageAll(session)
        val used = manager.use(session.sessionId, "owner-2", session.epoch, session.contentRevision, 0)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(used)
        assertNull(manager.toInput(session.sessionId, "owner-2", session.epoch, session.contentRevision))
        assertEquals(
            0,
            manager.revoke(session.sessionId, "owner-2", session.epoch, session.contentRevision),
        )
    }

    @Test
    fun staleEpochAndRevisionRejected() {
        setup()
        val session = openOk()
        stageAll(session)
        val staleEpoch = manager.use(session.sessionId, session.ownerId, 8L, session.contentRevision, 0)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(staleEpoch)
        val staleRevision = manager.use(session.sessionId, session.ownerId, session.epoch, 1L, 0)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(staleRevision)
        assertNull(manager.toInput(session.sessionId, session.ownerId, 8L, session.contentRevision))
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
        assertEquals("analysis-$hash", analyze(hash, "owner-1", 7L, 0L))
        assertEquals("analysis-$hash", analyze(hash, "owner-1", 7L, 0L))
        assertEquals(1, computes)
        analyze(hash, "owner-2", 7L, 0L)
        analyze(hash, "owner-1", 8L, 0L)
        analyze(hash, "owner-1", 7L, 1L)
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
        val after = manager.use(session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(after)
    }

    @Test
    fun sweepExpiresIdleSessionsOnly() {
        setup()
        val old = openOk()
        stageAll(old)
        now += GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS + 1
        val freshInput = input().copy(ownerId = "owner-2")
        val freshOpened = manager.openFor(freshInput, null)
        assertIs<GeneratorStaging.OpenResult.Opened>(freshOpened)
        assertEquals(1, manager.sweep(now, GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS))
        assertTrue(store.keys().none { it.startsWith(old.sessionId) })
        assertEquals(0, manager.sweep(now, GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS))
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
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 101, 200, bytes(1),
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
        stageAll(session)
        store.tamper("${session.sessionId}/1", bytes(9))
        val used = manager.use(session.sessionId, session.ownerId, session.epoch, session.contentRevision, 1)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(used)
        val clean = manager.use(session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0)
        assertIs<GeneratorStaging.LeaseUse.Bytes>(clean)
    }

    @Test
    fun boundsEnforced() {
        setup()
        val session = openOk()
        val huge = ByteArray(ProtocolV1Limits.NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES.toInt() + 1)
        val over = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch, session.contentRevision, 0, 100, 200, huge,
        )
        assertIs<GeneratorStaging.StageResult.Rejected>(over)
        var last: GeneratorStaging.OpenResult = manager.openFor(input(), null)
        for (index in 2..GeneratorStaging.MAX_SESSIONS + 2) {
            last = manager.openFor(input().copy(ownerId = "owner-$index"), null)
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
        assertEquals(3, manager.revokeOwner("owner-1"))
        assertTrue(store.keys().isEmpty())
        val after = manager.use(first.sessionId, first.ownerId, first.epoch, first.contentRevision, 0)
        assertIs<GeneratorStaging.LeaseUse.Rejected>(after)
    }
}
