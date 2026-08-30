package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IncomingCiphertextAdopterTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-0000000000a1")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca11")
    private val blobs = mapOf(
        CapsuleArtifactKind.RECOGNITION_MANIFEST to BlobId.parseRest(
            "0198f0a0-0000-7000-8000-00000000b101",
        ),
        CapsuleArtifactKind.CONTENT_MANIFEST to BlobId.parseRest(
            "0198f0a0-0000-7000-8000-00000000b102",
        ),
        CapsuleArtifactKind.PHOTO to BlobId.parseRest(
            "0198f0a0-0000-7000-8000-00000000b103",
        ),
    )

    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-incoming-generic-adopter-${System.nanoTime()}",
        )
        check(filesDir.mkdirs())
        roots = AccountScopedFileRoots(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun allArtifactKindsUseTheirOwnProtocolSizeLimitAndAdoptWithoutDecrypting() = runBlocking {
        val adopter = IncomingCiphertextAdopter(roots)
        val cases = listOf(
            Triple(CapsuleArtifactKind.RECOGNITION_MANIFEST, -1, "recognition"),
            Triple(CapsuleArtifactKind.CONTENT_MANIFEST, -1, "content"),
            Triple(CapsuleArtifactKind.PHOTO, 0, "photo"),
        )

        cases.forEach { (kind, ordinal, label) ->
            val bytes = "opaque-$label-ciphertext".toByteArray()
            val blob = blobs.getValue(kind)
            val source = source(blob, "$label.tmp", bytes)
            val result = adopter.adopt(request(kind, ordinal, blob, source, bytes))

            assertTrue(result is IncomingCiphertextAdoptionResult.Adopted)
            assertArrayEquals(bytes, destination(blob).readBytes())
            assertFalse(source.exists())
        }

        assertSizeRejected(CapsuleArtifactKind.RECOGNITION_MANIFEST, -1, ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES + 1)
        assertSizeRejected(CapsuleArtifactKind.CONTENT_MANIFEST, -1, ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES + 1)
        assertSizeRejected(CapsuleArtifactKind.PHOTO, 0, ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES + 1)
    }

    @Test
    fun contentAndPhotoExistingExactDestinationsReplayIdempotently() = runBlocking {
        val adopter = IncomingCiphertextAdopter(roots)
        listOf(CapsuleArtifactKind.CONTENT_MANIFEST, CapsuleArtifactKind.PHOTO).forEach { kind ->
            val bytes = "replay-${kind.name}".toByteArray()
            val blob = blobs.getValue(kind)
            val source = source(blob, "${kind.name.lowercase()}.tmp", bytes)
            val request = request(kind, if (kind == CapsuleArtifactKind.PHOTO) 0 else -1, blob, source, bytes)

            assertTrue(adopter.adopt(request) is IncomingCiphertextAdoptionResult.Adopted)
            val replay = adopter.adopt(request)

            assertTrue(replay is IncomingCiphertextAdoptionResult.Adopted)
            assertArrayEquals(bytes, destination(blob).readBytes())
        }
    }

    @Test
    fun genericMismatchAndUnsafeSourceNeverReplaceOrDelete() = runBlocking {
        val adopter = IncomingCiphertextAdopter(roots)
        val blob = blobs.getValue(CapsuleArtifactKind.CONTENT_MANIFEST)
        val expected = "expected-content".toByteArray()
        val destination = destination(blob).apply {
            parentFile!!.mkdirs()
            writeBytes("existing-winner".toByteArray())
        }
        val source = source(blob, "mismatch.tmp", expected)

        val conflict = adopter.adopt(
            request(CapsuleArtifactKind.CONTENT_MANIFEST, -1, blob, source, expected),
        )
        assertEquals(
            IncomingCiphertextAdoptionFailure.DESTINATION_CONFLICT,
            (conflict as IncomingCiphertextAdoptionResult.Failure).reason,
        )
        assertArrayEquals("existing-winner".toByteArray(), destination.readBytes())
        assertArrayEquals(expected, source.readBytes())

        val outside = File(filesDir, "outside-content.tmp").apply { writeBytes(expected) }
        val unsafe = adopter.adopt(
            request(CapsuleArtifactKind.PHOTO, 0, blob, outside, expected),
        )
        assertEquals(
            IncomingCiphertextAdoptionFailure.SOURCE_OUTSIDE_OWNER_TEMP,
            (unsafe as IncomingCiphertextAdoptionResult.Failure).reason,
        )
        assertArrayEquals(expected, outside.readBytes())
        assertArrayEquals("existing-winner".toByteArray(), destination.readBytes())
    }

    @Test
    fun sameDestinationConcurrentGenericAdoptersHaveOneExactWinner() = runBlocking {
        val bytes = ByteArray(128 * 1024) { (it * 17).toByte() }
        val blob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b199")
        val sourceA = source(blob, "a.tmp", bytes)
        val sourceB = source(blob, "b.tmp", bytes)
        val adopter = IncomingCiphertextAdopter(roots)

        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    adopter.adopt(request(CapsuleArtifactKind.PHOTO, 0, blob, sourceA, bytes))
                },
                async(Dispatchers.Default) {
                    adopter.adopt(request(CapsuleArtifactKind.PHOTO, 0, blob, sourceB, bytes))
                },
            ).awaitAll()
        }

        assertTrue(results.all { it is IncomingCiphertextAdoptionResult.Adopted })
        assertArrayEquals(bytes, destination(blob).readBytes())
        assertFalse(sourceA.exists())
        assertFalse(sourceB.exists())
    }

    private fun assertSizeRejected(
        kind: CapsuleArtifactKind,
        ordinal: Int,
        size: Long,
    ) {
        try {
            IncomingCiphertextAdoptionRequest(
                ownerUserId = owner,
                capsuleId = capsule,
                blobId = blobs.getValue(kind),
                expectedSizeBytes = size,
                expectedSha256 = ByteArray(32),
                sourceTempFile = source(blobs.getValue(kind), "limit.tmp", byteArrayOf(1)),
                artifactKind = kind,
                ordinal = ordinal,
            )
            throw AssertionError("size above $kind limit must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure before any filesystem adoption.
        }
    }

    private fun request(
        kind: CapsuleArtifactKind,
        ordinal: Int,
        blob: BlobId,
        source: File,
        bytes: ByteArray,
    ) = IncomingCiphertextAdoptionRequest(
        ownerUserId = owner,
        capsuleId = capsule,
        blobId = blob,
        expectedSizeBytes = bytes.size.toLong(),
        expectedSha256 = sha256(bytes),
        sourceTempFile = source,
        artifactKind = kind,
        ordinal = ordinal,
    )

    private fun source(blob: BlobId, name: String, bytes: ByteArray): File = File(
        roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP),
        "${blob.toRestString()}/$name",
    ).apply {
        parentFile!!.mkdirs()
        writeBytes(bytes)
    }

    private fun destination(blob: BlobId): File = File(
        File(
            File(roots.child(owner, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT), "capsules"),
            capsule.toRestString(),
        ),
        "blobs/${blob.toRestString()}.ciphertext",
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
