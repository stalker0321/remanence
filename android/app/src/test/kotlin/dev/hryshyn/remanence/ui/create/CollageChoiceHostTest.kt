package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun collageChoiceJpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(0xFF336699.toInt())
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * Sender collage chooser host: no canvas before an explicit selection, the
 * selected candidate renders through the shared CollageCanvas, and a missing
 * authoritative analysis shows typed copy instead of a grid.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageChoiceHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val contentIds = listOf("p0", "p1", "p2")

    private fun input() = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
        },
        note = null,
        music = null,
    )

    private fun sources() = contentIds.map { GeneratorPreviewSource(it, collageChoiceJpeg(), 8, 8) }

    private fun cropSource() = CollageCropSource { requests ->
        requests.mapIndexed { index, request ->
            request.contentId to GeneratorExpression.CropWindow(index * 100, 0, 5000, 10000)
        }.toMap()
    }

    @Test
    fun showsSelectHintBeforeExplicitSelection() {
        val session = CollageChoiceSession()
        val input = input()
        val sources = sources()
        val crops = cropSource()
        composeRule.setContent {
            CollageChoiceHost(session, input, sources, null, null, crops)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collage_select_hint").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
    }

    @Test
    fun explicitSelectionShowsSharedCanvas() {
        val session = CollageChoiceSession()
        val input = input()
        val sources = sources()
        val crops = cropSource()
        composeRule.setContent {
            CollageChoiceHost(session, input, sources, null, null, crops)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { session.select(0) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collage_canvas").assertExists()
    }

    @Test
    fun selectedCanvasHasNonZeroHeightUnderVerticalScroll() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                CollageChoiceHost(session, input(), sources(), null, null, cropSource())
            }
        }
        composeRule.waitForIdle()

        // Before explicit selection no canvas is shown, even under scroll.
        composeRule.onNodeWithTag("collage_select_hint").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()

        composeRule.runOnIdle { session.select(0) }
        composeRule.waitForIdle()

        val height = composeRule.onNodeWithTag("collage_canvas").fetchSemanticsNode().size.height
        assertTrue("sealed-aspect canvas height must be > 0 under verticalScroll, was $height", height > 0)
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
    }

    @Test
    fun selectedCandidateRendersNonPaperPixelsAndNextChangesThem() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                CollageChoiceHost(session, input(), sources(), null, null, cropSource())
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { session.select(0) }
        composeRule.waitForIdle()

        val first = renderedContentPixels()
        val firstNonPaper = first.count { it != paperOfSelected(session) }
        assertTrue("candidate 0 must render photographic pixels, got $firstNonPaper", firstNonPaper >= 1000)

        composeRule.onNodeWithTag("collage_next").performScrollTo()
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        assertEquals(1, session.selectedIndex)

        val second = renderedContentPixels()
        val secondNonPaper = second.count { it != paperOfSelected(session) }
        assertTrue("candidate 1 must render photographic pixels, got $secondNonPaper", secondNonPaper >= 1000)
        assertTrue("Next must change composition pixels", !first.contentEquals(second))
    }

    @Test
    fun unavailableAnalysisShowsNoticeWithoutCanvas() {
        val session = CollageChoiceSession()
        val input = input()
        val sources = sources()
        val unavailable = CollageCropSource { null }
        composeRule.setContent {
            CollageChoiceHost(session, input, sources, null, null, unavailable)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collage_notice").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        composeRule.onNodeWithTag("collage_select_hint").assertDoesNotExist()
    }

    @Test
    fun selectRequiresHighlightAndReportsIt() {
        val session = CollageChoiceSession()
        val selected = mutableListOf<Int>()
        val navigated = mutableListOf<Unit>()
        composeRule.setContent {
            CollageChoiceHost(
                session, input(), sources(), null, null, cropSource(),
                onSelect = { selected += it },
                onNavigate = { navigated += Unit },
            )
        }
        composeRule.waitForIdle()

        // Nothing highlighted: Select is disabled and reports nothing.
        composeRule.onNodeWithTag("collage_select").assertIsNotEnabled()
        assertTrue(selected.isEmpty())
        // Browsing from null highlights the first variant and reports navigation.
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        assertEquals(0, session.selectedIndex)
        assertEquals(1, navigated.size)
        // The explicit action freezes exactly the highlighted candidate.
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(0), selected)
        // Moving highlights another candidate and reports navigation again.
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        assertEquals(1, session.selectedIndex)
        assertEquals(2, navigated.size)
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(0, 1), selected)
    }

    @Test
    fun frozenCandidateIdRendersWhenProvided() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            CollageChoiceHost(
                session, input(), sources(), null, null, cropSource(),
                frozenCandidateId = { "ber1-frozen" },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_frozen").assertExists()
    }

    private fun renderedContentPixels(): IntArray {
        // PixelCopy has no real Surface producer on Robolectric (see
        // HoldVisualReviewTest): draw the measured native View tree directly.
        composeRule.onNodeWithTag("collage_canvas").performScrollTo()
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithTag("collage_canvas").fetchSemanticsNode()
        var pixels = IntArray(0)
        composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(full))
            val left = node.positionInRoot.x.toInt().coerceIn(0, full.width - 1)
            val top = node.positionInRoot.y.toInt().coerceIn(0, full.height - 1)
            val right = (left + node.size.width).coerceIn(left + 1, full.width)
            val bottom = (top + node.size.height).coerceIn(top + 1, full.height)
            val out = IntArray((right - left) * (bottom - top))
            full.getPixels(out, 0, right - left, left, top, right - left, bottom - top)
            pixels = out
        }
        return pixels
    }

    private fun paperOfSelected(session: CollageChoiceSession): Int {
        val styleId = requireNotNull(session.selected).styleId
        val paper = requireNotNull(StyleRegistry.style(styleId)).palette.paper
        return 0xFF000000.toInt() or paper.substring(1).toInt(16)
    }
}
