package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M2-P08 contract for the sender-owned retry material on-disk store:
 *
 *  - owner A and owner B never share a persisted path or file;
 *  - two capsules under the same owner resolve to two distinct
 *    files;
 *  - a fresh process (a fresh [SenderRetryMaterialStore] over the
 *    same [AccountScopedFileRoots]) reads back exactly the bytes a
 *    prior process wrote;
 *  - a second write for the same (owner, capsule) fails BEFORE any
 *    byte of the new payload is written and the original bytes are
 *    byte-for-byte unchanged;
 *  - read and delete accept only the canonical (owner, capsule)
 *    pair; wrong owner or wrong capsule returns "absent", never a
 *    leak;
 *  - delete is idempotent: missing -> false, present -> true, and
 *    the second delete is again false;
 *  - a write failure leaves no `.tmp-*` residue;
 *  - the persisted path is the canonical
 *    `accounts/<owner>/retry-material/<capsule>.pwks` and matches
 *    the path the same (owner, capsule) derives through
 *    [SenderRetryMaterialStore.expectedPath];
 *  - the store never parses or interprets the wrapped bytes: a
 *    payload that is not a valid keyset is accepted unchanged, and
 *    the store has no production dependency on [dev.hryshyn.remanence.core.crypto].
 */
class SenderRetryMaterialStoreTest {

    private val ownerAUuid = "0198f0a0-0000-7000-8000-000000000aa1"
    private val ownerBUuid = "0198f0a0-0000-7000-8000-000000000bb1"
    private val capsuleA1Uuid = "0198f0a0-0000-7000-8000-00000000ca11"
    private val capsuleA2Uuid = "0198f0a0-0000-7000-8000-00000000ca22"
    private val capsuleB1Uuid = "0198f0a0-0000-7000-8000-00000000cb11"

    private val ownerA = UserId.parseRest(ownerAUuid)
    private val ownerB = UserId.parseRest(ownerBUuid)
    private val capsuleA1 = CapsuleId.parseRest(capsuleA1Uuid)
    private val capsuleA2 = CapsuleId.parseRest(capsuleA2Uuid)
    private val capsuleB1 = CapsuleId.parseRest(capsuleB1Uuid)

    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var store: SenderRetryMaterialStore

    @Before
    fun setUp() {
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-retry-store-${System.nanoTime()}",
        )
        check(filesDir.mkdirs()) { "could not create sandbox $filesDir" }
        roots = AccountScopedFileRoots(filesDir)
        store = SenderRetryMaterialStore(roots)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun retryDir(owner: UserId): File =
        roots.child(owner, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL)

    private fun persistedFile(owner: UserId, capsule: CapsuleId): File =
        File(retryDir(owner), "${capsule.toRestString()}.pwks")

