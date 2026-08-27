package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.protocol.v1.ArtifactBinding
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-P12a focused tests for the full-material binding check used by the
 * future presentation gate. [DeliveredBlobBindingVerifier.matchesFullCoverage]
 * requires exact one-to-one coverage of every signed [ArtifactBinding],
 * derives ciphertext size and SHA-256 solely from the actual bytes the
 * caller holds, and rejects missing, extra, duplicate IDs, wrong
 * size/hash, and substitution. No caller-supplied size or digest is
 * trusted.
 */
class DeliveredBlobBindingVerifierFullCoverageTest {

    private val verifier = DeliveredBlobBindingVerifier()

    private val recognitionBlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000001"))
    private val contentBlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000002"))
    private val photo1BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000003"))
    private val photo2BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000004"))
    private val photo3BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000005"))

    private val capsuleId = dev.hryshyn.remanence.core.model.CapsuleId(
        UUID.fromString("1a111111-2222-4333-8444-555555555555"),
    )
    private val senderUser = dev.hryshyn.remanence.core.model.UserId(
        UUID.fromString("2a222222-3333-4444-8555-666666666666"),
    )
    private val recipientUser = dev.hryshyn.remanence.core.model.UserId(
        UUID.fromString("3a333333-4444-4555-8666-777777777777"),
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun binding(
        blobId: BlobId,
        kind: ArtifactKind,
        ordinal: Int,
        ciphertext: ByteArray,
    ): ArtifactBinding = ArtifactBinding.newBuilder()
        .setBlobId(blobId.toProtoBytes())
        .setKind(kind)
        .setOrdinal(ordinal)
        .setCiphertextSize(ciphertext.size.toLong())
        .setCiphertextSha256(ByteString.copyFrom(sha256(ciphertext)))
        .build()

    private fun fullBindings(ciphertexts: Map<BlobId, ByteArray>): List<ArtifactBinding> = listOf(
        binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, ciphertexts.getValue(recognitionBlobId)),
        binding(contentBlobId, ArtifactKind.CONTENT_MANIFEST, -1, ciphertexts.getValue(contentBlobId)),
        binding(photo1BlobId, ArtifactKind.PHOTO, 0, ciphertexts.getValue(photo1BlobId)),
        binding(photo2BlobId, ArtifactKind.PHOTO, 1, ciphertexts.getValue(photo2BlobId)),
        binding(photo3BlobId, ArtifactKind.PHOTO, 2, ciphertexts.getValue(photo3BlobId)),
    )

    private fun fullCiphertexts(): Map<BlobId, ByteArray> = mapOf(
        recognitionBlobId to ByteArray(100) { (it + 1).toByte() },
        contentBlobId to ByteArray(200) { (it + 2).toByte() },
        photo1BlobId to ByteArray(300) { (it + 3).toByte() },
        photo2BlobId to ByteArray(301) { (it + 4).toByte() },
        photo3BlobId to ByteArray(302) { (it + 5).toByte() },
    )

    private fun delivered(ciphertexts: Map<BlobId, ByteArray>): List<DeliveredCiphertext> =
        listOf(
            DeliveredCiphertext(recognitionBlobId, ciphertexts.getValue(recognitionBlobId)),
            DeliveredCiphertext(contentBlobId, ciphertexts.getValue(contentBlobId)),
            DeliveredCiphertext(photo1BlobId, ciphertexts.getValue(photo1BlobId)),
            DeliveredCiphertext(photo2BlobId, ciphertexts.getValue(photo2BlobId)),
            DeliveredCiphertext(photo3BlobId, ciphertexts.getValue(photo3BlobId)),
        )

