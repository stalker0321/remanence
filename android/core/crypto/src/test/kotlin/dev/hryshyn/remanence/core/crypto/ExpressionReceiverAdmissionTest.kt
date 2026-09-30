package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V5-P3B: V3 admission is temporarily fail-closed with one stable reason until
 * the shared collage renderer and the receiver registries land. Structural
 * rules A1-A9 stay in `validateResolved` and surface through the generic reason.
 * Legacy/V1/V2 behavior is unchanged, including the V2-only note <-> noteRegion
 * rule (V1 is exempt because it has no noteRegion slot).
 */
class ExpressionReceiverAdmissionTest {

    private val contentIds = listOf("c0", "c1", "c2")
    private val blobUuids = listOf(
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0001"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0002"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0003"),
    )

    private val fitsPort = GeneratorEditorialRows.NoteMeasurementPort {
        GeneratorEditorialRows.NoteMeasurement.Fits(GeneratorEditorialRows.NOTE_REGION)
    }

    private fun manifestPhotos(): List<ManifestPhoto> =
        blobUuids.mapIndexed { index, id -> ManifestPhoto(blobId = id, ordinal = index, width = 1000, height = 1000) }

    private fun input(note: String? = null) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(
                contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                contentHash = ('a' + index).toString().repeat(64),
            )
        },
        note = note,
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
        diagnostics = emptyList(),
        fontVersion = GeneratorEditorialRows.FONT_VERSION,
        paletteVersion = GeneratorEditorialRows.PALETTE_VERSION,
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

    private fun contentOf(
        expression: GeneratorExpression.ResolvedExpression,
        trackSnapshot: CapsuleTrackSnapshotV1? = null,
        note: String? = null,
        protocolVersion: Int = 2,
    ) = ContentManifestContent(
        protocolVersion = protocolVersion,
        photos = manifestPhotos(),
        note = note,
        expression = ContentExpression("cand-1", "a".repeat(64), expression),
        trackSnapshot = trackSnapshot,
    )

    private fun unsupportedReason(content: ContentManifestContent): String {
        val result = ExpressionReceiverAdmission.admit(content)
        assertTrue(result is ExpressionReceiverAdmission.Result.Unsupported, "expected Unsupported, got $result")
        return result.reason
    }

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
    fun legacyV1PathUnchanged() {
        assertEquals(
            "content manifest carries no v2 expression artifact",
            unsupportedReason(
                ContentManifestContent(protocolVersion = 1, photos = manifestPhotos(), note = null),
            ),
        )
        assertEquals(
            "v2 expression artifact is missing",
            unsupportedReason(
                ContentManifestContent(protocolVersion = 2, photos = manifestPhotos(), note = null),
            ),
        )
    }

    @Test
    fun contractV2RoundTripStillSupported() {
        val planned = GeneratorEditorialRows.plan(input())
        assertTrue(planned is GeneratorEditorialRows.PlanResult.Planned, "got $planned")
        val result = ExpressionReceiverAdmission.admit(contentOf(planned.expression))
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "got $result")
    }

    @Test
    fun contractV2NoteRegionMismatchRemainsUnsupported() {
        // The expression carries a measured note region, but the manifest note
        // is absent: the legacy v2 equivalence rule must still reject it.
        val planned = GeneratorEditorialRows.plan(input(note = "hi"), fitsPort)
        assertTrue(planned is GeneratorEditorialRows.PlanResult.Planned, "got $planned")
        val expression = planned.expression
        assertEquals(
            "expression note region does not match the manifest note",
            unsupportedReason(contentOf(expression, note = null)),
        )
    }

    @Test
    fun contractV1ExpressionIsExemptFromTheV2NoteRegionRule() {
        // v1 has no noteRegion slot, so the legacy v2 note <-> region rule
        // deliberately does not apply: a v1 note without a region is admitted.
        val v1 = expressionV3Base().copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V1,
            input = input(note = "hi"),
            placements = contentIds.map { id ->
                GeneratorExpression.Placement(
                    contentId = id, x = 0, y = 0, width = 120, height = 213,
                    crop = GeneratorExpression.CropWindow(100, 200, 500, 600),
                    maskId = null, contentRect = null,
                )
            },
            noteRegion = null,
            style = null,
            noteType = null,
            musicRegion = null,
        )
        assertTrue(
            GeneratorExpression.validateResolved(v1) is GeneratorExpression.InputValidation.Valid,
            "fixture must be structurally valid",
        )
        val result = ExpressionReceiverAdmission.admit(contentOf(v1, note = "hi"))
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "got $result")
    }

    @Test
    fun everyV3ShapeFailsClosedUntilRendererLands() {
        assertEquals(
            "finished collage rendering is not supported",
            unsupportedReason(contentOf(expressionV3Base())),
        )
        assertEquals(
            "finished collage rendering is not supported",
            unsupportedReason(contentOf(expressionV3TextMusic(), snapshot())),
        )
        assertEquals(
            "finished collage rendering is not supported",
            unsupportedReason(contentOf(expressionV3Artwork(), snapshot())),
        )
    }

    @Test
    fun structuralFailuresStillSurfaceThroughTheGenericReason() {
        // A1 thumbnail-without-artwork is rejected by validateResolved before
        // the temporary V3 fail-closed gate.
        val dangling = expressionV3Base().copy(
            musicRegion = GeneratorExpression.MusicRegion(
                x = 16, y = 480, width = 328, height = 64,
                mode = "thumbnail",
                type = noteType(),
                artwork = null,
            ),
        )
        assertEquals(
            "expression artifact failed validation",
            unsupportedReason(contentOf(dangling, snapshot())),
        )
        // Missing style is likewise structural.
        assertEquals(
            "expression artifact failed validation",
            unsupportedReason(contentOf(expressionV3Base().copy(style = null))),
        )
    }

    @Test
    fun disabledCapabilityKeepsTheBlanketV3Refusal() {
        assertEquals(
            "finished collage rendering is not supported",
            unsupportedReason(contentOf(expressionV3Base()), CollageRenderCapability.Disabled),
        )
        assertEquals(
            "finished collage rendering is not supported",
            unsupportedReason(contentOf(expressionV3TextMusic(), snapshot()), CollageRenderCapability.Disabled),
        )
        assertEquals(
            "finished collage rendering is not supported",
            unsupportedReason(contentOf(expressionV3Artwork(), snapshot()), CollageRenderCapability.Disabled),
        )
    }

    @Test
    fun enablingCapabilityAdmitsAValidTextOnlyV3() {
        val result = ExpressionReceiverAdmission.admit(contentOf(expressionV3Base()), capability())
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "got $result")
    }

    @Test
    fun wrongStyleDigestOrUnknownStyleVersionIsRejected() {
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(expressionV3Base()), capability(digest = ByteArray(32) { 9 })),
        )
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(expressionV3Base()), capability(digest = null)),
        )
        assertEquals(
            "expression style is not supported",
            unsupportedReason(
                contentOf(expressionV3Base().copy(style = style().copy(styleVersion = "style-study-2"))),
                capability(),
            ),
        )
    }

    @Test
    fun unbundledFaceIsRejected() {
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(expressionV3Base()), capability(face = null)),
        )
    }

    @Test
    fun fontOrPaletteVersionMismatchIsRejected() {
        assertEquals(
            "expression font or palette version is not supported",
            unsupportedReason(contentOf(expressionV3Base()), capability(fontVersionPin = 2)),
        )
        assertEquals(
            "expression font or palette version is not supported",
            unsupportedReason(contentOf(expressionV3Base()), capability(paletteVersionPin = 2)),
        )
    }

    @Test
    fun thumbnailArtworkIsRejectedWhenArtworkIsUnsupported() {
        assertEquals(
            "music artwork rendering is not supported",
            unsupportedReason(contentOf(expressionV3Artwork(), snapshot()), capability()),
        )
    }

    @Test
    fun thumbnailArtworkIsAdmittedWhenArtworkIsSupported() {
        val result = ExpressionReceiverAdmission.admit(
            contentOf(expressionV3Artwork(), snapshot()),
            capability(artworkSupported = true),
        )
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "expected Supported, got $result")
    }

    @Test
    fun maskIsRejectedWhenMaskIsUnsupported() {
        val masked = expressionV3Base().copy(
            placements = contentIds.map { placementV3(it).copy(maskId = "rect") },
        )
        assertEquals(
            "expression mask is not supported",
            unsupportedReason(contentOf(masked), capability()),
        )
    }
}