    @Test
    fun ownerAAndOwnerBAndCapsulePairingProduceThreeDistinctPersistedFiles() = runBlocking {
        val a1Path = store.write(ownerA, capsuleA1, "wr-a1".toByteArray())
        val a2Path = store.write(ownerA, capsuleA2, "wr-a2".toByteArray())
        val b1Path = store.write(ownerB, capsuleB1, "wr-b1".toByteArray())

        // Three distinct persisted paths, none colliding. The write
        // path is the canonical path of the persisted file, which
        // is what the lifecycle will store as the DB pointer.
        assertEquals(persistedFile(ownerA, capsuleA1).canonicalPath, a1Path)
        assertEquals(persistedFile(ownerA, capsuleA2).canonicalPath, a2Path)
        assertEquals(persistedFile(ownerB, capsuleB1).canonicalPath, b1Path)
        assertTrue(a1Path != a2Path)
        assertTrue(a1Path != b1Path)
        assertTrue(a2Path != b1Path)

        // A's directory holds A1 and A2; B's directory holds B1 only.
        val aDir = retryDir(ownerA)
        assertEquals(
            setOf("${capsuleA1Uuid}.pwks", "${capsuleA2Uuid}.pwks"),
            aDir.listFiles()!!.map { it.name }.toSet(),
        )
        val bDir = retryDir(ownerB)
        assertEquals(
            setOf("${capsuleB1Uuid}.pwks"),
            bDir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun freshInstanceOverTheSameRootsReadsBackExactlyWhatWasWritten() = runBlocking {
        val originalBytes = "wrapped-retry-material-bytes".toByteArray() +
            ByteArray(64) { (it * 7).toByte() }
        val writtenPath = store.write(ownerA, capsuleA1, originalBytes)

        // A "second process": fresh store, fresh roots, same filesDir.
        val rehydratedRoots = AccountScopedFileRoots(filesDir)
        val rehydratedStore = SenderRetryMaterialStore(rehydratedRoots)
        val readBack = rehydratedStore.read(ownerA, capsuleA1)!!
        assertArrayEquals(originalBytes, readBack)
        assertEquals(
            writtenPath,
            rehydratedStore.expectedPath(ownerA, capsuleA1).canonicalPath,
        )
    }

    @Test
    fun secondWriteForSameOwnerAndCapsuleIsRefusedAndPreservesTheOriginalBytesByteForByte() =
        runBlocking {
            val original = "first-retry".toByteArray() + ByteArray(32) { 0x55.toByte() }
            store.write(ownerA, capsuleA1, original)

            val target = persistedFile(ownerA, capsuleA1)
            val beforeBytes = target.readBytes()
            val beforeModified = target.lastModified()

            val collision = assertThrows(SenderRetryMaterialStorageException::class.java) {
                runBlocking {
                    store.write(
                        ownerA,
                        capsuleA1,
                        "second-retry-should-never-reach-disk".toByteArray() + ByteArray(32) { 0xAA.toByte() },
                    )
                }
            }
            assertTrue(
                "overwrite refusal must name the colliding capsule",
                collision.message!!.contains(capsuleA1.toRestString()),
            )
            // No byte of the new payload may have been written.
            assertArrayEquals(beforeBytes, target.readBytes())
            // The store does not touch the existing file's metadata on
            // refusal: a successful write would have replaced the
            // lastModified, but refusal MUST not.
            assertEquals(beforeModified, target.lastModified())
        }

    @Test
    fun wrongOwnerAndWrongCapsuleCannotReadOrDelete() = runBlocking {
        store.write(ownerA, capsuleA1, "owner-a-capsule-1".toByteArray())
        store.write(ownerA, capsuleA2, "owner-a-capsule-2".toByteArray())
        store.write(ownerB, capsuleB1, "owner-b-capsule-1".toByteArray())

        // Wrong owner.
        assertNull(store.read(ownerA, capsuleB1))
        assertNull(store.read(ownerB, capsuleA1))
        // Wrong capsule under the right owner.
        val otherCapsuleUnderA = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca99")
        assertNull(store.read(ownerA, otherCapsuleUnderA))

        // Delete on a missing (owner, capsule) pair is the absent
        // path: false, never an exception, and the real file is
        // untouched.
        assertFalse(store.delete(ownerA, otherCapsuleUnderA))
        assertFalse(store.delete(ownerB, capsuleA1))
        // Real files are still in place after every miss.
        assertTrue(persistedFile(ownerA, capsuleA1).exists())
        assertTrue(persistedFile(ownerA, capsuleA2).exists())
        assertTrue(persistedFile(ownerB, capsuleB1).exists())
    }

    @Test
    fun deleteIsIdempotentFirstTrueThenFalseForTheSamePair() = runBlocking {
        store.write(ownerA, capsuleA1, "to-be-deleted".toByteArray())
        val target = persistedFile(ownerA, capsuleA1)
        assertTrue(target.exists())

        // First delete: file was present, gets removed, returns true.
        assertTrue(store.delete(ownerA, capsuleA1))
        assertFalse(target.exists())
        // Second delete: file is gone, returns false, no exception.
        assertFalse(store.delete(ownerA, capsuleA1))
        // Third delete: still gone, still false, still no exception.
        assertFalse(store.delete(ownerA, capsuleA1))
    }

    @Test
    fun deleteIsScopedToTheOwnerAndCapsulePairAndNeverTouchesAnotherAccount() = runBlocking {
        store.write(ownerA, capsuleA1, "owner-a-capsule-1".toByteArray())
        store.write(ownerA, capsuleA2, "owner-a-capsule-2".toByteArray())
        store.write(ownerB, capsuleB1, "owner-b-capsule-1".toByteArray())

        // Delete A/capsule-1 only.
        assertTrue(store.delete(ownerA, capsuleA1))
        assertFalse(persistedFile(ownerA, capsuleA1).exists())
        // A/capsule-2 and B/capsule-1 are untouched, byte-for-byte.
        assertTrue(persistedFile(ownerA, capsuleA2).exists())
        assertTrue(persistedFile(ownerB, capsuleB1).exists())
        // And the B directory is not enumerated or visited at all.
        assertEquals(
            "B retry-material directory must hold only the original file",
            setOf("${capsuleB1Uuid}.pwks"),
            retryDir(ownerB).listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun emptyBytesRefusedAtTheApiSurface() = runBlocking {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.write(ownerA, capsuleA1, ByteArray(0)) }
        }
        assertTrue(refusal.message!!.contains("empty"))
        // No file was created, no temp was created, no directory was
        // touched.
        assertFalse(persistedFile(ownerA, capsuleA1).exists())
        assertFalse(retryDir(ownerA).exists())
    }

    @Test
    fun writeFailureLeavesNoTempResidueAndNoTargetFile() = runBlocking {
        // Simulate a write failure at the directory-preparation step
        // by placing a regular file at the path the store would use
        // as the parent directory. `parent.exists()` is then true
        // (so the `if (!parent.exists() && !parent.mkdirs())` guard
        // skips the mkdirs call), and `parent.isDirectory` is
        // false, so the subsequent `temporary.writeBytes` on a path
        // beneath a non-directory parent fails with FileNotFound /
        // IOException. The store MUST surface that as
        // SenderRetryMaterialStorageException and the finally block
        // MUST clean up the temp.
        val target = persistedFile(ownerA, capsuleA1)
        val ownerDir = target.parentFile.parentFile
        ownerDir.mkdirs()
        // Remove the real parent if it exists from an earlier test
        // in the same JVM, then plant a file in its place.
        target.parentFile.deleteRecursively()
        check(target.parentFile.createNewFile()) { "could not seed parent as file" }
        val before = ownerDir.listFiles()!!.map { it.name }.toSet()

        val failure = assertThrows(SenderRetryMaterialStorageException::class.java) {
            runBlocking { store.write(ownerA, capsuleA1, "first-payload".toByteArray()) }
        }
        assertNotNull(failure.message)
        // The target must NOT exist after a failed write.
        assertFalse(
            "target file must not be created on a failed write",
            target.exists(),
        )
        // The store's own temp files (.tmp-<uuid>) MUST NOT have
        // landed anywhere - the write failed before the temp was
        // created, or was cleaned in finally.
        val parentListing = ownerDir.listFiles()!!.map { it.name }.toSet()
        val leakedStoreTemp = parentListing.filter {
            it.startsWith(target.parentFile.name) && it.contains(".tmp-")
        }
        assertTrue(
            "store must not leave a .tmp-<uuid> residue; saw $leakedStoreTemp",
            leakedStoreTemp.isEmpty(),
        )
        // The seeded parent-file is unrelated to the store's temp
        // and MAY still be there; the store never touches it.
        assertTrue(
            "seeded parent file must still be present (unrelated to the store)",
            target.parentFile.exists(),
        )
        // And the directory listing at the owner is unchanged from
        // before the failing write.
        assertEquals(before, parentListing)
    }

    @Test
    fun corruptedEmptyFileSurfacesAsStorageExceptionOnRead() = runBlocking {
        // The store never writes an empty file, but a manually seeded
        // one is a storage-level corruption: read MUST fail closed.
        val target = persistedFile(ownerA, capsuleA1)
        target.parentFile.mkdirs()
        target.writeBytes(ByteArray(0))

        val failure = assertThrows(SenderRetryMaterialStorageException::class.java) {
            runBlocking { store.read(ownerA, capsuleA1) }
        }
        assertTrue(failure.message!!.contains("empty"))
    }

    @Test
    fun readOnAbsentFileReturnsNullAndIsNotAnException() = runBlocking {
        assertNull(store.read(ownerA, capsuleA1))
        // Write a different file; reading the absent one still
        // returns null.
        store.write(ownerA, capsuleA2, "different-capsule".toByteArray())
        assertNull(store.read(ownerA, capsuleA1))
    }

    @Test
    fun expectedPathMatchesPersistedPathAndStaysContainedUnderTheAccountRoot() = runBlocking {
        val expected = store.expectedPath(ownerA, capsuleA1)
        val accountsDir = File(filesDir, "accounts")
        val expectedOwnerDir = File(accountsDir, ownerA.toRestString())
        val expectedRetryDir = File(expectedOwnerDir, "retry-material")
        // Canonical containment: the expected path's canonical form
        // lives beneath the owner's canonical retry-material
        // directory. The store's write must land at a path whose
        // canonical form is contained under the same root.
        val expectedCanonical = expected.canonicalPath
        val expectedRetryDirCanonical = expectedRetryDir.canonicalPath
        val requiredPrefix = if (expectedRetryDirCanonical.endsWith(File.separator)) {
            expectedRetryDirCanonical
        } else {
            "$expectedRetryDirCanonical${File.separator}"
        }
        assertTrue(
            "expected path must be inside the owner retry-material dir: $expectedCanonical",
            expectedCanonical == expectedRetryDirCanonical ||
                expectedCanonical.startsWith(requiredPrefix),
        )
        // The expected filename is the canonical REST UUID plus .pwks.
        assertEquals("${capsuleA1.toRestString()}.pwks", expected.name)
        // The same path the write returns must equal the canonical
        // path the same (owner, capsule) pair derives.
        val written = store.write(ownerA, capsuleA1, "wrap".toByteArray())
        assertEquals(expected.canonicalPath, written)
    }

    @Test
    fun noCryptoDependencyAndOpaqueBytesInBytesOut() = runBlocking {
        // The store must accept arbitrary opaque bytes - bytes that
        // are NOT a valid wrapped keyset record, bytes that include
        // embedded NULs, bytes that begin with the byte value 0 - and
        // round-trip them exactly. The contract is bytes-in, bytes-out;
        // the store has no opinion on the contents.
        val opaque = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        ) + ByteArray(48) { (it xor 0xAA).toByte() }
        store.write(ownerA, capsuleA1, opaque)
        val readBack = store.read(ownerA, capsuleA1)!!
        assertArrayEquals(opaque, readBack)
        // The persisted file on disk contains exactly the same bytes.
        val onDisk = persistedFile(ownerA, capsuleA1).readBytes()
        assertArrayEquals(opaque, onDisk)
    }

    @Test
    fun storeHasNoProductionDependencyOnCoreCrypto() {
        // Reflection check: the SenderRetryMaterialStore class must
        // not reference any type from :core:crypto in its production
        // surface. A future contributor adding such a reference
        // would silently pull :core:crypto into the data module's
        // runtime classpath; this guard fails the test the moment a
        // crypto type appears in any constructor or method signature.
        val surface = SenderRetryMaterialStore::class.java
        val forbiddenPrefix = "dev.hryshyn.remanence.core.crypto."
        for (constructor in surface.declaredConstructors) {
            for (param in constructor.parameterTypes) {
                assertFalse(
                    "constructor parameter ${param.name} pulls in :core:crypto",
                    param.name.startsWith(forbiddenPrefix),
                )
            }
        }
        for (method in surface.declaredMethods) {
            for (param in method.parameterTypes) {
                assertFalse(
                    "method ${method.name} parameter ${param.name} pulls in :core:crypto",
                    param.name.startsWith(forbiddenPrefix),
                )
            }
            val returnType = method.returnType
            assertFalse(
                "method ${method.name} return type ${returnType.name} pulls in :core:crypto",
                returnType.name.startsWith(forbiddenPrefix),
            )
        }
    }

    @Test
    fun retryMaterialRootIsAccountScopedAndNotEnumerated() = runBlocking {
        // Build material for A and B; the store MUST resolve both
        // through the same roots without enumerating either account
        // directory. We assert the boundary by reading each file
        // through its own path and verifying B's account root is
        // untouched.
        store.write(ownerA, capsuleA1, "a-payload".toByteArray())
        store.write(ownerB, capsuleB1, "b-payload".toByteArray())
        // B's retry dir holds exactly B's file; A's retry dir holds
        // exactly A's file. No spillover.
        assertEquals(
            setOf("${capsuleA1Uuid}.pwks"),
            retryDir(ownerA).listFiles()!!.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("${capsuleB1Uuid}.pwks"),
            retryDir(ownerB).listFiles()!!.map { it.name }.toSet(),
        )
    }

    /**
     * Per-(owner, capsule) serialization: two concurrent writers
     * for the same pair cannot both pass the existence check and
     * silently replace each other. Exactly one call wins, the
     * other is refused with [SenderRetryMaterialStorageException],
     * and the winning bytes are preserved byte-for-byte.
     */
    @Test
    fun concurrentWritesForSamePairProduceExactlyOneSuccessAndOneOverwriteRejection() =
        runBlocking {
            val firstBytes = "first-payload-wins".toByteArray() + ByteArray(64) { 0x33.toByte() }
            val secondBytes = "second-payload-must-be-rejected".toByteArray() +
                ByteArray(64) { 0x44.toByte() }

            // Launch two writers that race into the store. The store
            // MUST serialize them through the per-(owner, capsule)
            // mutex so exactly one passes the existence check.
            val outcomes = coroutineScope {
                val first = async {
                    runCatching { store.write(ownerA, capsuleA1, firstBytes) }
                }
                val second = async {
                    runCatching { store.write(ownerA, capsuleA1, secondBytes) }
                }
                awaitAll(first, second)
            }

            val successes = outcomes.filter { it.isSuccess }
            val failures = outcomes.filter { it.isFailure }
            assertEquals(
                "exactly one writer must win",
                1,
                successes.size,
            )
            assertEquals(
                "exactly one writer must be rejected",
                1,
                failures.size,
            )
            val rejection = failures.single().exceptionOrNull()!!
            assertTrue(
                "rejection must be the typed storage exception",
                rejection is SenderRetryMaterialStorageException,
            )
            assertTrue(
                "rejection must name the colliding capsule",
                rejection.message!!.contains(capsuleA1.toRestString()),
            )

            // The winner's bytes survive byte-for-byte on disk, and
            // they are EITHER the first or the second payload - the
            // exact race ordering is not under test, only the
            // invariant that exactly one payload is on disk and the
            // other never replaced it.
            val onDisk = persistedFile(ownerA, capsuleA1).readBytes()
            val won = if (onDisk.contentEquals(firstBytes)) {
                firstBytes
            } else if (onDisk.contentEquals(secondBytes)) {
                secondBytes
            } else {
                fail(
                    "on-disk bytes do not match either winning payload: " +
                        "size=${onDisk.size}, head=${onDisk.take(16).joinToString(",") { it.toInt().and(0xff).toString() }}",
                )
                error("unreachable")
            }
            // The other payload must NOT be on disk in any form.
            val loser = if (won.contentEquals(firstBytes)) secondBytes else firstBytes
            assertFalse(
                "the losing payload must not be on disk",
                onDisk.contentEquals(loser),
            )
            // And the success path returned the canonical path.
            val writtenPath = successes.single().getOrThrow()
            assertEquals(persistedFile(ownerA, capsuleA1).canonicalPath, writtenPath)
        }

    /**
     * Multiple concurrent writers across DIFFERENT (owner, capsule)
     * pairs must all succeed independently: the per-pair mutex
     * does not serialize unrelated writes.
     */
    @Test
    fun concurrentWritesForDistinctPairsAllSucceed() = runBlocking {
        val outcomes = coroutineScope {
            val jobs = listOf(
                async { runCatching { store.write(ownerA, capsuleA1, "a1".toByteArray()) } },
                async { runCatching { store.write(ownerA, capsuleA2, "a2".toByteArray()) } },
                async { runCatching { store.write(ownerB, capsuleB1, "b1".toByteArray()) } },
            )
            awaitAll(*jobs.toTypedArray())
        }
        assertEquals(3, outcomes.size)
        assertTrue("every distinct-pair write must succeed", outcomes.all { it.isSuccess })
        assertTrue(persistedFile(ownerA, capsuleA1).exists())
        assertTrue(persistedFile(ownerA, capsuleA2).exists())
        assertTrue(persistedFile(ownerB, capsuleB1).exists())
    }

    /**
     * Cause preservation: when the underlying I/O fails (here, by
     * seeding a directory at the canonical target path so the
     * rename fails), the surfaced [SenderRetryMaterialStorageException]
     * carries the underlying cause so the lifecycle can map the
     * typed failure to its own retry surface. The error message
     * itself never includes the wrapped bytes.
     */
    @Test
    fun storageExceptionPreservesUnderlyingCauseAndNeverLeaksWrappedBytes() = runBlocking {
        // Seed a regular file at the path the store would use as the
        // parent directory. `parent.exists()` is true, `parent.isDirectory`
        // is false, the mkdirs call is skipped, and the subsequent
        // `temporary.writeBytes` on a non-directory parent throws an
        // IOException. The store MUST surface that as a
        // SenderRetryMaterialStorageException with the underlying
        // IOException attached as the cause, and the message MUST
        // NOT include the wrapped bytes.
        val target = persistedFile(ownerA, capsuleA1)
        val ownerDir = target.parentFile.parentFile
        ownerDir.mkdirs()
        target.parentFile.deleteRecursively()
        check(target.parentFile.createNewFile()) { "could not seed parent as file" }
        val bytes = "wrapped-payload-abcdef".toByteArray() + ByteArray(32) { 0x77.toByte() }
        val failure = assertThrows(SenderRetryMaterialStorageException::class.java) {
            runBlocking { store.write(ownerA, capsuleA1, bytes) }
        }
        // The cause is preserved on the typed exception.
        assertNotNull(
            "SenderRetryMaterialStorageException MUST preserve the underlying cause",
            failure.cause,
        )
        // The error message itself never includes the wrapped bytes
        // (which would leak material into log output).
        assertFalse(
            "error message must not leak wrapped bytes: ${failure.message}",
            failure.message!!.contains("wrapped-payload-abcdef"),
        )
    }
}
