package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CropTransform
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T150 crop stage surface — the B1 fixed-frame contract:
 * - the frame keeps the **slot aspect** (`--crop-ratio: slot.w/slot.h`);
 * - the source excerpt is **clipped** to that frame (the frame edge is the
 *   retained window), never a source-aspect unclipped preview;
 * - the `.crop-grid` overlay (thirds + border) sits on the frame;
 * - the retained window shown to the user is derived with the same accepted
 *   T157 `sourceWindow` math the ViewModel seals, so display and sealed
 *   window can never disagree;
 * - zoom control, the four direction buttons (T157 `panDelta`) and
 *   done/reset/back sinks work.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropCorrectionSurfaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Destination slot geometry (the frame ratio comes from the placement). */
    private val slotWidth = 340f
    private val slotHeight = 248f
    private val slotAspect = slotWidth / slotHeight

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

    // A partial approved window: its excerpt overflows the fixed frame, which
    // is what the clip assertion observes.
    private val baseCrop = GeneratorExpression.CropWindow(500, 500, 6_000, 6_000)

    private fun stage(
        correction: CreateViewModel.CropCorrection?,
        updates: MutableList<Triple<Float, Float, Float>> = mutableListOf(),
        resets: MutableList<Int> = mutableListOf(),
        dones: MutableList<Int> = mutableListOf(),
        backs: MutableList<Int> = mutableListOf(),
    ) {
        composeRule.setContent {
            // Production mounts the stage inside CreateScreen's scrollable
            // root; the test mirrors that so the bottom action row is
            // reachable on small windows.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CropStageContent(
                    source = source(),
                    slotAspect = slotAspect,
                    baseCrop = baseCrop,
                    correction = correction,
                    onUpdate = { zoom, panX, panY -> updates += Triple(zoom, panX, panY) },
                    onReset = { resets += 1 },
                    onDone = { dones += 1 },
                    onBack = { backs += 1 },
                )
            }
        }
    }

    private fun DpRect.ratio(): Float =
        if (height.value == 0f) Float.NaN else width.value / height.value

    @Test
    fun cropStageRendersFrameControlsAndSinks() {
        val updates = mutableListOf<Triple<Float, Float, Float>>()
        val resets = mutableListOf<Int>()
        val dones = mutableListOf<Int>()
        val backs = mutableListOf<Int>()
        stage(
            correction = CreateViewModel.CropCorrection("photo-0", 1.1f, 2f, 3f),
            updates = updates,
            resets = resets,
            dones = dones,
            backs = backs,
        )

        composeRule.onNodeWithTag("crop_stage").assertExists()
        composeRule.onNodeWithTag("crop_frame").assertExists()
        composeRule.onNodeWithTag("crop_grid").assertExists()
        composeRule.onNodeWithTag("crop_stage_hint").assertExists()
        composeRule.onNodeWithTag("crop_zoom").assertExists()
        composeRule.onNodeWithTag("crop_pan_left").assertExists()
        composeRule.onNodeWithTag("crop_pan_up").assertExists()
        composeRule.onNodeWithTag("crop_pan_down").assertExists()
        composeRule.onNodeWithTag("crop_pan_right").assertExists()

        // One direction press is exactly one T157 frame percent on one axis.
        composeRule.onNodeWithTag("crop_pan_right").performClick()
        assertEquals(listOf(Triple(1.1f, 3f, 3f)), updates)

        composeRule.onNodeWithTag("crop_back").performScrollTo()
        composeRule.onNodeWithTag("crop_back").performClick()
        assertEquals(listOf(1), backs)

        composeRule.onNodeWithTag("crop_reset").performScrollTo()
        composeRule.onNodeWithTag("crop_reset").performClick()
        assertEquals(listOf(1), resets)

        composeRule.onNodeWithTag("crop_done").performScrollTo()
        composeRule.onNodeWithTag("crop_done").performClick()
        assertEquals(listOf(1), dones)
    }

    @Test
    fun frameKeepsSlotAspectAndClipsTheSource() {
        stage(correction = null)

        val frame: DpRect = composeRule.onNodeWithTag("crop_frame").getUnclippedBoundsInRoot()
        assertEquals("frame must keep the slot aspect", slotAspect, frame.ratio(), 0.01f)

        // The grid overlay covers the frame exactly (inset 0 in the CSS).
        val grid: DpRect = composeRule.onNodeWithTag("crop_grid").getUnclippedBoundsInRoot()
        assertTrue("grid width", absDp(grid.width - frame.width) <= Dp(1f))
        assertTrue("grid height", absDp(grid.height - frame.height) <= Dp(1f))

        // The source excerpt overflows the frame (a base crop that does not
        // fill the window still draws beyond it) but is CLIPPED to it.
        val image = composeRule.onNodeWithTag("crop_image")
        val imageUnclipped: DpRect = image.getUnclippedBoundsInRoot()
        val imageClipped: DpRect = image.getBoundsInRoot()
        assertTrue(
            "clipped image must stay inside the fixed frame; frame=$frame clipped=$imageClipped unclipped=$imageUnclipped",
            imageClipped.left >= frame.left - Dp(1f) &&
                imageClipped.top >= frame.top - Dp(1f) &&
                imageClipped.right <= frame.right + Dp(1f) &&
                imageClipped.bottom <= frame.bottom + Dp(1f),
        )
        assertTrue(
            "the unclipped excerpt must be larger than the window; frame=$frame unclipped=$imageUnclipped base=$baseCrop",
            imageUnclipped.width > frame.width || imageUnclipped.height > frame.height,
        )
    }

    @Test
    fun retainedWindowIsIdentityWithoutCorrection() {
        val base = requireNotNull(FixedFrameCropCorrection.normalizedCrop(baseCrop))
        val identity = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.0, 0.0, 0.0)),
        )
        stage(correction = null)

        // Identity: the frame shows exactly the approved window, derived with
        // the accepted T157 sourceWindow (the same call the ViewModel seals).
        composeRule.onNode(stateMatches(windowState(identity))).assertExists()
    }

    @Test
    fun retainedWindowFollowsZoomAndPanFromT157Math() {
        val base = requireNotNull(FixedFrameCropCorrection.normalizedCrop(baseCrop))
        val identity = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.0, 0.0, 0.0)),
        )
        stage(correction = CreateViewModel.CropCorrection("photo-0", 1.25f, 5f, 0f))
        val zoomed = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.25, 5.0, 0.0)),
        )
        val zoomOnly = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.25, 0.0, 0.0)),
        )

        assertTrue("zoom must narrow the window", zoomed.width < identity.width)
        // Positive x percent moves the window LEFT relative to the pure-zoom
        // window (T157 sourceWindow sign), not necessarily past base.x.
        assertTrue(
            "pan must shift the retained window left of the zoom-only window",
            zoomed.x < zoomOnly.x,
        )
        composeRule.onNode(stateMatches(windowState(zoomed))).assertExists()
        // ...and no longer the identity window.
        composeRule.onNode(stateMatches(windowState(identity))).assertDoesNotExist()
    }

    @Test
    fun nonSquareSlotIsAcceptedAndKeptAsFrameRatio() {
        val portraitAspect = 167f / 248f
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CropStageContent(
                    source = source(),
                    slotAspect = portraitAspect,
                    baseCrop = baseCrop,
                    correction = null,
                    onUpdate = { _, _, _ -> },
                    onReset = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }
        val frame = composeRule.onNodeWithTag("crop_frame").getUnclippedBoundsInRoot()
        assertEquals(portraitAspect, frame.ratio(), 0.01f)
        composeRule.onNodeWithTag("crop_grid").assertIsDisplayed()
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

    @Test
    fun fiveSlotPickerKeepsEverySlotReachable() {
        var opened: String? = null
        val five = (0..4).map { GeneratorPreviewSource("photo-$it", jpeg(), 1000, 1000) }
        composeRule.setContent {
            Column(Modifier.width(320.dp)) {
                CropSlotPicker(
                    sources = five,
                    corrections = emptyMap(),
                    onOpen = { opened = it },
                )
            }
        }

        composeRule.onNodeWithTag("crop_slots").assertExists()
        // The trailing slot of five must be reachable (scrollable row,
        // never clipped away) and open its own content id.
        composeRule.onNodeWithTag("crop_slot_4").performScrollTo()
        composeRule.onNodeWithTag("crop_slot_4").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_slot_4").performClick()
        assertEquals("photo-4", opened)
    }

    private fun absDp(value: Dp): Dp = if (value.value < 0f) -value else value

    /** Matcher for the frame's retained-window state description. */
    private fun stateMatches(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
}
