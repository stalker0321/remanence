package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V5-P2 slice section 9 T6 (landed in P3): V3 admission readiness
 * (R6/R7/R8) plus the artwork capability gates (AD1/AD2) with exact stable
 * reasons. Legacy/V1/V2 paths are asserted unchanged (R10); structural
 * rules A1-A9 stay in `validateResolved` and surface here only through the
 * generic `:29` reason.
 */
class ExpressionReceiverAdmissionTest {

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
        protocolVersion: Int = 2,
    ) = ContentManifestContent(
        protocolVersion = protocolVersion,
        photos = manifestPhotos(),
        note = null,
        expression = ContentExpression("cand-1", "a".repeat(64), expression),
        trackSnapshot = trackSnapshot,
    )

    private fun unsupportedReason(content: ContentManifestContent): String {
        val result = ExpressionReceiverAdmission.admit(content)
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
    fun v3TextOnlyWithoutMusicIsSupported() {
        val result = ExpressionReceiverAdmission.admit(contentOf(expressionV3Base()))
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "got $result")
    }

    @Test
    fun v3TextMusicWithSnapshotIsSupported() {
        val result = ExpressionReceiverAdmission.admit(contentOf(expressionV3TextMusic(), snapshot()))
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "got $result")
    }

    @Test
    fun ad1ArtworkBearingV3FailsClosedWithExactReason() {
        assertEquals(
            "music artwork rendering is not supported",
            unsupportedReason(contentOf(expressionV3Artwork(), snapshot())),
        )
    }

    @Test
    fun ad2MusicSnapshotMismatchIsUnsupported() {
        assertEquals(
            "expression music region and track snapshot must match",
            unsupportedReason(contentOf(expressionV3TextMusic(), null)),
        )
        assertEquals(
            "expression music region and track snapshot must match",
            unsupportedReason(contentOf(expressionV3Base(), snapshot())),
        )
    }

    @Test
    fun r7MaskIdIsUnsupported() {
        val masked = expressionV3Base().copy(
            placements = contentIds.map { placementV3(it).copy(maskId = "rect") },
        )
        assertEquals(
            "expression mask is not supported",
            unsupportedReason(contentOf(masked)),
        )
    }

    @Test
    fun r8UnknownFontOrPaletteVersionIsUnsupported() {
        assertEquals(
            "expression font or palette version is not supported",
            unsupportedReason(contentOf(expressionV3Base().copy(fontVersion = 2))),
        )
        assertEquals(
            "expression font or palette version is not supported",
            unsupportedReason(contentOf(expressionV3Base().copy(paletteVersion = 2))),
        )
    }

    @Test
    fun r6UnbundledTypefaceIsUnsupported() {
        // Narrow ships 700-only: Narrow @400 passes seal-time validation but
        // the slice-1 receiver has no such face, so admission refuses it.
        val narrow = expressionV3Base().copy(
            noteType = noteType().copy(family = "Narrow", weight = 400),
        )
        assertTrue(
            GeneratorExpression.validateResolved(narrow) is GeneratorExpression.InputValidation.Valid,
            "fixture must stay sealable so the failure pins R6, not :29",
        )
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(narrow)),
        )
        // StudyMono ships 400-only.
        val mono = expressionV3TextMusic().copy(
            musicRegion = expressionV3TextMusic().musicRegion!!.copy(
                type = noteType().copy(family = "StudyMono", weight = 700),
            ),
        )
        assertTrue(
            GeneratorExpression.validateResolved(mono) is GeneratorExpression.InputValidation.Valid,
            "fixture must stay sealable so the failure pins R6, not :29",
        )
        assertEquals(
            "expression style is not supported",
            unsupportedReason(contentOf(mono, snapshot())),
        )
    }

    @Test
    fun structuralFailuresStillSurfaceThroughTheGenericReason() {
        // A1 thumbnail-without-artwork never reaches AD1: :29 rejects first.
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
        // R3 twin at admission: style missing.
        assertEquals(
            "expression artifact failed validation",
            unsupportedReason(contentOf(expressionV3Base().copy(style = null))),
        )
    }
}
