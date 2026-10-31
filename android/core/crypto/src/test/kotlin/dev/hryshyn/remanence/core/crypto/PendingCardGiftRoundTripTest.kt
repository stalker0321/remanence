package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.PendingCardSealV1
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ADR-020: what the sender seals opens on the recipient, through the ordinary admission. */
class PendingCardGiftRoundTripTest {
    private val hybridTemplate = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

    @BeforeTest
    fun register() {
        HybridConfig.register()
        SignatureConfig.register()
        com.google.crypto.tink.aead.AeadConfig.register()
    }

    private fun uuid16(value: String): ByteString {
        val id = UUID.fromString(value)
        return ByteString.copyFrom(
            java.nio.ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array(),
        )
    }

    @Test
    fun recipientAdmitsTheEnvelopeItSealedItself() {
        val capsuleId = UUID.randomUUID().toString()
        val targetId = UUID.randomUUID().toString()
        val senderId = UUID.randomUUID().toString()
        val senderBundle = UUID.randomUUID().toString()
        val serviceKeyId = UUID.randomUUID().toString()
        val recipientId = UUID.randomUUID().toString()
        val recipientBundle = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000L
        val senderSigning = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val senderHpke = KeysetHandle.generateNew(KeyTemplates.get(hybridTemplate))
        val serviceSigning = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val recipientHpke = KeysetHandle.generateNew(KeyTemplates.get(hybridTemplate))
        val capsuleKeyset = CapsuleKeysetGenerator().generate()

        val publication = PendingCapsulePublisher.publish(
            capsuleId = capsuleId, senderUserId = senderId, senderKeyBundleId = senderBundle,
            deliveryTargetId = targetId, serviceKeyId = serviceKeyId,
            channel = PendingPublishCrypto.CHANNEL_SMS, normalizedDestination = "+31612345612",
            salt32 = ByteArray(32) { 7 }, claimExpiresAt = now + 86_400, createdAtEpochSeconds = now,
            signingHandle = senderSigning, capsuleKeyset = capsuleKeyset, photos = listOf(ByteArray(64) { 1 }),
            cardSenderPublic = senderHpke.publicKeysetHandle,
        )
        val card = assertNotNull(publication.card)
        assertEquals(0, publication.escrow.size, "a card gift leaves no key for the service")
        assertEquals(11, card.code.length)
        assert(card.code.endsWith("12"))

        // Only the owner of the sender key reads the code back.
        assertEquals(card.code, PendingCardSeal.openCodeFromSelf(card.senderCodeSealed, capsuleId, senderHpke))

        val parsed = assertNotNull(PendingCardSeal.parseCode(card.code))
        val seal = PendingCardSealV1.parseFrom(card.seal)
        assertNull(PendingCardSeal.open("0000000", seal))
        val opened = assertNotNull(PendingCardSeal.open(parsed.secret, seal))
        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest(opened.proof),
            seal.claimProofVerifier.toByteArray(),
        )

        val recipientPublic = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(recipientHpke.publicKeysetHandle)
        val binding = ClaimBindingV1.newBuilder()
            .setBindingVersion(1)
            .setCapsuleProtocolVersion(2)
            .setCapsuleId(uuid16(capsuleId))
            .setDeliveryTargetId(uuid16(targetId))
            .setPublishStatementSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(publication.statement)))
            .setAddressCommitment(seal.binding.addressCommitment)
            .setRecipientUserId(uuid16(recipientId))
            .setRecipientKeyBundleId(uuid16(recipientBundle))
            .setRecipientEncryptionPublicKeysetSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(recipientPublic)))
            .setClaimId(uuid16(UUID.randomUUID().toString()))
            .setClaimedAtEpochSeconds(now)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(uuid16(serviceKeyId))
            .build()
            .toByteArray()
        val envelope = PendingCardSeal.sealEnvelopeToSelf(
            opened, publication.statement, binding, recipientHpke.publicKeysetHandle,
        )
        val bindingSignature = serviceSigning.getPrimitive(PublicKeySign::class.java).sign(
            "postmark/claim-binding/v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + binding,
        )

        val acceptance = PendingClaimedEnvelope.accept(
            sealedEnvelope = envelope,
            expectedEnvelopeSha256 = MessageDigest.getInstance("SHA-256").digest(envelope),
            statementBytes = publication.statement,
            claimBytes = binding,
            senderSignature = publication.signature,
            claimSignature = bindingSignature,
            recipientPrivateHandle = recipientHpke,
            senderVerifyHandle = senderSigning.publicKeysetHandle,
            serviceVerifyHandle = serviceSigning.publicKeysetHandle,
            expected = PendingClaimedEnvelope.LocalExpectation(
                recipientUserId = recipientId,
                recipientKeyBundleId = recipientBundle,
                recipientPublicKeyset = recipientPublic,
                nowEpochSeconds = now,
                authorizedServiceKeyId = serviceKeyId,
            ),
        )
        val accepted = assertIs<PendingClaimedEnvelope.EnvelopeAcceptance.Accepted>(acceptance)
        assertContentEquals(opened.capsuleAeadKeyset, accepted.capsuleAeadKeyset)
    }
}
