package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Restart-collision wiring tests: production [GeneratorStaging.Manager] +
 * production [GeneratorStagingFileStore] across a simulated process death,
 * built through the production recovery helper
 * ([GeneratorStagingRecovery.managerForOwner]) — the exact seam the
 * application singleton calls inside its `computeIfAbsent` lambda before
 * the Manager is published.
 *
 * Session ids are deterministic
 * (`stg-` + sha256(owner|epoch|revision|inputHash), no randomness), so a
 * fresh process re-publishing identical content reopens the SAME session
 * id. Staged files persist (swept only at logout/revocation); the
 * per-Manager in-memory record does not survive. No mocks: real tmpdir
 * filesystem, real manager, real hashes.
 */
class GeneratorStagingRestartTest {

    private val ownerUuid = "0198f0a0-0000-7000-8000-00000000a001"
    private val owner: UserId = UserId.parseRest(ownerUuid)
    private val ownerBUuid = "0198f0a0-0000-7000-8000-00000000b001"
    private val ownerB: UserId = UserId.parseRest(ownerBUuid)

    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var retention: AccountStorageRetention

    private var now = 1_000_000L

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "remanence-genstaging-restart-${System.nanoTime()}")
        check(filesDir.mkdirs()) { "could not create sandbox $filesDir" }
        roots = AccountScopedFileRoots(filesDir)
        retention = AccountStorageRetention(roots)
    }

    @After
    fun tearDown() {
        restorePermissions(filesDir)
        filesDir.deleteRecursively()
    }

    private fun restorePermissions(dir: File) {
        // T4 revokes write permission to force sweep failure; restore so
        // teardown can delete the sandbox on every path.
        try {
            dir.walkTopDown().forEach { it.setWritable(true) }
        } catch (_: Exception) {
        }
    }

    /** Production construction path: the exact seam the singleton uses. */
    private fun recoveryManager(forOwner: UserId = owner): GeneratorStaging.Manager =
        GeneratorStagingRecovery.managerForOwner(roots, retention, forOwner) { now }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(seed: Byte) = ByteArray(256) { (seed + it).toByte() }

    private fun photo(id: String, ordinal: Int, blob: ByteArray) =
        GeneratorExpression.PhotoRef(id, ordinal, 100, 200, sha(blob))

    private fun input(b1: ByteArray, b2: ByteArray, b3: ByteArray, forOwner: String = ownerUuid) =
        GeneratorExpression.GeneratorInput(
            ownerId = forOwner,
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

    private fun namespace(forOwner: UserId = owner): File =
        File(roots.createStagingRoot(forOwner), GeneratorStagingFileStore.NAMESPACE_DIR)

    /**
     * Retained RED mechanism (no recovery): without the construction
     * sweep, a fresh Manager re-staging the same input meets the
     * previous lifetime's persisted ordinal file and the first `put`
     * surfaces `store unavailable` (CREATE_NEW no-overwrite refusal);
     * the original bytes are unchanged. This is the pre-fix behavior
     * the wiring below repairs.
     */
    @Test
    fun noRecoveryRestageRefusesAndPreservesBytes() {
        val b1 = bytes(1)
        val b2 = bytes(2)
        val b3 = bytes(3)
        val payload = input(b1, b2, b3)

        // First lifetime stages slot 0, then "dies": no revoke, no close,
        // no file deletion — only the in-memory manager is dropped.
        val firstLifetime =
            GeneratorStaging.Manager(GeneratorStagingFileStore(roots, retention, owner)) { now }
        val first = openOk(firstLifetime, payload)
        stageOk(firstLifetime, first, 0, b1)

        // Fresh Manager built WITHOUT the recovery sweep.
        val restarted = GeneratorStaging.Manager(GeneratorStagingFileStore(roots, retention, owner)) { now }
        val second = openOk(restarted, payload)
        assertEquals(first.sessionId, second.sessionId)
        val attempt = restarted.stagePhoto(
            second.sessionId, second.ownerId, second.epoch,
            second.contentRevision, 0, 100, 200, b1,
        )
        assertTrue(
            "expected no-overwrite refusal without recovery, got $attempt",
            attempt is GeneratorStaging.StageResult.Rejected && attempt.reason == "store unavailable",
        )
        val stored = GeneratorStagingFileStore(roots, retention, owner).get("${first.sessionId}/0")
        assertTrue(
            "original staged file must be preserved byte-identical",
            stored != null && stored.contentEquals(b1),
        )
    }

    /**
     * GREEN via the production recovery seam: same collision sequence,
     * but the fresh Manager is built through
     * [GeneratorStagingRecovery.managerForOwner], whose pre-publication
     * sweep removes the non-live orphan directory. The first `put`
     * succeeds and the new ordinal bytes are the new payload.
     */
    @Test
    fun recoveryConstructionRestagesSameInput() {
        val b1 = bytes(1)
        val b2 = bytes(2)
        val b3 = bytes(3)
        val payload = input(b1, b2, b3)

        val firstManager = recoveryManager()
        val first = openOk(firstManager, payload)
        stageOk(firstManager, first, 0, b1)

        // Fresh lifetime through the production seam (drop the reference
        // to simulate process death; files persist, memory does not).
        val restarted = recoveryManager()
        val second = openOk(restarted, payload)
        assertEquals(
            "deterministic session id must rejoin the same session",
            first.sessionId,
            second.sessionId,
        )
        val lease = stageOk(restarted, second, 0, b1)
        assertEquals(0, lease.ordinal)
        assertEquals(first.sessionId, lease.sessionId)
        val stored = GeneratorStagingFileStore(roots, retention, owner).get("${first.sessionId}/0")
        assertTrue(
            "restaged file must hold the new payload byte-identical",
            stored != null && stored.contentEquals(b1),
        )

        // The session then proceeds normally past the recovered slot.
        stageOk(restarted, second, 1, b2)
        stageOk(restarted, second, 2, b3)
        val used = restarted.use(lease, second.ownerId, second.epoch, second.contentRevision)
        assertTrue(
            "recovered lease must be usable, got $used",
            used is GeneratorStaging.LeaseUse.Bytes && used.bytes.contentEquals(b1),
        )
    }

    /**
     * T1 cached-request-twice: a per-owner cache around the production
     * seam (mirroring the singleton `computeIfAbsent`) sweeps the
     * pre-seeded orphan on the FIRST call only. The second call is a
     * cache hit — same instance, no sweep: an orphan marker placed
     * between the calls survives.
     */
    @Test
    fun cachedRequestTwiceSweepsOnlyOnce() {
        val cache = HashMap<String, GeneratorStaging.Manager>()
        fun cached(forOwner: UserId): GeneratorStaging.Manager =
            cache.computeIfAbsent(forOwner.toRestString()) { recoveryManager(forOwner) }

        // Pre-seed an orphan session directory before first publication.
        val orphanDir = File(namespace(), "stg-0123456789abcdef")
        check(orphanDir.mkdirs()) { "orphan seed failed" }
        check(File(orphanDir, "0").writeBytes(bytes(5)) == Unit) { "orphan bytes failed" }

        val first = cached(owner)
        assertTrue("first call must sweep the pre-seeded orphan", !orphanDir.exists())

        // Marker an orphan AFTER publication: the cache hit must not sweep.
        val lateOrphan = File(namespace(), "stg-fedcba9876543210")
        check(lateOrphan.mkdirs()) { "late orphan seed failed" }
        check(File(lateOrphan, "0").writeBytes(bytes(6)) == Unit) { "late orphan bytes failed" }

        val second = cached(owner)
        assertSame("second call must be a cache hit", first, second)
        assertTrue(
            "cache hit must not sweep live-namespace bytes",
            File(lateOrphan, "0").exists(),
        )
    }

    /**
     * T2 live lease untouched: after construction, `openFor` + `stagePhoto`
     * pin a live session; repeated cached requests keep its directory
     * and staged bytes intact, ACTIVE rejoin returns the same id without
     * deleting files, and an explicit live-set sweep keeps the directory.
     */
    @Test
    fun liveLeaseSurvivesCachedRequests() {
        val cache = HashMap<String, GeneratorStaging.Manager>()
        fun cached(forOwner: UserId): GeneratorStaging.Manager =
            cache.computeIfAbsent(forOwner.toRestString()) { recoveryManager(forOwner) }

        val b1 = bytes(1)
        val payload = input(b1, bytes(2), bytes(3))
        val manager = cached(owner)
        val session = openOk(manager, payload)
        stageOk(manager, session, 0, b1)

        repeat(2) { cached(owner) }

        val liveDir = File(namespace(), session.sessionId)
        assertTrue("live session directory must survive cached requests", liveDir.isDirectory)
        val stored = GeneratorStagingFileStore(roots, retention, owner).get("${session.sessionId}/0")
        assertTrue(
            "live staged bytes must survive cached requests",
            stored != null && stored.contentEquals(b1),
        )
        val rejoined = openOk(manager, payload)
        assertEquals("ACTIVE rejoin must return the same id", session.sessionId, rejoined.sessionId)
        assertTrue(
            "rejoin must not delete live files",
            File(liveDir, "0").exists(),
        )

        val probe = GeneratorStagingFileStore(roots, retention, owner)
        probe.sweepOwner(setOf(session.sessionId))
        assertTrue(
            "live-id sweep must keep the pinned directory",
            File(liveDir, "0").exists(),
        )
    }

    /**
     * T3 cross-owner preservation: recovery construction for owner A
     * removes only A's orphan `gen-staging` entries; owner B's
     * `gen-staging`, both owners' durable ciphertext, and
     * `temp/create/<uuid>` capsule dirs are unchanged.
     */
    @Test
    fun recoveryConstructionPreservesOtherNamespaces() {
        val orphanA = File(namespace(owner), "stg-aaaaaaaaaaaaaaaa")
        check(orphanA.mkdirs()) { "owner-A orphan seed failed" }
        check(File(orphanA, "0").writeBytes(bytes(7)) == Unit) { "owner-A bytes failed" }

        val dirB = File(namespace(ownerB), "stg-bbbbbbbbbbbbbbbb")
        check(dirB.mkdirs()) { "owner-B session seed failed" }
        val keepB = File(dirB, "0")
        check(keepB.writeBytes(bytes(8)) == Unit) { "owner-B bytes failed" }

        val durableA = File(roots.child(owner, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT), "keep.bin")
        check(durableA.parentFile.mkdirs()) { "durable-A dir failed" }
        check(durableA.writeBytes(bytes(9)) == Unit) { "durable-A bytes failed" }
        val durableB = File(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT), "keep.bin")
        check(durableB.parentFile.mkdirs()) { "durable-B dir failed" }
        check(durableB.writeBytes(bytes(10)) == Unit) { "durable-B bytes failed" }

        val uuidDir = File(roots.createStagingRoot(owner), "123e4567-e89b-12d3-a456-426614174000")
        check(uuidDir.mkdirs()) { "uuid capsule seed failed" }
        val uuidKeep = File(uuidDir, "capsule.bin")
        check(uuidKeep.writeBytes(bytes(11)) == Unit) { "uuid bytes failed" }

        recoveryManager(owner)

        assertTrue("owner-A orphan must be swept", !orphanA.exists())
        assertTrue("owner-B session bytes must survive", keepB.exists() && keepB.readBytes().contentEquals(bytes(8)))
        assertTrue("owner-A durable ciphertext must survive", durableA.exists())
        assertTrue("owner-B durable ciphertext must survive", durableB.exists())
        assertTrue("uuid capsule dir must survive", uuidKeep.exists())
    }

    /**
     * T4 cleanup-fail-no-overwrite: an orphan entry the sweep cannot
     * unlink remains (read-only permissions force best-effort failure),
     * construction never throws out of the seam, and the following
     * `put` still refuses overwrite — fail closed, no
     * overwrite/truncate/replace fallback.
     */
    @Test
    fun sweepFailureRemainsFailClosed() {
        val b1 = bytes(1)
        val payload = input(b1, bytes(2), bytes(3))

        val firstManager = recoveryManager()
        val first = openOk(firstManager, payload)
        stageOk(firstManager, first, 0, b1)

        val orphanDir = File(namespace(), first.sessionId)
        assertTrue("precondition: orphan session dir exists", File(orphanDir, "0").exists())
        // Revoke write permission so the sweep cannot unlink the entry.
        check(orphanDir.setWritable(false)) { "chmod failed" }
        check(File(orphanDir, "0").setWritable(false)) { "chmod file failed" }
        try {
            // Must not throw: sweep is best-effort per entry.
            val restarted = recoveryManager()
            assertTrue(
                "unremovable orphan must remain after best-effort sweep",
                File(orphanDir, "0").exists(),
            )
            val second = openOk(restarted, payload)
            val attempt = restarted.stagePhoto(
                second.sessionId, second.ownerId, second.epoch,
                second.contentRevision, 0, 100, 200, b1,
            )
            assertTrue(
                "surviving entry must still refuse overwrite, got $attempt",
                attempt is GeneratorStaging.StageResult.Rejected && attempt.reason == "store unavailable",
            )
        } finally {
            orphanDir.setWritable(true)
            File(orphanDir, "0").setWritable(true)
        }
        val stored = GeneratorStagingFileStore(roots, retention, owner).get("${first.sessionId}/0")
        assertTrue(
            "original staged file must survive the refused attempt",
            stored != null && stored.contentEquals(b1),
        )
    }

    /**
     * Hash-gated invariant (unchanged): staging bytes that do not match
     * the expected content hash are rejected before any store write,
     * and the original file survives.
     */
    @Test
    fun restartNeverOverwritesDifferingBytes() {
        val b1 = bytes(1)
        val payload = input(b1, bytes(2), bytes(3))

        val firstManager = recoveryManager()
        val first = openOk(firstManager, payload)
        stageOk(firstManager, first, 0, b1)

        val restarted = recoveryManager()
        val second = openOk(restarted, payload)
        // The sweep already removed the orphan, so the session proceeds
        // from a clean namespace: mismatched bytes fail the hash gate.
        val attempt = restarted.stagePhoto(
            second.sessionId, second.ownerId, second.epoch,
            second.contentRevision, 0, 100, 200, bytes(9),
        )
        assertTrue(
            "mismatched bytes must refuse, got $attempt",
            attempt is GeneratorStaging.StageResult.Rejected,
        )
        val stored = GeneratorStagingFileStore(roots, retention, owner).get("${second.sessionId}/0")
        assertTrue(
            "no foreign bytes may be written on refusal",
            stored == null || stored.contentEquals(b1),
        )
    }
}
