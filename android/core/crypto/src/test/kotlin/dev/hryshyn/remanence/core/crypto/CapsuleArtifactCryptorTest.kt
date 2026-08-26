package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID

class CapsuleArtifactCryptorTest {

    private val cryptor = CapsuleArtifactCryptor()
    private lateinit var keyset: KeysetHandle
    private val plaintext = "recognition manifest payload".toByteArray()

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    private fun context(
        capsule: String = "1f0a1234-5678-4abc-9def-aabbccdd1001",
        blob: String = "2f0a1234-5678-4abc-9def-aabbccdd2002",
        kind: CapsuleArtifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
        ordinal: Int = -1,
        sender: String = "3f0a1234-5678-4abc-9def-aabbccdd3003",
        recipient: String = "4f0a1234-5678-4abc-9def-aabbccdd4004",
    ) = ArtifactAadInput(
        capsuleId = CapsuleId(UUID.fromString(capsule)),
        blobId = BlobId(UUID.fromString(blob)),
        artifactKind = kind,
        ordinal = ordinal,
        senderUserId = UserId(UUID.fromString(sender)),
        recipientUserId = UserId(UUID.fromString(recipient)),
    )

    private fun photoContext(ordinal: Int) =
        context(kind = CapsuleArtifactKind.PHOTO, ordinal = ordinal)

    @Test
    fun roundTripReturnsPlaintextForIdenticalContext() {
        val ciphertext = cryptor.encrypt(keyset, context(), plaintext)
        assertContentEquals(plaintext, cryptor.decrypt(keyset, context(), ciphertext))
    }

    @Test
    fun ciphertextDiffersPerBlobIdentityEvenUnderSameKey() {
        val a = cryptor.encrypt(keyset, context(blob = "5f0a1234-5678-4abc-9def-aabbccdd5005"), plaintext)
        val b = cryptor.encrypt(keyset, context(blob = "6f0a1234-5678-4abc-9def-aabbccdd6006"), plaintext)
        assertTrue(!a.contentEquals(b))
    }

    private fun assertDecryptFails(ciphertext: ByteArray, altered: ArtifactAadInput) {
        assertFailsWith<GeneralSecurityException> { cryptor.decrypt(keyset, altered, ciphertext) }
    }

    @Test
    fun wrongCapsuleIdFailsClosed() {
        val ciphertext = cryptor.encrypt(keyset, context(), plaintext)
        assertDecryptFails(
            ciphertext,
            context(capsule = "9f0a1234-5678-4abc-9def-aabbccdd9999"),
        )
    }

    @Test
    fun wrongBlobIdFailsClosed() {
        val ciphertext = cryptor.encrypt(keyset, context(), plaintext)
        assertDecryptFails(
            ciphertext,
            context(blob = "8f0a1234-5678-4abc-9def-aabbccdd8888"),
        )
    }

    @Test
    fun wrongArtifactKindFailsClosed() {
        val ciphertext = cryptor.encrypt(keyset, context(), plaintext)
        assertDecryptFails(
            ciphertext,
            context(kind = CapsuleArtifactKind.CONTENT_MANIFEST),
        )
    }

    @Test
    fun wrongOrdinalFailsClosedForPhoto() {
        val ciphertext = cryptor.encrypt(keyset, photoContext(ordinal = 0), plaintext)
        assertDecryptFails(ciphertext, photoContext(ordinal = 1))
    }

    @Test
    fun wrongSenderOrRecipientFailsClosed() {
        val ciphertext = cryptor.encrypt(keyset, context(), plaintext)
        assertDecryptFails(
            ciphertext,
            context(sender = "7f0a1234-5678-4abc-9def-aabbccdd7777"),
        )
        assertDecryptFails(
            ciphertext,
            context(recipient = "6f0a1234-5678-4abc-9def-aabbccdd6666"),
        )
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val ciphertext = cryptor.encrypt(keyset, context(), plaintext)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        assertFailsWith<GeneralSecurityException> { cryptor.decrypt(keyset, context(), ciphertext) }
    }

    @Test
    fun invalidOrdinalsRejectedBeforePrimitiveInvocation() {
        // PHOTO ordinals must be 0..4.
        for (bad in intArrayOf(-1, 5)) {
            assertFailsWith<IllegalArgumentException> {
                cryptor.encrypt(keyset, photoContext(ordinal = bad), plaintext)
            }
        }
        // Non-photo artifacts must use the sentinel -1.
        assertFailsWith<IllegalArgumentException> {
            cryptor.encrypt(keyset, context(ordinal = 0), plaintext)
        }
    }
}
