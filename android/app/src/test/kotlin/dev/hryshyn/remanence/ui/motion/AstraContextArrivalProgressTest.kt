package dev.hryshyn.remanence.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
 * afterwards. REDUCED_SHORT never travels; INSTANT rests immediately.
 * Position-based (translationY), locale-independent. NOT run in this
 * tick (no Gradle execution per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AstraContextArrivalProgressTest {

    @get:Rule
    val composeRule = createAndroidComposeRule()

    private fun mount(motion: AstraMotionSpec.Resolved) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HoldTheme {
                AstraContextArrival(motion = motion) {
                    Box(Modifier.testTag("arrival_probe").size(48.dp))
                }
            }
        }
    }

    private fun probeY(): Float =
        composeRule.onNodeWithTag("arrival_probe").fetchSemanticsNode().positionInRoot.y

    @Test
    fun fullRegimeTravelsSmoothlyThenSettles() {
        mount(AstraMotionSpec.Resolved.FULL)
        composeRule.mainClock.advanceTimeBy(16)
        val y0 = probeY()
        composeRule.mainClock.advanceTimeBy(260)
        val y1 = probeY()
        // Mid-flight: travel easing is strictly monotonic, so the probe
        // has measurably moved toward rest but not arrived.
        assertTrue("no mid-flight progress ($y0 -> $y1)", y1 < y0)
        composeRule.mainClock.advanceTimeBy(5_000)
        val y2 = probeY()
        assertTrue("no continued progress ($y1 -> $y2)", y2 != y1)
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
        // Alpha-only crossfade: zero travel at every instant.
        assertEquals(y0, probeY(), 0f)
        composeRule.onNodeWithTag("arrival_probe").assertIsDisplayed()
    }

    @Test
    fun instantRegimeRestsImmediately() {
        mount(AstraMotionSpec.Resolved.INSTANT)
        composeRule.mainClock.advanceTimeBy(16)
        val y0 = probeY()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(y0, probeY(), 0f)
        composeRule.onNodeWithTag("arrival_probe").assertIsDisplayed()
    }
}
