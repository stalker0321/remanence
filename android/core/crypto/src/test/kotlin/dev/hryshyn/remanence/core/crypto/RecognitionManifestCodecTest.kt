package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.security.GeneralSecurityException
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.RecognitionManifest

class RecognitionManifestCodecTest {

    private val codec = RecognitionManifestCodec()
    private lateinit var keyset: KeysetHandle
    private lateinit var otherKeyset: KeysetHandle

    private val routing = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd1001")),
        blobId = BlobId(UUID.fromString("5f0a1234-5678-4abc-9def-aabbccdd5005")),
        senderUserId = UserId(UUID.fromString("3f0a1234-5678-4abc-9def-aabbccdd3003")),
        recipientUserId = UserId(UUID.fromString("4f0a1234-5678-4abc-9def-aabbccdd4004")),
    )

    private val front = ByteArray(96) { (it * 3).toByte() }
    private val back = ByteArray(96) { (it * 7 + 1).toByte() }

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
        otherKeyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun roundTripPreservesEveryFieldIncludingOptionalPlace() {
        val ciphertext = codec.buildAndEncrypt(
            capsuleKeyset = keyset,
            routingContext = routing,
            senderHandleSnapshot = "mykola",
            createdAtEpochSeconds = 1_755_000_000L,
            placeLabel = "Львів",
            frontFingerprint = front,
            backFingerprint = back,
        )

        val content = codec.decryptAndParse(keyset, routing, ciphertext)

        assertEquals(1, content.protocolVersion)
        assertContentEquals(routing.capsuleId.toProtoBytes().toByteArray(), content.capsuleIdRaw)
        assertEquals("mykola", content.senderHandleSnapshot)
        assertEquals(1_755_000_000L, content.createdAtEpochSeconds)
        assertEquals("Львів", content.placeLabel)
        assertContentEquals(front, content.frontFingerprint)
        assertContentEquals(back, content.backFingerprint)
    }

    @Test
    fun absentPlaceLabelStaysAbsent() {
        val ciphertext = codec.buildAndEncrypt(
            capsuleKeyset = keyset,
            routingContext = routing,
            senderHandleSnapshot = "mykola",
            createdAtEpochSeconds = 5L,
            placeLabel = null,
            frontFingerprint = front,
            backFingerprint = back,
        )
        assertEquals(null, codec.decryptAndParse(keyset, routing, ciphertext).placeLabel)
    }

    @Test
    fun wrongInnerCapsuleIdFailsClosedAfterValidReencryption() {
        val otherCapsule = CapsuleId(UUID.fromString("9f0a1234-5678-4abc-9def-aabbccdd9999"))
        val manifest = decryptedManifest().toBuilder()
            .setCapsuleId(otherCapsule.toProtoBytes())
            .build()

        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, encryptManifest(manifest))
        }
    }

    @Test
    fun nonCanonicalSenderHandleFailsClosedAfterValidReencryption() {
        val manifest = decryptedManifest().toBuilder()
            .setChooserHint(
                decryptedManifest().chooserHint.toBuilder()
                    .setSenderHandleSnapshot("@Mykola")
                    .build(),
            )
            .build()

        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, encryptManifest(manifest))
        }
    }

    @Test
    fun invalidSenderHandleFailsClosedAfterValidReencryption() {
        val original = decryptedManifest()
        val manifest = original.toBuilder()
            .setChooserHint(original.chooserHint.toBuilder().setSenderHandleSnapshot("bad-name").build())
            .build()

        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, encryptManifest(manifest))
        }
    }

    @Test
    fun oversizedUtf8PlaceLabelFailsClosedAfterValidReencryption() {
        val original = decryptedManifest()
        val manifest = original.toBuilder()
            .setChooserHint(original.chooserHint.toBuilder().setPlaceLabel("м".repeat(61)).build())
            .build()

        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, encryptManifest(manifest))
        }
    }

    @Test
    fun emptyFrontFingerprintFailsClosedAfterValidReencryption() {
        val manifest = decryptedManifest().toBuilder().clearFrontFingerprint().build()

        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, encryptManifest(manifest))
        }
    }

    @Test
    fun emptyBackFingerprintFailsClosedAfterValidReencryption() {
        val manifest = decryptedManifest().toBuilder().clearBackFingerprint().build()

        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, encryptManifest(manifest))
        }
    }

    @Test
    fun malformedProtobufPlaintextFailsClosed() {
        // A valid AEAD wrapper around a truncated length-delimited protobuf
        // field must still normalize parsing failure to GeneralSecurityException.
        val ciphertext = encryptPlaintext(byteArrayOf(0x0a, 0x80.toByte()))

        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun wrongCapsuleKeyFailsClosed() {
        val ciphertext = codec.buildAndEncrypt(
            capsuleKeyset = keyset,
            routingContext = routing,
            senderHandleSnapshot = "mykola",
            createdAtEpochSeconds = 1L,
            placeLabel = null,
            frontFingerprint = front,
            backFingerprint = back,
        )
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(otherKeyset, routing, ciphertext)
        }
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val ciphertext = codec.buildAndEncrypt(
            capsuleKeyset = keyset,
            routingContext = routing,
            senderHandleSnapshot = "mykola",
            createdAtEpochSeconds = 1L,
            placeLabel = null,
            frontFingerprint = front,
            backFingerprint = back,
        )
        ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2].toInt() xor 1).toByte()
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun oversizedPlaceLabelRejectedBeforeEncryption() {
        assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncrypt(
                capsuleKeyset = keyset,
                routingContext = routing,
                senderHandleSnapshot = "mykola",
                createdAtEpochSeconds = 1L,
                placeLabel = "м".repeat(61), // 61 * 2 UTF-8 bytes = 122 > 120
                frontFingerprint = front,
                backFingerprint = back,
            )
        }
    }

    @Test
    fun nonCanonicalSenderHandleRejectedBeforeEncryption() {
        for (handle in listOf("@mykola", "Mykola", "ab")) {
            assertFailsWith<IllegalArgumentException> {
                codec.buildAndEncrypt(
                    capsuleKeyset = keyset,
                    routingContext = routing,
                    senderHandleSnapshot = handle,
                    createdAtEpochSeconds = 1L,
                    placeLabel = null,
                    frontFingerprint = front,
                    backFingerprint = back,
                )
            }
        }
    }

    @Test
    fun missingFingerprintRejected() {
        assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncrypt(
                capsuleKeyset = keyset,
                routingContext = routing,
                senderHandleSnapshot = "mykola",
                createdAtEpochSeconds = 1L,
                placeLabel = null,
                frontFingerprint = ByteArray(0),
                backFingerprint = back,
            )
        }
    }

    @Test
    fun missingBackFingerprintRejected() {
        assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncrypt(
                capsuleKeyset = keyset,
                routingContext = routing,
                senderHandleSnapshot = "mykola",
                createdAtEpochSeconds = 1L,
                placeLabel = null,
                frontFingerprint = front,
                backFingerprint = ByteArray(0),
            )
        }
    }

    private fun decryptedManifest(): RecognitionManifest = RecognitionManifest.parseFrom(
        CapsuleArtifactCryptor().decrypt(keyset, recognitionAad(), validCiphertext()),
    )

    private fun validCiphertext(): ByteArray = codec.buildAndEncrypt(
        capsuleKeyset = keyset,
        routingContext = routing,
        senderHandleSnapshot = "mykola",
        createdAtEpochSeconds = 1L,
        placeLabel = null,
        frontFingerprint = front,
        backFingerprint = back,
    )

    private fun encryptManifest(manifest: RecognitionManifest): ByteArray =
        encryptPlaintext(manifest.toByteArray())

    private fun encryptPlaintext(plaintext: ByteArray): ByteArray = CapsuleArtifactCryptor().encrypt(
        capsuleKeyset = keyset,
        context = recognitionAad(),
        plaintext = plaintext,
    )

    private fun recognitionAad(): ArtifactAadInput = ArtifactAadInput(
        capsuleId = routing.capsuleId,
        blobId = routing.blobId,
        artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
        ordinal = -1,
        senderUserId = routing.senderUserId,
        recipientUserId = routing.recipientUserId,
    )
}
