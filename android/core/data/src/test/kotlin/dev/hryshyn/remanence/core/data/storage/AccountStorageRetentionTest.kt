package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * M2-P04: retention cleanup boundary tests.
 *
 * The boundary must:
 *  - on normal logout, leave every durable root of the same account in
 *    place and remove only that account's temp root;
 *  - never touch a different account's material on either path;
 *  - on explicit local-account purge, remove the entire account root of
 *    only the targeted account and leave every other account untouched.
 */
class AccountStorageRetentionTest {

    private val ownerAUuid = "0198f0a0-0000-7000-8000-0000000000a1"
    private val ownerBUuid = "0198f0a0-0000-7000-8000-0000000000b1"
    private val ownerA: UserId = UserId.parseRest(ownerAUuid)
    private val ownerB: UserId = UserId.parseRest(ownerBUuid)

    private lateinit var filesDir: File
    private lateinit var retention: AccountStorageRetention
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-retention-${System.nanoTime()}",
        )
        check(filesDir.mkdirs()) { "could not create sandbox $filesDir" }
        roots = AccountScopedFileRoots(filesDir)
        retention = AccountStorageRetention(roots)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun seedOwnerMaterial(
        owner: UserId,
        builder: (File) -> Unit,
    ) {
        for (root in AccountScopedFileRoots.ChildRoot.values()) {
            val dir = roots.child(owner, root)
            check(dir.mkdirs()) { "could not create $dir" }
            builder(dir)
        }
    }

    private fun fileCount(directory: File): Int {
        if (!directory.exists()) return 0
        val listed = directory.listFiles() ?: return 0
        var count = 0
        for (entry in listed) {
            count++
            if (entry.isDirectory) count += fileCount(entry)
        }
        return count
    }

    private fun touch(directory: File, name: String): File {
        val file = File(directory, name)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    @Test
    fun logoutAKeepsAllADurableRootsAndAllBMaterialAndRemovesOnlyATemp() {
        // Seed A: a fingerprint file, an outbox blob, an incoming blob, a
        // retry-material file, and a temp file.
        seedOwnerMaterial(ownerA) { dir ->
            val name = when (dir.name) {
                "fingerprints" -> "fp-a.fpw"
                "outbox-ciphertext" -> "blob-a.bin"
                "incoming-ciphertext" -> "capsule-a.bin"
                "retry-material" -> "retry-a.bin"
                "temp" -> "scratch-a.tmp"
                else -> error("unexpected child root ${dir.name}")
            }
            touch(dir, name)
        }
        // Seed B fully: every root has a file that must survive every A operation.
        seedOwnerMaterial(ownerB) { dir ->
            val name = when (dir.name) {
                "fingerprints" -> "fp-b.fpw"
                "outbox-ciphertext" -> "blob-b.bin"
                "incoming-ciphertext" -> "capsule-b.bin"
                "retry-material" -> "retry-b.bin"
                "temp" -> "scratch-b.tmp"
                else -> error("unexpected child root ${dir.name}")
            }
            touch(dir, name)
        }

        // Snapshot B's file count before any operation.
        val bFingerprintCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS))
        val bOutboxCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT))
        val bIncomingCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT))
        val bRetryCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL))
        val bTempCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP))

        retention.onLogout(ownerA)

        // Every durable A root survives.
        for (root in listOf(
            AccountScopedFileRoots.ChildRoot.FINGERPRINTS,
            AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT,
            AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT,
            AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL,
        )) {
            val dir = roots.child(ownerA, root)
            assertTrue("durable A root ${root.directoryName} must survive logout", dir.exists())
            assertEquals(
                "durable A root ${root.directoryName} file count",
                1,
                fileCount(dir),
            )
        }
        // Only A's temp is removed.
        val aTemp = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP)
        assertFalse("A temp must be purged on logout", aTemp.exists())
        // B is untouched across every root.
        assertEquals(bFingerprintCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)))
        assertEquals(bOutboxCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT)))
        assertEquals(bIncomingCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)))
        assertEquals(bRetryCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL)))
        assertEquals(bTempCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP)))
        // B's account directory still exists.
        assertTrue(roots.accountDirectory(ownerB).exists())
        // A's account directory still exists because only temp was purged.
        assertTrue(roots.accountDirectory(ownerA).exists())
    }

    @Test
    fun logoutIsIdempotentWhenTempAlreadyAbsent() {
        seedOwnerMaterial(ownerA) { dir ->
            if (dir.name == "temp") touch(dir, "scratch-a.tmp")
        }
        val aTemp = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP)
        assertTrue(aTemp.exists())
        // First logout removes the temp; second logout is a no-op.
        retention.onLogout(ownerA)
        assertFalse(aTemp.exists())
        retention.onLogout(ownerA)
        assertFalse(aTemp.exists())
        // Durable material still in place.
        assertTrue(roots.child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS).exists())
    }

    @Test
    fun explicitPurgeARemovesAllARootsAndLeavesBUntouched() {
        // Seed both A and B fully.
        seedOwnerMaterial(ownerA) { dir ->
            val name = when (dir.name) {
                "fingerprints" -> "fp-a.fpw"
                "outbox-ciphertext" -> "blob-a.bin"
                "incoming-ciphertext" -> "capsule-a.bin"
                "retry-material" -> "retry-a.bin"
                "temp" -> "scratch-a.tmp"
                else -> error("unexpected child root ${dir.name}")
            }
            touch(dir, name)
        }
        seedOwnerMaterial(ownerB) { dir ->
            val name = when (dir.name) {
                "fingerprints" -> "fp-b.fpw"
                "outbox-ciphertext" -> "blob-b.bin"
                "incoming-ciphertext" -> "capsule-b.bin"
                "retry-material" -> "retry-b.bin"
                "temp" -> "scratch-b.tmp"
                else -> error("unexpected child root ${dir.name}")
            }
            touch(dir, name)
        }

        // Snapshot B's material before purge.
        val bFingerprintCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS))
        val bOutboxCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT))
        val bIncomingCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT))
        val bRetryCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL))
        val bTempCount = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP))

        retention.purgeAccount(ownerA)

        // Every A root is gone, including the account directory itself.
        for (root in AccountScopedFileRoots.ChildRoot.values()) {
            val dir = roots.child(ownerA, root)
            assertFalse("A root ${root.directoryName} must be purged", dir.exists())
        }
        assertFalse("A account directory must be removed", roots.accountDirectory(ownerA).exists())

        // B is untouched at every level.
        assertEquals(bFingerprintCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)))
        assertEquals(bOutboxCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT)))
        assertEquals(bIncomingCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)))
        assertEquals(bRetryCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL)))
        assertEquals(bTempCount, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP)))
        assertTrue("B account directory must remain", roots.accountDirectory(ownerB).exists())
    }

    @Test
    fun explicitPurgeIsIdempotentWhenAccountAlreadyAbsent() {
        // No seeding: A has no directory at all.
        assertFalse(roots.accountDirectory(ownerA).exists())
        // First purge is a no-op; second purge is a no-op; nothing else moves.
        retention.purgeAccount(ownerA)
        assertFalse(roots.accountDirectory(ownerA).exists())
        retention.purgeAccount(ownerA)
        assertFalse(roots.accountDirectory(ownerA).exists())
    }

    @Test
    fun logoutOnAAndThenPurgeALeavesBUntouched() {
        // Seed both, then exercise the two operations in sequence and
        // confirm B is invariant through every step.
        seedOwnerMaterial(ownerA) { dir ->
            val name = when (dir.name) {
                "fingerprints" -> "fp-a.fpw"
                "outbox-ciphertext" -> "blob-a.bin"
                "incoming-ciphertext" -> "capsule-a.bin"
                "retry-material" -> "retry-a.bin"
                "temp" -> "scratch-a.tmp"
                else -> error("unexpected child root ${dir.name}")
            }
            touch(dir, name)
        }
        seedOwnerMaterial(ownerB) { dir ->
            val name = when (dir.name) {
                "fingerprints" -> "fp-b.fpw"
                "outbox-ciphertext" -> "blob-b.bin"
                "incoming-ciphertext" -> "capsule-b.bin"
                "retry-material" -> "retry-b.bin"
                "temp" -> "scratch-b.tmp"
                else -> error("unexpected child root ${dir.name}")
            }
            touch(dir, name)
        }

        val bSnapshotBefore = bSnapshot()

        retention.onLogout(ownerA)
        assertBUnchanged(bSnapshotBefore)

        retention.purgeAccount(ownerA)
        assertBUnchanged(bSnapshotBefore)
    }

    private data class BSnapshot(
        val fingerprints: Int,
        val outbox: Int,
        val incoming: Int,
        val retry: Int,
        val temp: Int,
    )

    private fun bSnapshot(): BSnapshot = BSnapshot(
        fingerprints = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)),
        outbox = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT)),
        incoming = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)),
        retry = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL)),
        temp = fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP)),
    )

    private fun assertBUnchanged(snapshot: BSnapshot) {
        assertEquals(snapshot.fingerprints, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)))
        assertEquals(snapshot.outbox, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT)))
        assertEquals(snapshot.incoming, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)))
        assertEquals(snapshot.retry, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL)))
        assertEquals(snapshot.temp, fileCount(roots.child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP)))
        assertTrue(roots.accountDirectory(ownerB).exists())
    }

    /**
     * Regression: a symbolic link nested inside A's temp directory targets
     * material that lives outside A's account root (B's fingerprint file).
     * A normal logout of A must unlink the symlink entry but never follow
     * it, and the B material it pointed at must remain intact afterwards.
     */
    @Test
    fun logoutANeverFollowsSymlinkInTempAndPreservesBTarget() {
        // Seed B's fingerprint file first so the symlink target exists
        // when A's cleanup runs.
        val bFingerprints = roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
        check(bFingerprints.mkdirs())
        val bFingerprintFile = touch(bFingerprints, "fp-b.fpw")
        val bFingerprintBytes = bFingerprintFile.readBytes()
        check(bFingerprintBytes.isNotEmpty())

        // Build A temp with one regular file and a symlink to B's file.
        val aTemp = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP)
        check(aTemp.mkdirs())
        val aScratch = touch(aTemp, "scratch-a.tmp")
        val aScratchBytes = aScratch.readBytes()
        val symlinkInTemp = File(aTemp, "escape-to-b")
        java.nio.file.Files.createSymbolicLink(symlinkInTemp.toPath(), bFingerprintFile.toPath())

        // Sanity: the symlink resolves to the B file before cleanup.
        assertEquals(
            bFingerprintFile.canonicalPath,
            symlinkInTemp.canonicalFile.canonicalPath,
        )

        retention.onLogout(ownerA)

        // A's regular temp file is gone and the temp directory is removed.
        assertFalse(aScratch.exists())
        assertFalse(aTemp.exists())
        assertFalse(symlinkInTemp.exists() || symlinkInTemp.toPath().toFile().exists())

        // B's targeted material is byte-for-byte intact: the symlink was
        // unlinked, never followed, and its target was never opened for
        // deletion.
        assertTrue("B fingerprint file must survive A logout", bFingerprintFile.exists())
        assertArrayEquals(bFingerprintBytes, bFingerprintFile.readBytes())
        assertTrue("B account directory must survive A logout", roots.accountDirectory(ownerB).exists())
    }

    /**
     * Regression for the explicit purge path: a symlink in A's account root
     * that targets a file in B's account root must not delete the B file
     * when A is purged.
     */
    @Test
    fun purgeAccountANeverFollowsSymlinkAndPreservesBTarget() {
        val bFingerprints = roots.child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
        check(bFingerprints.mkdirs())
        val bFingerprintFile = touch(bFingerprints, "fp-b.fpw")
        val bFingerprintBytes = bFingerprintFile.readBytes()

        val aFingerprints = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
        check(aFingerprints.mkdirs())
        val aScratch = touch(aFingerprints, "fp-a.fpw")
        val symlinkInA = File(aFingerprints, "escape-to-b")
        java.nio.file.Files.createSymbolicLink(symlinkInA.toPath(), bFingerprintFile.toPath())

        retention.purgeAccount(ownerA)

        assertFalse("A account root must be removed", roots.accountDirectory(ownerA).exists())
        assertFalse("A fingerprint file must be removed", aScratch.exists())
        assertFalse("A symlink entry must be removed", symlinkInA.toPath().toFile().exists())
        // B material is intact.
        assertTrue(bFingerprintFile.exists())
        assertArrayEquals(bFingerprintBytes, bFingerprintFile.readBytes())
    }

    /**
     * A symlink to a directory outside the targeted account root must not
     * delete the target directory: only the symlink itself is unlinked.
     */
    @Test
    fun purgeAccountANeverFollowsSymlinkToForeignDirectory() {
        // Create a foreign directory outside any account root.
        val foreign = File(filesDir, "foreign")
        check(foreign.mkdirs())
        val foreignFile = touch(foreign, "outside.bin")
        val foreignBytes = foreignFile.readBytes()

        val aFingerprints = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
        check(aFingerprints.mkdirs())
        val aScratch = touch(aFingerprints, "fp-a.fpw")
        val symlinkDir = File(aFingerprints, "escape-dir")
        java.nio.file.Files.createSymbolicLink(symlinkDir.toPath(), foreign.toPath())

        retention.purgeAccount(ownerA)

        assertFalse(roots.accountDirectory(ownerA).exists())
        assertFalse(symlinkDir.toPath().toFile().exists())
        // Foreign material must be intact.
        assertTrue(foreign.isDirectory)
        assertTrue(foreignFile.exists())
        assertArrayEquals(foreignBytes, foreignFile.readBytes())
    }

    /**
     * If an in-scope entry cannot be removed (e.g. a directory whose
     * permissions make it unremovable), the routine must fail explicitly
     * with the offending path rather than silently leaving a partial
     * delete behind.
     */
    @Test
    fun cleanupFailsExplicitlyWhenAnInScopeEntryCannotBeRemoved() {
        val aTemp = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP)
        check(aTemp.mkdirs())
        val aScratch = touch(aTemp, "scratch-a.tmp")

        // Make a subdirectory inside A's temp read-only so its contents
        // cannot be removed. The walk will fail to delete a file inside it.
        val readOnlyChild = File(aTemp, "locked")
        check(readOnlyChild.mkdirs())
        val stuckFile = File(readOnlyChild, "stuck.bin")
        stuckFile.writeBytes(byteArrayOf(9, 8, 7))
        val readOnlyDir = readOnlyChild
        val originalPermissions = readOnlyDir.setWritable(false, false)
        val originalOwnerWritable = readOnlyDir.setWritable(false, true)
        check(originalPermissions || originalOwnerWritable) {
            "test environment does not support permission-based unremovability"
        }

        try {
            try {
                retention.onLogout(ownerA)
                fail("expected explicit failure for unremovable in-scope entry")
            } catch (expected: AccountStorageCleanupException) {
                // The path reported must be the in-scope entry that could
                // not be removed, not a path outside the account root.
                val reported = expected.message ?: ""
                assertTrue(
                    "failure message must name the in-scope path: $reported",
                    reported.contains("stuck.bin") || reported.contains("locked"),
                )
            }
            // A's temp directory must still exist because the cleanup
            // failed partway: the unremovable entry is still present.
            assertTrue(
                "A temp must still exist after a failed cleanup",
                aTemp.exists(),
            )
            assertTrue(
                "unremovable file must still exist after a failed cleanup",
                stuckFile.exists(),
            )
            assertTrue(
                "A scratch file must still exist because the walk was aborted",
                aScratch.exists(),
            )
        } finally {
            // Restore permissions so tearDown can clean up.
            readOnlyDir.setWritable(true, true)
            readOnlyDir.setWritable(true, false)
            aScratch.delete()
        }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) {
            fail("byte arrays differ (expected ${expected.size} bytes, got ${actual.size})")
        }
    }
}
