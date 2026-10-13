package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.protocol.delivery_v2.PendingEscrowPlaintextV1
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T186 M12: v2 sealed publication — structural coherence (S parses,
 * D/KC recompute, escrow binds H/D/KC/opening, photos round-trip,
 * sender signature verifies). No faked manifests.
 */
class PendingCapsulePublisherTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val sender = "01010101-0202-0303-0404-050505050505"
    private val senderBundle = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val serviceKey = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b"

    private fun publication(): PendingCapsulePublisher.Publication {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return PendingCapsulePublisher.publish(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = salt,
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = signing,
            capsuleKeyset = capsuleKey,
            photos = listOf("photo-one".toByteArray(), "photo-two".toByteArray()),
        )
    }

    @Test
    fun sealedPublicationIsCoherent() {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val publication = PendingCapsulePublisher.publish(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = salt,
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = signing,
            capsuleKeyset = capsuleKey,
            photos = listOf("photo-one".toByteArray(), "photo-two".toByteArray()),
        )
        // S parses structurally under the shared verifier path.
        val statement = PendingPublishVerifier.verifyStructure(publication.statement)
        assertEquals(2, statement.protocolVersion)
        // Sender signature verifies under the sender key.
        PendingPublishVerifier.verifyPublishWithHandle(
            signing.publicKeysetHandle, publication.statement, publication.signature,
        )
        // Escrow binds H(S)/D/KC and the exact opening.
        val escrow = PendingEscrowPlaintextV1.parseFrom(publication.escrow)
        assertContentEquals(
            PendingPublishCrypto.statementSha256(publication.statement),
            escrow.publishStatementSha256.toByteArray(),
        )
        assertContentEquals(
            PendingPublishCrypto.addressCommitment(escrow.addressOpening.toByteArray()),
            escrow.addressCommitment.toByteArray(),
        )
        // Photos round-trip under the capsule key with v2 AAD.
        assertEquals(2, publication.artifacts.size)
        publication.artifacts.forEachIndexed { index, artifact ->
            assertEquals(3, artifact.kind)
            assertEquals(index, artifact.ordinal)
            assertContentEquals(
                listOf("photo-one", "photo-two")[index].toByteArray(),
                PendingArtifactCryptor.decrypt(
                    capsuleKey, capsule, artifact.blobId, 3, index,
                    sender, target, artifact.ciphertext,
                ),
            )
        }
    }

    @Test
    fun emptyPhotosRefused() {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        assertFailsWith<IllegalArgumentException> {
            PendingCapsulePublisher.publish(
                capsuleId = capsule, senderUserId = sender,
                senderKeyBundleId = senderBundle, deliveryTargetId = target,
                serviceKeyId = serviceKey, channel = 1,
                normalizedDestination = "a42@example.test", salt32 = ByteArray(32),
                claimExpiresAt = 1L, createdAtEpochSeconds = 1L,
                signingHandle = signing, capsuleKeyset = capsuleKey,
                photos = emptyList(),
            )
        }
    }

    @Test
    fun badChannelRefused() {
        TinkPrimitives.ensureRegistered()
        assertFailsWith<IllegalArgumentException> {
            PendingCapsulePublisher.publish(
                capsuleId = capsule, senderUserId = sender,
                senderKeyBundleId = senderBundle, deliveryTargetId = target,
                serviceKeyId = serviceKey, channel = 0,
                normalizedDestination = "a42@example.test", salt32 = ByteArray(32),
                claimExpiresAt = 1L, createdAtEpochSeconds = 1L,
                signingHandle = KeysetHandle.generateNew(KeyTemplates.get("ED25519")),
                capsuleKeyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")),
                photos = listOf("x".toByteArray()),
            )
        }
    }

    @Test
    fun fixtureHelperBuilds() {
        assertTrue(publication().artifacts.size == 2)
    }
}
