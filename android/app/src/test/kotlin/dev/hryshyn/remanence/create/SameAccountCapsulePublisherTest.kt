package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceGate
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceInput
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceResult
import dev.hryshyn.remanence.core.crypto.CapsuleArtifactCryptor
import dev.hryshyn.remanence.core.crypto.DeliveredBlob
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId

/**
 * I08 end-to-end proof on the JVM with real crypto: publish produces a
 * ciphertext-only capsule whose envelope opens, whose statement passes the
 * acceptance gate, and whose artifacts decrypt under the envelope-carried
 * capsule keyset.
 */
class SameAccountCapsulePublisherTest {

    private val publisher = SameAccountCapsulePublisher()
    private val identity = AccountIdentityGenerator().generate()

    private val capsuleId = UUID.fromString("4d111111-2222-4333-8444-555555555555")
    private val userId = UUID.fromString("4d222222-3333-4444-8555-666666666666")
    private val bundleId = UUID.fromString("4d333333-4444-4555-8666-777777777777")

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

    private fun request() = SameAccountCapsuleRequest(
        capsuleId = CapsuleId(capsuleId),
        senderUserId = UserId(userId),
        senderKeyBundleId = KeyBundleId(bundleId),
        senderHandleSnapshot = "mykola",
        createdAtEpochSeconds = 1_700_000_000L,
        photoJpegs = (0 until 3).map { "jpeg-$it".toByteArray() + ByteArray(32) { b -> b.toByte() } },
        photoWidthsPx = listOf(800, 800, 800),
        photoHeightsPx = listOf(600, 600, 600),
        noteUtf8 = "hello self",
        frontFingerprintBytes = "front-fp".toByteArray(),
        backFingerprintBytes = "back-fp".toByteArray(),
        signingKeyset = identity.signingPrivateHandle,
        recipientEncryptionPublicKeyset = com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            identity.encryptionPublicKeyset,
        ),
    )

    @Test
    fun publishedCapsuleCarriesExactArtifactCardinalityAndEnvelope() {
        val prepared = publisher.publish(request())

        assertEquals(5, prepared.artifacts.size)
        assertEquals(
            listOf(OutboxArtifactKind.RECOGNITION_MANIFEST, OutboxArtifactKind.CONTENT_MANIFEST) +
                List(3) { OutboxArtifactKind.PHOTO },
            prepared.artifacts.map { it.kind },
        )
        assertTrue(prepared.envelopeCiphertext.size > 60)
        assertTrue(prepared.publishStatementBytes.isNotEmpty())
        assertEquals(69, prepared.publishStatementSignature.size)
    }

    @Test
    fun acceptanceGateVerifiesThePublishedCapsuleEndToEnd() {
        val prepared = publisher.publish(request())

        // Open our own envelope to recover capsule keyset + statement binding.
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        val envelopePlaintext = RecipientEnvelopePlaintext.parseFrom(opened)

        val result = CapsuleAcceptanceGate().verify(
            CapsuleAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleId),
                authenticatedUserId = UserId(userId),
                senderVerifyingKeyset = com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    identity.signingPublicKeyset,
                ),
                expectedSenderKeyBundleId = KeyBundleId(bundleId),
                envelopePlaintextBytes = opened,
                statementBytes = prepared.publishStatementBytes,
                signature = prepared.publishStatementSignature,
                deliveredBlobs = prepared.artifacts.map { artifact ->
                    DeliveredBlob(
                        BlobId(artifact.blobId),
                        artifact.ciphertext.size.toLong(),
                        sha256(artifact.ciphertext),
                    )
                },
            ),
        )
        val accepted = result as? CapsuleAcceptanceResult.Accepted
            ?: throw AssertionError("gate must accept own capsule: $result")
        assertEquals(5, accepted.statement.artifactsCount)
    }

    @Test
    fun envelopeCarriedKeysetDecryptsPhotoOrdinalZero() {
        val prepared = publisher.publish(request())

        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )

        val photo = prepared.artifacts.first { it.kind == OutboxArtifactKind.PHOTO }
        val decrypted = CapsuleArtifactCryptor().decrypt(
            capsuleKeyset,
            ArtifactAadInput(
                CapsuleId(capsuleId),
                BlobId(photo.blobId),
                CapsuleArtifactKind.PHOTO,
                ordinal = 0,
                senderUserId = UserId(userId),
                recipientUserId = UserId(userId),
            ),
            photo.ciphertext,
        )
        assertTrue(String(decrypted).startsWith("jpeg-0"))
    }

    @Test
    fun fewerThanThreePhotosIsRejectedBeforeAnyWork() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { publisher.publish(request().copy(photoJpegs = request().photoJpegs.take(2))) }
        }
        Unit
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
