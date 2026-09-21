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
        val evil = listOf("../evil/0", "/abs/0", "stg-abc/manifest", "stg-0123456789abcdef/0/extra", "")
        for (key in evil) {
            try {
                storeA.put(key, byteArrayOf(1))
                fail("traversal key accepted: $key")
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
    fun restartOrphanSweepKeepsLiveAndDropsUnknown() {
        val live = sessionId("aa")
        val dead = sessionId("bb")
        storeA.writeManifest(live, 7L, 0L, 1_000L)
        storeA.put("$live/0", byteArrayOf(1))
        storeA.writeManifest(dead, 7L, 0L, 1_000L)
        storeA.put("$dead/0", byteArrayOf(2))
        val namespace = File(roots.createStagingRoot(ownerA), GeneratorStagingFileStore.NAMESPACE_DIR)
        check(File(namespace, "junk-file").writeBytes(byteArrayOf(9)) == Unit) { "junk failed" }
        check(File(namespace, "junk-dir").mkdirs()) { "junk dir failed" }
        val removed = storeA.sweepOwner(setOf(live))
        assertEquals(3, removed)
        assertEquals(mapOf("sessionId" to live), storeA.readManifest(live)?.filterKeys { it == "sessionId" })
        assertNull(storeA.readManifest(dead))
        assertEquals(byteArrayOf(1).toList(), storeA.get("$live/0")!!.toList())
        assertTrue(storeA.keys() == setOf("$live/0"))
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
}
