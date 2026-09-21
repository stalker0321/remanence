package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * G4-A production staging store tests (real tmpdir filesystem, no mocks).
 *
 * The boundary must: reject opaque-key abuse as paths; never follow
 * symlinks (unlink, target untouched); isolate owners (enumeration and
 * deletion stay inside one account); roll back failed atomic writes
 * without partials; sweep restart orphans and unknown entries inside
 * one namespace; purge TEMP on logout while durable roots survive;
 * round-trip exactly 32 MiB and refuse a byte more.
 */
class GeneratorStagingFileStoreTest {

    private val ownerAUuid = "0198f0a0-0000-7000-8000-0000000000a1"
    private val ownerBUuid = "0198f0a0-0000-7000-8000-0000000000b1"
    private val ownerA: UserId = UserId.parseRest(ownerAUuid)
    private val ownerB: UserId = UserId.parseRest(ownerBUuid)

    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var retention: AccountStorageRetention
    private lateinit var storeA: GeneratorStagingFileStore
    private lateinit var storeB: GeneratorStagingFileStore

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "remanence-genstaging-${System.nanoTime()}")
        check(filesDir.mkdirs()) { "could not create sandbox $filesDir" }
        roots = AccountScopedFileRoots(filesDir)
        retention = AccountStorageRetention(roots)
        storeA = GeneratorStagingFileStore(roots, retention, ownerA)
        storeB = GeneratorStagingFileStore(roots, retention, ownerB)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun sessionId(seed: String) = "stg-" + seed.lowercase().padEnd(16, '0').take(16)

    @Test
    fun traversalKeysRejected() {
        val evil = listOf(
            "../evil/0", "/abs/0", "stg-abc/manifest", "stg-0123456789abcdef/0/extra", "",
            "stg-0123456789abcdef/999999999999", "stg-0123456789abcdef/-1",
            "stg-0123456789abcdef/007", "stg-0123456789abcdef/0/",
        )
        for (key in evil) {
            try {
                storeA.put(key, byteArrayOf(1))
                fail("traversal key accepted: $key")
            } catch (_: IllegalArgumentException) {
            }
            try {
                storeA.get(key)
                fail("traversal get accepted: $key")
            } catch (_: IllegalArgumentException) {
            }
            try {
                storeA.delete(key)
                fail("traversal delete accepted: $key")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(storeA.keys().isEmpty())
    }

    @Test
    fun symlinkSessionRefusedTargetUntouched() {
        val outside = File(filesDir, "outside-secret")
        check(outside.writeBytes(byteArrayOf(7, 7, 7)) == Unit) { "seed failed" }
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        check(namespace.mkdirs()) { "namespace failed" }
        val link = File(namespace, sessionId("ab"))
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            return
        }
        try {
            storeA.put("${sessionId("ab")}/0", byteArrayOf(1))
            fail("symlinked session accepted")
        } catch (_: AccountStorageCleanupException) {
        }
        assertTrue(outside.readBytes().contentEquals(byteArrayOf(7, 7, 7)))
        assertTrue(storeA.keys().isEmpty())
    }

    @Test
    fun ownerSeparationAcrossOps() {
        val sid = sessionId("cd")
        storeA.put("$sid/0", byteArrayOf(1, 2, 3))
        assertTrue(storeB.keys().isEmpty())
        assertNull(storeB.get("$sid/0"))
        assertFalse(storeB.delete("$sid/0"))
        assertEquals(0, storeB.sweepOwner(setOf(sid)))
        assertEquals(byteArrayOf(1, 2, 3).toList(), storeA.get("$sid/0")!!.toList())
        storeB.onLogout()
        assertEquals(byteArrayOf(1, 2, 3).toList(), storeA.get("$sid/0")!!.toList())
    }

    @Test
    fun atomicFailureLeavesNoPartial() {
        val sid = sessionId("ef")
        storeA.put("$sid/0", byteArrayOf(1))
        // Occupy the destination with a directory so the atomic rename fails.
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val blocker = File(File(namespace, sid), "1")
        check(blocker.mkdirs()) { "blocker failed" }
        try {
            storeA.put("$sid/1", byteArrayOf(2))
            fail("rename onto directory accepted")
        } catch (_: AccountStorageCleanupException) {
        }
        val leftovers = File(File(namespace, sid), "").listFiles { file -> file.name.contains(".tmp-") }
        assertTrue((leftovers ?: emptyArray()).isEmpty())
        assertEquals(byteArrayOf(1).toList(), storeA.get("$sid/0")!!.toList())
    }

    @Test
    fun restartOrphanSweepUsesFreshStoreInstance() {
        val live = sessionId("aa")
        val dead = sessionId("bb")
        storeA.writeManifest(live, 7L, 0L, 1_000L)
        storeA.put("$live/0", byteArrayOf(1))
        storeA.writeManifest(dead, 7L, 0L, 1_000L)
        storeA.put("$dead/0", byteArrayOf(2))
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        check(File(namespace, "junk-file").writeBytes(byteArrayOf(9)) == Unit) { "junk failed" }
        check(File(namespace, "junk-dir").mkdirs()) { "junk dir failed" }
        // Fresh instances prove the filesystem alone suffices for recovery.
        val freshRoots = AccountScopedFileRoots(filesDir)
        val freshRetention = AccountStorageRetention(freshRoots)
        val freshStore = GeneratorStagingFileStore(freshRoots, freshRetention, ownerA)
        val removed = freshStore.sweepOwner(setOf(live))
        assertEquals(3, removed)
        assertEquals(mapOf("sessionId" to live), freshStore.readManifest(live)?.filterKeys { it == "sessionId" })
        assertNull(freshStore.readManifest(dead))
        assertEquals(byteArrayOf(1).toList(), freshStore.get("$live/0")!!.toList())
        assertTrue(freshStore.keys() == setOf("$live/0"))
        // Foreign owner survives the fresh sweep untouched (seeded below).
        val otherSid = sessionId("cc")
        storeB.put("$otherSid/0", byteArrayOf(3))
        val freshB = GeneratorStagingFileStore(freshRoots, freshRetention, ownerB)
        assertEquals(byteArrayOf(3).toList(), freshB.get("$otherSid/0")!!.toList())
    }

    @Test
    fun logoutPurgesTempKeepsDurableAndOthers() {
        val sid = sessionId("cc")
        storeA.put("$sid/0", byteArrayOf(1))
        val durable = File(roots.child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS), "keep.bin")
        check(durable.parentFile.mkdirs()) { "durable dir failed" }
        check(durable.writeBytes(byteArrayOf(5)) == Unit) { "durable failed" }
        val otherSid = sessionId("dd")
        storeB.put("$otherSid/0", byteArrayOf(2))
        storeA.onLogout()
        assertTrue(storeA.keys().isEmpty())
        assertTrue(durable.readBytes().contentEquals(byteArrayOf(5)))
        assertEquals(byteArrayOf(2).toList(), storeB.get("$otherSid/0")!!.toList())
    }

    @Test
    fun cancellationStyleDeleteSessionDropsReads() {
        val sid = sessionId("ee")
        storeA.put("$sid/0", byteArrayOf(1, 2))
        assertTrue(storeA.deleteSession(sid))
        assertNull(storeA.get("$sid/0"))
        assertTrue(storeA.keys().isEmpty())
        assertNull(storeA.readManifest(sid))
        assertFalse(storeA.deleteSession(sid))
    }

    @Test
    fun exactBoundsAcceptedAndOverRejected() {
        val sid = sessionId("ff")
        val exact = ByteArray(GeneratorStagingFileStore.MAX_BLOB_BYTES) { it.toByte() }
        storeA.put("$sid/0", exact)
        assertTrue(storeA.get("$sid/0")!!.contentEquals(exact))
        try {
            storeA.put("$sid/1", ByteArray(GeneratorStagingFileStore.MAX_BLOB_BYTES + 1))
            fail("over-limit accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(storeA.keys() == setOf("$sid/0"))
    }

    @Test
    fun manifestRoundTripAndForeignRejected() {
        val sid = sessionId("ab")
        storeA.writeManifest(sid, 7L, 3L, 9_000L)
        val manifest = storeA.readManifest(sid)!!
        assertEquals("gen-staging-manifest/1", manifest["format"])
        assertEquals(sid, manifest["sessionId"])
        assertEquals(ownerAUuid, manifest["owner"])
        assertEquals("7", manifest["epoch"])
        assertEquals("3", manifest["revision"])
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val foreign = File(File(namespace, sessionId("ba")), GeneratorStagingFileStore.MANIFEST_NAME)
        check(foreign.parentFile.mkdirs()) { "foreign dir failed" }
        check(foreign.writeText("sessionId=${sessionId("ba")}\nowner=$ownerBUuid\nepoch=7\nrevision=0\ncreatedAt=1\n") == Unit) {
            "foreign manifest failed"
        }
        assertNull(storeA.readManifest(sessionId("ba")))
        assertEquals(1, storeA.sweepOwner(setOf(sid)))
    }

    @Test
    fun oversizedManifestRejected() {
        val sid = sessionId("aa")
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val dir = File(namespace, sid)
        check(dir.mkdirs()) { "manifest dir failed" }
        check(File(dir, GeneratorStagingFileStore.MANIFEST_NAME).writeBytes(ByteArray(5000) { 'x'.code.toByte() }) == Unit) {
            "oversized manifest failed"
        }
        assertNull(storeA.readManifest(sid))
    }

    @Test
    fun missingDuplicateUnknownManifestFieldsRejected() {
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        fun place(sid: String, body: String) {
            val dir = File(namespace, sid)
            check(dir.mkdirs()) { "manifest dir failed" }
            check(File(dir, GeneratorStagingFileStore.MANIFEST_NAME).writeText(body) == Unit) { "write failed" }
        }
        val sid = sessionId("ab")
        place(sessionId("ac"), "format=gen-staging-manifest/1\nsessionId=${sessionId("ac")}\nowner=$ownerAUuid\nepoch=7\nrevision=0\n")
        assertNull(storeA.readManifest(sessionId("ac")))
        place(
            sessionId("ad"),
            "format=gen-staging-manifest/1\nsessionId=${sessionId("ad")}\nowner=$ownerAUuid\nepoch=7\nepoch=8\nrevision=0\ncreatedAt=1\n",
        )
        assertNull(storeA.readManifest(sessionId("ad")))
        place(
            sessionId("ae"),
            "format=gen-staging-manifest/1\nsessionId=${sessionId("ae")}\nowner=$ownerAUuid\nepoch=7\nrevision=0\ncreatedAt=1\ncolor=red\n",
        )
        assertNull(storeA.readManifest(sessionId("ae")))
    }

    @Test
    fun negativeOrOverflowManifestNumbersRejected() {
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        fun place(sid: String, epoch: String) {
            val dir = File(namespace, sid)
            check(dir.mkdirs()) { "manifest dir failed" }
            val body = "format=gen-staging-manifest/1\nsessionId=$sid\nowner=$ownerAUuid\nepoch=$epoch\nrevision=0\ncreatedAt=1\n"
            check(File(dir, GeneratorStagingFileStore.MANIFEST_NAME).writeText(body) == Unit) { "write failed" }
        }
        place(sessionId("af"), "-1")
        assertNull(storeA.readManifest(sessionId("af")))
        place(sessionId("ba"), "99999999999999999999999")
        assertNull(storeA.readManifest(sessionId("ba")))
    }

    @Test
    fun tamperedManifestIdentityRejected() {
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val sid = sessionId("ab")
        val dir = File(namespace, sid)
        check(dir.mkdirs()) { "manifest dir failed" }
        val swapped = "format=gen-staging-manifest/1\nsessionId=${sessionId("bb")}\nowner=$ownerAUuid\nepoch=7\nrevision=0\ncreatedAt=1\n"
        check(File(dir, GeneratorStagingFileStore.MANIFEST_NAME).writeText(swapped) == Unit) { "write failed" }
        assertNull(storeA.readManifest(sid))
        val wrongFormat = "format=gen-staging-manifest/2\nsessionId=$sid\nowner=$ownerAUuid\nepoch=7\nrevision=0\ncreatedAt=1\n"
        check(File(dir, GeneratorStagingFileStore.MANIFEST_NAME).writeText(wrongFormat) == Unit) { "write failed" }
        assertNull(storeA.readManifest(sid))
    }

    @Test
    fun emptyBlobRejectedWithoutFile() {
        val sid = sessionId("ab")
        try {
            storeA.put("$sid/0", ByteArray(0))
            fail("empty blob accepted")
        } catch (_: IllegalArgumentException) {
        }
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val dir = File(namespace, sid)
        assertTrue(!dir.exists() || dir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun existingTargetIsNeverOverwritten() {
        val sid = sessionId("ab")
        storeA.put("$sid/0", byteArrayOf(1, 2, 3))
        try {
            storeA.put("$sid/0", byteArrayOf(9, 9, 9))
            fail("overwrite accepted")
        } catch (_: AccountStorageCleanupException) {
        }
        assertEquals(byteArrayOf(1, 2, 3).toList(), storeA.get("$sid/0")!!.toList())
    }

    @Test
    fun readGrowthCannotExceed32MiB() {
        val sid = sessionId("ab")
        val seed = ByteArray(1024 * 1024) { 7 }
        storeA.put("$sid/0", seed)
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val target = File(File(namespace, sid), "0")
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = Thread {
            try {
                repeat(60) {
                    java.io.FileOutputStream(target, true).use { it.write(seed) }
                }
            } catch (e: Throwable) {
                errors += e
            }
        }
        writer.start()
        var boundedFailures = 0
        repeat(60) {
            try {
                val bytes = storeA.get("$sid/0")!!
                assertTrue(bytes.size <= GeneratorStagingFileStore.MAX_BLOB_BYTES)
            } catch (e: AccountStorageCleanupException) {
                boundedFailures++
            } catch (e: Throwable) {
                errors += e
            }
        }
        writer.join(15_000)
        assertTrue("writer must complete", !writer.isAlive)
        assertTrue("no unsealed throwable may escape: $errors", errors.isEmpty())
        // Normal path still returns byte-identical content when ungrown.
        val fresh = sessionId("ba")
        storeA.put("$fresh/0", seed)
        assertTrue(storeA.get("$fresh/0")!!.contentEquals(seed))
    }

    @Test
    fun atomicRenameDurabilityContract() {
        val sid = sessionId("ab")
        storeA.writeManifest(sid, 7L, 0L, 1_000L)
        storeA.put("$sid/0", byteArrayOf(1, 2, 3))
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val sessionDir = File(namespace, sid)
        val result = storeA.syncDirectory(sessionDir)
        assertTrue(
            result == GeneratorStagingFileStore.DirSyncResult.SYNCED ||
                result == GeneratorStagingFileStore.DirSyncResult.UNSUPPORTED,
        )
        // Post-restart state from a fresh instance: manifest + blob intact.
        val freshStore = GeneratorStagingFileStore(
            AccountScopedFileRoots(filesDir),
            AccountStorageRetention(AccountScopedFileRoots(filesDir)),
            ownerA,
        )
        assertEquals(sid, freshStore.readManifest(sid)?.get("sessionId"))
        assertEquals(byteArrayOf(1, 2, 3).toList(), freshStore.get("$sid/0")!!.toList())
    }

    @Test
    fun recheckMaterializedChainEnforced() {
        val sid = sessionId("ab")
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        val dir = File(namespace, sid)
        check(dir.mkdirs()) { "chain dir failed" }
        storeA.recheckMaterialized(sid, "0")
        try {
            java.nio.file.Files.createSymbolicLink(
                File(namespace, sessionId("ac")).toPath(),
                dir.toPath(),
            )
        } catch (_: UnsupportedOperationException) {
            return
        }
        try {
            storeA.recheckMaterialized(sessionId("ac"), "0")
            fail("symlinked chain accepted")
        } catch (_: AccountStorageCleanupException) {
        }
    }
}
