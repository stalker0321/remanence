package dev.hryshyn.remanence.ui.capsule

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A genuinely decodable JPEG so the bounded on-screen decode succeeds. */
private fun t012RealJpeg(ordinal: Int): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor((0xFF000000L + ordinal * 0x010101L).toInt())
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * T012: capsule underlayer reduced-motion parity.
 *
 * FULL keeps the existing 280 ms settle; REDUCED_SHORT and INSTANT snap
 * without tween/travel. Drag thresholds, geometry and end states are
 * identical in every regime.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsuleUnderlayerMotionTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private class RecordingLoader : CapsulePhotoLoader {
        override suspend fun load(ordinal: Int): DecryptedPhoto =
            DecryptedPhoto(ordinal, t012RealJpeg(ordinal))
    }

    private fun openNotedState(): CapsulePresentationState =
        CapsulePresentationState(RecordingLoader()).also { it.open(3, "t012 underlayer note") }

    @Test
    fun motionRegimeSelectionMatchesAnimatorScale() {
        assertEquals(AstraMotionSpec.Resolved.FULL, AstraMotionSpec.resolve(1f))
        assertEquals(AstraMotionSpec.Resolved.FULL, AstraMotionSpec.resolve(2f))
        assertEquals(AstraMotionSpec.Resolved.REDUCED_SHORT, AstraMotionSpec.resolve(0.5f))
        assertEquals(AstraMotionSpec.Resolved.INSTANT, AstraMotionSpec.resolve(0f))
    }

    @Test
    fun fullMotionKeeps280msTweenSettle() {
        val spec = capsuleUnderlayerSettleSpec(AstraMotionSpec.Resolved.FULL)
        val tweenSpec: TweenSpec<Float> = checkNotNull(spec as? TweenSpec<Float>) {
            "FULL must keep the 280 ms tween, got $spec"
        }
        assertEquals(280, tweenSpec.durationMillis)
    }

    @Test
    fun reducedAndOffSettleWithSnapAndNoTravel() {
        val reduced = capsuleUnderlayerSettleSpec(AstraMotionSpec.Resolved.REDUCED_SHORT)
        assertTrue("REDUCED must snap without tween/travel, got $reduced", reduced is SnapSpec<*>)
        val off = capsuleUnderlayerSettleSpec(AstraMotionSpec.Resolved.INSTANT)
        assertTrue("OFF must snap without tween/travel, got $off", off is SnapSpec<*>)
    }

    private fun assertRevealButtonEndState(motion: AstraMotionSpec.Resolved) {
        val presentationState = openNotedState()
        composeRule.setContent {
            CapsuleScreen(presentationState, motion = motion) {}
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capsule_reveal_button").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithTag("capsule_underlayer").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_note_text").assertIsDisplayed()
        // End state: the same page is shown and the button offers collapse.
        composeRule.onNodeWithTag("capsule_page_0").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_reveal_button").assertIsDisplayed()
        // Toggling back hides the underlayer again in the same regime.
        composeRule.onNodeWithTag("capsule_reveal_button").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onAllNodesWithTag("capsule_underlayer").assertCountEquals(0)
    }

    @Test
    fun fullMotionRevealButtonEndState() {
        assertRevealButtonEndState(AstraMotionSpec.Resolved.FULL)
    }

    @Test
    fun reducedMotionRevealButtonEndStateMatchesFull() {
        assertRevealButtonEndState(AstraMotionSpec.Resolved.REDUCED_SHORT)
    }

    @Test
    fun offMotionRevealButtonEndStateMatchesFull() {
        assertRevealButtonEndState(AstraMotionSpec.Resolved.INSTANT)
    }

    @Test
    fun dragRevealEndStateMatchesButtonEndStateInFullMotion() {
        val presentationState = openNotedState()
        composeRule.setContent {
            CapsuleScreen(presentationState, motion = AstraMotionSpec.Resolved.FULL) {}
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capsule_page_0").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithTag("capsule_underlayer").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_note_text").assertIsDisplayed()
    }
}
