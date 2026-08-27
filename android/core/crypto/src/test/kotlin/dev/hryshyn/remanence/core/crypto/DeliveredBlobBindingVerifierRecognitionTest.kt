package dev.hryshyn.remanence.core.crypto

import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.protocol.v1.ArtifactBinding
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-P11 focused tests for the recognition-index binding check used by
 * [ControlIndexAcceptanceGate]. Verifies the contract: the statement
 * must declare exactly one RECOGNITION_MANIFEST binding with ordinal -1
 * and that binding must match the supplied recognition blob identity,
 * byte size, and constant-time SHA-256. Other content/photo bindings
 * are not consulted.
 */
class DeliveredBlobBindingVerifierRecognitionTest {

    private val verifier = DeliveredBlobBindingVerifier()

    private val recognitionBlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000001"))
    private val contentBlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000002"))
    private val photo1BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000003"))
    private val photo2BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000004"))
    private val photo3BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000005"))

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun binding(
        blobId: BlobId,
        kind: ArtifactKind,
        ordinal: Int,
        size: Long = 100L,
        hash: ByteArray = sha256("blob-$blobId".toByteArray()),
    ): ArtifactBinding = ArtifactBinding.newBuilder()
        .setBlobId(blobId.toProtoBytes())
        .setKind(kind)
        .setOrdinal(ordinal)
        .setCiphertextSize(size)
        .setCiphertextSha256(ByteString.copyFrom(hash))
        .build()

    private fun fullRecognitionBinding(size: Long = 100L, hash: ByteArray = sha256("recognition".toByteArray())) =
        binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, size, hash)

    private fun fullStatement(): List<ArtifactBinding> = listOf(
        fullRecognitionBinding(),
        binding(contentBlobId, ArtifactKind.CONTENT_MANIFEST, -1, 200L, sha256("content".toByteArray())),
        binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        binding(photo2BlobId, ArtifactKind.PHOTO, 1, 301L, sha256("p2".toByteArray())),
        binding(photo3BlobId, ArtifactKind.PHOTO, 2, 302L, sha256("p3".toByteArray())),
    )

    @Test
    fun fullStatementWithMatchingRecognitionMatches() {
        val recognition = fullRecognitionBinding()
        val result = verifier.matchesRecognition(
            bindings = fullStatement(),
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertextSize = recognition.ciphertextSize,
            recognitionCiphertextSha256 = recognition.ciphertextSha256.toByteArray(),
        )
        assertTrue(result)
    }

    @Test
    fun missingRecognitionBindingRejects() {
        val statement = listOf(
            binding(contentBlobId, ArtifactKind.CONTENT_MANIFEST, -1, 200L, sha256("content".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 100L,
                recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
            ),
        )
    }

    @Test
    fun wrongRecognitionBlobIdRejects() {
        val statement = listOf(
            binding(contentBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, 100L, sha256("recognition".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 100L,
                recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
            ),
        )
    }

    @Test
    fun wrongKindRejects() {
        val statement = listOf(
            binding(recognitionBlobId, ArtifactKind.CONTENT_MANIFEST, -1, 100L, sha256("recognition".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 100L,
                recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
            ),
        )
    }

    @Test
    fun wrongOrdinalRejects() {
        val statement = listOf(
            binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, 0, 100L, sha256("recognition".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 100L,
                recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
            ),
        )
    }

    @Test
    fun duplicateRecognitionBindingRejects() {
        val statement = listOf(
            binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, 100L, sha256("recognition".toByteArray())),
            binding(contentBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, 101L, sha256("recognition2".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 100L,
                recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
            ),
        )
    }

    @Test
    fun wrongRecognitionSizeRejects() {
        val statement = listOf(
            binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, 100L, sha256("recognition".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 999L,
                recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
            ),
        )
    }

    @Test
    fun wrongRecognitionHashRejects() {
        val statement = listOf(
            binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, 100L, sha256("recognition".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 100L,
                recognitionCiphertextSha256 = sha256("evil".toByteArray()),
            ),
        )
    }

    @Test
    fun nonFullStatementStillAcceptsRecognitionWhenItMatches() {
        val statement = listOf(fullRecognitionBinding())
        assertTrue(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertextSize = 100L,
                recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
            ),
        )
    }

    @Test
    fun fullStatementMissingAllContentAndPhotosStillAcceptsRecognition() {
        val statement = listOf(fullRecognitionBinding())
        val result = verifier.matchesRecognition(
            bindings = statement,
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertextSize = 100L,
            recognitionCiphertextSha256 = sha256("recognition".toByteArray()),
        )
        assertTrue(result)
    }
}
