package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the deterministic [CollageRenderer.prepare] slice:
 * determinism, per-style colours/faces, exact crop math, injected text
 * fit/overflow, music title-then-artist, ordered rejections, and the no-UI
 * boundary. No UI-toolkit types are used anywhere here.
 */
class CollageRendererTest {

    private val contentIds = listOf("p0", "p1", "p2")

    private val noteRegion = GeneratorExpression.NoteRegion(0, 400, 360, 200)
    private val musicRegion = GeneratorExpression.MusicRegion(
        x = 0, y = 220, width = 360, height = 60,
        mode = "text",
        type = GeneratorExpression.TextType("StudySerif", 14, 400, true, 16),
        artwork = null,
    )

    private fun noteType() = GeneratorExpression.TextType("StudySans", 16, 400, false, 18)

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

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

    private fun placement(
        id: String,
        x: Int,
        y: Int,
        crop: GeneratorExpression.CropWindow? = GeneratorExpression.CropWindow(0, 0, 10000, 10000),
        maskId: String? = null,
        contentRect: GeneratorExpression.ContentRect? = null,
    ) = GeneratorExpression.Placement(id, x, y, 120, 213, crop, maskId, contentRect)

    private fun defaultPlacements() = listOf(
        placement("p0", 0, 0),
        placement("p1", 120, 0),
        placement("p2", 240, 0),
    )

