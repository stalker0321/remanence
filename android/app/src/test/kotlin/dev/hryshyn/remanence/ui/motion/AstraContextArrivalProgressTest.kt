package dev.hryshyn.remanence.ui.motion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Routine boundary arrival proves SMOOTH progress, not just final
 * presence: under FULL the shared 4 dp travel is measurably mid-flight
 * partway through the authored duration and settles exactly at rest
 * afterwards, while the starting opacity itself is observable mid-flight
 * (the material T204 change) before settling at fully opaque. Opacity is
 * inferred from composited color channels — never raw window pixel alpha:
 * the capture is a composited-window crop, so an opaque backdrop would
 * read 1.0 regardless of the layer value. A deterministic opaque WHITE
 * backdrop sits OUTSIDE the animated wrapper with a solid red probe
 * inside (red-over-white: green channel == 1 - opacity, modulo pixel
 * quantization). REDUCED_SHORT never travels; INSTANT rests immediately.
 * Synthetic content only — no app/private pixels. NOT run in this tick
 * (no Gradle execution per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AstraContextArrivalProgressTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun mount(motion: AstraMotionSpec.Resolved) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HoldTheme {
                Box(
                    Modifier.testTag("arrival_backdrop").size(96.dp)
                        .background(Color.White),
                ) {
                    AstraContextArrival(motion = motion) {
                        Box(
                            Modifier.testTag("arrival_probe").size(48.dp)
                                .background(Color.Red),
                        )
                    }
                }
            }
        }
    }

    private fun probeY(): Float =
        composeRule.onNodeWithTag("arrival_probe").fetchSemanticsNode().positionInRoot.y

    /**
     * Center-pixel opacity of the synthetic probe, inferred from the
     * composited green channel (red probe over the opaque white
     * backdrop: green == 1 - opacity). Drawn synchronously on the test
     * thread via the established decorView.draw pattern — deliberately
     * NOT runOnIdle/waitForIdle/captureToImage: idleness machinery pumps
     * frames, which would advance the paused animation clock and let the
     * mid-flight read land anywhere. Raw captured alpha is always 1.0
     * over the opaque window and measures nothing.
     */
    private fun probeOpacity(): Float {
        val bounds = composeRule.onNodeWithTag("arrival_probe").fetchSemanticsNode().boundsInWindow
        val cx = (bounds.left + bounds.width / 2).toInt().coerceAtLeast(0)
        val cy = (bounds.top + bounds.height / 2).toInt().coerceAtLeast(0)
        var green = -1
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(
                view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888,
            )
            try {
                view.draw(android.graphics.Canvas(bitmap))
                val px = cx.coerceAtMost(bitmap.width - 1)
                val py = cy.coerceAtMost(bitmap.height - 1)
                green = android.graphics.Color.green(bitmap.getPixel(px, py))
            } finally {
                bitmap.recycle()
            }
        }
        return 1f - green / 255f
    }

    @Test
    fun fullRegimeTravelsSmoothlyThenSettles() {
        mount(AstraMotionSpec.Resolved.FULL)
        composeRule.mainClock.advanceTimeBy(16)
        val y0 = probeY()
        val o0 = probeOpacity()
        // Changed behavior: the arrival starts soft, well below opaque.
        assertTrue("arrival starts near-opaque ($o0)", o0 > 0.60f && o0 < 0.80f)
        composeRule.mainClock.advanceTimeBy(260)
        val y1 = probeY()
        val o1 = probeOpacity()
        // Mid-flight: travel easing is strictly monotonic and inferred
        // opacity is strictly between the soft start and fully opaque.
        assertTrue("no mid-flight travel ($y0 -> $y1)", y1 < y0)
        assertTrue("no mid-flight opacity progress ($o0 -> $o1)", o1 > o0 && o1 < 1f)
        composeRule.mainClock.advanceTimeBy(5_000)
        val y2 = probeY()
        assertTrue("no continued progress ($y1 -> $y2)", y2 != y1)
        assertEquals(1f, probeOpacity(), 0f)
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
        assertEquals(1f, probeOpacity(), 0f)
        composeRule.onNodeWithTag("arrival_probe").assertIsDisplayed()
    }

    @Test
    fun instantRegimeRestsImmediately() {
        mount(AstraMotionSpec.Resolved.INSTANT)
        composeRule.mainClock.advanceTimeBy(16)
        val y0 = probeY()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(y0, probeY(), 0f)
        assertEquals(1f, probeOpacity(), 0f)
        composeRule.onNodeWithTag("arrival_probe").assertIsDisplayed()
    }
}
