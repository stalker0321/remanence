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

    private fun fullPublication(): PendingCapsulePublisher.Publication {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return PendingCapsulePublisher.publishFull(
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
            senderHandleSnapshot = "mykola",
            frontFingerprintBytes = ByteArray(96) { (it * 3).toByte() },
            photos = listOf(
                PendingCapsulePublisher.PhotoInput("photo-one".toByteArray(), 1000, 1000),
                PendingCapsulePublisher.PhotoInput("photo-two".toByteArray(), 1000, 1000),
            ),
            noteUtf8 = "hello",
        )
    }

    @Test
    fun fullPayloadKindSetAndOrder() {
        val publication = fullPublication()
        // Server-frozen declaration order: (kind, ordinal, blob_id).
        assertEquals(
            listOf(1, 2, 3, 3),
            publication.artifacts.map { it.kind },
        )
        assertEquals(
            listOf(-1, -1, 0, 1),
            publication.artifacts.map { it.ordinal },
        )
        val statement = PendingPublishVerifier.verifyStructure(publication.statement)
        // Four declarations: recognition + content + two photos (the
        // kind/order assertion above is the contract; the count binds S
        // to the same full set).
        assertEquals(4, statement.artifactsCount)
        assertEquals(
            publication.artifacts.map { it.kind },
            statement.artifactsList.map { it.kind },
        )
        // Blob tags: 0x01 recognition, 0x02 content, 0x10+ordinal photos.
        val tags = publication.artifacts.map {
            java.util.UUID.fromString(it.blobId).mostSignificantBits ushr 56
        }
        assertEquals(listOf(0x01L, 0x02L, 0x10L, 0x11L), tags)
    }

    @Test
    fun recognitionInnerBytesMatchV1() {
        TinkPrimitives.ensureRegistered()
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val capsuleId = dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule)
        val routing = RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = dev.hryshyn.remanence.core.model.BlobId.parseRest(
                "01010101-0202-0303-0404-050505050505",
            ),
            senderUserId = dev.hryshyn.remanence.core.model.UserId.parseRest(sender),
            recipientUserId = dev.hryshyn.remanence.core.model.UserId.parseRest(
                "33333333-4444-5555-6666-777777777777",
            ),
        )
        val front = ByteArray(96) { (it * 3).toByte() }
        val v1Sealed = RecognitionManifestCodec().buildAndEncrypt(
            capsuleKey, routing, "mykola", 1798765432L, null, front,
        )
        val v1Inner = CapsuleArtifactCryptor().decrypt(
            capsuleKey,
            dev.hryshyn.remanence.core.model.ArtifactAadInput(
                capsuleId = routing.capsuleId,
                blobId = routing.blobId,
                artifactKind = dev.hryshyn.remanence.core.model.CapsuleArtifactKind.RECOGNITION_MANIFEST,
                ordinal = -1,
                senderUserId = routing.senderUserId,
                recipientUserId = routing.recipientUserId,
            ),
            v1Sealed,
        )
        // The seam emits the exact bytes v1 seals (unchanged inner).
        assertContentEquals(
            v1Inner,
            RecognitionManifestCodec().buildPlaintext(
                capsuleId, "mykola", 1798765432L, null, front,
            ),
        )
    }

    @Test
    fun fullPayloadV2InnerMatchesV1() {
        TinkPrimitives.ensureRegistered()
        // ONE capsule key for the whole check: v2-sealed manifests must
        // open to the exact bytes v1 seals (unchanged inner payloads).
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val front = ByteArray(96) { (it * 3).toByte() }
        val photos = listOf(
            PendingCapsulePublisher.PhotoInput("photo-one".toByteArray(), 1000, 1000),
            PendingCapsulePublisher.PhotoInput("photo-two".toByteArray(), 1000, 1000),
        )
        val publication = PendingCapsulePublisher.publishFull(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = ByteArray(32) { it.toByte() },
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = signing,
            capsuleKeyset = capsuleKey,
            senderHandleSnapshot = "mykola",
            frontFingerprintBytes = front,
            photos = photos,
            noteUtf8 = "hello",
        )
        val recognition = publication.artifacts.single { it.kind == 1 }
        assertContentEquals(
            RecognitionManifestCodec().buildPlaintext(
                dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule),
                "mykola", 1798765432L, null, front,
            ),
            PendingArtifactCryptor.decrypt(
                capsuleKey, capsule, recognition.blobId, 1, -1,
                sender, target, recognition.ciphertext,
            ),
        )
        val content = publication.artifacts.single { it.kind == 2 }
        assertContentEquals(
            ContentManifestCodec().buildPlaintext(
                dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule),
                listOf(
                    ManifestPhoto(
                        dev.hryshyn.remanence.core.model.CapsulePhotoIdentity.photoBlobId(
                            dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule), 0,
                        ).value,
                        0, 1000, 1000,
                    ),
                    ManifestPhoto(
                        dev.hryshyn.remanence.core.model.CapsulePhotoIdentity.photoBlobId(
                            dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule), 1,
                        ).value,
                        1, 1000, 1000,
                    ),
                ),
                "hello", null, null, null,
            ),
            PendingArtifactCryptor.decrypt(
                capsuleKey, capsule, content.blobId, 2, -1,
                sender, target, content.ciphertext,
            ),
        )
    }

    @Test
    fun contentInnerBytesMatchV1() {
        TinkPrimitives.ensureRegistered()
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val capsuleId = dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule)
        val blobId = dev.hryshyn.remanence.core.model.BlobId.parseRest(
            "02020202-0303-0404-0505-060606060606",
        )
        val routing = RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = blobId,
            senderUserId = dev.hryshyn.remanence.core.model.UserId.parseRest(sender),
            recipientUserId = dev.hryshyn.remanence.core.model.UserId.parseRest(
                "33333333-4444-5555-6666-777777777777",
            ),
        )
        val photos = listOf(
            ManifestPhoto(
                dev.hryshyn.remanence.core.model.CapsulePhotoIdentity.photoBlobId(
                    capsuleId, 0,
                ).value,
                0, 1000, 1000,
            ),
            ManifestPhoto(
                dev.hryshyn.remanence.core.model.CapsulePhotoIdentity.photoBlobId(
                    capsuleId, 1,
                ).value,
                1, 1000, 1000,
            ),
        )
        val v1Sealed = ContentManifestCodec().buildAndEncrypt(
            capsuleKey, routing, photos, "hello",
        )
        // buildAndEncrypt is the (photos, note) overload; decrypt raw v1.
        val v1Inner = CapsuleArtifactCryptor().decrypt(
            capsuleKey,
            dev.hryshyn.remanence.core.model.ArtifactAadInput(
                capsuleId = routing.capsuleId,
                blobId = routing.blobId,
                artifactKind = dev.hryshyn.remanence.core.model.CapsuleArtifactKind.CONTENT_MANIFEST,
                ordinal = -1,
                senderUserId = routing.senderUserId,
                recipientUserId = routing.recipientUserId,
            ),
            v1Sealed,
        )
        assertContentEquals(
            v1Inner,
            ContentManifestCodec().buildPlaintext(
                capsuleId, photos, "hello", null, null, null,
            ),
        )
    }

    @Test
    fun v1AadCannotOpenV2Manifests() {
        TinkPrimitives.ensureRegistered()
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val capsuleId = dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule)
        val recipient = dev.hryshyn.remanence.core.model.UserId.parseRest(
            "33333333-4444-5555-6666-777777777777",
        )
        val publication = PendingCapsulePublisher.publishFull(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = ByteArray(32),
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = KeysetHandle.generateNew(KeyTemplates.get("ED25519")),
            capsuleKeyset = capsuleKey,
            senderHandleSnapshot = "mykola",
            frontFingerprintBytes = ByteArray(96) { (it * 3).toByte() },
            photos = listOf(
                PendingCapsulePublisher.PhotoInput("photo-one".toByteArray(), 1000, 1000),
            ),
            noteUtf8 = "hello",
        )
        for (artifact in publication.artifacts) {
            // The v1 recipient-bound AAD never opens v2 target-bound bytes.
            assertFailsWith<java.security.GeneralSecurityException> {
                CapsuleArtifactCryptor().decrypt(
                    capsuleKey,
                    dev.hryshyn.remanence.core.model.ArtifactAadInput(
                        capsuleId = capsuleId,
                        blobId = dev.hryshyn.remanence.core.model.BlobId.parseRest(artifact.blobId),
                        artifactKind = when (artifact.kind) {
                            1 -> dev.hryshyn.remanence.core.model.CapsuleArtifactKind.RECOGNITION_MANIFEST
                            2 -> dev.hryshyn.remanence.core.model.CapsuleArtifactKind.CONTENT_MANIFEST
                            else -> dev.hryshyn.remanence.core.model.CapsuleArtifactKind.PHOTO
                        },
                        ordinal = artifact.ordinal,
                        senderUserId = dev.hryshyn.remanence.core.model.UserId.parseRest(sender),
                        recipientUserId = recipient,
                    ),
                    artifact.ciphertext,
                )
            }
        }
    }
}
