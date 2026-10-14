package dev.hryshyn.remanence.pending

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.PendingArtifactCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import dev.hryshyn.remanence.protocol.delivery_v2.ArtifactBindingV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopeContextV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopePlaintextV2
import dev.hryshyn.remanence.protocol.delivery_v2.KeyCommitmentInput
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T186 M14d-scan: the admitted-row scan bridge with REAL crypto end
 * to end (generated keys, sealed kind-1 FRONT under the genuine
 * target AAD, full S/R/envelope admission). Staged rows enumerate as
 * PENDING candidates with round-tripping FRONTs; tampered/foreign
 * rows never enumerate; unknown rows never verify.
 */
class PendingAdmittedCandidateProviderTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val sender = "01010101-0202-0303-0404-050505050505"
    private val recipient = "33333333-4444-5555-6666-777777777777"
    private val bundle = "01010101-0202-0303-0404-050505050506"
    private val serviceId = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val delivery = "0198f0a0-0000-7000-8000-00000000d401"
    private val claim = "55555555-6666-7777-8888-999999999999"
    private val recognitionBlobId = "cccccccc-dddd-eeee-ffff-000000000001"

    private fun uuidBytes(raw: String): ByteArray {
        val uuid = UUID.fromString(raw)
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Valid serialized SIFT FRONT (same construction as the e2e flow). */
    private fun syntheticFront(seed: Int): ByteArray {
        val profile = dev.hryshyn.remanence.core.recognition.RecognitionProfile.postcardSiftRootSiftV1()
        val keypoints = List(64) {
            dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint(
                xMicro = Math.rint((it % 8) / 8.0 * dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                yMicro = Math.rint((it / 8) / 8.0 * dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                scaleMicro = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.MICRO_UNITS,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        val fp = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint(
            profileId = profile.profileId,
            canonicalWidthPx = profile.capture.canonicalLongEdgePx,
            canonicalHeightPx = 1000,
            coarseHash64 = seed.toLong(),
            keypoints = keypoints,
            quantizedSiftDescriptors = List(64) { i ->
                ByteArray(dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) {
                    ((it * 7 + i * 13 + seed * 29).coerceIn(1, 255)).toByte()
                }
            },
        )
        return try {
            dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.serialize(fp)
        } finally {
            fp.wipe()
        }
    }

    private data class Vectors(
        val row: PendingAdmittedGiftStore.AdmittedGift,
        val frontBytes: ByteArray,
        val senderPublicBytes: ByteArray,
        val recipientPrivate: KeysetHandle,
        val recipientPublicBytes: ByteArray,
        val servicePin: PendingServicePin,
    )

    private fun vectors(frontBytes: ByteArray = syntheticFront(11)): Vectors {
        TinkPrimitives.ensureRegistered()
        val now = System.currentTimeMillis() / 1000L
        val senderKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val senderPublicBytes =
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(senderKeys.publicKeysetHandle)
        val serviceKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val servicePublicB64 = Base64.getEncoder().encodeToString(
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(serviceKeys.publicKeysetHandle),
        )
        val recipientPrivate = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val recipientPublicBytes =
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(recipientPrivate.publicKeysetHandle)
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val rawKey = TinkProtoKeysetFormat.serializeKeyset(capsuleKey, InsecureSecretKeyAccess.get())
        val opening = AddressOpening.newBuilder()
            .setNormalizationVersion(1)
            .setChannel(1)
            .setNormalizedDestination("a42@example.test")
            .setSalt(ByteString.copyFrom(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            .build()
        val d = sha256(
            "postmark/delivery-address/v1".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + opening.toByteArray(),
        )
        val kcInput = KeyCommitmentInput.newBuilder()
            .setVersion(1)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .build().toByteArray()
        val kc = sha256(
            "postmark/delivery-key/v1".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + kcInput,
        )
        // The staged kind-1 ciphertext: genuine recognition plaintext
        // sealed under the genuine delivery-target AAD.
        val recognitionPlaintext = RecognitionManifestCodec().buildPlaintext(
            CapsuleId(UUID.fromString(capsule)),
            "mykola",
            now - 3600L,
            null,
            frontBytes,
        )
        val recognitionCiphertext = PendingArtifactCryptor.encrypt(
            capsuleKey, capsule, recognitionBlobId,
            1, -1, sender, target, recognitionPlaintext,
        )
        val statement = PendingPublishStatementV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setSenderUserId(ByteString.copyFrom(uuidBytes(sender)))
            .setSenderKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setKeyCommitment(ByteString.copyFrom(kc))
            .setCreatedAtEpochSeconds(now - 3600L)
            .setClaimExpiresAtEpochSeconds(now + 86400L)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(uuidBytes(serviceId)))
            .addArtifacts(
                ArtifactBindingV2.newBuilder()
                    .setBlobId(ByteString.copyFrom(uuidBytes(recognitionBlobId)))
                    .setKind(1)
                    .setOrdinal(-1)
                    .setCiphertextSize(recognitionCiphertext.size.toLong())
                    .setCiphertextSha256(ByteString.copyFrom(sha256(recognitionCiphertext)))
                    .build(),
            )
            .build().toByteArray()
        val senderSig = senderKeys.getPrimitive(PublicKeySign::class.java).sign(
            "postmark/pending-publish/v2".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + statement,
        )
        val claimBytes = ClaimBindingV1.newBuilder()
            .setBindingVersion(1)
            .setCapsuleProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(recipient)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setRecipientEncryptionPublicKeysetSha256(
                ByteString.copyFrom(sha256(recipientPublicBytes)),
            )
            .setClaimId(ByteString.copyFrom(uuidBytes(claim)))
            .setClaimedAtEpochSeconds(now)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(uuidBytes(serviceId)))
            .build().toByteArray()
        val contextRaw = ClaimedEnvelopeContextV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setSenderUserId(ByteString.copyFrom(uuidBytes(sender)))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(recipient)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .setClaimBindingSha256(ByteString.copyFrom(sha256(claimBytes)))
            .build().toByteArray()
        val plaintext = ClaimedEnvelopePlaintextV2.newBuilder()
            .setBinding(
                ClaimedEnvelopeContextV2.newBuilder()
                    .setProtocolVersion(2)
                    .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
                    .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
                    .setSenderUserId(ByteString.copyFrom(uuidBytes(sender)))
                    .setRecipientUserId(ByteString.copyFrom(uuidBytes(recipient)))
                    .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
                    .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
                    .setClaimBindingSha256(ByteString.copyFrom(sha256(claimBytes)))
                    .build(),
            )
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .setAddressOpening(opening)
            .build().toByteArray()
        val info = "postmark/claimed-envelope/v2".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + contextRaw
        val sealed = recipientPrivate.publicKeysetHandle
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(plaintext, info)
        val claimSig = serviceKeys.getPrimitive(PublicKeySign::class.java).sign(
            "postmark/claim-binding/v1".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + claimBytes,
        )
        val enc = Base64.getEncoder()
        val row = PendingAdmittedGiftStore.AdmittedGift(
            ownerUserId = recipient,
            capsuleId = capsule,
            deliveryId = delivery,
            deliveryTargetId = target,
            claimId = claim,
            availableAt = now,
            envelopeB64 = enc.encodeToString(sealed),
            envelopeSha256Hex = sha256(sealed).joinToString("") { "%02x".format(it) },
            statementB64 = enc.encodeToString(statement),
            statementSha256Hex = sha256(statement).joinToString("") { "%02x".format(it) },
            publishSignature69B64 = enc.encodeToString(senderSig),
            claimBindingB64 = enc.encodeToString(claimBytes),
            claimSignature69B64 = enc.encodeToString(claimSig),
            recipientKeyBundleId = bundle,
            envelopeRef = "/v2/pending-deliveries/$delivery/claim",
            manifestRef = "/v2/pending-deliveries/$delivery/manifest",
            blobRefs = emptyList(),
            completedAt = null,
            admittedAtMillis = System.currentTimeMillis(),
            recognitionCiphertextB64 = enc.encodeToString(recognitionCiphertext),
        )
        return Vectors(
            row, frontBytes, senderPublicBytes, recipientPrivate,
            recipientPublicBytes, PendingServicePin(serviceId, servicePublicB64),
        )
    }

    private fun bridge(
        dir: java.io.File,
        vectors: Vectors,
        featureEnabled: Boolean = true,
        directoryThrows: Boolean = false,
        recipientThrows: Boolean = false,
    ) =
        PendingAdmittedCandidateProvider(
            admittedStore = PendingAdmittedGiftStore(dir),
            directoryPort = PendingClaimIngest.DirectoryPort { bundleId ->
                if (directoryThrows) throw AssertionError("directory must not be consulted")
                assertEquals(bundle, bundleId)
                PendingClaimIngest.DirectoryBundle(sender, vectors.senderPublicBytes)
            },
            recipientKeysPort = PendingClaimIngest.RecipientKeysPort {
                if (recipientThrows) throw AssertionError("recipient keys must not be consulted")
                PendingClaimIngest.RecipientKeys(
                    recipient, bundle,
                    vectors.recipientPrivate, vectors.recipientPublicBytes,
                )
            },
            servicePinPort = { vectors.servicePin },
            featureEnabled = { featureEnabled },
        )

    @Test
    fun loadsStagedFrontAsPendingCandidate() = runTest {
        val vectors = vectors()
        val dir = Files.createTempDirectory("pending-bridge").toFile()
        try {
            assertTrue(PendingAdmittedGiftStore(dir).upsert(vectors.row))
            val provider = bridge(dir, vectors)
            assertTrue(provider.verify(recipient, UUID.fromString(capsule)))
            val index = provider.load(recipient)
            try {
                assertEquals(1, index.candidates.size)
                val candidate = index.candidates.single()
                assertEquals(UUID.fromString(capsule), candidate.capsuleId)
                assertEquals(
                    CapsulePresentationSource.PENDING,
                    index.presentationSources[candidate.capsuleId],
                )
                assertTrue(index.chooserHints.isEmpty())
                // The enumerated FRONT round-trips the sealed bytes.
                val roundTripped =
                    dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.serialize(
                        candidate.front,
                    )
                assertArrayEquals(vectors.frontBytes, roundTripped)
                assertEquals(1, index.diagnostics.rawCandidateCount)
                assertEquals(1, index.diagnostics.validCandidateCount)
            } finally {
                index.wipeFingerprints()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tamperedStagedBytesEnumerateNothing() = runTest {
        val vectors = vectors()
        val tampered = Base64.getDecoder().decode(vectors.row.recognitionCiphertextB64)
            .copyOf().also { it[0] = (it[0] + 1).toByte() }
        val row = vectors.row.copy(
            recognitionCiphertextB64 = Base64.getEncoder().encodeToString(tampered),
        )
        val dir = Files.createTempDirectory("pending-bridge-tampered").toFile()
        try {
            assertTrue(PendingAdmittedGiftStore(dir).upsert(row))
            val provider = bridge(dir, vectors)
            val index = provider.load(recipient)
            try {
                assertTrue(index.candidates.isEmpty())
                assertEquals(1, index.diagnostics.rawCandidateCount)
                assertEquals(0, index.diagnostics.validCandidateCount)
                assertEquals(1, index.diagnostics.invalidCandidateCount)
            } finally {
                index.wipeFingerprints()
            }
            // Admission itself is untouched by staged bytes: the row
            // still verifies (separation of enumeration vs admission).
            assertTrue(provider.verify(recipient, UUID.fromString(capsule)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownAndForeignRowsNeverVerify() = runTest {
        val vectors = vectors()
        val dir = Files.createTempDirectory("pending-bridge-foreign").toFile()
        try {
            assertTrue(PendingAdmittedGiftStore(dir).upsert(vectors.row))
            val provider = bridge(dir, vectors)
            assertFalse(
                provider.verify(recipient, UUID.fromString("99999999-2222-3333-4444-555555555555")),
            )
            assertTrue(provider.load("99999999-2222-3333-4444-555555555555").candidates.isEmpty())
            assertTrue(provider.load("").candidates.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14d-scan: frozen OFF performs zero pending-path calls —
     * even with a staged row present, enumeration is empty,
     * verification is false, and no port is consulted.
     */
    @Test
    fun featureOffPerformsNoPortCalls() = runTest {
        val vectors = vectors()
        val dir = Files.createTempDirectory("pending-bridge-off").toFile()
        try {
            assertTrue(PendingAdmittedGiftStore(dir).upsert(vectors.row))
            val provider = bridge(
                dir, vectors,
                featureEnabled = false,
                directoryThrows = true,
                recipientThrows = true,
            )
            val index = provider.load(recipient)
            assertTrue(index.candidates.isEmpty())
            assertFalse(provider.verify(recipient, UUID.fromString(capsule)))
            try {
                provider.openKey(recipient, UUID.fromString(capsule))
                fail("expected feature-disabled throw")
            } catch (_: IllegalStateException) {
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14d-scan: an empty index short-circuits before any key
     * fetch — enumeration needs no ports when there is nothing to
     * enumerate.
     */
    @Test
    fun emptyStoreShortCircuitsBeforeKeys() = runTest {
        val vectors = vectors()
        val dir = Files.createTempDirectory("pending-bridge-empty").toFile()
        try {
            val provider = bridge(
                dir, vectors,
                directoryThrows = true,
                recipientThrows = true,
            )
            val index = provider.load(recipient)
            assertTrue(index.candidates.isEmpty())
            assertEquals(0, index.diagnostics.rawCandidateCount)
            assertEquals(0, index.diagnostics.validCandidateCount)
            assertEquals(0, index.diagnostics.invalidCandidateCount)
        } finally {
            dir.deleteRecursively()
        }
    }
}
