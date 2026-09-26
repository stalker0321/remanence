package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import java.io.ByteArrayOutputStream
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
    val composeRule = createComposeRule()

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

    private fun cropSource() = CollageCropSource { photos ->
        photos.associate { photo ->
            photo.contentId to GeneratorExpression.CropWindow(photo.ordinal * 100, 0, 5000, 10000)
        }
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
}
