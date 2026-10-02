package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T150 crop stage surface: fixed frame, zoom control, direction pad and
 * done/reset affordances render; Done and Reset invoke their sinks.
 * Value clamping lives in the ViewModel and is covered there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropCorrectionSurfaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF669933.toInt())
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun source() = GeneratorPreviewSource(
        contentId = "photo-0",
        jpegBytes = jpeg(),
        widthPx = 1000,
        heightPx = 1000,
    )

    @Test
    fun cropStageRendersFrameControlsAndDone() {
        var done = 0
        var reset = 0
        val updates = mutableListOf<Triple<Float, Float, Float>>()
        composeRule.setContent {
            // Production mounts the stage inside CreateScreen's scrollable
            // root; the test mirrors that so the bottom action row is
            // reachable on small windows.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CropStageContent(
                    source = source(),
                    correction = CreateViewModel.CropCorrection("photo-0", 1.1f, 2f, 3f),
                    onUpdate = { zoom, panX, panY -> updates += Triple(zoom, panX, panY) },
                    onReset = { reset++ },
                    onDone = { done++ },
                )
            }
        }

        composeRule.onNodeWithTag("crop_stage").assertExists()
        composeRule.onNodeWithTag("crop_frame").assertExists()
        composeRule.onNodeWithTag("crop_stage_hint").assertExists()
        composeRule.onNodeWithTag("crop_zoom").assertExists()
        composeRule.onNodeWithTag("crop_pan_left").assertExists()
        composeRule.onNodeWithTag("crop_pan_up").assertExists()
        composeRule.onNodeWithTag("crop_pan_down").assertExists()
        composeRule.onNodeWithTag("crop_pan_right").assertExists()

        composeRule.onNodeWithTag("crop_pan_right").performClick()
        assertEquals(listOf(Triple(1.1f, 3f, 3f)), updates)

        composeRule.onNodeWithTag("crop_reset").performScrollTo()
        composeRule.onNodeWithTag("crop_reset").performClick()
        assertEquals(1, reset)
        composeRule.onNodeWithTag("crop_done").performScrollTo()
        composeRule.onNodeWithTag("crop_done").performClick()
        assertEquals(1, done)
    }

    @Test
    fun slotPickerOpensSelectedSlot() {
        var opened: String? = null
        val second = GeneratorPreviewSource("photo-1", jpeg(), 1000, 1000)
        composeRule.setContent {
            CropSlotPicker(
                sources = listOf(source(), second),
                corrections = mapOf("photo-0" to CreateViewModel.CropCorrection("photo-0", 1.2f, 0f, 0f)),
                onOpen = { opened = it },
            )
        }

        composeRule.onNodeWithTag("crop_slots").assertExists()
        composeRule.onNodeWithTag("crop_slot_1").performClick()
        assertEquals("photo-1", opened)
    }
}
