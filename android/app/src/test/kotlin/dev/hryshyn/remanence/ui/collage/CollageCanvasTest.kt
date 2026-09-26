package dev.hryshyn.remanence.ui.collage

import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose coverage for [CollageCanvas]: root/content tags, one
 * merged accessibility node with the caller description + [Role.Image],
 * fail-closed no-content on missing images, too-small bitmaps, or overflowing
 * text, the sealed text-op colour reaching the draw calls, and agreement
 * between the real Compose measurer used by [CollageRenderer.prepare] and by
 * the painter.
 *
 * Environment note: Robolectric does not assert rendered pixels here; the
 * no-content cases are pinned by composition (the content node is absent),
 * the colour binding by a source-level assertion, and the exact
 * scale/order/crop math by [CollagePainterTest]. Real font metrics require
 * `@GraphicsMode(NATIVE)` (used below).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageCanvasTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p0", "p1", "p2")

    private fun rect(x: Int, y: Int, w: Int, h: Int) = CollageRenderer.RectI(x, y, w, h)

    private fun image(size: Int = 4): ImageBitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun images(vararg ids: String, size: Int = 4): Map<String, ImageBitmap> =
        ids.associateWith { image(size) }

    private fun scene(
        note: CollageRenderer.SceneText? = null,
        music: CollageRenderer.SceneMusic? = null,
        source: CollageRenderer.RectI = rect(0, 0, 4, 4),
    ) = CollageRenderer.Scene(
        canvasWidth = 360,
        canvasHeight = 640,
        styleId = "press",
        paper = 0xFFF2F3B0.toInt(),
        ink = 0xFF191B19.toInt(),
        sound = 0xFF191B19.toInt(),
        soundInk = 0xFFF2F3B0.toInt(),
        photos = contentIds.map { CollageRenderer.ScenePhoto(it, rect(0, 0, 120, 213), source) },
        note = note,
        music = music,
    )

    @Test
    fun readySceneRendersRootAndContentTags() {
        composeRule.setContent { CollageCanvas(scene(), images("p0", "p1", "p2"), "collage") }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
    }

    @Test
    fun missingImageRendersRootWithoutContent() {
        composeRule.setContent { CollageCanvas(scene(), images("p0", "p1"), "collage") }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun tooSmallBitmapRendersRootWithoutContent() {
        // Sealed source rect is 4x4 but the decoded bitmap is only 2x2.
        composeRule.setContent { CollageCanvas(scene(), images("p0", "p1", "p2", size = 2), "collage") }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun overflowingNoteRendersRootWithoutContent() {
        val note = CollageRenderer.SceneText(
            text = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            region = rect(0, 0, 1, 1),
            family = "StudySans",
            size = 16,
            weight = 400,
            italic = false,
            line = 18,
            fontResourceId = R.font.nimbus_sans_regular,
            color = 0xFF191B19.toInt(),
        )
        composeRule.setContent { CollageCanvas(scene(note = note), images("p0", "p1", "p2"), "collage") }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun semanticsExposeCallerDescriptionAndImageRole() {
        composeRule.setContent { CollageCanvas(scene(), images("p0", "p1", "p2"), "Коллаж") }
        composeRule.onNodeWithTag("collage_canvas").assertContentDescriptionEquals("Коллаж")
        composeRule.onNodeWithTag("collage_canvas")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
    }

    @Test
    fun textDrawCallsUseTheSealedOpColor() {
        val source = moduleFile("src/main/kotlin/dev/hryshyn/remanence/ui/collage/CollageCanvas.kt")
            .readText()
            .replace(Regex("\\s+"), " ")
        val needle = "drawText( textLayoutResult = layout, color = Color(op.color),"
        assertEquals(2, source.split(needle).size - 1)
    }

    @Test
    fun realMeasurerAgreesWithPrepareAndPaints() {
        val note = "dear mama"
        var ready = false
        var noteFits = false
        composeRule.setContent {
            val measurer = rememberCollageTextMeasurer()
            val expression = remember { expressionV3(note) }
            val result = remember(measurer) {
                CollageRenderer.prepare(expression, note, null, sourceMap(), measurer)
            }
            val scene = (result as? CollageRenderer.SceneResult.Ready)?.scene
            ready = scene != null
            val sealedNote = scene?.note
            if (scene != null && sealedNote != null) {
                val request = CollageRenderer.TextMeasureRequest(
                    text = sealedNote.text,
                    family = sealedNote.family,
                    size = sealedNote.size,
                    weight = sealedNote.weight,
                    italic = sealedNote.italic,
                    line = sealedNote.line,
                    maxWidth = sealedNote.region.width,
                    maxHeight = sealedNote.region.height,
                )
                val metrics = measurer.measure(request)
                noteFits = !metrics.hasVisualOverflow &&
                    metrics.width in 1..sealedNote.region.width &&
                    metrics.height in 1..sealedNote.region.height
                CollageCanvas(scene, images("p0", "p1", "p2"), "collage", textMeasurer = measurer)
            }
        }
        composeRule.waitForIdle()
        assertTrue("prepare must be Ready with the real Compose measurer", ready)
        assertTrue("the same real measurer must still fit the sealed region", noteFits)
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
    }

    private fun expressionV3(note: String) = GeneratorExpression.ResolvedExpression(
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
            note = note,
            music = null,
        ),
        placements = contentIds.mapIndexed { index, id ->
            GeneratorExpression.Placement(
                contentId = id, x = index * 120, y = 0, width = 120, height = 213,
                crop = GeneratorExpression.CropWindow(0, 0, 10000, 10000),
                maskId = null,
                contentRect = null,
            )
        },
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        noteRegion = GeneratorExpression.NoteRegion(0, 400, 360, 200),
        style = GeneratorExpression.StyleBinding(
            styleId = "press",
            styleVersion = "style-study-1",
            digest = requireNotNull(StyleRegistry.digest("press")),
        ),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = null,
    )

    private fun sourceMap(): Map<String, CollageRenderer.SourcePhoto> =
        contentIds.associateWith { CollageRenderer.SourcePhoto(4, 4) }

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
