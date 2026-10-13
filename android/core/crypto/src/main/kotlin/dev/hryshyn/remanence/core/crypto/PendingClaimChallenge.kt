package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimChallengeV1
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID

/**
 * T186 M7c: R10 claim-challenge acceptance (frozen §8 R10 amendment).
 *
 * The server returns the EXACT canonical `ClaimChallengeV1` bytes
 * (`claim_challenge_b64`) because the HPKE context is unknowable before
 * decrypting the nonce. The client validates the known fields
 * (capsule/target/account/bundle/expiry) against these exact bytes, then
 * uses them verbatim as the HPKE info to open `hpke_challenge_b64` with
 * the recipient bundle private handle. Real Tink hybrid crypto throughout
 * — never mocks, never disabled checks.
 */
object PendingClaimChallenge {

    /** Expected identities, canonical UUID strings (lowercase wire form). */
    data class Expectation(
        val capsuleId: String,
        val deliveryTargetId: String,
        val recipientUserId: String,
        val recipientKeyBundleId: String,
        val sessionId: String,
        /** Lower bound: challenges expiring at or before now are rejected. */
        val nowEpochSeconds: Long,
    )

    /**
     * Returns the opened 32-byte nonce. Throws [GeneralSecurityException]
     * on any mismatch (unknown fields, non-canonical bytes, identity
     * disagreement, expiry, HPKE failure, nonce-hash mismatch).
     */
    fun openNonce(
        challengeRaw: ByteArray,
        sealedNonce: ByteArray,
        expected: Expectation,
        recipientPrivateHandle: KeysetHandle,
    ): ByteArray {
        PendingStrictProto.requireNoUnknownFields(
            challengeRaw,
            PendingStrictProto.CLAIM_CHALLENGE_V1,
            "claim challenge",
        )
        val challenge = try {
            ClaimChallengeV1.parseFrom(challengeRaw)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending claim challenge malformed")
        }
        if (!challenge.toByteArray().contentEquals(challengeRaw)) {
            throw GeneralSecurityException("pending claim challenge not canonical")
        }
        fun uuid16(raw: String, what: String): ByteArray {
            val uuid = try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                throw GeneralSecurityException("pending claim challenge expectation malformed: $what")
            }
            val buffer = java.nio.ByteBuffer.allocate(16)
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
            return buffer.array()
        }
        fun eq(actual: ByteString, expected: ByteArray, what: String) {
            if (!actual.toByteArray().contentEquals(expected)) {
                throw GeneralSecurityException("pending claim challenge agreement failed: $what")
            }
        }
        eq(challenge.capsuleId, uuid16(expected.capsuleId, "capsule"), "capsule")
        eq(challenge.deliveryTargetId, uuid16(expected.deliveryTargetId, "target"), "target")
        eq(challenge.recipientUserId, uuid16(expected.recipientUserId, "account"), "account")
        eq(challenge.recipientKeyBundleId, uuid16(expected.recipientKeyBundleId, "bundle"), "bundle")
        eq(challenge.sessionId, uuid16(expected.sessionId, "session"), "session")
        if (challenge.expiresAtEpochSeconds <= expected.nowEpochSeconds) {
            throw GeneralSecurityException("pending claim challenge expired")
        }
        val info = CLAIM_CHALLENGE_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + challengeRaw
        val nonce = try {
            recipientPrivateHandle.getPrimitive(HybridDecrypt::class.java).decrypt(sealedNonce, info)
        } catch (failure: GeneralSecurityException) {
            throw failure
        } catch (_: Exception) {
            throw GeneralSecurityException("pending claim challenge open failed")
        }
        if (nonce.size != 32) {
            throw GeneralSecurityException("pending claim challenge nonce malformed")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(nonce)
        if (!digest.contentEquals(challenge.nonceSha256.toByteArray())) {
            throw GeneralSecurityException("pending claim challenge nonce mismatch")
        }
        return nonce
    }

    /** Frozen §2 domain for the claim-challenge HPKE info. */
    const val CLAIM_CHALLENGE_DOMAIN: String = "postmark/claim-challenge/v1"
}