    @Test
    fun validExactCoverageMatches() {
        val ciphertexts = fullCiphertexts()
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = delivered(ciphertexts),
        )
        assertTrue(result)
    }

    @Test
    fun missingBindingRejects() {
        val ciphertexts = fullCiphertexts()
        val bindings = fullBindings(ciphertexts).drop(1) // drop recognition
        val result = verifier.matchesFullCoverage(
            bindings = bindings,
            delivered = delivered(ciphertexts),
        )
        assertFalse(result)
    }

    @Test
    fun extraDeliveredCiphertextRejects() {
        val ciphertexts = fullCiphertexts()
        val extraId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000099"))
        val extraDelivered = delivered(ciphertexts) + DeliveredCiphertext(extraId, ByteArray(64) { 0x55 })
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = extraDelivered,
        )
        assertFalse(result)
    }

    @Test
    fun missingDeliveredCiphertextRejects() {
        val ciphertexts = fullCiphertexts()
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = delivered(ciphertexts).dropLast(1),
        )
        assertFalse(result)
    }

    @Test
    fun duplicateDeliveredCiphertextIdRejects() {
        val ciphertexts = fullCiphertexts()
        val duplicated = delivered(ciphertexts).toMutableList()
        duplicated[3] = DeliveredCiphertext(photo1BlobId, ciphertexts.getValue(photo1BlobId))
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = duplicated,
        )
        assertFalse(result)
    }

    @Test
    fun wrongBlobIdRejects() {
        val ciphertexts = fullCiphertexts()
        val wrongId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000099"))
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = delivered(ciphertexts).mapIndexed { index, dc ->
                if (index == 0) DeliveredCiphertext(wrongId, dc.ciphertext) else dc
            },
        )
        assertFalse(result)
    }

    @Test
    fun wrongSizeRejects() {
        val ciphertexts = fullCiphertexts()
        val tampered = delivered(ciphertexts).mapIndexed { index, dc ->
            if (index == 0) DeliveredCiphertext(dc.blobId, dc.ciphertext.copyOf(dc.ciphertext.size - 1)) else dc
        }
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = tampered,
        )
        assertFalse(result)
    }

    @Test
    fun wrongHashRejects() {
        val ciphertexts = fullCiphertexts()
        val tampered = delivered(ciphertexts).mapIndexed { index, dc ->
            if (index == 0) {
                DeliveredCiphertext(
                    dc.blobId,
                    dc.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
                )
            } else dc
        }
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = tampered,
        )
        assertFalse(result)
    }

    @Test
    fun substitutionRejects() {
        val ciphertexts = fullCiphertexts()
        val swapped = delivered(ciphertexts).mapIndexed { index, dc ->
            if (index == 0) {
                DeliveredCiphertext(
                    recognitionBlobId,
                    ciphertexts.getValue(photo1BlobId),
                )
            } else dc
        }
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = swapped,
        )
        assertFalse(result)
    }

    @Test
    fun aeadValidAltCiphertextUnderSameKeyAndAadFailsItsOldSignedBinding() {
        val keyset = CapsuleKeysetGenerator().generate()
        val keysetBytes = TinkProtoKeysetFormat.serializeKeyset(keyset, InsecureSecretKeyAccess.get())
        val plaintextA = ByteArray(64) { 0x10 }
        val plaintextB = ByteArray(64) { 0x20 }
        val aad = ArtifactAadInput(
            capsuleId = capsuleId,
            blobId = recognitionBlobId,
            artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
            ordinal = -1,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val ciphertextA = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = aad,
            plaintext = plaintextA,
        )
        val ciphertextB = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = aad,
            plaintext = plaintextB,
        )
        assertFalse(MessageDigest.isEqual(sha256(ciphertextA), sha256(ciphertextB)))
        assertFalse(ciphertextA.contentEquals(ciphertextB))

        val binding = binding(recognitionBlobId, ArtifactKind.RECOGNITION_MANIFEST, -1, ciphertextA)
        val result = verifier.matchesFullCoverage(
            bindings = listOf(binding),
            delivered = listOf(DeliveredCiphertext(recognitionBlobId, ciphertextB)),
        )
        assertFalse(result)
    }

    @Test
    fun aeadValidAltCiphertextInsideFullCoverageRejects() {
        val keyset = CapsuleKeysetGenerator().generate()
        val aad = ArtifactAadInput(
            capsuleId = capsuleId,
            blobId = recognitionBlobId,
            artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
            ordinal = -1,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val realRecognition = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = aad,
            plaintext = ByteArray(64) { 0x10 },
        )
        val altRecognition = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = aad,
            plaintext = ByteArray(64) { 0x20 },
        )
        val realContent = ByteArray(200) { 0x33 }
        val realP1 = ByteArray(300) { 0x44 }
        val realP2 = ByteArray(301) { 0x55 }
        val realP3 = ByteArray(302) { 0x66 }

        val ciphertexts = mapOf(
            recognitionBlobId to realRecognition,
            contentBlobId to realContent,
            photo1BlobId to realP1,
            photo2BlobId to realP2,
            photo3BlobId to realP3,
        )
        val result = verifier.matchesFullCoverage(
            bindings = fullBindings(ciphertexts),
            delivered = listOf(
                DeliveredCiphertext(recognitionBlobId, altRecognition),
                DeliveredCiphertext(contentBlobId, realContent),
                DeliveredCiphertext(photo1BlobId, realP1),
                DeliveredCiphertext(photo2BlobId, realP2),
                DeliveredCiphertext(photo3BlobId, realP3),
            ),
        )
        assertFalse(result)
    }

    @Test
    fun duplicateBindingIdInStatementRejects() {
        val ciphertexts = fullCiphertexts()
        val bindings = fullBindings(ciphertexts).toMutableList()
        bindings[3] = binding(photo1BlobId, ArtifactKind.PHOTO, 0, ciphertexts.getValue(photo2BlobId))
        val result = verifier.matchesFullCoverage(
            bindings = bindings,
            delivered = delivered(ciphertexts),
        )
        assertFalse(result)
    }

    @Test
    fun veryShortCiphertextRejectsBindingHashCheck() {
        val tooShort = ByteArray(16) { 0x77 }
        val binding = binding(photo1BlobId, ArtifactKind.PHOTO, 0, ByteArray(16) { 0x78 })
        val result = verifier.matchesFullCoverage(
            bindings = listOf(binding),
            delivered = listOf(DeliveredCiphertext(photo1BlobId, tooShort)),
        )
        assertFalse(result)
    }
}
