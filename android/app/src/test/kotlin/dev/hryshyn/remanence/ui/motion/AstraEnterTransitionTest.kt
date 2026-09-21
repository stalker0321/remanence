package dev.hryshyn.remanence.ui.motion

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The routine entry transition renders its content under every regime
 * without throwing — including the instant snap path. Tag-based,
 * locale-independent. NOT run in this tick (no Gradle execution per the
 * task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AstraEnterTransitionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fullRegimeRendersContent() {
        composeRule.setContent {
            HoldTheme {
                AstraEnterTransition(motion = AstraMotionSpec.Resolved.FULL) {
                    Text("probe", modifier = Modifier.testTag("enter_probe"))
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("enter_probe").assertIsDisplayed()
    }

    @Test
    fun reducedRegimeRendersContent() {
        composeRule.setContent {
            HoldTheme {
                AstraEnterTransition(motion = AstraMotionSpec.Resolved.REDUCED_SHORT) {
                    Text("probe", modifier = Modifier.testTag("enter_probe"))
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("enter_probe").assertIsDisplayed()
    }

    @Test
    fun instantRegimeRendersContentWithoutAdvancingClock() {
        composeRule.setContent {
            HoldTheme {
                AstraEnterTransition(motion = AstraMotionSpec.Resolved.INSTANT) {
                    Text("probe", modifier = Modifier.testTag("enter_probe"))
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("enter_probe").assertIsDisplayed()
    }
}
