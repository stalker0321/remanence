package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.PublishArtifact
import dev.hryshyn.remanence.core.model.PublishStatementBuildResult
import dev.hryshyn.remanence.core.model.PublishStatementBuilder
import dev.hryshyn.remanence.core.model.PublishStatementInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.MediaEntry
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * ADR-021: a card with a video square passes the presentation gate only when
 * its manifest names exactly the one signed VIDEO binding over a square poster
 * among its photos, and the prepared material decrypts that film (an MP4).
 */
class PresentationVideoTest {

    private val gate = PresentationAcceptanceGate()
    private val signer = PublishStatementSigner()
    private val sender = AccountIdentityGenerator().generate()
    private val codec = ContentManifestCodec()
    private lateinit var keyset: KeysetHandle

    private val capsuleId = CapsuleId(UUID.fromString("1b111111-2222-4333-8444-555555555555"))
    private val senderUser = UserId(UUID.fromString("2b222222-3333-4444-8555-666666666666"))
    private val recipientUser = UserId(UUID.fromString("3b333333-4444-4555-8666-777777777777"))
    private val senderBundle = KeyBundleId(UUID.fromString("4b444444-5555-4666-8777-888888888888"))
    private val recipientBundle = KeyBundleId(UUID.fromString("5b555555-6666-4777-8888-999999999999"))
    private val recognitionBlob = BlobId(UUID.fromString("b1000000-0000-4000-8000-000000000001"))
    private val contentBlob = BlobId(UUID.fromString("b1000000-0000-4000-8000-000000000002"))
    private val photoBlobs = (3..5).map { BlobId(UUID.fromString("b1000000-0000-4000-8000-00000000000$it")) }
    private val videoBlob = BlobId(UUID.fromString("b1000000-0000-4000-8000-00000000000a"))
    private val contentIds = listOf("c0", "c1", "c2")

    /** An MP4 that is only its `ftyp` box. */
    private val film = byteArrayOf(0, 0, 0, 16) + "ftypisom".toByteArray() + ByteArray(4)

