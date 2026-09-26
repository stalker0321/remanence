package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V5-P2 (slice section 9 T2/T5): contract-3 (GENEX03) <-> artifact
 * expression_version 2 transport round-trips, frozen identity/hash, and the
 * v1 encode anti-in-place-extension guard.
 *
 * Hermetic artwork fixture shape mirrors the model T8 fixture
 * (provider "catalog-sample", example.org URL, explicit 32 bytes,
 * image/webp, 640x640); NO test performs a fetch and image BYTES never
 * enter the proto — only the six descriptor fields.
 */
class ContentManifestCodecV3Test {

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

    private fun expressionV3Base() = GeneratorExpression.ResolvedExpression(
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
        musicRegion = null,
    )

    private fun expressionV3TextMusic() = expressionV3Base().copy(
        musicRegion = GeneratorExpression.MusicRegion(
            x = 16, y = 480, width = 328, height = 64,
            mode = "text",
            type = noteType(),
            artwork = null,
        ),
    )

    private fun expressionV3Artwork() = expressionV3Base().copy(
        musicRegion = GeneratorExpression.MusicRegion(
            x = 16, y = 480, width = 328, height = 64,
            mode = "thumbnail",
            type = noteType(),
            artwork = artwork(),
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

    /**
     * GENEX03 binds sender owner/epoch/diagnostics, which the receiver
     * cannot reproduce by design (it reconstructs owner "receiver",
     * epoch 0, no diagnostics). Normalizing those transport-excluded
     * fields proves the sealed descriptor/geometry payload itself
     * round-tripped byte-identically.
     */
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
    fun v3RoundTripWithArtworkPreservesAllFieldsAndIdentity() {
        val expression = expressionV3Artwork()
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expression, blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        assertEquals(2, raw.expression.expressionVersion)
        assertTrue(raw.expression.hasMusic())
        assertTrue(raw.expression.music.hasArtwork())

        val content = codec.decryptAndParse(keyset, routing(), ciphertext)
        val parsed = assertNotNull(content.expression)
        assertEquals("cand-1", parsed.candidateId)
        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V3, parsed.expression.expressionContractVersion)
        // Frozen identity/hash obligations (slice section 3): expression_hash
        // equal, GENEX03 canonical hash of the parsed model equal, all six
        // artwork fields + mode + type equal.
        assertEquals(
            GeneratorExpressionProjection.hash(expression, "cand-1", blobMap()),
            parsed.projectionHash,
        )
        assertEquals(
            GeneratorExpression.canonicalHash(expression),
            GeneratorExpression.canonicalHash(senderNormalized(parsed.expression, expression)),
        )
        val sealedArt = assertNotNull(expression.musicRegion?.artwork)
        val parsedArt = assertNotNull(parsed.expression.musicRegion?.artwork)
        assertEquals(sealedArt.provider, parsedArt.provider)
        assertEquals(sealedArt.url, parsedArt.url)
        assertTrue(sealedArt.sha256.contentEquals(parsedArt.sha256))
        assertEquals(sealedArt.mimeType, parsedArt.mimeType)
        assertEquals(sealedArt.width, parsedArt.width)
        assertEquals(sealedArt.height, parsedArt.height)
        assertEquals(expression.musicRegion?.mode, parsed.expression.musicRegion?.mode)
        assertEquals(expression.musicRegion?.type, parsed.expression.musicRegion?.type)
        assertEquals(expression.style, parsed.expression.style)
        assertEquals(expression.noteType, parsed.expression.noteType)
        assertEquals(
            expression.placements.map { it.crop },
            parsed.expression.placements.map { it.crop },
        )
        assertNotNull(content.trackSnapshot)
    }

    @Test
    fun v3RoundTripModeTextWithoutArtwork() {
        val expression = expressionV3TextMusic()
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expression, blobMap(), snapshot(),
        )
        val raw = rawManifest(ciphertext)
        assertTrue(raw.expression.hasMusic())
        assertFalse(raw.expression.music.hasArtwork())

        val parsed = assertNotNull(codec.decryptAndParse(keyset, routing(), ciphertext).expression)
        assertEquals("cand-1", parsed.candidateId)
        assertNotNull(parsed.expression.musicRegion)
        assertNull(parsed.expression.musicRegion?.artwork)
        assertEquals("text", parsed.expression.musicRegion?.mode)
        assertEquals(
            GeneratorExpression.canonicalHash(expression),
            GeneratorExpression.canonicalHash(senderNormalized(parsed.expression, expression)),
        )
    }

    @Test
    fun v3RoundTripWithoutMusicRegion() {
        val expression = expressionV3Base()
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expression, blobMap(),
        )
        val raw = rawManifest(ciphertext)
        assertFalse(raw.expression.hasMusic())

