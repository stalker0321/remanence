package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId

class PhotoArtifactEncryptorTest {

    private val encryptor = PhotoArtifactEncryptor()
    private lateinit var keyset: KeysetHandle
    private lateinit var wrongKeyset: KeysetHandle

    private val routing = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd1001")),
        blobId = BlobId(UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd000a")),
        senderUserId = UserId(UUID.fromString("3f0a1234-5678-4abc-9def-aabbccdd3003")),
        recipientUserId = UserId(UUID.fromString("4f0a1234-5678-4abc-9def-aabbccdd4004")),
    )

    private val jpeg = ByteArray(4096) { (it * 31).toByte() }

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
        wrongKeyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun roundTripRestoresExactPhotoBytes() {
        val encrypted = encryptor.encryptPhoto(keyset, routing, ordinal = 2, normalizedJpeg = jpeg)
        assertTrue(encrypted.ciphertextSha256.size == 32)
        assertEquals(encrypted.ciphertext.size.toLong(), encrypted.sizeBytes)
        assertContentEquals(jpeg, encryptor.decryptPhoto(keyset, routing, ordinal = 2, encrypted))
    }

    @Test
    fun bindingMatchesCiphertextDigest() {
        val encrypted = encryptor.encryptPhoto(keyset, routing, ordinal = 0, normalizedJpeg = jpeg)
        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest(encrypted.ciphertext),
            encrypted.ciphertextSha256,
        )
    }

    @Test
    fun differentOrdinalsProduceDifferentBindings() {
        val a = encryptor.encryptPhoto(keyset, routing, ordinal = 0, normalizedJpeg = jpeg)
        val b = encryptor.encryptPhoto(keyset, routing, ordinal = 1, normalizedJpeg = jpeg)
        assertTrue(!a.ciphertextSha256.contentEquals(b.ciphertextSha256))
    }

    @Test
    fun wrongOrdinalOnDecryptFailsClosed() {
        val encrypted = encryptor.encryptPhoto(keyset, routing, ordinal = 3, normalizedJpeg = jpeg)
        assertFailsWith<GeneralSecurityException> {
            encryptor.decryptPhoto(keyset, routing, ordinal = 4, encrypted)
        }
    }

    @Test
    fun wrongKeyFailsClosed() {
        val encrypted = encryptor.encryptPhoto(keyset, routing, ordinal = 1, normalizedJpeg = jpeg)
        assertFailsWith<GeneralSecurityException> {
            encryptor.decryptPhoto(wrongKeyset, routing, ordinal = 1, encrypted)
        }
    }

    @Test
    fun emptyAndOversizedPlaintextRejectedBeforeEncryption() {
        assertFailsWith<IllegalArgumentException> {
            encryptor.encryptPhoto(keyset, routing, 0, ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            encryptor.encryptPhoto(keyset, routing, 0, ByteArray(PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES + 1))
        }
    }
}
