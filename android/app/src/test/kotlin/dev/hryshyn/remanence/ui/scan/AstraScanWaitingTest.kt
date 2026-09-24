package dev.hryshyn.remanence.ui.scan

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * State-to-visual contract for the post-scan waiting group:
 * every waiting state renders the decorative drawn postcard plus its own
 * two-line status copy under the same tags the production [ScanScreen]
 * branches used before, the progress indicator follows the motion regime
 * (indeterminate only under FULL; static track otherwise — no infinite
 * pulse when reduced), and the stop action fires the real recovery.
 *
 * All assertions are locale-independent (tags, counts, semantics keys —
 * never localized text). Robolectric Compose tests. NOT run in this tick
 * (no Gradle execution per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AstraScanWaitingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun matchingRendersPostcardAndTwoLineCopy() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("astra_postcard")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("graphic postcard front")
        // The artwork node is merged into the parent's single graphic
        // semantics, so it is queried from the unmerged tree.
        composeRule.onNodeWithTag("astra_postcard_art", useUnmergedTree = true).assertIsDisplayed()
        // Exactly one postcard node: no clones, no remnants.
        composeRule.onAllNodesWithTag("astra_postcard").assertCountEquals(1)
        composeRule.onNodeWithTag("scan_matching").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_matching_body").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_wait_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_stop_scanning")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun acceptedRendersTwoLineOpeningCopy() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Accepted("candidate-id", viaSenderFallback = false),
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_500L)

        composeRule.onNodeWithTag("astra_postcard").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_verified").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_verified_body").assertIsDisplayed()
    }

    @Test
    fun materialPendingOfflineRendersOfflineCopyWithoutBody() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.MaterialPending("capsule-id", connected = false),
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("astra_postcard").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_material_pending_offline").assertIsDisplayed()
        // The offline line 1 is already a complete two-sentence
        // instruction; no duplicating body is added.
        composeRule.onAllNodesWithTag("scan_material_pending_online_body").assertCountEquals(0)
    }

    @Test
    fun materialPendingOnlineRendersTwoLineCopy() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.MaterialPending("capsule-id", connected = true),
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("scan_material_pending_online").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_material_pending_online_body").assertIsDisplayed()
    }

    @Test
    fun postcardCarriesAcrossWaitingStatesWithoutGhosts() {
        val state = mutableStateOf<ScanMatchUiState>(ScanMatchUiState.Matching)
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = state.value,
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithTag("scan_matching").assertIsDisplayed()

        state.value = ScanMatchUiState.MaterialPending("capsule-id", connected = true)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithTag("scan_material_pending_online").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_material_pending_online_body").assertIsDisplayed()

        state.value = ScanMatchUiState.Accepted("candidate-id", viaSenderFallback = false)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_500L)
        composeRule.onNodeWithTag("scan_verified").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_verified_body").assertIsDisplayed()

        // One postcard instance throughout every transition.
        composeRule.onAllNodesWithTag("astra_postcard").assertCountEquals(1)
    }

    @Test
    fun liveMotionChangeSwapsIndicatorWithoutLosingContent() {
        val motion = mutableStateOf(AstraMotionSpec.Resolved.FULL)
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = motion.value,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithTag("scan_wait_progress").assertIsDisplayed()

        motion.value = AstraMotionSpec.Resolved.INSTANT
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500L)

        composeRule.onNodeWithTag("scan_wait_static").assertIsDisplayed()
        // The spinner is truly gone after the swap, not just hidden.
        composeRule.onAllNodesWithTag("scan_wait_progress").assertCountEquals(0)
        // Content survives the regime change.
        composeRule.onNodeWithTag("astra_postcard").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_matching").assertIsDisplayed()
    }

    @Test
    fun instantRegimeRendersStaticTrackAndNoSpinner() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = AstraMotionSpec.Resolved.INSTANT,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        // The drawn postcard and copy are still present; the infinite
        // progress is replaced by a static track.
        composeRule.onNodeWithTag("astra_postcard").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_matching").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_wait_static").assertIsDisplayed()
        composeRule.onAllNodesWithTag("scan_wait_progress").assertCountEquals(0)
    }

    @Test
    fun reducedRegimeRendersStaticTrackAndNoSpinner() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = AstraMotionSpec.Resolved.REDUCED_SHORT,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("scan_wait_static").assertIsDisplayed()
        composeRule.onAllNodesWithTag("scan_wait_progress").assertCountEquals(0)
    }

    @Test
    fun progressIsHiddenFromTalkBackButStillLaidOut() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        // Decorative spinner: laid out and present, but flagged
        // invisible-to-user so TalkBack never announces it twice
        // alongside the status copy.
        composeRule.onNodeWithTag("scan_wait_progress").assertIsDisplayed()
        val spinner = composeRule.onNodeWithTag("scan_wait_progress")
            .fetchSemanticsNode("decorative spinner must exist for the check")
        assertTrue(spinner.config.contains(SemanticsProperties.InvisibleToUser))
    }

    @Test
    fun staticTrackIsHiddenFromTalkBackButStillLaidOut() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = AstraMotionSpec.Resolved.INSTANT,
                    onStopScanning = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("scan_wait_static").assertIsDisplayed()
        val track = composeRule.onNodeWithTag("scan_wait_static")
            .fetchSemanticsNode("static wait track must exist for the check")
        assertTrue(track.config.contains(SemanticsProperties.InvisibleToUser))
    }

    @Test
    fun stopActionFiresRealRecovery() {
        var stops = 0
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = { stops += 1 },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("scan_stop_scanning").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun nonWaitingStateRendersNothingAndDoesNotCrash() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.AwaitingCapture,
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("astra_postcard").assertCountEquals(0)
        composeRule.onAllNodesWithTag("scan_matching").assertCountEquals(0)
        composeRule.onAllNodesWithTag("scan_stop_scanning").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun postcardIsSeventySixPercentCenteredWithSafeBounds() {
        composeRule.setContent {
            HoldTheme {
                ScanWaitingGroup(
                    state = ScanMatchUiState.Matching,
                    motion = AstraMotionSpec.Resolved.FULL,
                    onStopScanning = {},
                    modifier = Modifier.fillMaxSize().testTag("waiting_group"),
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        val parent = composeRule.onNodeWithTag("waiting_group").getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag("astra_postcard").getUnclippedBoundsInRoot()
        val parentWidth = (parent.right - parent.left).value
        val cardWidth = (card.right - card.left).value
        val ratio = cardWidth / parentWidth
        // styles.css `.postcard{width:76%}`: the unrotated layout width is
        // exactly 76%; a rotated bounding box may read up to ~82% (7° tilt).
        assertTrue("postcard must be ~76% of waiting width: ratio=$ratio", ratio in 0.70f..0.86f)
        // Centered: card center matches parent center.
        val parentCx = ((parent.left + parent.right) / 2f).value
        val cardCx = ((card.left + card.right) / 2f).value
        assertTrue("postcard must be centered: parentCx=$parentCx cardCx=$cardCx", abs(cardCx - parentCx) < 8f)
        // Safe bounds: rotated corners + card shadow stay inside the group
        // (the 12% side room absorbs them), with a small measuring slack.
        assertTrue(
            "card left inside group: ${card.left.value} vs ${parent.left.value}",
            card.left.value >= parent.left.value - 16f,
        )
        assertTrue(
            "card right inside group: ${card.right.value} vs ${parent.right.value}",
            card.right.value <= parent.right.value + 16f,
        )
        assertTrue("card top inside group: ${card.top.value}", card.top.value >= parent.top.value - 16f)
    }
}
