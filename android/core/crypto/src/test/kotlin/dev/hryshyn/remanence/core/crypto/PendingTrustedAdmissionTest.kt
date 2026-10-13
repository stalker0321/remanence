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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T186 M14a: trusted admission — the directory owner-pair gate fires
 * BEFORE any crypto (substituted bundle ⇒ `OWNER_MISMATCH`-style
 * failure, never proof readiness), and a matching pair delegates to
 * the full acceptance chain (Accepted vs typed partial).
 */
class PendingTrustedAdmissionTest {

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
    )

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val sender = "01010101-0202-0303-0404-050505050505"
    private val recipient = "33333333-4444-5555-6666-777777777777"
    private val bundle = "01010101-0202-0303-0404-050505050506"
    private val serviceId = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"

    private fun fixture(): Fixture {
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
        val rawKey = TinkProtoKeysetFormat.serializeKeyset(aesHandle, InsecureSecretKeyAccess.get())
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
            .setClaimSigningKeyId(ByteString.copyFrom(uuidBytes(serviceId)))
            .build().toByteArray()
        val recipientKeys = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val recipientPublicBytes =
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(recipientKeys.publicKeysetHandle)
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
            .setClaimId(ByteString.copyFrom(uuidBytes("55555555-6666-7777-8888-999999999999")))
            .setClaimedAtEpochSeconds(1_798_765_100L)
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
            .setClaimBindingSha256(ByteString.copyFrom(sha256(claim)))
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
                    .setClaimBindingSha256(ByteString.copyFrom(sha256(claim)))
                    .build(),
            )
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .setAddressOpening(opening)
            .build().toByteArray()
        val info = PendingClaimedEnvelope.CLAIMED_ENVELOPE_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + contextRaw
        val sealed = recipientKeys.publicKeysetHandle
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(plaintext, info)
        val senderSig = senderKeys.getPrimitive(PublicKeySign::class.java).sign(
            "postmark/pending-publish/v2".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + statement,
        )
        val claimSig = serviceKeys.getPrimitive(PublicKeySign::class.java).sign(
            PendingClaimedEnvelope.CLAIM_BINDING_DOMAIN.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + claim,
        )
        return Fixture(
            statement, senderSig, senderKeys.publicKeysetHandle,
            claim, claimSig, serviceKeys.publicKeysetHandle,
            sealed, sha256(sealed), recipientKeys, recipientPublicBytes, rawKey,
        )
    }

    private fun expectation(directoryOwner: String, recipientPublicBytes: ByteArray) =
        PendingTrustedAdmission.LocalExpectation(
            recipientUserId = recipient,
            recipientKeyBundleId = bundle,
            directoryBundleOwnerUserId = directoryOwner,
            recipientPublicKeyset = recipientPublicBytes,
            nowEpochSeconds = 1_798_765_200L,
            authorizedServiceKeyId = serviceId,
        )

    @Test
    fun ownerMismatchRejectedBeforeCrypto() {
        val f = fixture()
        // Everything else is valid — only the directory owner differs
        // from the statement sender. Must fail before any proof/HPKE.
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingTrustedAdmission.accept(
                sealedEnvelope = f.sealedEnvelope,
                expectedEnvelopeSha256 = f.envelopeSha,
                statementBytes = f.statement,
                claimBytes = f.claim,
                senderSignature = f.senderSig,
                claimSignature = f.claimSig,
                recipientPrivateHandle = f.recipientPrivate,
                senderVerifyHandle = f.senderPublic,
                serviceVerifyHandle = f.servicePublic,
                expected = expectation("99999999-2222-3333-4444-555555555555", f.recipientPublicBytes),
            )
        }
        assertTrue(failure.message!!.contains("owner mismatch"))
    }

    @Test
    fun matchingOwnerAdmitsWithServicePin() {
        val f = fixture()
        val admitted = PendingTrustedAdmission.accept(
            sealedEnvelope = f.sealedEnvelope,
            expectedEnvelopeSha256 = f.envelopeSha,
            statementBytes = f.statement,
            claimBytes = f.claim,
            senderSignature = f.senderSig,
            claimSignature = f.claimSig,
            recipientPrivateHandle = f.recipientPrivate,
            senderVerifyHandle = f.senderPublic,
            serviceVerifyHandle = f.servicePublic,
            expected = expectation(sender, f.recipientPublicBytes),
        ) as PendingTrustedAdmission.Admission.Admitted
        assertContentEquals(f.rawKey, admitted.capsuleAeadKeyset)
    }

    @Test
    fun matchingOwnerWithoutServicePinIsPartial() {
        val f = fixture()
        val partial = PendingTrustedAdmission.accept(
            sealedEnvelope = f.sealedEnvelope,
            expectedEnvelopeSha256 = f.envelopeSha,
            statementBytes = f.statement,
            claimBytes = f.claim,
            senderSignature = f.senderSig,
            claimSignature = f.claimSig,
            recipientPrivateHandle = f.recipientPrivate,
            senderVerifyHandle = f.senderPublic,
            serviceVerifyHandle = null,
            expected = expectation(sender, f.recipientPublicBytes).copy(authorizedServiceKeyId = null),
        )
        // Explicitly untrusted type — never scan/display admission.
        assertTrue(partial is PendingTrustedAdmission.Admission.PartiallyAdmitted)
    }
}
