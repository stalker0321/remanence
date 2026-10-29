package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T154: contract-V4 prepare — provenance/decor/shapes/nine music modes,
 * CSS-traced colours, and typed rejections. The V3 path is untouched
 * (see [CollageRendererTest]); V3 scenes still carry no V4 layers.
 */
class CollageRendererV4Test {

    private val contentIds = listOf("p0", "p1", "p2")

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

    private fun placement(
        id: String,
        x: Int,
        shape: GeneratorExpression.PhotoShape = GeneratorExpression.PhotoShape.RECT,
    ) = GeneratorExpression.Placement(
        id, x, 0, 120, 213,
        GeneratorExpression.CropWindow(0, 0, 10000, 10000),
        null, null, shape,
    )

    private fun styleBinding(styleId: String): GeneratorExpression.StyleBinding {
        val style = requireNotNull(StyleRegistry.style(styleId))
        return GeneratorExpression.StyleBinding(
            styleId = style.id,
            styleVersion = style.version,
            digest = requireNotNull(StyleRegistry.digest(style.id)),
        )
    }

    private fun expressionV4(
        styleId: String = "signal",
        grammarId: String = "6",
        provenance: List<GeneratorExpression.ProvenanceMark>? = BranchProvenance.marksFor(
            12, "score-3-equal", "alice",
        ),
        decor: List<GeneratorExpression.SceneDecor>? = listOf(
            GeneratorExpression.SceneDecor(GeneratorExpression.DecorKind.CARRIER, 6, 5, 349, 627),
        ),
        musicRegion: GeneratorExpression.MusicRegion? = null,
        placements: List<GeneratorExpression.Placement> = listOf(
            placement("p0", 0),
            placement("p1", 120, GeneratorExpression.PhotoShape.ROUND),
            placement("p2", 240, GeneratorExpression.PhotoShape.ELLIPSE),
        ),
    ) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = grammarId,
        grammarVersion = 3,
        branchId = "hinge",
        input = input(),
        placements = placements,
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        noteRegion = null,
        style = styleBinding(styleId),
        noteType = GeneratorExpression.TextType("StudySans", 22, 400, false, 25),
        musicRegion = musicRegion,
        provenance = provenance,
        decor = decor,
    )

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    private fun artworkRef() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 640,
        height = 640,
    )

    private fun sources(): Map<String, CollageRenderer.SourcePhoto> =
        contentIds.associateWith { CollageRenderer.SourcePhoto(1000, 1000) }

    private class FakeMeasurer(
        private val overflow: Boolean = false,
    ) : CollageRenderer.TextMeasurer {
        override fun measure(request: CollageRenderer.TextMeasureRequest): CollageRenderer.TextMeasurement =
            CollageRenderer.TextMeasurement(
                width = minOf(request.maxWidth, request.text.length * 8),
                height = request.line,
                hasVisualOverflow = overflow,
            )
    }

    private fun ready(
        expression: GeneratorExpression.ResolvedExpression,
        trackSnapshot: CapsuleTrackSnapshotV1? = null,
        measurer: CollageRenderer.TextMeasurer = FakeMeasurer(),
        artwork: CollageRenderer.SourcePhoto? = null,
    ): CollageRenderer.Scene {
        val result = CollageRenderer.prepare(expression, null, trackSnapshot, sources(), measurer, artwork)
        assertTrue("expected Ready, got $result", result is CollageRenderer.SceneResult.Ready)
        return (result as CollageRenderer.SceneResult.Ready).scene
    }

    private fun rejected(
        expression: GeneratorExpression.ResolvedExpression,
        trackSnapshot: CapsuleTrackSnapshotV1? = null,
        measurer: CollageRenderer.TextMeasurer = FakeMeasurer(),
        artwork: CollageRenderer.SourcePhoto? = null,
    ): String {
        val result = CollageRenderer.prepare(expression, null, trackSnapshot, sources(), measurer, artwork)
        assertTrue("expected Rejected, got $result", result is CollageRenderer.SceneResult.Rejected)
        return (result as CollageRenderer.SceneResult.Rejected).reason
    }

    @Test
    fun v4SceneCarriesShapesProvenanceAndDecor() {
        val scene = ready(expressionV4())
        assertEquals(GeneratorExpression.PhotoShape.RECT, scene.photos[0].shape)
        assertEquals(GeneratorExpression.PhotoShape.ROUND, scene.photos[1].shape)
        assertEquals(GeneratorExpression.PhotoShape.ELLIPSE, scene.photos[2].shape)
        assertEquals(2, scene.provenance.size)
        // g12 marks resolve to the ink role.
        assertEquals(scene.ink, scene.provenance[0].color)
        assertEquals(scene.ink, scene.provenance[1].color)
        assertEquals(1, scene.decor.size)
        // Carrier resolves to surface + edge (provisional R1 map).
        val signal = requireNotNull(StyleRegistry.style("signal"))
        assertEquals(0xFFF34E33.toInt(), scene.paper)
        assertEquals(signal.surfaceMode, "flat")
    }

    @Test
    fun v4CoverModeEmitsCoverPlusText() {
        val music = GeneratorExpression.MusicRegion(
            x = 10, y = 500, width = 340, height = 76,
            mode = "pulse",
            type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
            artwork = artworkRef(),
        )
        val scene = ready(
            expressionV4(musicRegion = music),
            trackSnapshot = snapshot(),
            artwork = CollageRenderer.SourcePhoto(640, 640),
        )
        assertNotNull(scene.music)
        assertNotNull(scene.artwork)
        assertEquals("505", scene.music!!.title)
    }

    @Test
    fun v4SpineIsTextOnly() {
        val music = GeneratorExpression.MusicRegion(
            x = 156, y = 0, width = 48, height = 590,
            mode = "spine",
            type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
            artwork = null,
        )
        val scene = ready(expressionV4(musicRegion = music), trackSnapshot = snapshot())
        assertNotNull(scene.music)
        assertNull(scene.artwork)
    }

    @Test
    fun v4TransparentBandsAreTraced() {
        val music = GeneratorExpression.MusicRegion(
            x = 0, y = 560, width = 360, height = 50,
            mode = "text",
            type = GeneratorExpression.TextType("StudySerif", 22, 400, true, 24),
            artwork = null,
        )
        val scene = ready(
            expressionV4(styleId = "blue-letter", grammarId = "5", provenance = null, decor = null, musicRegion = music),
            trackSnapshot = snapshot(),
        )
        assertTrue(scene.music!!.transparentBackground)
        assertEquals(scene.ink, scene.music.color)
    }

    @Test
    fun v4Grammar23BrandResolvesToSoundInk() {
        val brand = GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.BRAND,
            text = "Remanence",
            x = 9, y = 221, width = 342, height = 33,
            family = "Gothic", size = 30, weight = 700, italic = false, line = 31,
            orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        val sender = GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.SENDER,
            text = "From alice",
            x = 14, y = 616, width = 332, height = 20,
            family = "StudySans", size = 17, weight = 700, italic = false, line = 18,
            orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        val scene = ready(
            expressionV4(styleId = "glacier", grammarId = "23", provenance = listOf(brand, sender), decor = null),
        )
        assertEquals(scene.soundInk, scene.provenance[0].color)
        assertEquals(scene.ink, scene.provenance[1].color)
    }

    @Test
    fun v4ProvenanceOverflowRejects() {
        assertEquals(
            "provenance 0 does not fit its sealed region",
            rejected(expressionV4(), measurer = FakeMeasurer(overflow = true)),
        )
    }

    @Test
    fun v4UnbundledFaceRejects() {
        val mark = GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.BRAND,
            text = "Remanence",
            x = 12, y = 4, width = 340, height = 75,
            // Narrow 400 is a legal TEXT_FAMILY entry shape but no bundled face.
            family = "Narrow", size = 64, weight = 400, italic = false, line = 66,
            orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        assertEquals(
            "font face Narrow/400/false is not bundled",
            rejected(expressionV4(provenance = listOf(mark))),
        )
    }

    @Test
    fun v4RequiresV4Contract() {
        val result = CollageRenderer.prepare(
            expressionV4().copy(expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2),
            null, null, sources(), FakeMeasurer(),
        )
        assertTrue(result is CollageRenderer.SceneResult.Rejected)
    }

    @Test
    fun v4SpineCopyIsVerticalWithSwappedMeasure() {
        val music = GeneratorExpression.MusicRegion(
            x = 156, y = 0, width = 48, height = 590,
            mode = "spine",
            type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
            artwork = null,
        )
        val scene = ready(expressionV4(musicRegion = music), trackSnapshot = snapshot())
        assertTrue(scene.music!!.vertical)
        assertEquals("spine", scene.music.mode)
        assertNull(scene.artwork)
    }

    @Test
    fun v4CoverTooSmallForBandRejects() {
        val music = GeneratorExpression.MusicRegion(
            x = 10, y = 500, width = 60, height = 76,
            mode = "pulse",
            type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
            artwork = artworkRef(),
        )
        assertEquals(
            "music cover does not fit its sealed region",
            rejected(
                expressionV4(styleId = "ember", grammarId = "24", musicRegion = music),
                trackSnapshot = snapshot(),
                artwork = CollageRenderer.SourcePhoto(640, 640),
            ),
        )
    }

    @Test
    fun v4DecorShadowAndBorderRules() {
        val plane = GeneratorExpression.SceneDecor(GeneratorExpression.DecorKind.PRINT_PLANE, 10, 480, 300, 100)
        val shadowed = ready(
            expressionV4(styleId = "darkroom", grammarId = "25", provenance = null, decor = listOf(plane)),
        )
        assertTrue(shadowed.decor.single().shadow)
        assertNull(shadowed.decor.single().border)
        val edged = ready(
            expressionV4(styleId = "registration", grammarId = "25", provenance = null, decor = listOf(plane)),
        )
        assertTrue(!edged.decor.single().shadow)
        assertNotNull(edged.decor.single().border)
        assertEquals(1, edged.decor.single().borderWidth)
        val carrier = GeneratorExpression.SceneDecor(GeneratorExpression.DecorKind.CARRIER, 6, 5, 349, 627)
        val proof = ready(
            expressionV4(styleId = "proof", grammarId = "18", provenance = null, decor = listOf(carrier)),
        )
        assertEquals(2, proof.decor.single().borderWidth)
        val ochre = ready(
            expressionV4(styleId = "ochre", grammarId = "18", provenance = null, decor = listOf(carrier)),
        )
        assertEquals(1, ochre.decor.single().borderWidth)
    }

    @Test
    fun v4SceneCarriesGrammarAndMode() {
        val music = GeneratorExpression.MusicRegion(
            x = 10, y = 500, width = 340, height = 76,
            mode = "pulse",
            type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
            artwork = artworkRef(),
        )
        val scene = ready(
            expressionV4(grammarId = "24", musicRegion = music),
            trackSnapshot = snapshot(),
            artwork = CollageRenderer.SourcePhoto(640, 640),
        )
        assertEquals("24", scene.grammarId)
        assertEquals("pulse", scene.music!!.mode)
    }

    @Test
    fun characterStylesDrawTheWordInTheirCaseAndColour() {
        val tape = ready(expressionV4(styleId = "tape", grammarId = "6")).provenance
            .single { it.kind == GeneratorExpression.ProvenanceKind.BRAND }
        assertEquals("REMANENCE", tape.text)
        assertEquals(0xFF2F6BFF.toInt(), tape.color)
        val signal = ready(expressionV4()).provenance.single { it.kind == GeneratorExpression.ProvenanceKind.BRAND }
        assertEquals(GeneratorExpression.BRAND_TEXT, signal.text)
    }
}
