package dev.hryshyn.remanence.ui.scan

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Single-arrival gate for the Scan capture entry: the first mount composes
 * under the root Home -> Scan boundary arrival, so it must snap (INSTANT)
 * instead of stacking a second fade; afterwards the live regime applies.
 * Tag-based, locale-independent. NOT run in this tick (no Gradle execution
 * per the task brief; window 10 owns Gradle).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScanEntryMotionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun entryMotionSnapsFirstThenFollowsLive() {
        val seen = mutableListOf<AstraMotionSpec.Resolved>()
        val live = mutableStateOf(AstraMotionSpec.Resolved.FULL)
        composeRule.setContent {
            HoldTheme {
                val entry = rememberScanEntryMotion(live.value)
                seen += entry
                Text(entry.name, modifier = Modifier.testTag("entry_motion_probe"))
            }
        }
        composeRule.waitForIdle()

        // First composition snaps under the root arrival, then the latch
        // releases to the live regime.
        assertEquals(
            "first entry composition must snap: $seen",
            AstraMotionSpec.Resolved.INSTANT,
            seen.first(),
        )
        assertTrue("latch must release to live motion: $seen", seen.contains(AstraMotionSpec.Resolved.FULL))
        composeRule.onNodeWithTag("entry_motion_probe").assertIsDisplayed()

        composeRule.runOnIdle { live.value = AstraMotionSpec.Resolved.REDUCED_SHORT }
        composeRule.waitForIdle()
        assertEquals(
            "live regime changes must apply after entry: $seen",
            AstraMotionSpec.Resolved.REDUCED_SHORT,
            seen.last(),
        )
    }
}
