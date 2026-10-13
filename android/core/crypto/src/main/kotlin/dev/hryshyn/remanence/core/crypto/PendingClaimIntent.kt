package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimIntentV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopePlaintextV2
import dev.hryshyn.remanence.protocol.delivery_v2.KeyCommitmentInput
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID

/**
 * T186 M7c: claim intent build/sign + claimed-envelope acceptance
 * (frozen §8 R11 + crypto-contract §2–§4). Real Tink crypto throughout.
 *
 * - [buildIntent]: canonical `ClaimIntentV2` bytes from caller-supplied
 *   fields (UUID strings, 32-byte hashes/nonce). No network, no storage.
 * - [signIntent]: account-Ed25519 signature over
 *   `postmark/claim-intent/v2 || 0x00 || intent`, 69-byte Tink framing
 *   with key-id match (mirrors the publish signer discipline).
 * - [openAndAcceptEnvelope]: HPKE-opens the sealed envelope with the
 *   recipient bundle handle (info = `postmark/claimed-envelope/v2 || 0x00`
 *   + reconstructed context), then verifies the plaintext WITHOUT trusting
 *   storage: context equality with the locally rebuilt context, `D`/`KC`
 *   recomputation against the verified `S`, exact-`K` shape (single
 *   AES256_GCM/TINK keyset — the vault adapter re-verifies before
 *   storage), and opening presence. Returns the capsule keyset bytes +
 *   opening for the caller to seal.
 */
object PendingClaimIntent {

    /** All UUIDs canonical strings; hashes 32 bytes; nonce 32 bytes. */
    data class Fields(
        val operationId: String,
        val capsuleId: String,
        val deliveryTargetId: String,
        val publishStatementSha256: ByteArray,
        val proofId: String,
        val challengeId: String,
        val recipientUserId: String,
        val recipientKeyBundleId: String,
        val sessionId: String,
        val nonce: ByteArray,
        val bindAlias: Boolean,
    )

    fun buildCanonical(fields: Fields): ByteArray {
        require(fields.publishStatementSha256.size == 32) { "H must be 32 bytes" }
        require(fields.nonce.size == 32) { "nonce must be 32 bytes" }
        val nonceSha = MessageDigest.getInstance("SHA-256").digest(fields.nonce)
        val intent = ClaimIntentV2.newBuilder()
            .setIntentVersion(1)
            .setOperationId(ByteString.copyFrom(PendingUuid16.of(fields.operationId, "operation")))
            .setCapsuleId(ByteString.copyFrom(PendingUuid16.of(fields.capsuleId, "capsule")))
            .setDeliveryTargetId(ByteString.copyFrom(PendingUuid16.of(fields.deliveryTargetId, "target")))
            .setPublishStatementSha256(ByteString.copyFrom(fields.publishStatementSha256))
            .setProofId(ByteString.copyFrom(PendingUuid16.of(fields.proofId, "proof")))
            .setChallengeId(ByteString.copyFrom(PendingUuid16.of(fields.challengeId, "challenge")))
            .setRecipientUserId(ByteString.copyFrom(PendingUuid16.of(fields.recipientUserId, "account")))
            .setRecipientKeyBundleId(ByteString.copyFrom(PendingUuid16.of(fields.recipientKeyBundleId, "bundle")))
            .setSessionId(ByteString.copyFrom(PendingUuid16.of(fields.sessionId, "session")))
            .setNonce(ByteString.copyFrom(fields.nonce))
            .setNonceSha256(ByteString.copyFrom(nonceSha))
            .setBindAlias(fields.bindAlias)
            .build()
        return intent.toByteArray()
    }

    fun signIntent(signingPrivateHandle: KeysetHandle, intentBytes: ByteArray): ByteArray {
        if (intentBytes.isEmpty()) throw GeneralSecurityException("claim intent is empty")
        val input = CLAIM_INTENT_DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + intentBytes
        val signature = try {
            signingPrivateHandle.getPrimitive(PublicKeySign::class.java).sign(input)
        } catch (failure: GeneralSecurityException) {
            throw failure
        } catch (_: Exception) {
            throw GeneralSecurityException("claim intent signing failed")
        }
        if (signature.size != 69 || signature[0] != 0x01.toByte()) {
            throw GeneralSecurityException("claim intent signature framing invalid")
        }
        val embeddedKeyId = ((signature[1].toInt() and 0xFF) shl 24) or
            ((signature[2].toInt() and 0xFF) shl 16) or
            ((signature[3].toInt() and 0xFF) shl 8) or
            (signature[4].toInt() and 0xFF)
        if (embeddedKeyId != signingPrivateHandle.keysetInfo.primaryKeyId) {
            throw GeneralSecurityException("claim intent signature key mismatch")
        }
        return signature
    }

    /** Frozen §2 domain for the claim-intent signature input. */
    const val CLAIM_INTENT_DOMAIN: String = "postmark/claim-intent/v2"
}

