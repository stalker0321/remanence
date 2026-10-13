package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import dev.hryshyn.remanence.protocol.delivery_v2.ArtifactAadV2
import dev.hryshyn.remanence.protocol.delivery_v2.ArtifactBindingV2
import dev.hryshyn.remanence.protocol.delivery_v2.KeyCommitmentInput
import dev.hryshyn.remanence.protocol.delivery_v2.PendingEscrowPlaintextV1
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID

/**
 * T186 M11: sender-side v2 publish crypto (frozen T185 `crypto-contract.md`
 * §1–§2). Real Tink/canonical protobuf throughout; no mocks.
 *
 * - [addressOpening]: canonical `AddressOpening` (version 1, EMAIL 1 /
 *   SMS 2, normalized destination, 32-byte salt).
 * - [addressCommitment]: `D = SHA256("postmark/delivery-address/v1" ||
 *   0x00 || canonical AddressOpening)`.
 * - [keyCommitment]: `KC = SHA256("postmark/delivery-key/v1" || 0x00 ||
 *   canonical KeyCommitmentInput(v1, capsule, target, exact K))`.
 * - [artifactAad]: `postmark/artifact/v2 || 0x00 || canonical
 *   ArtifactAadV2`. The binding carries the DELIVERY TARGET
 *   (`sender_user_id` + `delivery_target_id`) — NEVER the final
 *   recipient user. There is no recipient parameter by construction,
 *   so `UserId(target)` fakery cannot be expressed.
 * - [buildStatement]/[signStatement]: canonical `PendingPublishStatementV2`
 *   (S) + account-Ed25519 signature over
 *   `postmark/pending-publish/v2 || 0x00 || S` with 69-byte Tink framing
 *   and key-id match (mirrors the claim-intent signer discipline).
 * - [buildEscrow]: canonical `PendingEscrowPlaintextV1` (travels ONLY
 *   through the dedicated R5 handler; never idempotency storage/logs).
 *
 * Inner payloads (capsule bytes, manifests, photos) are unchanged: this
 * file frames commitments/AAD/statements around caller-supplied
 * ciphertext, never re-encrypts product content.
 */
object PendingPublishCrypto {

    /** Frozen §2 domains. */
    const val DELIVERY_ADDRESS_DOMAIN: String = "postmark/delivery-address/v1"
    const val DELIVERY_KEY_DOMAIN: String = "postmark/delivery-key/v1"
    const val ARTIFACT_V2_DOMAIN: String = "postmark/artifact/v2"
    const val PENDING_PUBLISH_DOMAIN: String = "postmark/pending-publish/v2"

    /** Channel codes (frozen §1; zero/other rejected). */
    const val CHANNEL_EMAIL: Int = 1
    const val CHANNEL_SMS: Int = 2

    /** Normalization version for the address opening (frozen §1). */
    const val ADDRESS_NORMALIZATION_VERSION: Int = 1

    /** Artifact kinds reuse the existing numbers 1, 2, 3. */
    val ARTIFACT_KINDS: Set<Int> = setOf(1, 2, 3)

    fun addressOpening(
        channel: Int,
        normalizedDestination: String,
        salt32: ByteArray,
    ): ByteArray {
        require(channel == CHANNEL_EMAIL || channel == CHANNEL_SMS) {
            "pending address channel must be 1 (EMAIL) or 2 (SMS)"
        }
        require(normalizedDestination.isNotBlank()) { "pending destination must not be blank" }
        require(salt32.size == 32) { "pending address salt must be 32 bytes" }
        return AddressOpening.newBuilder()
            .setNormalizationVersion(ADDRESS_NORMALIZATION_VERSION)
            .setChannel(channel)
            .setNormalizedDestination(normalizedDestination)
            .setSalt(ByteString.copyFrom(salt32))
            .build()
            .toByteArray()
    }

    fun addressCommitment(openingCanonical: ByteArray): ByteArray {
        require(openingCanonical.isNotEmpty()) { "pending address opening is empty" }
        return MessageDigest.getInstance("SHA-256").digest(
            DELIVERY_ADDRESS_DOMAIN.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + openingCanonical,
        )
    }

