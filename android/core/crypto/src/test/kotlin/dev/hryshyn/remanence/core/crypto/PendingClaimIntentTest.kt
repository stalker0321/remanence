package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopeContextV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopePlaintextV2
import dev.hryshyn.remanence.protocol.delivery_v2.KeyCommitmentInput
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * T186 M7c: claim intent + envelope acceptance with REAL Tink crypto.
 * The whole v2 fixture (S, signature, R, sealed envelope) is built
 * in-test with generated keys following the frozen constructions — the
 * production open/verify paths consume only the resulting bytes.
 */
class PendingClaimIntentTest {

    private fun uuidBytes(raw: String): ByteArray {
        val uuid = UUID.fromString(raw)
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class Fixture(
        val statement: ByteArray,
        val claim: ByteArray,
        val sealedEnvelope: ByteArray,
        val envelopeSha: ByteArray,
        val recipientPrivate: KeysetHandle,
        val rawKey: ByteArray,
        val opening: AddressOpening,
    )

    private fun fixture(): Fixture {
        TinkPrimitives.ensureRegistered()
        val capsule = "11111111-2222-3333-4444-555555555555"
        val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val sender = "01010101-0202-0303-0404-050505050505"
        val recipient = "33333333-4444-5555-6666-777777777777"
        val bundle = "01010101-0202-0303-0404-050505050506"
        val opening = AddressOpening.newBuilder()
            .setNormalizationVersion(1)
            .setChannel(1)
            .setNormalizedDestination("a42@example.test")
            .setSalt(ByteString.copyFrom(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            .build()
        val openingBytes = opening.toByteArray()
        val d = sha256("postmark/delivery-address/v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + openingBytes)
        val aesHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val rawKey = TinkProtoKeysetFormat.serializeKeyset(aesHandle, InsecureSecretKeyAccess.get())
        val kcInput = KeyCommitmentInput.newBuilder()
            .setVersion(1)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .build().toByteArray()
        val kc = sha256("postmark/delivery-key/v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + kcInput)
        val serviceKey = uuidBytes("0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f")
        val statement = PendingPublishStatementV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setSenderUserId(ByteString.copyFrom(uuidBytes(sender)))
            .setSenderKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setKeyCommitment(ByteString.copyFrom(kc))
            .setCreatedAtEpochSeconds(1_798_765_000L)
            .setClaimExpiresAtEpochSeconds(1_798_765_432L)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(serviceKey))
            .build().toByteArray()
        val claimId = "55555555-6666-7777-8888-999999999999"
        val claim = ClaimBindingV1.newBuilder()
            .setBindingVersion(1)
            .setCapsuleProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(recipient)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setRecipientEncryptionPublicKeysetSha256(ByteString.copyFrom(ByteArray(32)))
            .setClaimId(ByteString.copyFrom(uuidBytes(claimId)))
            .setClaimedAtEpochSeconds(1_798_765_100L)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(serviceKey))
            .build().toByteArray()
        val recipientKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val context = ClaimedEnvelopeContextV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setSenderUserId(ByteString.copyFrom(uuidBytes(sender)))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(recipient)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundle)))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .setClaimBindingSha256(ByteString.copyFrom(sha256(claim)))
            .build()
        val contextRaw = context.toByteArray()
        val plaintext = ClaimedEnvelopePlaintextV2.newBuilder()
            .setBinding(context)
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .setAddressOpening(opening)
            .build().toByteArray()
        val info = PendingClaimedEnvelope.CLAIMED_ENVELOPE_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + contextRaw
        val sealed = recipientKeys.publicKeysetHandle
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(plaintext, info)
        return Fixture(statement, claim, sealed, sha256(sealed), recipientKeys, rawKey, opening)
    }

    @Test
    fun sealedEnvelopeOpensAndVerifies() {
        val f = fixture()
        val accepted = PendingClaimedEnvelope.openAndAccept(
            f.sealedEnvelope, f.envelopeSha, f.statement, f.claim, f.recipientPrivate,
        )
        assertContentEquals(f.rawKey, accepted.capsuleAeadKeyset)
        assertEquals(f.opening, accepted.addressOpening)
    }

    @Test
    fun digestMismatchFailsBeforeOpen() {
        val f = fixture()
        val bad = f.envelopeSha.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingClaimedEnvelope.openAndAccept(f.sealedEnvelope, bad, f.statement, f.claim, f.recipientPrivate)
        }
        assertEquals("pending envelope digest mismatch", failure.message)
    }

    @Test
    fun wrongRecipientFails() {
        TinkPrimitives.ensureRegistered()
        val f = fixture()
        val other = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        assertFailsWith<GeneralSecurityException> {
            PendingClaimedEnvelope.openAndAccept(f.sealedEnvelope, f.envelopeSha, f.statement, f.claim, other)
        }
    }

    @Test
    fun intentBuildSignVerifies() {
        TinkPrimitives.ensureRegistered()
        val accountKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val h = sha256("statement".toByteArray())
        val intent = PendingClaimIntent.buildCanonical(
            PendingClaimIntent.Fields(
                operationId = "0198f0a0-0000-7000-8000-00000000c001",
                capsuleId = "11111111-2222-3333-4444-555555555555",
                deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                publishStatementSha256 = h,
                proofId = "0198f0a0-0000-7000-8000-00000000a701",
                challengeId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                recipientUserId = "33333333-4444-5555-6666-777777777777",
                recipientKeyBundleId = "01010101-0202-0303-0404-050505050506",
                sessionId = "02020202-0303-0404-0505-060606060606",
                nonce = nonce,
                bindAlias = false,
            ),
        )
        // Canonical form round-trips through the generated classes.
        val parsed = dev.hryshyn.remanence.protocol.delivery_v2.ClaimIntentV2.parseFrom(intent)
        assertEquals(intent.toList(), parsed.toByteArray().toList())
        assertEquals(1, parsed.intentVersion)
        val signature = PendingClaimIntent.signIntent(accountKeys, intent)
        assertEquals(69, signature.size)
        // Self-verifies under the matching public key over the frozen input.
        val input = PendingClaimIntent.CLAIM_INTENT_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + intent
        val publicBytes = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(accountKeys.publicKeysetHandle)
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(publicBytes)
            .getPrimitive(com.google.crypto.tink.PublicKeyVerify::class.java)
            .verify(signature, input)
    }

    @Test
    fun intentRejectsBadSizes() {
        assertFailsWith<IllegalArgumentException> {
            PendingClaimIntent.buildCanonical(
                PendingClaimIntent.Fields(
                    operationId = "not-a-uuid",
                    capsuleId = "11111111-2222-3333-4444-555555555555",
                    deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    publishStatementSha256 = ByteArray(32),
                    proofId = "0198f0a0-0000-7000-8000-00000000a701",
                    challengeId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    recipientUserId = "33333333-4444-5555-6666-777777777777",
                    recipientKeyBundleId = "01010101-0202-0303-0404-050505050506",
                    sessionId = "02020202-0303-0404-0505-060606060606",
                    nonce = ByteArray(32),
                    bindAlias = false,
                ),
            )
        }
    }
}