        val parsed = assertNotNull(codec.decryptAndParse(keyset, routing(), ciphertext).expression)
        assertNull(parsed.expression.musicRegion)
        assertEquals(
            GeneratorExpression.canonicalHash(expression),
            GeneratorExpression.canonicalHash(senderNormalized(parsed.expression, expression)),
        )
    }

    @Test
    fun sealAcceptsV2AndV3ButRejectsV1Contract() {
        val v2input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1", epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = null, music = null,
        )
        val planned = GeneratorEditorialRows.plan(v2input)
        assertTrue(planned is GeneratorEditorialRows.PlanResult.Planned, "got $planned")
        val v2 = planned.expression
        val v2Ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", v2, blobMap(),
        )
        assertEquals(1, rawManifest(v2Ciphertext).expression.expressionVersion)

        val v3Ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV3Base(), blobMap(),
        )
        assertEquals(2, rawManifest(v3Ciphertext).expression.expressionVersion)

        val v1 = v2.copy(expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V1)
        val failure = assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncryptV2(keyset, routing(), manifestPhotos(), null, "cand-1", v1, blobMap())
        }
        assertEquals("expression artifact requires a v2 expression", failure.message)
    }

    @Test
    fun v1EncodePathWritesNoVersion2Fields() {
        val v2input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1", epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = null, music = null,
        )
        val planned = GeneratorEditorialRows.plan(v2input)
        val v2 = ((planned as GeneratorEditorialRows.PlanResult.Planned).expression)
        val raw = rawManifest(
            codec.buildAndEncryptV2(keyset, routing(), manifestPhotos(), null, "cand-1", v2, blobMap()),
        )
        // Anti in-place extension: the v1 artifact path must stay free of
        // every v2 field even after the V3 transport lands.
        assertEquals(1, raw.expression.expressionVersion)
        assertFalse(raw.expression.hasStyle())
        assertFalse(raw.expression.hasNoteType())
        assertFalse(raw.expression.hasMusic())
        assertTrue(raw.expression.placementsList.none { it.hasCrop() || it.hasMaskId() })
    }

    @Test
    fun r1UnknownArtifactVersionRejectedAndVersionCheckWins() {
        val sealed = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null, "cand-1", expressionV3Base(), blobMap(),
        )
        val base = rawManifest(sealed).expression
        // Unknown artifact versions fail with the frozen R1 message, which
        // fires before the candidate-id check (blank candidate still reports
        // the version failure, mirroring the rc.5 pin).
        for (version in listOf(3, 99)) {
            val mutated = reseal(
                rawManifest(sealed).toBuilder()
                    .setExpression(base.toBuilder().setExpressionVersion(version).build())
                    .build(),
            )
            val failure = assertFailsWith<GeneralSecurityException> {
                codec.decryptAndParse(keyset, routing(), mutated)
            }
            assertEquals("unsupported expression artifact version", failure.message)
        }
        val blankCandidate = reseal(
            rawManifest(sealed).toBuilder()
                .setExpression(base.toBuilder().setExpressionVersion(3).setCandidateId("").build())
                .build(),
        )
        val ordered = assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), blankCandidate)
        }
        assertEquals("unsupported expression artifact version", ordered.message)
    }

    @Test
    fun descriptorIdentityPairAtCodecLevel() {
        val base = expressionV3Artwork()
        val urlVariant = base.copy(
            musicRegion = base.musicRegion!!.copy(
                artwork = base.musicRegion!!.artwork!!.copy(url = "https://art.example.org/cover/cd34.webp"),
            ),
        )
        val digestVariant = base.copy(
            musicRegion = base.musicRegion!!.copy(
                artwork = base.musicRegion!!.artwork!!.copy(sha256 = ByteArray(32) { (it + 1).toByte() }),
            ),
        )
        // GENEX03 binds the descriptor: any descriptor change yields a new
        // candidate identity.
        assertNotEquals(GeneratorExpression.canonicalHash(base), GeneratorExpression.canonicalHash(urlVariant))
        assertNotEquals(GeneratorExpression.canonicalHash(base), GeneratorExpression.canonicalHash(digestVariant))
        assertEquals(
            GeneratorExpression.canonicalHash(base),
            GeneratorExpression.canonicalHash(expressionV3Artwork()),
        )
        // BEXPR02 binds it too: the sealed expression_hash differs.
        fun sealedHash(expression: GeneratorExpression.ResolvedExpression): String {
            val ciphertext = codec.buildAndEncryptV2(
                keyset, routing(), manifestPhotos(), null, "cand-1", expression, blobMap(), snapshot(),
            )
            return rawManifest(ciphertext).expression.expressionHash
        }
        assertNotEquals(sealedHash(base), sealedHash(urlVariant))
        assertNotEquals(sealedHash(base), sealedHash(digestVariant))
        assertEquals(sealedHash(base), sealedHash(expressionV3Artwork()))
    }
}
