package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.ExpressionV1
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V5-P2 (slice section 9 T3/T4): crafted v2 manifests must fail closed with
 * the exact R1/R2/R3/R4/A1-A7 and hash-mismatch messages.
 *
 * Reviewer rules applied throughout: the music/snapshot cross-check (R4b)
 * fires only on artifact expression_version == 2, and every music-bearing
 * negative carries a valid snapshot so R4b can never mask the intended
 * failure under test.
 */
class ContentManifestCodecV3NegativeTest {

    private val codec = ContentManifestCodec()
    private lateinit var keyset: KeysetHandle

    private val capsuleUuid = UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd2001")
    private val contentBlobUuid = UUID.fromString("6f0a1234-5678-4abc-9def-aabbccdd6007")
    private val senderUuid = UUID.fromString("3f0a1234-5678-4abc-9def-aabbccdd3003")
    private val recipientUuid = UUID.fromString("4f0a1234-5678-4abc-9def-aabbccdd4004")

    private val contentIds = listOf("c0", "c1", "c2")
    private val blobUuids = listOf(
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0001"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0002"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0003"),
    )

    private fun uuidBytes(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun routing() = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(capsuleUuid),
        blobId = BlobId(contentBlobUuid),
        senderUserId = UserId(senderUuid),
        recipientUserId = UserId(recipientUuid),
    )

    private fun manifestPhotos(): List<ManifestPhoto> =
        blobUuids.mapIndexed { index, id -> ManifestPhoto(blobId = id, ordinal = index, width = 1000, height = 1000) }

    private fun blobMap(): Map<String, ByteArray> =
        contentIds.mapIndexed { i, id -> id to uuidBytes(blobUuids[i]) }.toMap()

