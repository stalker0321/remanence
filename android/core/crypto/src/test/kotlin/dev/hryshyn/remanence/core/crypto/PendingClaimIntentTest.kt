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
import kotlin.test.assertTrue

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
        val senderSig: ByteArray,
        val senderPublic: KeysetHandle,
        val claim: ByteArray,
        val claimSig: ByteArray,
        val servicePublic: KeysetHandle,
        val sealedEnvelope: ByteArray,
        val envelopeSha: ByteArray,
        val recipientPrivate: KeysetHandle,
        val recipientPublicBytes: ByteArray,
        val rawKey: ByteArray,
        val opening: AddressOpening,
        val expectation: PendingClaimedEnvelope.LocalExpectation,
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
        val senderKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val serviceKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
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
        val recipientKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val recipientPublicBytes =
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(recipientKeys.publicKeysetHandle)
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
            .setRecipientEncryptionPublicKeysetSha256(
                ByteString.copyFrom(sha256(recipientPublicBytes)),
            )
            .setClaimId(ByteString.copyFrom(uuidBytes(claimId)))
            .setClaimedAtEpochSeconds(1_798_765_100L)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(serviceKey))
            .build().toByteArray()
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
        // Sender + service signatures over the frozen domain inputs (the
        // service pair is synthetic test-only, like the backend fixture
        // pattern — never an R-carried key as trust root).
        val senderSig = senderKeys.getPrimitive(com.google.crypto.tink.PublicKeySign::class.java).sign(
            "postmark/pending-publish/v2".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + statement,
        )
        val claimSig = serviceKeys.getPrimitive(com.google.crypto.tink.PublicKeySign::class.java).sign(
            PendingClaimedEnvelope.CLAIM_BINDING_DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + claim,
        )
        val expectation = PendingClaimedEnvelope.LocalExpectation(
            recipientUserId = recipient,
            recipientKeyBundleId = bundle,
            recipientPublicKeyset = recipientPublicBytes,
            nowEpochSeconds = 1_798_765_200L,
            // Deployment pin under test: the same id S/R carry. S/R-only
            // self-consistency over an arbitrary id is NOT sufficient.
            authorizedServiceKeyId = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f",
        )
        return Fixture(
            statement, senderSig, senderKeys.publicKeysetHandle,
            claim, claimSig, serviceKeys.publicKeysetHandle,
            sealed, sha256(sealed), recipientKeys, recipientPublicBytes,
            rawKey, opening, expectation,
        )
    }

    @Test
    fun fullAcceptedWithServiceSignature() {
        val f = fixture()
        val result = PendingClaimedEnvelope.accept(
            f.sealedEnvelope, f.envelopeSha, f.statement, f.claim,
            f.senderSig, f.claimSig, f.recipientPrivate,
            senderVerifyHandle = f.senderPublic,
            serviceVerifyHandle = f.servicePublic,
            expected = f.expectation,
        )
        val accepted = result as PendingClaimedEnvelope.EnvelopeAcceptance.Accepted
        assertContentEquals(f.rawKey, accepted.capsuleAeadKeyset)
        assertEquals(f.opening, accepted.addressOpening)
    }

    @Test
    fun partialWithoutServiceKeyIsUntrusted() {
        val f = fixture()
        val result = PendingClaimedEnvelope.accept(
            f.sealedEnvelope, f.envelopeSha, f.statement, f.claim,
            f.senderSig, f.claimSig, f.recipientPrivate,
            senderVerifyHandle = f.senderPublic,
            serviceVerifyHandle = null,
            expected = f.expectation,
        )
        // Explicitly untrusted type — never a production Accepted.
        val partial = result as PendingClaimedEnvelope.EnvelopeAcceptance.PartiallyAccepted
        assertContentEquals(f.rawKey, partial.capsuleAeadKeyset)
    }

    @Test
    fun handleWithoutPinNeverYieldsAccepted() {
        val f = fixture()
        // A service handle WITHOUT an authorized deployment UUID must not
        // yield Accepted — the numeric Tink key id is not a deployment UUID.
        val unpinned = f.expectation.copy(authorizedServiceKeyId = null)
        val result = PendingClaimedEnvelope.accept(
            f.sealedEnvelope, f.envelopeSha, f.statement, f.claim,
            f.senderSig, f.claimSig, f.recipientPrivate,
            senderVerifyHandle = f.senderPublic,
            serviceVerifyHandle = f.servicePublic,
            expected = unpinned,
        )
        assertTrue(result is PendingClaimedEnvelope.EnvelopeAcceptance.PartiallyAccepted)
    }

    @Test
    fun digestMismatchFailsBeforeOpen() {
        val f = fixture()
        val bad = f.envelopeSha.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingClaimedEnvelope.accept(
                f.sealedEnvelope, bad, f.statement, f.claim,
                f.senderSig, f.claimSig, f.recipientPrivate,
                f.senderPublic, f.servicePublic, f.expectation,
            )
        }
        assertEquals("pending envelope digest mismatch", failure.message)
    }

    @Test
    fun wrongSenderSignatureFails() {
        val f = fixture()
        val other = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val badSig = other.getPrimitive(com.google.crypto.tink.PublicKeySign::class.java).sign(
            "postmark/pending-publish/v2".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + f.statement,
        )
        assertFailsWith<GeneralSecurityException> {
            PendingClaimedEnvelope.accept(
                f.sealedEnvelope, f.envelopeSha, f.statement, f.claim,
                badSig, f.claimSig, f.recipientPrivate,
                f.senderPublic, f.servicePublic, f.expectation,
            )
        }
    }

    @Test
    fun wrongRecipientFails() {
        TinkPrimitives.ensureRegistered()
        val f = fixture()
        val other = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        assertFailsWith<GeneralSecurityException> {
            PendingClaimedEnvelope.accept(
                f.sealedEnvelope, f.envelopeSha, f.statement, f.claim,
                f.senderSig, f.claimSig, other,
                f.senderPublic, f.servicePublic, f.expectation,
            )
        }
    }

    @Test
    fun wrongServicePinRejected() {
        val f = fixture()
        // S/R agree with each other but NOT with the deployment pin:
        // self-consistency over an arbitrary id proves nothing.
        val wrong = f.expectation.copy(authorizedServiceKeyId = "0c0c0c0c-1c1c-2d2d-3e3e-4f4f4f4f4f4f")
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingClaimedEnvelope.accept(
                f.sealedEnvelope, f.envelopeSha, f.statement, f.claim,
                f.senderSig, f.claimSig, f.recipientPrivate,
                f.senderPublic, f.servicePublic, wrong,
            )
        }
        assertEquals("pending envelope service key mismatch", failure.message)
    }

    @Test
    fun ownerMismatchFailsBeforeHpke() {
        val f = fixture()
        val wrong = f.expectation.copy(recipientUserId = "99999999-2222-3333-4444-555555555555")
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingClaimedEnvelope.accept(
                f.sealedEnvelope, f.envelopeSha, f.statement, f.claim,
                f.senderSig, f.claimSig, f.recipientPrivate,
                f.senderPublic, f.servicePublic, wrong,
            )
        }
        assertEquals("pending envelope owner mismatch", failure.message)
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
    fun intentBindAliasTrueRoundTripsAndVerifies() {
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
                bindAlias = true,
            ),
        )
        // Canonical form round-trips; the true bit survives the wire.
        val parsed = dev.hryshyn.remanence.protocol.delivery_v2.ClaimIntentV2.parseFrom(intent)
        assertEquals(intent.toList(), parsed.toByteArray().toList())
        assertTrue(parsed.bindAlias)
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
    fun intentRejectsMalformedUuid() {
        // PendingUuid16 raises GeneralSecurityException (not bare
        // IllegalArgumentException) so malformed ids fail the crypto gate,
        // never slip into signed bytes. Size checks below stay
        // IllegalArgumentException by construction (documented).
        assertFailsWith<GeneralSecurityException> {
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
