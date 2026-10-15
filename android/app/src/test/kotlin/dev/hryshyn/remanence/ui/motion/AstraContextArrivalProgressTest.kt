package dev.hryshyn.remanence.ui.motion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Routine boundary arrival proves SMOOTH progress, not just final
 * presence: under FULL the shared 4 dp travel is measurably mid-flight
 * partway through the authored duration and settles exactly at rest
 * afterwards, while the starting opacity itself is observable mid-flight
 * (the material T204 change) before settling at fully opaque.
 * REDUCED_SHORT never travels; INSTANT rests immediately. Synthetic red
 * probe content only — no app/private pixels. NOT run in this tick (no
 * Gradle execution per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AstraContextArrivalProgressTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun mount(motion: AstraMotionSpec.Resolved) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HoldTheme {
                AstraContextArrival(motion = motion) {
                    Box(
                        Modifier.testTag("arrival_probe").size(48.dp)
                            .background(Color.Red),
                    )
                }
            }
        }
    }

    private fun probeY(): Float =
        composeRule.onNodeWithTag("arrival_probe").fetchSemanticsNode().positionInRoot.y

    /** Center-pixel opacity of the synthetic probe (graphicsLayer applied). */
    private fun probeAlpha(): Float {
        val image = composeRule.onNodeWithTag("arrival_probe").captureToImage()
        val pixels = image.toPixelMap()
        return pixels[image.width / 2, image.height / 2].alpha
    }

    @Test
    fun fullRegimeTravelsSmoothlyThenSettles() {
        mount(AstraMotionSpec.Resolved.FULL)
        composeRule.mainClock.advanceTimeBy(16)
        val y0 = probeY()
        val a0 = probeAlpha()
        // Changed behavior: the arrival starts soft, well below opaque.
        assertTrue("arrival starts near-opaque ($a0)", a0 > 0.60f && a0 < 0.80f)
        composeRule.mainClock.advanceTimeBy(260)
        val y1 = probeY()
        val a1 = probeAlpha()
        // Mid-flight: travel easing is strictly monotonic and opacity is
        // strictly between the soft start and fully opaque.
        assertTrue("no mid-flight travel ($y0 -> $y1)", y1 < y0)
        assertTrue("no mid-flight opacity progress ($a0 -> $a1)", a1 > a0 && a1 < 1f)
        composeRule.mainClock.advanceTimeBy(5_000)
        val y2 = probeY()
        assertTrue("no continued progress ($y1 -> $y2)", y2 != y1)
        assertEquals(1f, probeAlpha(), 0f)
        composeRule.mainClock.advanceTimeBy(30_000)
        // Settled: the finished animation issues no further frames.
        assertEquals(y2, probeY(), 0f)
        composeRule.onNodeWithTag("arrival_probe").assertIsDisplayed()
    }

    @Test
    fun reducedRegimeNeverTravels() {
        mount(AstraMotionSpec.Resolved.REDUCED_SHORT)
        composeRule.mainClock.advanceTimeBy(16)
        val y0 = probeY()
        composeRule.mainClock.advanceTimeBy(1_000)
        // Alpha-only crossfade: zero travel at every instant, settled opaque.
        assertEquals(y0, probeY(), 0f)
        assertEquals(1f, probeAlpha(), 0f)
        composeRule.onNodeWithTag("arrival_probe").assertIsDisplayed()
    }

    @Test
    fun instantRegimeRestsImmediately() {
        mount(AstraMotionSpec.Resolved.INSTANT)
        composeRule.mainClock.advanceTimeBy(16)
        val y0 = probeY()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(y0, probeY(), 0f)
        assertEquals(1f, probeAlpha(), 0f)
        composeRule.onNodeWithTag("arrival_probe").assertIsDisplayed()
    }
}
