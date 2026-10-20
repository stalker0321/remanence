package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropCorrectionSurfaceTest {
    private val DpRect.width get() = right - left
    private val DpRect.height get() = bottom - top
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun source(width: Int = 120, height: Int = 80): GeneratorPreviewSource {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            bitmap.setPixel(x, y, android.graphics.Color.rgb(x * 255 / (width - 1), y * 255 / (height - 1), 128))
        }
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        return GeneratorPreviewSource("photo", bytes, width, height)
    }

    private fun stage(
        initial: CreateViewModel.CropCorrection? = null,
        onUpdate: (CreateViewModel.CropCorrection) -> Unit = {},
        onBack: () -> Unit = {},
        onReset: () -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        val image = source()
        var correction by mutableStateOf(initial)
        composeRule.setContent {
            HoldTheme {
                Column(Modifier.width(320.dp).height(540.dp)) {
                    CropStageContent(
                        source = image, slotAspect = 1f,
                        baseCrop = GeneratorExpression.CropWindow(2_000, 1_000, 4_000, 6_000),
                        correction = correction,
                        onUpdate = { z, x, y ->
                            correction = CreateViewModel.CropCorrection("photo", z, x, y)
                            onUpdate(requireNotNull(correction))
                        },
                        onReset = onReset, onDone = onDone, onBack = onBack,
                    )
                }
            }
        }
    }

    @Test
    fun wholeOriginalIsVisibleAndFrameKeepsDestinationAspect() {
        stage()
        val image = composeRule.onNodeWithTag("crop_image").getUnclippedBoundsInRoot()
        val visible = composeRule.onNodeWithTag("crop_image").getBoundsInRoot()
        val frame = composeRule.onNodeWithTag("crop_frame").getUnclippedBoundsInRoot()
        assertEquals(1.5f, image.width.value / image.height.value, .01f)
        assertEquals(image, visible)
        assertEquals(1f, frame.width.value / frame.height.value, .01f)
        assertTrue(frame.left > image.left && frame.right < image.right)
        assertTrue(frame.top > image.top && frame.bottom < image.bottom)
        composeRule.onNodeWithTag("crop_done").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_zoom").assertDoesNotExist()
        composeRule.onNodeWithTag("crop_pan_left").assertDoesNotExist()
    }

    @Test
    fun draggingFrameAtIdentityMovesItRightWithoutMovingTheOriginal() {
        stage()
        val imageBefore = composeRule.onNodeWithTag("crop_image").getBoundsInRoot()
        val frameBefore = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        composeRule.onNodeWithTag("crop_source_surface").performTouchInput {
            down(Offset(width * .4f, height * .4f))
            moveBy(Offset(width * .1f, 0f))
            up()
        }
        val frameAfter = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        assertTrue(frameAfter.left > frameBefore.left + 10.dp)
        assertEquals(frameBefore.width.value, frameAfter.width.value, .5f)
        assertEquals(imageBefore, composeRule.onNodeWithTag("crop_image").getBoundsInRoot())
    }

    @Test
    fun pinchingResizesFrameAndAccumulatesMultipleTouchEvents() {
        var latest: CreateViewModel.CropCorrection? = null
        stage(onUpdate = { latest = it })
        val before = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        composeRule.onNodeWithTag("crop_source_surface").performTouchInput {
            down(0, Offset(width * .30f, height * .4f))
            down(1, Offset(width * .50f, height * .4f))
            updatePointerTo(0, Offset(width * .29f, height * .4f))
            updatePointerTo(1, Offset(width * .51f, height * .4f))
            move()
            updatePointerTo(0, Offset(width * .28f, height * .4f))
            updatePointerTo(1, Offset(width * .52f, height * .4f))
            move()
            up(0)
            up(1)
        }
        assertEquals(1.2f, requireNotNull(latest).zoom, .01f)
        val after = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        assertTrue(after.width < before.width && after.height < before.height)
        assertEquals(before.width.value / 1.2f, after.width.value, 1f)
    }

    @Test
    fun draggingOutsideTheFrameDoesNotMoveIt() {
        stage()
        val before = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        composeRule.onNodeWithTag("crop_source_surface").performTouchInput {
            down(Offset(width * .05f, height * .5f))
            moveBy(Offset(width * .1f, 0f))
            up()
        }
        assertEquals(before, composeRule.onNodeWithTag("crop_frame").getBoundsInRoot())
    }

    @Test
    fun cancelResetAndDoneRemainReachable() {
        var backs = 0
        var resets = 0
        var dones = 0
        stage(onBack = { backs++ }, onReset = { resets++ }, onDone = { dones++ })
        composeRule.onNodeWithTag("crop_back").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("crop_reset").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("crop_done").assertIsDisplayed().performClick()
        assertEquals(1, backs)
        assertEquals(1, resets)
        assertEquals(1, dones)
    }

    @Test fun semanticCropActionsMoveAndZoomTheRealFrame() {
        stage()
        val before = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        composeRule.onNodeWithTag("crop_frame").performSemanticsAction(SemanticsActions.CustomActions) { actions ->
            assertEquals(6, actions.size)
            assertTrue(actions.single { it.label == "Move crop right" }.action())
        }
        val moved = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        assertTrue(moved.left > before.left)
        composeRule.onNodeWithTag("crop_frame").performSemanticsAction(SemanticsActions.CustomActions) { actions ->
            assertTrue(actions.single { it.label == "Zoom in" }.action())
        }
        assertTrue(composeRule.onNodeWithTag("crop_frame").getBoundsInRoot().width < moved.width)
    }

    @Test
    fun savedTransformAndReopenedCropHaveTheSameFrameBounds() {
        val image = source()
        var saved by mutableStateOf(false)
        composeRule.setContent {
            Column(Modifier.width(320.dp).height(540.dp)) {
                CropStageContent(
                    source = image, slotAspect = 1f,
                    baseCrop = if (saved) GeneratorExpression.CropWindow(2_000, 1_750, 3_333, 5_000)
                        else GeneratorExpression.CropWindow(2_000, 1_000, 4_000, 6_000),
                    correction = if (saved) null else CreateViewModel.CropCorrection("photo", 1.2f, 10f, -5f),
                    onUpdate = { _, _, _ -> }, onReset = {}, onDone = {}, onBack = {},
                )
            }
        }
        val before = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        composeRule.runOnIdle { saved = true }
        val after = composeRule.onNodeWithTag("crop_frame").getBoundsInRoot()
        assertEquals(before.left.value, after.left.value, .5f)
        assertEquals(before.top.value, after.top.value, .5f)
        assertEquals(before.width.value, after.width.value, .5f)
        assertEquals(before.height.value, after.height.value, .5f)
    }
}
