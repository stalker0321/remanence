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
 * must declare exactly one RECOGNITION_MANIFEST binding with ordinal
 * -1 and that binding must match the supplied recognition blob
 * identity, the byte length of the supplied ciphertext, and the
 * SHA-256 of the supplied ciphertext. Other content/photo bindings
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

    private fun fullRecognitionBinding(
        ciphertext: ByteArray = recognitionCiphertext(),
    ): ArtifactBinding = binding(
        recognitionBlobId,
        ArtifactKind.RECOGNITION_MANIFEST,
        -1,
        size = ciphertext.size.toLong(),
        hash = sha256(ciphertext),
    )

    private fun recognitionCiphertext(): ByteArray = ByteArray(100) { (it + 1).toByte() }

    private fun fullStatement(): List<ArtifactBinding> = listOf(
        fullRecognitionBinding(),
        binding(contentBlobId, ArtifactKind.CONTENT_MANIFEST, -1, 200L, sha256("content".toByteArray())),
        binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        binding(photo2BlobId, ArtifactKind.PHOTO, 1, 301L, sha256("p2".toByteArray())),
        binding(photo3BlobId, ArtifactKind.PHOTO, 2, 302L, sha256("p3".toByteArray())),
    )

    @Test
    fun fullStatementWithMatchingRecognitionMatches() {
        val ciphertext = recognitionCiphertext()
        val result = verifier.matchesRecognition(
            bindings = fullStatement(),
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertext = ciphertext,
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
                recognitionCiphertext = recognitionCiphertext(),
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
                recognitionCiphertext = recognitionCiphertext(),
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
                recognitionCiphertext = recognitionCiphertext(),
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
                recognitionCiphertext = recognitionCiphertext(),
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
                recognitionCiphertext = recognitionCiphertext(),
            ),
        )
    }

    @Test
    fun wrongRecognitionSizeRejects() {
        val statement = listOf(
            binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, 100L, sha256("recognition".toByteArray())),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        val ciphertext = ByteArray(99) { 0x33 }
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertext = ciphertext,
            ),
        )
    }

    @Test
    fun wrongRecognitionHashRejects() {
        val ciphertext = recognitionCiphertext()
        val differentCiphertext = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val statement = listOf(
            fullRecognitionBinding(ciphertext),
            binding(photo1BlobId, ArtifactKind.PHOTO, 0, 300L, sha256("p1".toByteArray())),
        )
        assertFalse(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertext = differentCiphertext,
            ),
        )
    }

    @Test
    fun nonFullStatementStillAcceptsRecognitionWhenItMatches() {
        val ciphertext = recognitionCiphertext()
        val statement = listOf(fullRecognitionBinding(ciphertext))
        assertTrue(
            verifier.matchesRecognition(
                bindings = statement,
                recognitionBlobId = recognitionBlobId,
                recognitionCiphertext = ciphertext,
            ),
        )
    }

    @Test
    fun fullStatementMissingAllContentAndPhotosStillAcceptsRecognition() {
        val ciphertext = recognitionCiphertext()
        val statement = listOf(fullRecognitionBinding(ciphertext))
        val result = verifier.matchesRecognition(
            bindings = statement,
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertext = ciphertext,
        )
        assertTrue(result)
    }

    @Test
    fun exactShortCiphertextWithMatchingSignedSizeAndHashPasses() {
        val ciphertext = ByteArray(8) { (it + 1).toByte() }
        val statement = listOf(fullRecognitionBinding(ciphertext))
        val result = verifier.matchesRecognition(
            bindings = statement,
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertext = ciphertext,
        )
        assertTrue(result)
    }

    @Test
    fun exactEmptyCiphertextWithMatchingSignedSizeAndHashPasses() {
        val ciphertext = ByteArray(0)
        val statement = listOf(fullRecognitionBinding(ciphertext))
        val result = verifier.matchesRecognition(
            bindings = statement,
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertext = ciphertext,
        )
        assertTrue(result)
    }

    @Test
    fun changedShortCiphertextStillRejects() {
        val signed = ByteArray(8) { (it + 1).toByte() }
        val delivered = signed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val statement = listOf(fullRecognitionBinding(signed))
        val result = verifier.matchesRecognition(
            bindings = statement,
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertext = delivered,
        )
        assertFalse(result)
    }
}