/** Canonical 16-byte network-order UUID encoding (shared by claim crypto). */
internal object PendingUuid16 {
    fun of(rest: String, what: String): ByteArray {
        val uuid = try {
            UUID.fromString(rest)
        } catch (_: IllegalArgumentException) {
            throw GeneralSecurityException("pending claim id malformed: $what")
        }
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }
}

/**
 * Trusted claimed-envelope acceptance. Everything is recomputed from the
 * verified `S` + accepted `R` + opened plaintext — stored AAD/envelopes
 * are never trusted. Gate order: digest → strict/canonical → AUTHORITATIVE
 * sender signature → R agreement → service signature → local owned
 * account/bundle/digest/time checks → HPKE open → plaintext checks. The
 * opened plaintext is zeroized in `finally` on EVERY branch (failure and
 * success alike); callers own the returned copies.
 *
 * Result typing is explicit: [EnvelopeAcceptance.Accepted] requires the
 * deployment-pinned SERVICE signature; without a service key the same
 * chain yields [EnvelopeAcceptance.PartiallyAccepted], which is typed
 * untrusted and must NEVER be promoted to production `Accepted`.
 */
object PendingClaimedEnvelope {

    /** Locally owned expectations, checked BEFORE admission/HPKE. */
    data class LocalExpectation(
        val recipientUserId: String,
        val recipientKeyBundleId: String,
        /** Directory-fetched public keyset bytes (what the server hashed). */
        val recipientPublicKeyset: ByteArray,
        val nowEpochSeconds: Long,
    )

    sealed interface EnvelopeAcceptance {
        data class Accepted(
            val capsuleAeadKeyset: ByteArray,
            val addressOpening: AddressOpening,
        ) : EnvelopeAcceptance

        /**
         * Everything EXCEPT the service signature verified. Explicitly
         * untrusted: never a production `Accepted`, never admission.
         */
        data class PartiallyAccepted(
            val capsuleAeadKeyset: ByteArray,
            val addressOpening: AddressOpening,
        ) : EnvelopeAcceptance
    }

