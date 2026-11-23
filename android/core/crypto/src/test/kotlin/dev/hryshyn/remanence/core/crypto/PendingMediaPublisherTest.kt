package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ADR-021: a first gift seals its voice note as kind 4 and its video square
 * as kind 5 under the delivery-target AAD, after the photos, and the content
 * manifest names exactly those blobs.
 */
class PendingMediaPublisherTest {

    private val capsule = "21111111-2222-4333-8444-555555555555"
    private val sender = "01010101-0202-4303-8404-050505050505"
    private val senderBundle = "0b0b0b0b-1c1c-4d2d-8e3e-4f4f4f4f4f4f"
    private val target = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val serviceKey = "9f9f9f9f-8e8e-4d7d-8c6c-5b5b5b5b5b5b"
    private val contentIds = listOf("c0", "c1", "c2")
    private val sound = byteArrayOf(0, 0, 0, 16) + "ftypM4A ".toByteArray() + ByteArray(8)
    private val film = byteArrayOf(0, 0, 0, 16) + "ftypisom".toByteArray() + ByteArray(8) { 3 }

    /** Grammar 6 with its band given to a voice note (no cover), or with no band. */
    private fun expression(mode: String?) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "6",
        grammarVersion = 3,
        branchId = "hinge-2/1-0.52-equal-paired",
        input = GeneratorExpression.GeneratorInput(
            ownerId = sender,
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
            },
            note = null,
            music = null,
        ),
        placements = contentIds.mapIndexed { index, id ->
            GeneratorExpression.Placement(
                contentId = id, x = index * 120, y = 0, width = 120, height = 213,
                crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
                maskId = null, contentRect = null, shape = GeneratorExpression.PhotoShape.RECT,
            )
        },
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        noteRegion = null,
        style = GeneratorExpression.StyleBinding("signal", "style-study-1", ByteArray(32) { 7 }),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = mode?.let {
            GeneratorExpression.MusicRegion(
                x = 10, y = 500, width = 340, height = 76, mode = it,
                type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
                artwork = null,
            )
        },
        provenance = listOf(
            GeneratorExpression.ProvenanceMark(
                kind = GeneratorExpression.ProvenanceKind.BRAND, text = GeneratorExpression.BRAND_TEXT,
                x = 12, y = 4, width = 340, height = 75, family = "StudySerif", size = 64, weight = 400,
                italic = false, line = 70, orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                align = GeneratorExpression.MarkAlign.LEFT,
            ),
        ),
        decor = listOf(GeneratorExpression.SceneDecor(GeneratorExpression.DecorKind.CARRIER, 6, 5, 349, 627)),
    )


    private fun publish(voice: PendingCapsulePublisher.MediaInput?, video: PendingCapsulePublisher.MediaInput?, key: KeysetHandle): PendingCapsulePublisher.Publication {
        val resolved = expression(if (voice != null) "voice-plate" else null)
        return PendingCapsulePublisher.publishFull(
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
            signingHandle = KeysetHandle.generateNew(KeyTemplates.get("ED25519")),
            capsuleKeyset = key,
            senderHandleSnapshot = "mykola",
            frontFingerprintBytes = ByteArray(96) { it.toByte() },
            photos = contentIds.map { PendingCapsulePublisher.PhotoInput(it.toByteArray(), 1000, 1000) },
            noteUtf8 = null,
            expression = PendingCapsulePublisher.ExpressionInput(
                candidateId = GeneratorBer1Provider.candidateIdFor(resolved),
                expression = resolved,
                contentIds = contentIds,
                trackSnapshot = null,
                voice = voice,
                video = video,
            ),
        )
    }

    @Test
    fun aVoiceNoteAndAVideoSquareTravelAsKinds4And5NamedByTheManifest() {
        TinkPrimitives.ensureRegistered()
        val key = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val publication = publish(
            PendingCapsulePublisher.MediaInput(sound, 4_200, peaks = ByteArray(48) { it.toByte() }),
            PendingCapsulePublisher.MediaInput(film, 12_300, size = 720, posterContentId = "c1"),
            key,
        )
        assertEquals(listOf(1, 2, 3, 3, 3, 4, 5), publication.artifacts.map { it.kind })
        val voice = publication.artifacts.single { it.kind == 4 }
        val video = publication.artifacts.single { it.kind == 5 }
        assertEquals(-1, voice.ordinal)
        assertEquals(-1, video.ordinal)
        assertContentEquals(sound, PendingArtifactCryptor.decrypt(key, capsule, voice.blobId, 4, -1, sender, target, voice.ciphertext))
        assertContentEquals(film, PendingArtifactCryptor.decrypt(key, capsule, video.blobId, 5, -1, sender, target, video.ciphertext))
        val content = publication.artifacts.single { it.kind == 2 }
        val manifest = ContentManifestCodec().parseAndValidate(
            PendingArtifactCryptor.decrypt(key, capsule, content.blobId, 2, -1, sender, target, content.ciphertext),
            CapsuleId.parseRest(capsule).toProtoBytes().toByteArray(),
        )
        assertEquals(voice.blobId, manifest.voice?.blobId.toString())
        assertEquals(4_200, manifest.voice?.durationMs)
        assertEquals(video.blobId, manifest.video?.blobId.toString())
        assertEquals(ManifestVideo(UUID.fromString(video.blobId), 12_300, 720, "c1"), manifest.video)
    }

    @Test
    fun aVideoAloneNeedsAnMp4AndItsFirstFrame() {
        TinkPrimitives.ensureRegistered()
        val key = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        assertEquals(listOf(1, 2, 3, 3, 3, 5), publish(null, PendingCapsulePublisher.MediaInput(film, 9_000, size = 480, posterContentId = "c2"), key).artifacts.map { it.kind })
        assertFailsWith<IllegalArgumentException> { publish(null, PendingCapsulePublisher.MediaInput(ByteArray(16), 9_000, size = 480, posterContentId = "c2"), key) }
        assertFailsWith<IllegalArgumentException> { publish(null, PendingCapsulePublisher.MediaInput(film, 9_000, size = 480), key) }
    }
}
