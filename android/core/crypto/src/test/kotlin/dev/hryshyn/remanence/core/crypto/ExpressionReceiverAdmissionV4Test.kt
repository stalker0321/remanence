package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T154 full frozen V5: contract-4 admission matrix. Mirrors every V3 gate
 * (capability enabled, constant-time style digest, font/palette versions,
 * note face) plus provenance faces, the nine-mode music matrix, and the
 * mask/contentRect gate.
 */
class ExpressionReceiverAdmissionV4Test {

    private val contentIds = listOf("c0", "c1", "c2")
    private val blobUuids = listOf(
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0001"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0002"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0003"),
    )

    private fun manifestPhotos(): List<ManifestPhoto> =
        blobUuids.mapIndexed { index, id -> ManifestPhoto(blobId = id, ordinal = index, width = 1000, height = 1000) }

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

    private fun placementV4(id: String, x: Int) = GeneratorExpression.Placement(
        contentId = id,
        x = x, y = 0, width = 120, height = 213,
        crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
        maskId = null,
        contentRect = null,
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

    private fun expressionV4Base() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = input(),
        placements = listOf(placementV4("c0", 0), placementV4("c1", 120), placementV4("c2", 240)),
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        fontVersion = GeneratorEditorialRows.FONT_VERSION,
        paletteVersion = GeneratorEditorialRows.PALETTE_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        noteRegion = null,
        style = style(),
        noteType = noteType(),
        musicRegion = null,
        provenance = listOf(brand(), sender()),
        decor = null,
    )

    private fun musicRegion(mode: String, withArtwork: Boolean) = GeneratorExpression.MusicRegion(
        x = 16, y = 480, width = 328, height = 64,
        mode = mode,
        type = noteType(),
        artwork = if (withArtwork) artwork() else null,
    )

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    private fun contentOf(
        expression: GeneratorExpression.ResolvedExpression,
        trackSnapshot: CapsuleTrackSnapshotV1? = null,
    ) = ContentManifestContent(
        protocolVersion = 2,
        photos = manifestPhotos(),
        note = null,
        expression = ContentExpression("cand-1", "a".repeat(64), expression),
        trackSnapshot = trackSnapshot,
    )

    private fun capability(
        digest: ByteArray? = ByteArray(32) { 7 },
        face: Int? = 1,
        fontVersionPin: Int = GeneratorEditorialRows.FONT_VERSION,
        paletteVersionPin: Int = GeneratorEditorialRows.PALETTE_VERSION,
        maskSupported: Boolean = false,
        artworkSupported: Boolean = false,
    ) = object : CollageRenderCapability {
        override val enabled: Boolean = true
        override fun styleDigest(styleId: String, styleVersion: String): ByteArray? =
            if (styleId == "press" && styleVersion == "style-study-1") digest else null
        override fun fontResource(family: String, weight: Int, italic: Boolean): Int? = face
        override val fontVersion: Int = fontVersionPin
        override val paletteVersion: Int = paletteVersionPin
        override val maskSupported: Boolean = maskSupported
        override val artworkSupported: Boolean = artworkSupported
    }

    private fun unsupportedReason(content: ContentManifestContent, capability: CollageRenderCapability): String {
        val result = ExpressionReceiverAdmission.admit(content, capability)
        assertTrue(result is ExpressionReceiverAdmission.Result.Unsupported, "expected Unsupported, got $result")
        return result.reason
    }

    @Test
    fun v4DisabledCapabilityRefuses() {
        assertEquals(
            "finished collage rendering is not supported",
            unsupportedReason(contentOf(expressionV4Base()), CollageRenderCapability.Disabled),
        )
    }

    @Test
    fun v4FullyCapableSupports() {
        val result = ExpressionReceiverAdmission.admit(
            contentOf(expressionV4Base()),
            capability(),
        )
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "got $result")
        assertEquals(expressionV4Base(), (result as ExpressionReceiverAdmission.Result.Supported).expression)
    }

    @Test
    fun v4StyleDigestMismatchRefuses() {
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(expressionV4Base()), capability(digest = ByteArray(32) { 9 })),
        )
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(expressionV4Base()), capability(digest = null)),
        )
    }

    @Test
    fun v4UnknownProvenanceFaceRefuses() {
        // face == null refuses every face, including the provenance marks.
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(expressionV4Base()), capability(face = null)),
        )
    }

    @Test
    fun v4NineModeMatrix() {
        val supportedNoArtwork = mapOf(
            "text" to true, "tile-text" to true, "sleeve-empty" to true, "spine" to true,
            "inline" to true, "pulse" to true,
        )
        for ((mode, _) in supportedNoArtwork) {
            val expression = expressionV4Base().copy(musicRegion = musicRegion(mode, false))
            val result = ExpressionReceiverAdmission.admit(contentOf(expression), capability())
            assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "mode=$mode got $result")
        }
        for (mode in listOf("thumbnail", "tile-art", "sleeve")) {
            // The model gate fires first: these modes always seal artwork.
            assertEquals(
                "expression artifact failed validation",
                unsupportedReason(
                    contentOf(expressionV4Base().copy(musicRegion = musicRegion(mode, false))),
                    capability(),
                ),
                "mode=$mode without artwork",
            )
        }
        for (mode in listOf("thumbnail", "inline", "tile-art", "sleeve", "pulse")) {
            // Artwork present but the capability advertises no artwork support.
            assertEquals(
                "music artwork rendering is not supported",
                unsupportedReason(
                    contentOf(expressionV4Base().copy(musicRegion = musicRegion(mode, true))),
                    capability(),
                ),
                "mode=$mode with artwork, unsupported capability",
            )
            val result = ExpressionReceiverAdmission.admit(
                contentOf(expressionV4Base().copy(musicRegion = musicRegion(mode, true))),
                capability(artworkSupported = true),
            )
            assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "mode=$mode got $result")
        }
        assertEquals(
            "expression artifact failed validation",
            unsupportedReason(
                contentOf(expressionV4Base().copy(musicRegion = musicRegion("sleeve-empty", true))),
                capability(artworkSupported = true),
            ),
        )
        // Text-only modes with artwork die at the model gate (which runs
        // first), so the admission music matrix below only sees valid models.
    }

    @Test
    fun v4MaskAndContentRectRefusedWithoutCapability() {
        val masked = expressionV4Base().copy(
            placements = expressionV4Base().placements.map { it.copy(maskId = "m") },
        )
        assertEquals(
            "expression mask is not supported",
            unsupportedReason(contentOf(masked), capability()),
        )
        val rect = expressionV4Base().copy(
            placements = expressionV4Base().placements.map {
                it.copy(contentRect = GeneratorExpression.ContentRect(it.x, it.y, it.width, it.height))
            },
        )
        assertEquals(
            "expression mask is not supported",
            unsupportedReason(contentOf(rect), capability()),
        )
        val supported = ExpressionReceiverAdmission.admit(contentOf(rect), capability(maskSupported = true))
        assertTrue(supported is ExpressionReceiverAdmission.Result.Supported, "got $supported")
    }
}