    private class Capsule(
        val statement: ByteArray,
        val signature: ByteArray,
        val envelope: ByteArray,
        val delivered: List<DeliveredCiphertext>,
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun routing(blob: BlobId) = RecognitionManifestCodec.RoutingContext(capsuleId, blob, senderUser, recipientUser)

    private fun aad(blob: BlobId, kind: CapsuleArtifactKind) = ArtifactAadInput(capsuleId, blob, kind, -1, senderUser, recipientUser)

    /** Grammar 6 with its band given to a voice note (no cover), or with no band. */
    private fun expression(mode: String?) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "6",
        grammarVersion = 3,
        branchId = "hinge-2/1-0.52-equal-paired",
        input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
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

    /** A sealed card; [video] names the film in the manifest, [sealed] puts its blob beside it. */
    private fun capsule(video: ManifestVideo?, sealed: ByteArray?): Capsule {
        val photos = photoBlobs.mapIndexed { ordinal, blob ->
            PhotoArtifactEncryptor().encryptPhoto(keyset, routing(blob), ordinal, ByteArray(512) { (ordinal + it).toByte() })
        }
        val manifestPhotos = photoBlobs.mapIndexed { ordinal, blob -> ManifestPhoto(blob.value, ordinal, 1000, 1000) }
        val blobMap = contentIds.mapIndexed { index, id -> id to uuidBytes(photoBlobs[index].value) }.toMap()
        val content = codec.buildAndEncryptV2(
            keyset, routing(contentBlob), manifestPhotos, null, "cand-1",
            expression(null), blobMap, null, video = video,
        )
        val recognition = RecognitionManifestCodec().buildAndEncrypt(
            keyset, routing(recognitionBlob), "mykola", 1_700_000_000L, null, ByteArray(96) { it.toByte() },
        )
        val sealedVideo = sealed?.let { CapsuleArtifactCryptor().encrypt(keyset, aad(videoBlob, CapsuleArtifactKind.VIDEO), it) }
        val delivered = listOf(DeliveredCiphertext(recognitionBlob, recognition), DeliveredCiphertext(contentBlob, content)) +
            photoBlobs.mapIndexed { ordinal, blob -> DeliveredCiphertext(blob, photos[ordinal].ciphertext) } +
            listOfNotNull(sealedVideo?.let { DeliveredCiphertext(videoBlob, it) })
        val slots = listOf(
            ArtifactSlot(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
            ArtifactSlot(contentBlob, CapsuleArtifactKind.CONTENT_MANIFEST, -1),
        ) + photoBlobs.mapIndexed { ordinal, blob -> ArtifactSlot(blob, CapsuleArtifactKind.PHOTO, ordinal) } +
            listOfNotNull(sealedVideo?.let { ArtifactSlot(videoBlob, CapsuleArtifactKind.VIDEO, -1) })
        val artifacts = slots.zip(delivered).map { (slot, bytes) ->
            PublishArtifact(slot, bytes.ciphertext.size.toLong(), ByteString.copyFrom(sha256(bytes.ciphertext)))
        }
        val statement = (
            PublishStatementBuilder.build(
                PublishStatementInput(capsuleId, senderUser, recipientUser, senderBundle, recipientBundle, 1_700_000_000L, artifacts),
            ) as PublishStatementBuildResult.Success
            ).deterministicBytes.toByteArray()
        val parsed = PublishStatement.parseFrom(statement)
        val envelope = RecipientEnvelopePlaintext.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(parsed.capsuleId)
            .setSenderUserId(parsed.senderUserId)
            .setRecipientUserId(parsed.recipientUserId)
            .setSenderKeyBundleId(parsed.senderKeyBundleId)
            .setRecipientKeyBundleId(parsed.recipientKeyBundleId)
            .setCapsuleAeadKeyset(ByteString.copyFrom(TinkProtoKeysetFormat.serializeKeyset(keyset, InsecureSecretKeyAccess.get())))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .build()
            .toByteArray()
        return Capsule(statement, signer.sign(sender.signingPrivateHandle, statement).signature, envelope, delivered)
    }

    private fun prepare(capsule: Capsule) = gate.prepare(
        PresentationAcceptanceInput(
            expectedCapsuleId = capsuleId,
            authenticatedUserId = recipientUser,
            senderVerifyingKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(sender.signingPublicKeyset),
            expectedSenderKeyBundleId = senderBundle,
            expectedSenderUserId = senderUser,
            expectedRecipientKeyBundleId = recipientBundle,
            envelopePlaintextBytes = capsule.envelope,
            statementBytes = capsule.statement,
            signature = capsule.signature,
            deliveredCiphertexts = capsule.delivered,
        ),
    )

    private val video = ManifestVideo(videoBlob.value, 12_300, 480, "c1")

    @Test
    fun aVideoSquareOpensWithItsOneSignedFilm() {
        val material = assertIs<PresentationAcceptancePreparationResult.Prepared>(prepare(capsule(video, film))).material
        assertEquals(video, material.video())
        assertNull(material.voice())
        assertContentEquals(film, material.loadVideo())
        material.close()
        assertFailsWith<IllegalStateException> { material.loadVideo() }
    }

    @Test
    fun aFilmTheManifestDoesNotNameOrAMissingOneIsRefused() {
        assertIs<PresentationAcceptancePreparationResult.Rejected>(prepare(capsule(null, film)))
        assertIs<PresentationAcceptancePreparationResult.Rejected>(prepare(capsule(video, null)))
    }

    @Test
    fun theManifestRefusesAVideoOutOfBoundsOrWithoutItsPosterAmongThePhotos() {
        for (bad in listOf(
            video.copy(durationMs = 30_001),
            video.copy(size = 100),
            video.copy(posterContentId = "nobody"),
        )) {
            assertFailsWith<IllegalArgumentException> { capsule(bad, film) }
        }
    }

    @Test
    fun aNonMp4FilmFailsClosed() {
        val material = assertIs<PresentationAcceptancePreparationResult.Prepared>(prepare(capsule(video, ByteArray(32)))).material
        assertFailsWith<GeneralSecurityException> { material.loadVideo() }
    }
}
