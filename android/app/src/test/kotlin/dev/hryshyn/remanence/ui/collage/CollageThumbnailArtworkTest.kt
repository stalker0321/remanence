package dev.hryshyn.remanence.ui.collage

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T144: the thumbnail contract the sender host now wires. A thumbnail
 * expression plus a size-matching verified bitmap yields a scene carrying
 * `SceneArtwork` that the canvas renders; without the bitmap the same
 * expression degrades to the text band. Real font metrics require
 * `@GraphicsMode(NATIVE)` (same note as [CollageCanvasTest]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageThumbnailArtworkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p0", "p1", "p2")

    private fun artworkRef() = GeneratorExpression.ArtworkRef(
        provider = "coverartarchive",
        url = "https://archive.org/download/x/y-500.jpg",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/jpeg",
        width = 8,
        height = 8,
    )

    private fun thumbnailMusicRegion() = GeneratorExpression.MusicRegion(
        x = 0, y = 220, width = 360, height = 60,
        mode = "thumbnail",
        type = GeneratorExpression.TextType("StudySerif", 14, 400, true, 16),
        artwork = artworkRef(),
    )

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "300f0db4-c980-4040-bf54-1f5ff81f975c",
        title = "Is This Love (live)",
        artistDisplay = "Whitesnake",
        version = null,
        durationMs = 283000L,
    )

    private fun expression() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = "a note",
            music = null,
        ),
        placements = contentIds.mapIndexed { index, id ->
            GeneratorExpression.Placement(
                id, index * 120, 0, 120, 213,
                GeneratorExpression.CropWindow(0, 0, 10000, 10000), null, null,
            )
        },
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        noteRegion = GeneratorExpression.NoteRegion(0, 400, 360, 200),
        fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        style = GeneratorExpression.StyleBinding(
            styleId = "press",
            styleVersion = "style-study-1",
            digest = StyleRegistry.digest("press") ?: ByteArray(32),
        ),
        musicRegion = thumbnailMusicRegion(),
    )

    private class FakeMeasurer : CollageRenderer.TextMeasurer {
        override fun measure(
            request: CollageRenderer.TextMeasureRequest,
        ): CollageRenderer.TextMeasurement = CollageRenderer.TextMeasurement(
            width = minOf(request.maxWidth, request.text.length * 8),
            height = request.line,
            hasVisualOverflow = false,
        )
    }

    private fun bitmap(size: Int = 8) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

    /**
     * Canvas photo bitmaps must cover the sealed source rects, which prepare
     * computed against 1000x1000 sources — 4px bitmaps would fail the painter
     * bounds check exactly as a too-small real photo would.
     */
    private fun images() = contentIds.associateWith { bitmap(1000) }

    @Test
    fun thumbnailWithMatchingBitmapReachesSceneArtworkAndCanvas() {
        val result = CollageRenderer.prepare(
            expression(),
            "a note",
            snapshot(),
            contentIds.associateWith { CollageRenderer.SourcePhoto(1000, 1000) },
            FakeMeasurer(),
            artwork = CollageRenderer.SourcePhoto(8, 8),
        )
        assertTrue("expected Ready, got $result", result is CollageRenderer.SceneResult.Ready)
        val scene = (result as CollageRenderer.SceneResult.Ready).scene
        assertNull(scene.music)
        assertNotNull(scene.artwork)

        composeRule.setContent {
            CollageCanvas(scene, images(), "collage", artwork = bitmap())
        }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
    }

    @Test
    fun thumbnailWithoutBitmapPreservesTextBandRendering() {
        val result = CollageRenderer.prepare(
            expression(),
            "a note",
            snapshot(),
            contentIds.associateWith { CollageRenderer.SourcePhoto(1000, 1000) },
            FakeMeasurer(),
            artwork = null,
        )
        assertTrue("expected Ready, got $result", result is CollageRenderer.SceneResult.Ready)
        val scene = (result as CollageRenderer.SceneResult.Ready).scene
        assertNull(scene.artwork)
        assertNotNull(scene.music)

        // The planned draw list carries the sealed words and no cover op:
        // canvas text is drawn, not composed, so ops are the precise proof.
        val ops = CollagePainter.drawList(
            scene,
            contentIds.associateWith { CollageRenderer.SourcePhoto(1000, 1000) },
        )
        assertNotNull(ops)
        val texts = ops!!.filterIsInstance<CollagePainter.Op.MusicText>()
        assertEquals(1, texts.size)
        assertEquals("Is This Love (live)", texts.single().title)
        assertEquals("Whitesnake", texts.single().artist)
        assertTrue(ops.none { it is CollagePainter.Op.MusicArtwork })

        composeRule.setContent {
            CollageCanvas(scene, images(), "collage", artwork = null)
        }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
    }
}