    private fun input() = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(
                contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                contentHash = ('a' + index).toString().repeat(64),
            )
        },
        note = null,
        music = null,
    )

    private fun style() = GeneratorExpression.StyleBinding(
        styleId = "press",
        styleVersion = "style-study-1",
        digest = ByteArray(32) { 7 },
    )

    private fun noteType() = GeneratorExpression.TextType(
        family = "StudySans",
        size = 16,
        weight = 400,
        italic = false,
        line = 18,
    )

    private fun artwork() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 640,
        height = 640,
    )

    private fun placementV3(id: String) = GeneratorExpression.Placement(
        contentId = id,
        x = 0, y = 0, width = 120, height = 213,
        crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
        maskId = null,
        contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213),
    )

    private fun expressionV3Artwork() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = input(),
        placements = contentIds.map(::placementV3),
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        noteRegion = null,
        style = style(),
        noteType = noteType(),
        musicRegion = GeneratorExpression.MusicRegion(
            x = 16, y = 480, width = 328, height = 64,
            mode = "thumbnail",
            type = noteType(),
            artwork = artwork(),
        ),
    )

    private fun expressionV3Base() = expressionV3Artwork().copy(musicRegion = null)

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    private fun contentContext() = ArtifactAadInput(
        capsuleId = CapsuleId(capsuleUuid),
        blobId = BlobId(contentBlobUuid),
        artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
        ordinal = -1,
        senderUserId = UserId(senderUuid),
        recipientUserId = UserId(recipientUuid),
    )

    private fun rawManifest(ciphertext: ByteArray): ContentManifest =
        ContentManifest.parseFrom(CapsuleArtifactCryptor().decrypt(keyset, contentContext(), ciphertext))

    private fun reseal(manifest: ContentManifest): ByteArray =
        CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = contentContext(),
            plaintext = manifest.toByteArray(),
        )

    private fun sealedArtworkRaw(): ContentManifest = rawManifest(
        codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV3Artwork(), blobMap(), snapshot(),
        ),
    )

    private fun sealedV1ArtifactRaw(): ContentManifest {
        val planned = GeneratorEditorialRows.plan(input())
        val v2 = ((planned as GeneratorEditorialRows.PlanResult.Planned).expression)
        return rawManifest(
            codec.buildAndEncryptV2(keyset, routing(), manifestPhotos(), null, "cand-1", v2, blobMap()),
        )
    }

    private fun expectParseMessage(manifest: ContentManifest, expected: String) {
        val failure = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), reseal(manifest))
        }
        assertEquals(expected, failure.message)
    }

    private fun withExpression(raw: ContentManifest, mutate: (ExpressionV1.Builder) -> Unit): ContentManifest {
        val expression = raw.expression.toBuilder().also(mutate).build()
        return raw.toBuilder().setExpression(expression).build()
    }

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun r1UnknownArtifactVersionRejected() {
        val raw = sealedArtworkRaw()
        // Artifact 3 is the T154 full-V5 frame (contract V4); the
        // out-of-range examples are 4 and 99. The frozen message and its
        // precedence survive unchanged.
        expectParseMessage(
            withExpression(raw) { it.expressionVersion = 4 },
            "unsupported expression artifact version",
        )
    }

    @Test
    fun r2Version1WithVersion2FieldsRejected() {
        val raw = sealedV1ArtifactRaw()
        val artworkField = dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.newBuilder()
            .setProvider("catalog-sample")
            .setUrl("https://art.example.org/cover/ab12.webp")
            .setSha256(ByteString.copyFrom(ByteArray(32) { it.toByte() }))
            .setMimeType("image/webp")
            .setWidth(640)
            .setHeight(640)
            .build()
        val musicField = dev.hryshyn.remanence.protocol.v1.ExpressionMusic.newBuilder()
            .setX(16).setY(480).setWidth(328).setHeight(64)
            .setMode("thumbnail")
            .setType(
                dev.hryshyn.remanence.protocol.v1.ExpressionTextType.newBuilder()
                    .setFamily("StudySans").setSize(16).setWeight(400).setLine(18),
            )
            .setArtwork(artworkField)
            .build()
        // v1 + field 20 carrying artwork (snapshot attached per the
        // music-bearing rule; R4b stays gated off on artifact v1).
        val withSnapshot = raw.toBuilder().setTrackSnapshot(
            dev.hryshyn.remanence.protocol.v1.CapsuleTrackSnapshotV1.newBuilder()
                .setSnapshotVersion(1)
                .setTrackId("be30e36b-1111-4111-8111-000000000001")
                .setTitle("505")
                .setArtistDisplay("Arctic Monkeys"),
        ).build()
        expectParseMessage(
            withExpression(withSnapshot) { it.music = musicField },
            "expression artifact version 1 must not carry version 2 fields",
        )
        // v1 + placement 11 (crop).
        expectParseMessage(
            withExpression(raw) {
                val placement = it.getPlacements(0).toBuilder()
                    .setCrop(
                        dev.hryshyn.remanence.protocol.v1.ExpressionCrop.newBuilder()
                            .setX(100).setY(200).setWidth(5000).setHeight(6000),
                    ).build()
                it.setPlacements(0, placement)
            },
            "expression artifact version 1 must not carry version 2 fields",
        )
        // v1 + placement 12 (mask_id).
        expectParseMessage(
            withExpression(raw) {
                val placement = it.getPlacements(0).toBuilder().setMaskId("rect").build()
                it.setPlacements(0, placement)
            },
            "expression artifact version 1 must not carry version 2 fields",
        )
    }

    @Test
    fun r3Version2MissingStyleNoteTypeOrCropRejected() {
        val raw = sealedArtworkRaw()
        expectParseMessage(
            withExpression(raw) { it.clearStyle() },
            "expression artifact version 2 requires style, note type, and per-placement crop",
        )
        expectParseMessage(
            withExpression(raw) { it.clearNoteType() },
            "expression artifact version 2 requires style, note type, and per-placement crop",
        )
        expectParseMessage(
            withExpression(raw) {
                it.setPlacements(0, it.getPlacements(0).toBuilder().clearCrop().build())
            },
            "expression artifact version 2 requires style, note type, and per-placement crop",
        )
    }

    @Test
    fun r4aMusicWithoutTypeRejected() {
        // Snapshot attached so the failure below can only be the R4a
        // structural rule, never the R4b cross-check.
        val raw = sealedArtworkRaw()
        assertTrue(raw.hasTrackSnapshot())
        expectParseMessage(
            withExpression(raw) {
                it.music = it.music.toBuilder().clearType().build()
            },
            "expression music region requires its resolved type",
        )
    }

    @Test
    fun r4bMusicSnapshotMismatchRejected() {
        // Music present without a snapshot.
        val noSnapshot = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV3Artwork(), blobMap(),
        )
        val musicOnly = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), noSnapshot)
        }
        assertEquals("expression music region and track snapshot must match", musicOnly.message)
        // Snapshot present without music (artifact v2, so R4b applies).
        val snapshotOnly = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV3Base(), blobMap(), snapshot(),
        )
        val mismatch = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), snapshotOnly)
        }
        assertEquals("expression music region and track snapshot must match", mismatch.message)
    }

    @Test
    fun a1ModeArtworkMismatchRejectedAtValidation() {
        // Both directions land at :408 with the generic message; the
        // attached snapshot keeps R4b out of the picture.
        val raw = sealedArtworkRaw()
        expectParseMessage(
            withExpression(raw) {
                it.music = it.music.toBuilder().clearArtwork().build()
            },
            "expression artifact failed validation",
        )
        expectParseMessage(
            withExpression(raw) {
                it.music = it.music.toBuilder().setMode("text").build()
            },
            "expression artifact failed validation",
        )
    }

    @Test
    fun artworkDescriptorViolationsRejectedAtValidation() {
        val raw = sealedArtworkRaw()
        val cases = listOf(
            "unknown provider" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.provider = "no-such-provider"
            },
            "http scheme" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.url = "http://art.example.org/cover/ab12.webp"
            },
            "query" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.url = "https://art.example.org/cover/ab12.webp?sig=abc"
            },
            "fragment" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.url = "https://art.example.org/cover/ab12.webp#frag"
            },
            "userinfo" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.url = "https://user@art.example.org/cover/ab12.webp"
            },
            "non-443 port" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.url = "https://art.example.org:8080/cover/ab12.webp"
            },
            "513-char url" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.url = "https://art.example.org/" + "a".repeat(489)
            },
            "sha256 31 bytes" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.sha256 = ByteString.copyFrom(ByteArray(31))
            },
            "sha256 33 bytes" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.sha256 = ByteString.copyFrom(ByteArray(33))
            },
            "gif mime" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.mimeType = "image/gif"
            },
            "width 0" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.width = 0
            },
            "width 4097" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.width = 4097
            },
            "height 4097" to { b: dev.hryshyn.remanence.protocol.v1.ExpressionArtwork.Builder ->
                b.height = 4097
            },
        )
        for ((label, mutate) in cases) {
            expectParseMessage(
                withExpression(raw) {
                    val art = it.music.artwork.toBuilder().also(mutate).build()
                    it.music = it.music.toBuilder().setArtwork(art).build()
                },
                "expression artifact failed validation",
            )
        }
        assertEquals(13, cases.size)
    }

    @Test
    fun tamperedDescriptorThatStaysValidFailsOnProjectionHash() {
        // A descriptor swap that still passes A3-A7 sails through :408 and
        // must die at :411-418 with the hash message (ordering pin).
        val raw = sealedArtworkRaw()
        expectParseMessage(
            withExpression(raw) {
                val art = it.music.artwork.toBuilder()
                    .setUrl("https://art.example.org/cover/cd34.webp")
                    .build()
                it.music = it.music.toBuilder().setArtwork(art).build()
            },
            "expression projection hash mismatch",
        )
    }
}