    private fun expressionV3(
        styleId: String = "press",
        styleVersion: String = "style-study-1",
        styleDigest: ByteArray? = null,
        note: String? = null,
        noteRegion: GeneratorExpression.NoteRegion? = this.noteRegion,
        noteType: GeneratorExpression.TextType? = noteType(),
        musicRegion: GeneratorExpression.MusicRegion? = null,
        fontVersion: Int = FontRegistry.VERSION,
        paletteVersion: Int = CollageRenderer.PALETTE_REGISTRY_VERSION,
        contractVersion: Int = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        placements: List<GeneratorExpression.Placement> = defaultPlacements(),
    ) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = input(note),
        placements = placements,
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        fontVersion = fontVersion,
        paletteVersion = paletteVersion,
        expressionContractVersion = contractVersion,
        noteRegion = if (note == null) null else noteRegion,
        style = GeneratorExpression.StyleBinding(
            styleId = styleId,
            styleVersion = styleVersion,
            digest = styleDigest ?: StyleRegistry.digest(styleId) ?: ByteArray(32),
        ),
        noteType = noteType,
        musicRegion = musicRegion,
    )

    private fun sources(width: Int = 1000, height: Int = 1000): Map<String, CollageRenderer.SourcePhoto> =
        contentIds.associateWith { CollageRenderer.SourcePhoto(width, height) }

    private fun artwork() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 640,
        height = 640,
    )

    private class FakeMeasurer(
        private val overflow: Boolean = false,
        private val heightOverride: Int? = null,
    ) : CollageRenderer.TextMeasurer {
        val requests = mutableListOf<CollageRenderer.TextMeasureRequest>()

        override fun measure(request: CollageRenderer.TextMeasureRequest): CollageRenderer.TextMeasurement {
            requests += request
            return CollageRenderer.TextMeasurement(
                width = minOf(request.maxWidth, request.text.length * 8),
                height = heightOverride ?: request.line,
                hasVisualOverflow = overflow,
            )
        }
    }

    private fun ready(
        expression: GeneratorExpression.ResolvedExpression,
        note: String? = null,
        trackSnapshot: CapsuleTrackSnapshotV1? = null,
        measurer: CollageRenderer.TextMeasurer = FakeMeasurer(),
    ): CollageRenderer.Scene {
        val result = CollageRenderer.prepare(expression, note, trackSnapshot, sources(), measurer)
        assertTrue("expected Ready, got $result", result is CollageRenderer.SceneResult.Ready)
        return (result as CollageRenderer.SceneResult.Ready).scene
    }

    private fun rejected(
        expression: GeneratorExpression.ResolvedExpression,
        note: String? = null,
        trackSnapshot: CapsuleTrackSnapshotV1? = null,
        measurer: CollageRenderer.TextMeasurer = FakeMeasurer(),
    ): String {
        val result = CollageRenderer.prepare(expression, note, trackSnapshot, sources(), measurer)
        assertTrue("expected Rejected, got $result", result is CollageRenderer.SceneResult.Rejected)
        return (result as CollageRenderer.SceneResult.Rejected).reason
    }

    @Test
    fun prepareTwiceIsDeterministic() {
        val expression = expressionV3(styleId = "blue-letter", note = "dear mama")
        val first = CollageRenderer.prepare(expression, "dear mama", null, sources(), FakeMeasurer())
        val second = CollageRenderer.prepare(expression, "dear mama", null, sources(), FakeMeasurer())
        assertEquals(first, second)
        assertTrue(first is CollageRenderer.SceneResult.Ready)
    }

    @Test
    fun allThreeStylesExposeExactColorRoles() {
        val press = ready(expressionV3(styleId = "press", note = "hi"), note = "hi")
        assertEquals(0xFFF2F3B0.toInt(), press.paper)
        assertEquals(0xFF191B19.toInt(), press.ink)
        assertEquals(0xFF191B19.toInt(), press.sound)
        assertEquals(0xFFF2F3B0.toInt(), press.soundInk)

        val blueLetter = ready(expressionV3(styleId = "blue-letter", note = "hi"), note = "hi")
        assertEquals(0xFF1525A0.toInt(), blueLetter.paper)
        assertEquals(0xFFF9EDD8.toInt(), blueLetter.ink)
        assertEquals(0xFFF9EDD8.toInt(), blueLetter.sound)
        assertEquals(0xFF1525A0.toInt(), blueLetter.soundInk)

        val poster = ready(expressionV3(styleId = "poster", note = "hi"), note = "hi")
        assertEquals(0xFFF4C4C2.toInt(), poster.paper)
        assertEquals(0xFF521719.toInt(), poster.ink)
        assertEquals(0xFFB82025.toInt(), poster.sound)
        assertEquals(0xFFFFF6DE.toInt(), poster.soundInk)
    }

    @Test
    fun noteAndMusicResolveToBundledFaces() {
        val scene = ready(
            expressionV3(styleId = "press", note = "hi", musicRegion = musicRegion),
            note = "hi",
            trackSnapshot = snapshot(),
        )
        assertEquals(R.font.nimbus_sans_regular, scene.note?.fontResourceId)
        assertEquals(R.font.nimbus_roman_italic, scene.music?.fontResourceId)
    }

    @Test
    fun widenedInventoryFacesResolveThroughPrepare() {
        val gothic = ready(
            expressionV3(
                styleId = "press",
                note = "hi",
                noteType = GeneratorExpression.TextType("Gothic", 18, 700, false, 20),
            ),
            note = "hi",
        )
        assertEquals(R.font.urw_gothic_demi, gothic.note?.fontResourceId)
    }

    @Test
    fun cropConversionIsExactIntegerMath() {
        assertEquals(
            CollageRenderer.RectI(0, 0, 1000, 1000),
            CollageRenderer.sourceRect(GeneratorExpression.CropWindow(0, 0, 10000, 10000), 1000, 1000),
        )
        assertEquals(
            CollageRenderer.RectI(0, 0, 333, 333),
            CollageRenderer.sourceRect(GeneratorExpression.CropWindow(1, 1, 3333, 3333), 1000, 1000),
        )
        assertEquals(
            CollageRenderer.RectI(0, 0, 1000, 3000),
            CollageRenderer.sourceRect(GeneratorExpression.CropWindow(0, 0, 5000, 10000), 2000, 3000),
        )
        assertEquals(
            CollageRenderer.RectI(4936, 22712, 4000, 8000),
            CollageRenderer.sourceRect(GeneratorExpression.CropWindow(1234, 5678, 1000, 2000), 40000, 40000),
        )
    }

    @Test
    fun cropRightBottomEdgeClosureLandsOnTheBitmapEdge() {
        assertEquals(
            CollageRenderer.RectI(999, 0, 1, 1000),
            CollageRenderer.sourceRect(GeneratorExpression.CropWindow(9999, 0, 1, 10000), 1000, 1000),
        )
        assertEquals(
            CollageRenderer.RectI(0, 999, 1000, 1),
            CollageRenderer.sourceRect(GeneratorExpression.CropWindow(0, 9999, 10000, 1), 1000, 1000),
        )
    }

    @Test
    fun collapsingOrInvalidSourceRectIsRejected() {
        assertNull(CollageRenderer.sourceRect(GeneratorExpression.CropWindow(0, 0, 1, 1), 1, 1))
        assertNull(CollageRenderer.sourceRect(GeneratorExpression.CropWindow(0, 0, 10000, 10000), 0, 1000))
    }

    @Test
    fun noteFitsYieldsReadyAndOverflowRejects() {
        val expression = expressionV3(styleId = "press", note = "hi")
        assertTrue(ready(expression, note = "hi").note != null)
        val overflowed = CollageRenderer.prepare(expression, "hi", null, sources(), FakeMeasurer(overflow = true))
        assertEquals(
            "note does not fit its sealed region",
            (overflowed as CollageRenderer.SceneResult.Rejected).reason,
        )
        val tooTall = CollageRenderer.prepare(
            expression, "hi", null, sources(), FakeMeasurer(heightOverride = noteRegion.height + 1),
        )
        assertEquals(
            "note does not fit its sealed region",
            (tooTall as CollageRenderer.SceneResult.Rejected).reason,
        )
    }

    @Test
    fun musicIsTitleThenArtistOnSoundAndSoundInk() {
        val measurer = FakeMeasurer()
        val scene = ready(
            expressionV3(styleId = "press", musicRegion = musicRegion),
            trackSnapshot = snapshot(),
            measurer = measurer,
        )
        assertEquals("505\nArctic Monkeys", measurer.requests.single().text)
        assertEquals("505", scene.music?.title)
        assertEquals("Arctic Monkeys", scene.music?.artist)
        assertEquals(0xFF191B19.toInt(), scene.music?.background)
        assertEquals(0xFFF2F3B0.toInt(), scene.music?.color)
    }

    @Test
    fun musicOverflowRejectsTheWholeScene() {
        val rejected = CollageRenderer.prepare(
            expressionV3(styleId = "press", musicRegion = musicRegion),
            null,
            snapshot(),
            sources(),
            FakeMeasurer(overflow = true),
        )
        assertEquals(
            "music does not fit its sealed region",
            (rejected as CollageRenderer.SceneResult.Rejected).reason,
        )
    }

    @Test
    fun structuralAndCapabilityRejections() {
        assertEquals(
            "collage renderer requires a contract V3 expression, got 2",
            rejected(expressionV3(styleId = "press", contractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2)),
        )
        assertEquals(
            "style signal is not a bundled collage style",
            rejected(expressionV3(styleId = "signal", styleDigest = ByteArray(32))),
        )
        assertEquals(
            "style version does not match the bundled style record",
            rejected(expressionV3(styleId = "press", styleVersion = "style-study-2", note = "hi"), note = "hi"),
        )
        assertEquals(
            "style digest does not match the bundled style record",
            rejected(
                expressionV3(styleId = "press", styleDigest = ByteArray(32) { 9 }, note = "hi"),
                note = "hi",
            ),
        )
        assertEquals("unsupported fontVersion 2", rejected(expressionV3(styleId = "press", fontVersion = 2)))
        assertEquals(
            "unsupported paletteVersion 2",
            rejected(expressionV3(styleId = "press", paletteVersion = 2)),
        )
        assertEquals(
            "font face StudyMono/700/false is not bundled",
            rejected(
                expressionV3(
                    styleId = "press",
                    noteType = GeneratorExpression.TextType("StudyMono", 16, 700, false, 18),
                ),
            ),
        )
    }

    @Test
    fun noteMusicAndPlacementRejections() {
        assertEquals(
            "non-empty note requires a sealed note region",
            rejected(expressionV3(styleId = "press", note = "hi", noteRegion = null), note = "hi"),
        )
        assertEquals(
            "music region and track snapshot must match",
            rejected(expressionV3(styleId = "press", musicRegion = musicRegion), trackSnapshot = null),
        )
        assertEquals(
            "music region and track snapshot must match",
            rejected(expressionV3(styleId = "press"), trackSnapshot = snapshot()),
        )
        val thumbnail = musicRegion.copy(mode = "thumbnail", artwork = artwork())
        assertEquals(
            "music artwork rendering is not supported",
            rejected(expressionV3(styleId = "press", musicRegion = thumbnail), trackSnapshot = snapshot()),
        )
        assertEquals(
            "placement 0 carries a mask",
            rejected(
                expressionV3(
                    styleId = "press",
                    placements = listOf(placement("p0", 0, 0, maskId = "rect")) +
                        listOf(placement("p1", 120, 0), placement("p2", 240, 0)),
                ),
            ),
        )
        assertEquals(
            "placement 0 carries a contentRect",
            rejected(
                expressionV3(
                    styleId = "press",
                    placements = listOf(
                        placement("p0", 0, 0, contentRect = GeneratorExpression.ContentRect(0, 0, 10, 10)),
                    ) + listOf(placement("p1", 120, 0), placement("p2", 240, 0)),
                ),
            ),
        )
        assertEquals(
            "expression artifact failed validation",
            rejected(expressionV3(styleId = "press", placements = listOf(placement("p0", 0, 0, crop = null)))),
        )
    }

    @Test
    fun manifestNoteMustMatchTheSealedNote() {
        val sealed = expressionV3(styleId = "press", note = "dear mama")
        assertEquals(
            "manifest note does not match the sealed expression note",
            rejected(sealed, note = null),
        )
        assertEquals(
            "manifest note does not match the sealed expression note",
            rejected(sealed, note = "different"),
        )
        assertTrue(ready(sealed, note = "dear mama").note != null)
    }

    @Test
    fun blankNoteNormalizesToAbsent() {
        val blank = expressionV3(styleId = "press", note = "   ", noteRegion = null)
        assertNull(ready(blank, note = "   ").note)
    }

    @Test
    fun noteRegionRequiresANonEmptyNote() {
        val regionWithoutNote = expressionV3(styleId = "press").copy(noteRegion = noteRegion)
        assertEquals(
            "sealed note region requires a non-empty note",
            rejected(regionWithoutNote, note = null),
        )
    }

    @Test
    fun sourceMetadataRejections() {
        val missing = CollageRenderer.prepare(
            expressionV3(styleId = "press"), null, null,
            sources().filterKeys { it != "p2" },
            FakeMeasurer(),
        )
        assertEquals(
            "source metadata does not match authored content",
            (missing as CollageRenderer.SceneResult.Rejected).reason,
        )

        val invalid = CollageRenderer.prepare(
            expressionV3(styleId = "press"), null, null,
            sources().toMutableMap().apply { put("p0", CollageRenderer.SourcePhoto(0, 1000)) },
            FakeMeasurer(),
        )
        assertEquals(
            "invalid source dimensions for p0",
            (invalid as CollageRenderer.SceneResult.Rejected).reason,
        )

        val collapsing = CollageRenderer.prepare(
            expressionV3(
                styleId = "press",
                placements = listOf(
                    placement("p0", 0, 0, crop = GeneratorExpression.CropWindow(0, 0, 1, 1)),
                ) + listOf(placement("p1", 120, 0), placement("p2", 240, 0)),
            ),
            null, null,
            sources().toMutableMap().apply { put("p0", CollageRenderer.SourcePhoto(1, 1)) },
            FakeMeasurer(),
        )
        assertEquals(
            "placement 0 crop is outside its source bitmap",
            (collapsing as CollageRenderer.SceneResult.Rejected).reason,
        )
    }

    @Test
    fun rendererHasNoUiToolkitOrPlannerDependency() {
        val source = moduleFile("src/main/kotlin/dev/hryshyn/remanence/ui/collage/CollageRenderer.kt").readText()
        for (forbidden in listOf(
            "androidx.compose",
            "android.graphics",
            "ImageBitmap",
            "GeneratorEditorialRows",
            "GeneratorBer1",
            "GeneratorPreviewCoordinator",
        )) {
            assertFalse("CollageRenderer must not reference $forbidden", source.contains(forbidden))
        }
    }

    private fun moduleFile(relative: String): File {
        val prefixes = listOf("", "app/", "android/app/")
        var directory: File? = File("").absoluteFile
        repeat(6) {
            val base = directory
            if (base != null) {
                for (prefix in prefixes) {
                    val candidate = File(base, prefix + relative)
                    if (candidate.exists()) return candidate
                }
            }
            directory = directory?.parentFile
        }
        return File(relative)
    }
}
