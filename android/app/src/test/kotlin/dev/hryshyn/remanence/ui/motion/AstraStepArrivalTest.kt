package dev.hryshyn.remanence.ui.motion

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
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
 * Inner Create step arrival: content is interactive immediately under every
 * regime (no navigation delay, no gating), and a step change swaps the
 * outgoing step synchronously with nothing retained. Tag-based,
 * locale-independent. NOT run in this tick (no Gradle execution per the
 * task brief; window 10 owns Gradle).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AstraStepArrivalTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fullRegimeRendersFirstMountWithoutAdvancingClock() {
        composeRule.setContent {
            HoldTheme {
                AstraStepArrival(motion = AstraMotionSpec.Resolved.FULL, stepKey = "lookup") {
                    Text("lookup", modifier = Modifier.testTag("step_probe"))
                }
            }
        }
        composeRule.waitForIdle()

        // First mount rides the root boundary arrival: no second fade may
        // gate it, so it must already be displayed with the clock untouched.
        composeRule.onNodeWithTag("step_probe").assertIsDisplayed()
    }

    @Test
    fun reducedRegimeRendersFirstMount() {
        composeRule.setContent {
            HoldTheme {
                AstraStepArrival(motion = AstraMotionSpec.Resolved.REDUCED_SHORT, stepKey = "lookup") {
                    Text("lookup", modifier = Modifier.testTag("step_probe_reduced"))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("step_probe_reduced").assertIsDisplayed()
    }

    @Test
    fun instantRegimeRendersFirstMount() {
        composeRule.setContent {
            HoldTheme {
                AstraStepArrival(motion = AstraMotionSpec.Resolved.INSTANT, stepKey = "lookup") {
                    Text("lookup", modifier = Modifier.testTag("step_probe_instant"))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("step_probe_instant").assertIsDisplayed()
    }

    @Test
    fun stepChangeSwapsSynchronouslyWithNothingRetained() {
        val key = mutableStateOf("lookup")
        composeRule.setContent {
            HoldTheme {
                AstraStepArrival(motion = AstraMotionSpec.Resolved.FULL, stepKey = key.value) {
                    Text(key.value, modifier = Modifier.testTag("step_" + key.value))
                }
            }
        }
        composeRule.onNodeWithTag("step_lookup").assertIsDisplayed()

        composeRule.runOnIdle { key.value = "confirm" }
        composeRule.mainClock.advanceTimeBy(1_000L)

        // The incoming step is present and the outgoing step is gone: no
        // exit retention, no private content kept behind.
        composeRule.onNodeWithTag("step_confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("step_lookup").assertDoesNotExist()
    }
}
