package dev.hryshyn.remanence.pending

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * T186 M14a: admitted index — owner-fenced durable rows, metadata
 * only (sealed opaque bytes, never key material), completion marking,
 * corrupt tolerance.
 */
class PendingAdmittedGiftStoreTest {

    private fun gift(owner: String, capsule: String) = PendingAdmittedGiftStore.AdmittedGift(
        ownerUserId = owner,
        capsuleId = capsule,
        deliveryId = "0198f0a0-0000-7000-8000-00000000d401",
        deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        claimId = "55555555-6666-7777-8888-999999999999",
        availableAt = 1798765432L,
        envelopeB64 = "RW52",
        envelopeSha256Hex = "ab".repeat(32),
        envelopeRef = "/v2/pending-deliveries/0198f0a0-0000-7000-8000-00000000d401/claim",
        manifestRef = "/v2/pending-deliveries/0198f0a0-0000-7000-8000-00000000d401/manifest",
        blobRefs = listOf("/v2/pending-deliveries/0198f0a0-0000-7000-8000-00000000d401/blobs/cccccccc-dddd-eeee-ffff-000000000001"),
        completedAt = null,
        admittedAtMillis = 1L,
    )

    @Test
    fun upsertListGetRoundTrip() = runTest {
        val dir = Files.createTempDirectory("pending-admitted").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            assertTrue(store.upsert(gift("owner-a", "capsule-1")))
            assertTrue(store.upsert(gift("owner-a", "capsule-2")))
            // Fresh instance over the same files: durable.
            val reopened = PendingAdmittedGiftStore(dir)
            assertEquals(2, reopened.list("owner-a").size)
            assertEquals("capsule-1", reopened.get("owner-a", "capsule-1")?.capsuleId)
            // Replace is by capsule id, never duplicated.
            assertTrue(reopened.upsert(gift("owner-a", "capsule-1").copy(availableAt = 2L)))
            assertEquals(2, reopened.list("owner-a").size)
            assertEquals(2L, reopened.get("owner-a", "capsule-1")?.availableAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun foreignOwnerSeesNothing() = runTest {
        val dir = Files.createTempDirectory("pending-admitted-foreign").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            assertTrue(store.upsert(gift("owner-a", "capsule-1")))
            assertTrue(store.list("owner-b").isEmpty())
            assertNull(store.get("owner-b", "capsule-1"))
            assertFalse(store.markCompleted("owner-b", "capsule-1", 5L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun completionMarksRow() = runTest {
        val dir = Files.createTempDirectory("pending-admitted-done").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            assertTrue(store.upsert(gift("owner-a", "capsule-1")))
            assertTrue(store.markCompleted("owner-a", "capsule-1", 1798765500L))
            assertEquals(1798765500L, store.get("owner-a", "capsule-1")?.completedAt)
            assertFalse(store.markCompleted("owner-a", "missing", 1L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blankOwnersRejected() = runTest {
        val dir = Files.createTempDirectory("pending-admitted-blank").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            assertFalse(store.upsert(gift("", "capsule-1")))
            assertTrue(store.list("").isEmpty())
            assertTrue(store.list("../escape").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptFileReadsEmpty() = runTest {
        val dir = Files.createTempDirectory("pending-admitted-corrupt").toFile()
        try {
            val slot = File(File(dir, "pending-admitted/owner-a").apply { mkdirs() }, "index.json")
            slot.writeText("not-json{{{")
            assertTrue(PendingAdmittedGiftStore(dir).list("owner-a").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
