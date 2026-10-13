package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimChallengeV1
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * T186 M7c: R10 challenge acceptance with REAL Tink hybrid round trips.
 * Vectors are self-sealed with the frozen info construction (the server
 * seals with the same Tink hybrid to the same bundle shape) — no mocks.
 */
class PendingClaimChallengeTest {

    private fun uuidBytes(raw: String): ByteArray {
        val uuid = UUID.fromString(raw)
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private data class Vector(
        val contextRaw: ByteArray,
        val sealed: ByteArray,
        val nonce: ByteArray,
        val private: KeysetHandle,
        val expectation: PendingClaimChallenge.Expectation,
    )

    private fun vector(
        capsule: String = "11111111-2222-3333-4444-555555555555",
        bundle: String = "01010101-0202-0303-0404-050505050505",
        session: String = "02020202-0303-0404-0505-060606060606",
        nowSeconds: Long = 1_798_765_400L,
        expiresAt: Long = 1_798_765_432L,
    ): Vector {
        TinkPrimitives.ensureRegistered()
        val recipient = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val context = ClaimChallengeV1.newBuilder()
            .setChallengeId(com.google.protobuf.ByteString.copyFrom(uuidBytes("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
            .setCapsuleId(com.google.protobuf.ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(com.google.protobuf.ByteString.copyFrom(uuidBytes("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
            .setRecipientUserId(com.google.protobuf.ByteString.copyFrom(uuidBytes("11111111-2222-3333-4444-555555555556")))
            .setRecipientKeyBundleId(com.google.protobuf.ByteString.copyFrom(uuidBytes(bundle)))
            .setSessionId(com.google.protobuf.ByteString.copyFrom(uuidBytes(session)))
            .setNonceSha256(com.google.protobuf.ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(nonce)))
            .setExpiresAtEpochSeconds(expiresAt)
            .build()
        val contextRaw = context.toByteArray()
        val info = PendingClaimChallenge.CLAIM_CHALLENGE_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + contextRaw
        val sealed = recipient.publicKeysetHandle
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(nonce, info)
        return Vector(
            contextRaw, sealed, nonce, recipient,
            PendingClaimChallenge.Expectation(
                capsule, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "11111111-2222-3333-4444-555555555556", bundle, session, nowSeconds,
            ),
        )
    }

    @Test
    fun sealedChallengeOpensToNonce() {
        val v = vector()
        val opened = PendingClaimChallenge.openNonce(v.contextRaw, v.sealed, v.expectation, v.private)
        assertContentEquals(v.nonce, opened)
    }

    @Test
    fun wrongRecipientRejected() {
        TinkPrimitives.ensureRegistered()
        val v = vector()
        val other = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        assertFailsWith<GeneralSecurityException> {
            PendingClaimChallenge.openNonce(v.contextRaw, v.sealed, v.expectation, other)
        }
    }

    @Test
    fun tamperedContextRejected() {
        val v = vector()
        val tampered = v.contextRaw.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFailsWith<GeneralSecurityException> {
            PendingClaimChallenge.openNonce(tampered, v.sealed, v.expectation, v.private)
        }
    }

    @Test
    fun expiredChallengeRejected() {
        val v = vector(expiresAt = 1_798_765_400L, nowSeconds = 1_798_765_400L)
        assertFailsWith<GeneralSecurityException> {
            PendingClaimChallenge.openNonce(v.contextRaw, v.sealed, v.expectation, v.private)
        }
    }

    @Test
    fun identityMismatchRejected() {
        val v = vector()
        val wrong = v.expectation.copy(capsuleId = "99999999-2222-3333-4444-555555555555")
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingClaimChallenge.openNonce(v.contextRaw, v.sealed, wrong, v.private)
        }
        assertEquals("pending claim challenge agreement failed: capsule", failure.message)
    }
}
