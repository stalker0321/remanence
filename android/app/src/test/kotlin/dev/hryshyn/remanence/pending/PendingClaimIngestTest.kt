package dev.hryshyn.remanence.pending

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.PendingControlResult
import dev.hryshyn.remanence.core.data.network.PendingRedownloadResult
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopeContextV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopePlaintextV2
import dev.hryshyn.remanence.protocol.delivery_v2.KeyCommitmentInput
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T186 M14b-ingest: claim ingestion with REAL crypto fixtures
 * (generated keys, frozen constructions) — success persists an
 * admitted row; every failure mode leaves the index untouched so the
 * sync keeps its cursor and retries the same page.
 */
class PendingClaimIngestTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val sender = "01010101-0202-0303-0404-050505050505"
    private val recipient = "33333333-4444-5555-6666-777777777777"
    private val bundle = "01010101-0202-0303-0404-050505050506"
    private val serviceId = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val delivery = "0198f0a0-0000-7000-8000-00000000d401"
    private val claim = "55555555-6666-7777-8888-999999999999"

    private fun uuidBytes(raw: String): ByteArray {
        val uuid = UUID.fromString(raw)
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class Crypto(
        val statementB64: String,
        val senderSigB64: String,
        val claimB64: String,
        val claimSigB64: String,
        val envelopeB64: String,
        val envelopeShaHex: String,
        val senderKeys: KeysetHandle,
        val serviceKeys: KeysetHandle,
        val recipientKeys: KeysetHandle,
        val recipientPublicBytes: ByteArray,
        val recognitionBlob: ByteArray,
        val recognitionBlobId: String,
    )

    private fun crypto(forCapsule: String = capsule): Crypto {
        TinkPrimitives.ensureRegistered()
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
        val aesHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val rawKey = TinkProtoKeysetFormat.serializeKeyset(
            aesHandle, com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        val kcInput = KeyCommitmentInput.newBuilder()
            .setVersion(1)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(forCapsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .build().toByteArray()
        val kc = sha256(
            "postmark/delivery-key/v1".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + kcInput,
        )
        val senderKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val serviceKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        // T186 M14d-scan: single kind-1 recognition binding so staging
        // tests exercise the real S declaration path.
        val recognitionBlobId = "cccccccc-dddd-eeee-ffff-000000000001"
        val recognitionBlob =
            "recognition-front-ciphertext-0123456789-abcdef-0123456789-abcdef".toByteArray()
        val statement = PendingPublishStatementV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(forCapsule)))
            .setSenderUserId(ByteString.copyFrom(uuidBytes(sender)))
            .setSenderKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setKeyCommitment(ByteString.copyFrom(kc))
            .setCreatedAtEpochSeconds(1_798_765_000L)
            .setClaimExpiresAtEpochSeconds(1_798_765_432L)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(uuidBytes(serviceId)))
            .addArtifacts(
                dev.hryshyn.remanence.protocol.delivery_v2.ArtifactBindingV2.newBuilder()
                    .setBlobId(ByteString.copyFrom(uuidBytes(recognitionBlobId)))
                    .setKind(1)
                    .setOrdinal(-1)
                    .setCiphertextSize(recognitionBlob.size.toLong())
                    .setCiphertextSha256(ByteString.copyFrom(sha256(recognitionBlob)))
                    .build(),
            )
            .build().toByteArray()
        val recipientKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val recipientPublicBytes =
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(recipientKeys.publicKeysetHandle)
        val claimBytes = ClaimBindingV1.newBuilder()
            .setBindingVersion(1)
            .setCapsuleProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(forCapsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(recipient)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setRecipientEncryptionPublicKeysetSha256(
                ByteString.copyFrom(sha256(recipientPublicBytes)),
            )
            .setClaimId(ByteString.copyFrom(uuidBytes(claim)))
            .setClaimedAtEpochSeconds(1_798_765_100L)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(uuidBytes(serviceId)))
            .build().toByteArray()
        val contextRaw = ClaimedEnvelopeContextV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(forCapsule)))
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
                    .setCapsuleId(ByteString.copyFrom(uuidBytes(forCapsule)))
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
        val sealed = recipientKeys.publicKeysetHandle
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(plaintext, info)
        val enc = Base64.getEncoder()
        return Crypto(
            enc.encodeToString(statement),
            enc.encodeToString(
                senderKeys.getPrimitive(PublicKeySign::class.java).sign(
                    "postmark/pending-publish/v2".toByteArray(Charsets.US_ASCII) +
                        byteArrayOf(0x00) + statement,
                ),
            ),
            enc.encodeToString(claimBytes),
            enc.encodeToString(
                serviceKeys.getPrimitive(PublicKeySign::class.java).sign(
                    "postmark/claim-binding/v1".toByteArray(Charsets.US_ASCII) +
                        byteArrayOf(0x00) + claimBytes,
                ),
            ),
            enc.encodeToString(sealed),
            sha256(sealed).joinToString("") { "%02x".format(it) },
            senderKeys, serviceKeys, recipientKeys, recipientPublicBytes,
            recognitionBlob, recognitionBlobId,
        )
    }

    private fun item() = PendingClaimIngest.FeedItemInputs(
        capsuleId = capsule,
        deliveryId = delivery,
        deliveryTargetId = target,
        claimId = claim,
        availableAt = 1798765432L,
        envelopeRef = "/v2/pending-deliveries/$delivery/claim",
        manifestRef = "/v2/pending-deliveries/$delivery/manifest",
        blobRefs = emptyList(),
    )

    private fun pinFor(serviceKeys: KeysetHandle) = PendingServicePin(
        serviceId,
        Base64.getEncoder().encodeToString(
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(serviceKeys.publicKeysetHandle),
        ),
    )

    private inner class FencedGate(
        var owner: String = recipient,
        var epoch: Long = 1L,
    ) : dev.hryshyn.remanence.pending.PendingRetirementSessionGate {
        override fun capture(): dev.hryshyn.remanence.core.data.network.SessionRequestLease =
            dev.hryshyn.remanence.core.data.network.SessionRequestLease(
                dev.hryshyn.remanence.core.model.UserId.parseRest(owner), epoch,
            )

        override fun accessTokenFor(
            lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        ): String? = "token"

        override fun isLive(
            lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        ): Boolean =
            lease.ownerUserId == dev.hryshyn.remanence.core.model.UserId.parseRest(owner) &&
                lease.incarnation == epoch
    }

    private fun ingest(
        dir: File,
        crypto: Crypto,
        directoryOwner: String = sender,
        recipientUser: String = recipient,
        claimResult: PendingRedownloadResult? = null,
        gate: FencedGate = FencedGate(),
        echoCapsuleId: String = capsule,
        echoTargetId: String = target,
        echoClaimId: String = claim,
        recognitionBlobPort: PendingRecognitionStage.BlobFetchPort? = null,
    ): Triple<PendingClaimIngest, FencedGate, dev.hryshyn.remanence.core.data.network.SessionRequestLease> {
        val ingest = PendingClaimIngest(
        claimPort = PendingClaimIngest.ClaimFetchPort { _, _ ->
            claimResult ?: PendingRedownloadResult.Redownloaded(
                echoClaimId, crypto.claimB64, crypto.claimSigB64,
                crypto.envelopeB64, crypto.envelopeShaHex,
            )
        },
        controlPort = PendingClaimIngest.ControlFetchPort { _, _ ->
            PendingControlResult.Control(
                echoCapsuleId, echoTargetId, delivery, echoClaimId,
                crypto.statementB64, crypto.senderSigB64,
                sha256(Base64.getDecoder().decode(crypto.statementB64))
                    .joinToString("") { "%02x".format(it) },
            )
        },
        directoryPort = PendingClaimIngest.DirectoryPort { _ ->
            PendingClaimIngest.DirectoryBundle(
                directoryOwner,
                TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                    crypto.senderKeys.publicKeysetHandle,
                ),
            )
        },
        recipientKeysPort = PendingClaimIngest.RecipientKeysPort {
            PendingClaimIngest.RecipientKeys(
                recipientUser, bundle,
                crypto.recipientKeys, crypto.recipientPublicBytes,
            )
        },
        servicePinPort = { pinFor(crypto.serviceKeys) },
        sessionGate = gate,
        accountProvider = { dev.hryshyn.remanence.pending.ProofAccount(gate.owner, bundle) },
        admittedStore = PendingAdmittedGiftStore(dir),
        recognitionBlobPort = recognitionBlobPort,
        clockSeconds = { 1_798_765_200L },
    )
        return Triple(ingest, gate, gate.capture())
    }

    @Test
    fun ingestAdmitsAndPersistsRow() = runTest {
        val dir = Files.createTempDirectory("pending-ingest").toFile()
        try {
            val crypto = crypto()
            ingest(dir, crypto).let { (ingest, _, lease) -> ingest.ingest(recipient, lease, listOf(item())) }
            val row = PendingAdmittedGiftStore(dir).get(recipient, capsule)
            assertEquals(delivery, row?.deliveryId)
            assertEquals(crypto.envelopeB64, row?.envelopeB64)
            assertEquals(null, row?.completedAt)
            // No blob port: staging skipped, row still admits.
            assertEquals("", row?.recognitionCiphertextB64)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14d-scan: sync-time ingestion stages the S-declared kind-1
     * ciphertext into the row for offline scan enumeration.
     */
    @Test
    fun ingestStagesRecognitionCiphertext() = runTest {
        val dir = Files.createTempDirectory("pending-ingest-staged").toFile()
        try {
            val crypto = crypto()
            val port = PendingRecognitionStage.BlobFetchPort { deliveryId, blobId, _ ->
                assertEquals(delivery, deliveryId)
                assertEquals(crypto.recognitionBlobId, blobId)
                dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult.Bytes(
                    blobId, crypto.recognitionBlob,
                )
            }
            ingest(dir, crypto, recognitionBlobPort = port)
                .let { (ingest, _, lease) -> ingest.ingest(recipient, lease, listOf(item())) }
            assertEquals(
                Base64.getEncoder().encodeToString(crypto.recognitionBlob),
                PendingAdmittedGiftStore(dir).get(recipient, capsule)?.recognitionCiphertextB64,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ownerMismatchFailsWithoutRow() = runTest {
        val dir = Files.createTempDirectory("pending-ingest-owner").toFile()
        try {
            val crypto = crypto()
            assertThrowsSuspend(IllegalStateException::class) {
                ingest(dir, crypto, directoryOwner = "99999999-2222-3333-4444-555555555555")
                    .let { (ingest, _, lease) -> ingest.ingest(recipient, lease, listOf(item())) }
            }
            assertNull(PendingAdmittedGiftStore(dir).get(recipient, capsule))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun claimFailureFailsWithoutRow() = runTest {
        val dir = Files.createTempDirectory("pending-ingest-claim").toFile()
        try {
            val crypto = crypto()
            assertThrowsSuspend(IllegalStateException::class) {
                ingest(
                    dir, crypto,
                    claimResult = PendingRedownloadResult.Failure(
                        dev.hryshyn.remanence.core.data.network.PendingMaterialFailure.NOT_FOUND,
                        404, false,
                    ),
                ).let { (ingest, _, lease) -> ingest.ingest(recipient, lease, listOf(item())) }
            }
            assertNull(PendingAdmittedGiftStore(dir).get(recipient, capsule))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recipientOwnerMismatchFails() = runTest {
        val dir = Files.createTempDirectory("pending-ingest-recipient").toFile()
        try {
            val crypto = crypto()
            assertThrowsSuspend(IllegalStateException::class) {
                ingest(dir, crypto, recipientUser = "99999999-2222-3333-4444-555555555555")
                    .let { (ingest, _, lease) -> ingest.ingest(recipient, lease, listOf(item())) }
            }
            assertNull(PendingAdmittedGiftStore(dir).get(recipient, capsule))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14b-lease: a same-owner replacement across the ingest
     * awaits fails closed with no admitted row — the ORIGINAL carried
     * lease no longer matches a fresh capture.
     */
    @Test
    fun reloginAcrossIngestFailsWithoutRow() = runTest {
        val dir = Files.createTempDirectory("pending-ingest-relogin").toFile()
        try {
            val crypto = crypto()
            val (ingest, gate, stale) = ingest(dir, crypto)
            gate.epoch = 2L
            assertThrowsSuspend(IllegalStateException::class) {
                ingest.ingest(recipient, stale, listOf(item()))
            }
            assertNull(PendingAdmittedGiftStore(dir).get(recipient, capsule))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 (T187 B1): a substituted feed row is rejected against the
     * VERIFIED bytes — every signed byte is genuinely capsule-B
     * (internally consistent, valid signatures) while the feed row
     * AND the echo JSON consistently name capsule-A. The unsigned
     * echo passes, admission succeeds, but the verified binding
     * mismatch throws and no row is written under either id.
     */
    @Test
    fun substitutedFeedItemRejectedWithoutRow() = runTest {
        val dir = Files.createTempDirectory("pending-ingest-substituted").toFile()
        try {
            val otherCapsule = "22222222-3333-4444-5555-666666666666"
            val cryptoB = crypto(forCapsule = otherCapsule)
            val (ingest, _, lease) = ingest(
                dir, cryptoB,
                echoCapsuleId = capsule,
                echoTargetId = target,
                echoClaimId = claim,
            )
            assertThrowsSuspend(IllegalStateException::class) {
                ingest.ingest(recipient, lease, listOf(item()))
            }
            assertNull(PendingAdmittedGiftStore(dir).get(recipient, capsule))
            assertNull(PendingAdmittedGiftStore(dir).get(recipient, otherCapsule))
        } finally {
            dir.deleteRecursively()
        }
    }
}