    fun keyCommitment(
        capsuleId: String,
        deliveryTargetId: String,
        capsuleAeadKeyset: ByteArray,
    ): ByteArray {
        require(capsuleAeadKeyset.isNotEmpty()) { "pending capsule key is empty" }
        val input = KeyCommitmentInput.newBuilder()
            .setVersion(1)
            .setCapsuleId(ByteString.copyFrom(uuid16(capsuleId, "capsule")))
            .setDeliveryTargetId(ByteString.copyFrom(uuid16(deliveryTargetId, "target")))
            .setCapsuleAeadKeyset(ByteString.copyFrom(capsuleAeadKeyset))
            .build()
            .toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(
            DELIVERY_KEY_DOMAIN.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + input,
        )
    }

    /**
     * Canonical `ArtifactAadV2` bytes. `deliveryTargetId` is the frozen
     * pending target — never the final recipient user. `ordinal` is -1
     * except PHOTO 0..4 (same rule as the frozen fixture matrix).
     */
    fun artifactAad(
        capsuleId: String,
        blobId: String,
        artifactKind: Int,
        ordinal: Int,
        senderUserId: String,
        deliveryTargetId: String,
    ): ByteArray {
        require(artifactKind in ARTIFACT_KINDS) { "pending artifact kind must be 1, 2, or 3" }
        require(ordinal == -1 || ordinal in 0..4) { "pending artifact ordinal must be -1 or 0..4" }
        return ArtifactAadV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuid16(capsuleId, "capsule")))
            .setBlobId(ByteString.copyFrom(uuid16(blobId, "blob")))
            .setArtifactKind(artifactKind)
            .setOrdinal(ordinal)
            .setSenderUserId(ByteString.copyFrom(uuid16(senderUserId, "sender")))
            .setDeliveryTargetId(ByteString.copyFrom(uuid16(deliveryTargetId, "target")))
            .build()
            .toByteArray()
    }

    /** Full AAD input for AEAD: domain || 0x00 || canonical message. */
    fun artifactAadInput(
        capsuleId: String,
        blobId: String,
        artifactKind: Int,
        ordinal: Int,
        senderUserId: String,
        deliveryTargetId: String,
    ): ByteArray =
        ARTIFACT_V2_DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) +
            artifactAad(capsuleId, blobId, artifactKind, ordinal, senderUserId, deliveryTargetId)

    /** One artifact declaration bound into S (size + SHA-256 of ciphertext). */
    data class ArtifactDeclaration(
        val blobId: String,
        val kind: Int,
        val ordinal: Int,
        val ciphertextSize: Long,
        val ciphertextSha256: ByteArray,
    )

    fun buildStatement(
        capsuleId: String,
        senderUserId: String,
        senderKeyBundleId: String,
        deliveryTargetId: String,
        addressCommitment: ByteArray,
        keyCommitment: ByteArray,
        createdAtEpochSeconds: Long,
        claimExpiresAtEpochSeconds: Long,
        serviceKeyId: String,
        artifacts: List<ArtifactDeclaration>,
    ): ByteArray {
        require(addressCommitment.size == 32) { "D must be 32 bytes" }
        require(keyCommitment.size == 32) { "KC must be 32 bytes" }
        require(createdAtEpochSeconds >= 0 && claimExpiresAtEpochSeconds >= 0) {
            "pending statement times must be non-negative"
        }
        val builder = PendingPublishStatementV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuid16(capsuleId, "capsule")))
            .setSenderUserId(ByteString.copyFrom(uuid16(senderUserId, "sender")))
            .setSenderKeyBundleId(ByteString.copyFrom(uuid16(senderKeyBundleId, "bundle")))
            .setDeliveryTargetId(ByteString.copyFrom(uuid16(deliveryTargetId, "target")))
            .setAddressCommitment(ByteString.copyFrom(addressCommitment))
            .setKeyCommitment(ByteString.copyFrom(keyCommitment))
            .setCreatedAtEpochSeconds(createdAtEpochSeconds)
            .setClaimExpiresAtEpochSeconds(claimExpiresAtEpochSeconds)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(uuid16(serviceKeyId, "service-key")))
        for (artifact in artifacts) {
            require(artifact.kind in ARTIFACT_KINDS) { "pending artifact kind must be 1, 2, or 3" }
            require(artifact.ciphertextSize >= 0) { "pending artifact size must be non-negative" }
            require(artifact.ciphertextSha256.size == 32) { "pending artifact hash must be 32 bytes" }
            builder.addArtifacts(
                ArtifactBindingV2.newBuilder()
                    .setBlobId(ByteString.copyFrom(uuid16(artifact.blobId, "blob")))
                    .setKind(artifact.kind)
                    .setOrdinal(artifact.ordinal)
                    .setCiphertextSize(artifact.ciphertextSize)
                    .setCiphertextSha256(ByteString.copyFrom(artifact.ciphertextSha256))
                    .build(),
            )
        }
        return builder.build().toByteArray()
    }

    fun signStatement(signingPrivateHandle: KeysetHandle, statementBytes: ByteArray): ByteArray {
        if (statementBytes.isEmpty()) throw GeneralSecurityException("pending statement is empty")
        val input = PENDING_PUBLISH_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + statementBytes
        val signature = try {
            signingPrivateHandle.getPrimitive(PublicKeySign::class.java).sign(input)
        } catch (failure: GeneralSecurityException) {
            throw failure
        } catch (_: Exception) {
            throw GeneralSecurityException("pending statement signing failed")
        }
        if (signature.size != 69 || signature[0] != 0x01.toByte()) {
            throw GeneralSecurityException("pending statement signature framing invalid")
        }
        val embeddedKeyId = ((signature[1].toInt() and 0xFF) shl 24) or
            ((signature[2].toInt() and 0xFF) shl 16) or
            ((signature[3].toInt() and 0xFF) shl 8) or
            (signature[4].toInt() and 0xFF)
        if (embeddedKeyId != signingPrivateHandle.keysetInfo.primaryKeyId) {
            throw GeneralSecurityException("pending statement signature key mismatch")
        }
        return signature
    }

    fun statementSha256(statementBytes: ByteArray): ByteArray {
        require(statementBytes.isNotEmpty()) { "pending statement is empty" }
        return MessageDigest.getInstance("SHA-256").digest(statementBytes)
    }

    /**
     * Canonical escrow plaintext (bounded ≤2048 bytes per contract §3;
     * the exact-K shape is enforced server-side AND by the recipient
     * `requireExactAesKey` gate — never logged/echoed here).
     */
    fun buildEscrow(
        capsuleId: String,
        deliveryTargetId: String,
        senderUserId: String,
        statementSha256: ByteArray,
        addressCommitment: ByteArray,
        keyCommitment: ByteArray,
        claimExpiresAtEpochSeconds: Long,
        capsuleAeadKeyset: ByteArray,
        addressOpeningCanonical: ByteArray,
    ): ByteArray {
        require(statementSha256.size == 32) { "H must be 32 bytes" }
        require(addressCommitment.size == 32) { "D must be 32 bytes" }
        require(keyCommitment.size == 32) { "KC must be 32 bytes" }
        require(capsuleAeadKeyset.isNotEmpty()) { "pending capsule key is empty" }
        require(addressOpeningCanonical.isNotEmpty()) { "pending address opening is empty" }
        val opening = try {
            AddressOpening.parseFrom(addressOpeningCanonical)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending address opening malformed")
        }
        val plaintext = PendingEscrowPlaintextV1.newBuilder()
            .setEscrowVersion(1)
            .setCapsuleProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuid16(capsuleId, "capsule")))
            .setDeliveryTargetId(ByteString.copyFrom(uuid16(deliveryTargetId, "target")))
            .setSenderUserId(ByteString.copyFrom(uuid16(senderUserId, "sender")))
            .setPublishStatementSha256(ByteString.copyFrom(statementSha256))
            .setAddressCommitment(ByteString.copyFrom(addressCommitment))
            .setKeyCommitment(ByteString.copyFrom(keyCommitment))
            .setClaimExpiresAtEpochSeconds(claimExpiresAtEpochSeconds)
            .setCapsuleAeadKeyset(ByteString.copyFrom(capsuleAeadKeyset))
            .setAddressOpening(opening)
            .build()
            .toByteArray()
        require(plaintext.size <= 2048) { "pending escrow plaintext exceeds 2048 bytes" }
        return plaintext
    }

    private fun uuid16(rest: String, what: String): ByteArray {
        val uuid = try {
            UUID.fromString(rest)
        } catch (_: IllegalArgumentException) {
            throw GeneralSecurityException("pending publish id malformed: $what")
        }
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }
}