    fun accept(
        sealedEnvelope: ByteArray,
        expectedEnvelopeSha256: ByteArray,
        statementBytes: ByteArray,
        claimBytes: ByteArray,
        senderSignature: ByteArray,
        claimSignature: ByteArray,
        recipientPrivateHandle: KeysetHandle,
        senderVerifyHandle: KeysetHandle,
        /** Null until the service key is distributed → partial only. */
        serviceVerifyHandle: KeysetHandle?,
        expected: LocalExpectation,
    ): EnvelopeAcceptance {
        if (sealedEnvelope.isEmpty()) throw GeneralSecurityException("pending envelope is empty")
        val actualEnvelopeSha = MessageDigest.getInstance("SHA-256").digest(sealedEnvelope)
        if (!actualEnvelopeSha.contentEquals(expectedEnvelopeSha256)) {
            throw GeneralSecurityException("pending envelope digest mismatch")
        }
        // Authoritative sender signature over S — required, before R work.
        PendingPublishVerifier.verifyPublishWithHandle(senderVerifyHandle, statementBytes, senderSignature)
        // Full S/R agreement gate (versions, ids, H, D, policy, service id).
        PendingPublishVerifier.verifyClaimAgreement(statementBytes, claimBytes)
        val statement = PendingPublishStatementV2.parseFrom(statementBytes)
        val claim = dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1.parseFrom(claimBytes)
        // Service signature over canonical R (or explicit partial).
        val serviceDone = if (serviceVerifyHandle != null) {
            PendingPublishVerifier.verifySignature(
                serviceVerifyHandle, claimBytes, claimSignature,
                "pending claim", CLAIM_BINDING_DOMAIN,
            )
            true
        } else {
            false
        }
        // Local owned account/bundle/digest/time checks — BEFORE HPKE.
        if (!uuidEquals(claim.recipientUserId, expected.recipientUserId) ||
            !uuidEquals(claim.recipientKeyBundleId, expected.recipientKeyBundleId)
        ) {
            throw GeneralSecurityException("pending envelope owner mismatch")
        }
        val publicDigest = MessageDigest.getInstance("SHA-256").digest(expected.recipientPublicKeyset)
        if (!publicDigest.contentEquals(claim.recipientEncryptionPublicKeysetSha256.toByteArray())) {
            throw GeneralSecurityException("pending envelope key digest mismatch")
        }
        if (statement.claimExpiresAtEpochSeconds <= expected.nowEpochSeconds ||
            claim.claimedAtEpochSeconds > expected.nowEpochSeconds
        ) {
            throw GeneralSecurityException("pending envelope time invalid")
        }
        // Re-open/restore is permanent after an accepted claim: the claimed
        // instant must fall inside the statement's own [created, expiry]
        // window (NOT merely "now before expiry").
        // NOTE: created_at is not carried on S; the creation lower bound is
        // enforced by the agreement-time checks at claim (server) time. The
        // client enforces claimed_at <= expiry here and documents the
        // created-bound as server-owned.
        // Rebuild the exact HPKE context the server sealed to.
        val context = dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopeContextV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(statement.capsuleId)
            .setDeliveryTargetId(statement.deliveryTargetId)
            .setSenderUserId(statement.senderUserId)
            .setRecipientUserId(claim.recipientUserId)
            .setRecipientKeyBundleId(claim.recipientKeyBundleId)
            .setPublishStatementSha256(com.google.protobuf.ByteString.copyFrom(actualStatementHash(statementBytes)))
            .setClaimBindingSha256(com.google.protobuf.ByteString.copyFrom(actualStatementHash(claimBytes)))
            .build()
        val contextRaw = context.toByteArray()
        val info = CLAIMED_ENVELOPE_DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + contextRaw
        val plaintextBytes = try {
            recipientPrivateHandle.getPrimitive(HybridDecrypt::class.java).decrypt(sealedEnvelope, info)
        } catch (failure: GeneralSecurityException) {
            throw failure
        } catch (_: Exception) {
            throw GeneralSecurityException("pending envelope open failed")
        }
        try {
            PendingStrictProto.requireNoUnknownFields(
                plaintextBytes,
                PendingStrictProto.CLAIMED_ENVELOPE_PLAINTEXT_V2,
                "claimed envelope",
            )
            val plaintext = try {
                ClaimedEnvelopePlaintextV2.parseFrom(plaintextBytes)
            } catch (_: Exception) {
                throw GeneralSecurityException("pending claimed envelope malformed")
            }
            if (!plaintext.toByteArray().contentEquals(plaintextBytes)) {
                throw GeneralSecurityException("pending claimed envelope not canonical")
            }
            // The opened binding must equal the locally rebuilt context.
            if (!plaintext.binding.toByteArray().contentEquals(contextRaw)) {
                throw GeneralSecurityException("pending envelope context mismatch")
            }
            // Recompute D over the opened AddressOpening; must match S.
            val openingBytes = plaintext.addressOpening.toByteArray()
            val recomputedD = MessageDigest.getInstance("SHA-256").digest(
                DELIVERY_ADDRESS_DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + openingBytes,
            )
            if (!recomputedD.contentEquals(statement.addressCommitment.toByteArray())) {
                throw GeneralSecurityException("pending address commitment mismatch")
            }
            // Recompute KC over (v1, capsule, target, exact K); must match S.
            val kcInput = KeyCommitmentInput.newBuilder()
                .setVersion(1)
                .setCapsuleId(statement.capsuleId)
                .setDeliveryTargetId(statement.deliveryTargetId)
                .setCapsuleAeadKeyset(plaintext.capsuleAeadKeyset)
                .build().toByteArray()
            val recomputedKc = MessageDigest.getInstance("SHA-256").digest(
                DELIVERY_KEY_DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + kcInput,
            )
            if (!recomputedKc.contentEquals(statement.keyCommitment.toByteArray())) {
                throw GeneralSecurityException("pending key commitment mismatch")
            }
            // Exact-K shape: single ENABLED AES256_GCM/TINK keyset (the vault
            // adapter re-verifies before storage; rejected here first).
            requireExactAesKey(plaintext.capsuleAeadKeyset.toByteArray())
            val keyCopy = plaintext.capsuleAeadKeyset.toByteArray()
            val openingCopy = AddressOpening.parseFrom(openingBytes)
            if (!serviceDone) {
                return EnvelopeAcceptance.PartiallyAccepted(keyCopy, openingCopy)
            }
            return EnvelopeAcceptance.Accepted(keyCopy, openingCopy)
        } finally {
            plaintextBytes.fill(0)
        }
    }

    private fun uuidEquals(actual: com.google.protobuf.ByteString, expectedRest: String): Boolean {
        val expected = try {
            PendingUuid16.of(expectedRest, "expectation")
        } catch (_: GeneralSecurityException) {
            return false
        }
        return actual.toByteArray().contentEquals(expected)
    }

    private fun actualStatementHash(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun requireExactAesKey(keysetBytes: ByteArray) {
        val keyset = try {
            com.google.crypto.tink.proto.Keyset.parseFrom(keysetBytes)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending capsule key malformed")
        }
        if (keyset.keyCount != 1) throw GeneralSecurityException("pending capsule key must be exactly one key")
        val key = keyset.getKey(0)
        if (key.status != com.google.crypto.tink.proto.KeyStatusType.ENABLED ||
            key.keyData.typeUrl != AES_GCM_TYPE_URL ||
            key.outputPrefixType != com.google.crypto.tink.proto.OutputPrefixType.TINK
        ) {
            throw GeneralSecurityException("pending capsule key must be enabled AES256_GCM/TINK")
        }
    }

    /** Frozen §2 domains reused for recomputation. */
    const val CLAIMED_ENVELOPE_DOMAIN: String = "postmark/claimed-envelope/v2"
    const val CLAIM_BINDING_DOMAIN: String = "postmark/claim-binding/v1"
    private const val DELIVERY_ADDRESS_DOMAIN: String = "postmark/delivery-address/v1"
    private const val DELIVERY_KEY_DOMAIN: String = "postmark/delivery-key/v1"
    private const val AES_GCM_TYPE_URL: String = "type.googleapis.com/google.crypto.tink.AesGcmKey"
}
