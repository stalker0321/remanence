package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Restart-collision reproduction: production [GeneratorStaging.Manager] +
 * production filesystem store across a simulated process death.
 *
 * Session ids are deterministic
 * (`stg-` + sha256(owner|epoch|revision|inputHash), no randomness), so a
 * fresh process re-publishing identical content reopens the SAME session
 * id. Staged files persist (swept only at logout/revocation); the
 * in-memory record does not survive. Re-staging slot 0 must not fail
 * with `store unavailable` (target-exists refusal) for byte-identical
 * content, and must never overwrite differing bytes. No mocks: real
 * tmpdir filesystem, real manager, real hashes.
 */
class GeneratorStagingRestartTest {

    private val ownerUuid = "0198f0a0-0000-7000-8000-00000000a001"
    private val owner: UserId = UserId.parseRest(ownerUuid)

    private lateinit var filesDir: File
    private lateinit var store: GeneratorStagingFileStore

    private var now = 1_000_000L

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "remanence-genstaging-restart-${System.nanoTime()}")
        check(filesDir.mkdirs()) { "could not create sandbox $filesDir" }
        val roots = AccountScopedFileRoots(filesDir)
        store = GeneratorStagingFileStore(roots, AccountStorageRetention(roots), owner)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(seed: Byte) = ByteArray(256) { (seed + it).toByte() }

    private fun photo(id: String, ordinal: Int, blob: ByteArray) =
        GeneratorExpression.PhotoRef(id, ordinal, 100, 200, sha(blob))

    private fun input(b1: ByteArray, b2: ByteArray, b3: ByteArray) =
        GeneratorExpression.GeneratorInput(
            ownerId = ownerUuid,
            epoch = 7L,
            photos = listOf(photo("p1", 0, b1), photo("p2", 1, b2), photo("p3", 2, b3)),
            note = "n",
            music = null,
        )

    private fun openOk(
        manager: GeneratorStaging.Manager,
        input: GeneratorExpression.GeneratorInput,
    ): GeneratorStaging.StagingSession {
        val opened = manager.openFor(input, null, 0L, GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS)
        assertTrue("expected session open, got $opened", opened is GeneratorStaging.OpenResult.Opened)
        return (opened as GeneratorStaging.OpenResult.Opened).session
    }

    private fun stageOk(
        manager: GeneratorStaging.Manager,
        session: GeneratorStaging.StagingSession,
        ordinal: Int,
        blob: ByteArray,
    ): GeneratorStaging.Lease {
        val staged = manager.stagePhoto(
            session.sessionId, session.ownerId, session.epoch,
            session.contentRevision, ordinal, 100, 200, blob,
        )
        assertTrue(
            "expected staged slot $ordinal, got $staged",
            staged is GeneratorStaging.StageResult.Staged,
        )
        return (staged as GeneratorStaging.StageResult.Staged).lease
    }

    @Test
    fun restartRejoinsIdenticalStagedSlotIdempotently() {
        val b1 = bytes(1)
        val b2 = bytes(2)
        val b3 = bytes(3)
        val input = input(b1, b2, b3)

        // First lifetime stages slot 0, then "dies": no revoke, no close,
        // no file deletion — only the in-memory manager is dropped.
        var manager: GeneratorStaging.Manager? =
            GeneratorStaging.Manager(store) { now }
        val first = openOk(manager!!, input)
        stageOk(manager!!, first, 0, b1)
        manager = null

        // Fresh process, same owner/epoch/revision/input.
        val restarted = GeneratorStaging.Manager(store) { now }
        val second = openOk(restarted, input)
        assertEquals(
            "deterministic session id must rejoin the same session",
            first.sessionId,
            second.sessionId,
        )

        // Identical bytes for an unowned slot adopt idempotently —
        // never a store refusal, never an overwrite.
        val lease = stageOk(restarted, second, 0, b1)
        assertEquals(0, lease.ordinal)
        assertEquals(first.sessionId, lease.sessionId)
        val stored = store.get("${first.sessionId}/0")
        assertTrue(
            "original staged file must be preserved byte-identical",
            stored != null && stored.contentEquals(b1),
        )

        // The session then proceeds normally past the adopted slot.
        stageOk(restarted, second, 1, b2)
        stageOk(restarted, second, 2, b3)
        val used = restarted.use(lease, second.ownerId, second.epoch, second.contentRevision)
        assertTrue(
            "adopted lease must be usable, got $used",
            used is GeneratorStaging.LeaseUse.Bytes &&
                (used as GeneratorStaging.LeaseUse.Bytes).bytes.contentEquals(b1),
        )
    }

    @Test
    fun restartNeverOverwritesDifferingBytes() {
        val b1 = bytes(1)
        val input = input(b1, bytes(2), bytes(3))

        var manager: GeneratorStaging.Manager? =
            GeneratorStaging.Manager(store) { now }
        val first = openOk(manager!!, input)
        stageOk(manager!!, first, 0, b1)
        manager = null

        // A DIFFERENT photo under a colliding slot must still refuse —
        // adoption is hash-gated, never blind.
        val other = bytes(9)
        val restarted = GeneratorStaging.Manager(store) { now }
        val second = openOk(restarted, input)
        val attempt = restarted.stagePhoto(
            second.sessionId, second.ownerId, second.epoch,
            second.contentRevision, 0, 100, 200, other,
        )
        assertTrue(
            "mismatched bytes must refuse, got $attempt",
            attempt is GeneratorStaging.StageResult.Rejected,
        )
        val stored = store.get("${first.sessionId}/0")
        assertTrue(
            "original staged file must survive the refused attempt",
            stored != null && stored.contentEquals(b1),
        )
    }
}
