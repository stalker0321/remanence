package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorExpressionProjection
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ContentManifest
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T154 full frozen V5: contract-4 (GENEX04) <-> artifact
 * expression_version 3 transport round-trips, tamper evidence, and the
 * version-confusion guards. Image BYTES never enter the proto — only the
 * six artwork descriptor fields.
 */
class ContentManifestCodecV4Test {

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
        styleId = "signal",
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

    private fun placementV4(id: String, x: Int, shape: GeneratorExpression.PhotoShape) =
        GeneratorExpression.Placement(
            contentId = id,
            x = x, y = 0, width = 120, height = 213,
            crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
            maskId = null,
            contentRect = null,
            shape = shape,
        )

    private fun brand() = GeneratorExpression.ProvenanceMark(
        kind = GeneratorExpression.ProvenanceKind.BRAND,
        text = GeneratorExpression.BRAND_TEXT,
        x = 12, y = 4, width = 340, height = 75,
        family = "StudySerif", size = 64, weight = 400, italic = false, line = 70,
        orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
        align = GeneratorExpression.MarkAlign.LEFT,
    )

    private fun sender() = GeneratorExpression.ProvenanceMark(
        kind = GeneratorExpression.ProvenanceKind.SENDER,
        text = "From alice",
        x = 16, y = 613, width = 328, height = 23,
        family = "StudySerif", size = 20, weight = 400, italic = true, line = 21,
        orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
        align = GeneratorExpression.MarkAlign.RIGHT,
    )

    private fun expressionV4() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "6",
        grammarVersion = 3,
        branchId = "hinge-2/1-0.52-equal-paired",
        input = input(),
        placements = listOf(
            placementV4("c0", 0, GeneratorExpression.PhotoShape.RECT),
            placementV4("c1", 120, GeneratorExpression.PhotoShape.ROUND),
            placementV4("c2", 240, GeneratorExpression.PhotoShape.RECT),
        ),
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        noteRegion = null,
        style = style(),
        noteType = noteType(),
        musicRegion = GeneratorExpression.MusicRegion(
            x = 10, y = 500, width = 340, height = 76,
            mode = "pulse",
            type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
            artwork = artwork(),
        ),
        provenance = listOf(brand(), sender()),
        decor = listOf(
            GeneratorExpression.SceneDecor(GeneratorExpression.DecorKind.CARRIER, 6, 5, 349, 627),
        ),
    )

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

    private fun senderNormalized(
        parsed: GeneratorExpression.ResolvedExpression,
        sealed: GeneratorExpression.ResolvedExpression,
    ): GeneratorExpression.ResolvedExpression = parsed.copy(
        input = parsed.input.copy(ownerId = sealed.input.ownerId, epoch = sealed.input.epoch),
        diagnostics = sealed.diagnostics,
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun v4RoundTripPreservesNewSlotsAndIdentity() {
        val expression = expressionV4()
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expression, blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        assertEquals(3, raw.expression.expressionVersion)
        assertEquals(2, raw.expression.provenanceCount)
        assertEquals(1, raw.expression.decorCount)
        assertEquals("round", raw.expression.getPlacements(1).shape)

        val content = codec.decryptAndParse(keyset, routing(), ciphertext)
        val parsed = assertNotNull(content.expression)
        assertEquals("cand-1", parsed.candidateId)
        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V4, parsed.expression.expressionContractVersion)
        assertEquals(
            GeneratorExpressionProjection.hash(expression, "cand-1", blobMap()),
            parsed.projectionHash,
        )
        assertEquals(
            GeneratorExpression.canonicalHash(expression),
            GeneratorExpression.canonicalHash(senderNormalized(parsed.expression, expression)),
        )
        assertEquals(expression.provenance, parsed.expression.provenance)
        assertEquals(expression.decor, parsed.expression.decor)
        assertEquals(
            expression.placements.map { it.shape },
            parsed.expression.placements.map { it.shape },
        )
        assertEquals(expression.musicRegion?.mode, parsed.expression.musicRegion?.mode)
        assertNotNull(content.trackSnapshot)
    }

    @Test
    fun v4TamperedProvenanceByteFailsProjectionHash() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV4(), blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        val tampered = raw.toBuilder()
            .setExpression(
                raw.expression.toBuilder()
                    .setProvenance(
                        1,
                        raw.expression.getProvenance(1).toBuilder().setText("From alicf").build(),
                    ).build(),
            ).build()
        val failure = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), reseal(tampered))
        }
        assertEquals("expression projection hash mismatch", failure.message)
    }

    @Test
    fun v1ArtifactMustNotCarryV3Fields() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV4(), blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        // Downgrade the frame to version 1 while keeping v3 fields: the
        // receiver must refuse the confusion rather than drop the slots.
        val downgraded = raw.toBuilder()
            .setExpression(raw.expression.toBuilder().setExpressionVersion(1).build())
            .build()
        val failure = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), reseal(downgraded))
        }
        assertEquals("expression artifact version 1 must not carry version 2 fields", failure.message)
    }

    @Test
    fun v2ArtifactMustNotCarryV3Fields() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV4(), blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        val downgraded = raw.toBuilder()
            .setExpression(raw.expression.toBuilder().setExpressionVersion(2).build())
            .build()
        val failure = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), reseal(downgraded))
        }
        assertEquals("expression artifact version 2 must not carry version 3 fields", failure.message)
    }

    @Test
    fun unknownShapeStringRefused() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV4(), blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        val tampered = raw.toBuilder()
            .setExpression(
                raw.expression.toBuilder()
                    .setPlacements(0, raw.expression.getPlacements(0).toBuilder().setShape("oval").build())
                    .build(),
            ).build()
        val failure = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), reseal(tampered))
        }
        assertEquals("expression placement shape unknown", failure.message)
    }

    @Test
    fun unknownProvenanceKindRefused() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV4(), blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        val tampered = raw.toBuilder()
            .setExpression(
                raw.expression.toBuilder()
                    .setProvenance(0, raw.expression.getProvenance(0).toBuilder().setKind("wordmark").build())
                    .build(),
            ).build()
        val failure = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), reseal(tampered))
        }
        assertEquals("expression provenance kind unknown", failure.message)
    }

    @Test
    fun v4WithoutProvenanceRoundTripsAsNull() {
        val expression = expressionV4().copy(provenance = null, decor = null)
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expression, blobMap(), snapshot(),
        )
        val parsed = assertNotNull(codec.decryptAndParse(keyset, routing(), ciphertext).expression)
        assertNull(parsed.expression.provenance)
        assertNull(parsed.expression.decor)
    }
}
