package dev.hryshyn.remanence.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.session.RootScreen
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.scan.AstraPostcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Opening Carry composition: frame-0 presence without waits, ghost lifecycle,
 * regime policy, accessibility hiding, decorative front content, one-shot
 * bounds and the Scan→Capsule handoff through [RootScreen]. Tag-based and
 * locale-independent, same harness as `AstraStepArrivalTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AstraOpeningCarryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val front = OpeningRect(36f, 120f, 180f, 312f)

    private fun opening(
        motion: AstraMotionSpec.Resolved,
        frontRect: OpeningRect? = front,
    ) {
        // Virtual clock: the opening must be observable
        // mid-flight (auto-advance would run it to completion during idle).
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    AstraOpeningCarry(
                        motion = motion,
                        front = frontRect,
                        backdropColor = Color.Gray,
                        frontContent = {
                            Text("front probe", modifier = Modifier.testTag("front_probe"))
                        },
                    ) {
                        Text("capsule probe", modifier = Modifier.testTag("capsule_probe"))
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun capsuleIsPresentAtFrameZeroUnderFullMotion() {
        opening(AstraMotionSpec.Resolved.FULL)
        // No advanceTimeBy before the first assertion: nothing may gate the
        // capsule on a wait.
        composeRule.onNodeWithTag("capsule_probe").assertIsDisplayed()
    }

    @Test
    fun ghostsExistDuringOpeningAndAreGoneWhenItEnds() {
        opening(AstraMotionSpec.Resolved.FULL)
        composeRule.onNodeWithTag("astra_opening_bridge").assertIsDisplayed()
        composeRule.onNodeWithTag("astra_opening_front").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(AstraMotionSpec.OPENING_MS.toLong() + 32L)
        composeRule.waitForIdle()

        // Synchronous removal at t = d: no leftover card after close.
        composeRule.onAllNodesWithTag("astra_opening_bridge").assertCountEquals(0)
        composeRule.onAllNodesWithTag("astra_opening_front").assertCountEquals(0)
        composeRule.onNodeWithTag("capsule_probe").assertIsDisplayed()
    }

    @Test
    fun reducedRegimeRendersNoGhosts() {
        opening(AstraMotionSpec.Resolved.REDUCED_SHORT)
        composeRule.onAllNodesWithTag("astra_opening_bridge").assertCountEquals(0)
        composeRule.onAllNodesWithTag("astra_opening_front").assertCountEquals(0)
        composeRule.onNodeWithTag("capsule_probe").assertIsDisplayed()
    }

    @Test
    fun instantRegimeSnapsWithoutGhosts() {
        // Clock untouched: INSTANT lands on the end state with no animation.
        opening(AstraMotionSpec.Resolved.INSTANT)
        composeRule.onAllNodesWithTag("astra_opening_bridge").assertCountEquals(0)
        composeRule.onAllNodesWithTag("astra_opening_front").assertCountEquals(0)
        composeRule.onNodeWithTag("capsule_probe").assertIsDisplayed()
    }

    @Test
    fun ghostsAreInvisibleToAccessibilityServices() {
        opening(AstraMotionSpec.Resolved.FULL)
        val bridge = composeRule.onNodeWithTag("astra_opening_bridge").fetchSemanticsNode()
        assertTrue(bridge.config.contains(SemanticsProperties.InvisibleToUser))
        val frontNode = composeRule.onNodeWithTag("astra_opening_front").fetchSemanticsNode()
        assertTrue(frontNode.config.contains(SemanticsProperties.InvisibleToUser))
    }

    @Test
    fun ghostSubtreeContainsNoCapsulePage() {
        // Frozen clock: assert against the mid-flight ghost, not the settled end.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    AstraOpeningCarry(
                        motion = AstraMotionSpec.Resolved.FULL,
                        front = front,
                        backdropColor = Color.Gray,
                        frontContent = { AstraPostcard() },
                    ) {
                        Text("capsule probe", modifier = Modifier.testTag("capsule_probe"))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The ghost keeps its own tag (set inside clearAndSetSemantics) while
        // the nested decorative postcard description/role is cleared from the
        // merged tree: only the capsule surface stays exposed.
        composeRule.onAllNodesWithTag("astra_opening_front").assertCountEquals(1)
        composeRule.onAllNodesWithTag("astra_postcard").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("graphic postcard front").assertCountEquals(0)
        // Concrete capsule-page tags from CapsuleScreen: none may appear in
        // the decorative front subtree.
        for (tag in listOf(
            "capsule_page_0",
            "capsule_page_error",
            "capsule_page_retry",
            "capsule_page_loading",
            "capsule_page_indicator",
        )) {
            composeRule.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    @Test
    fun rootGeometryIsSharedWhenTheCarryParentHasANonZeroOrigin() {
        // Deliberately non-zero parent origin (a safeDrawing/IME-like inset):
        // the published postcard rect is root-space, so the ghosts and the
        // capsule transform must share one root `a`/`b` pair. The old
        // local-capsule implementation shifts both by the parent origin.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(420.dp, 700.dp)) {
                    Box(
                        Modifier
                            .padding(start = 20.dp, top = 30.dp)
                            .size(360.dp, 640.dp),
                    ) {
                        AstraOpeningCarry(
                            motion = AstraMotionSpec.Resolved.FULL,
                            front = front,
                            backdropColor = Color.Gray,
                            frontContent = {
                                Text("front probe", modifier = Modifier.testTag("front_probe"))
                            },
                        ) {
                            Box(Modifier.fillMaxSize().testTag("capsule_probe"))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The carry measures its own root box during layout; with the clock
        // frozen, one frame pumps that measurement's recomposition (same
        // pattern as frontBoundsAreConsumedOnceAndClearedOnLeave). The dissolve
        // easing has zero initial slope, so the one-frame ghost drift is
        // sub-pixel; the travel drift is bounded below.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        // Placed in root space: the front ghost coincides with the published
        // postcard rect, not shifted by the carry box's origin.
        val ghost = composeRule.onNodeWithTag("astra_opening_front")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(front.left, ghost.left, 0.5f)
        assertEquals(front.top, ghost.top, 0.5f)
        assertEquals(front.right, ghost.right, 0.5f)
        assertEquals(front.bottom, ghost.bottom, 0.5f)

        // The same root `a`/`b` pair drives translation: the capsule center
        // starts on the postcard center (within one animation frame).
        val capsule = composeRule.onNodeWithTag("capsule_probe")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(front.centerX, capsule.center.x, 12f)
        assertEquals(front.centerY, capsule.center.y, 12f)
    }

    @Test
    fun frontBoundsAreConsumedOnceAndClearedOnLeave() {
        val holder = AstraCarryFront()
        val rect = OpeningRect(36f, 120f, 180f, 312f)
        holder.publish(rect)
        val secondPass = mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    // First composition consumes the published bounds; the
                    // flagged recomposition reads an already-cleared holder.
                    val consumed = remember(secondPass.value) {
                        holder.consumeIf(true)
                    }
                    Text(
                        if (!secondPass.value) {
                            if (consumed != null) "opening" else "fallback"
                        } else {
                            if (consumed != null) "opening2" else "fallback_alt"
                        },
                        modifier = Modifier.testTag("carry_probe"),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("carry_probe").assertIsDisplayed()
        composeRule.runOnIdle { secondPass.value = true }
        // Frozen clock does not pump the write's recomposition on idle;
        // step exactly one frame so the flagged read re-executes mid-flight.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("carry_probe").assertIsDisplayed()
    }

    @Test
    fun scanToCapsuleHandoffUsesOpeningFallbackElsewhere() {
        val destination = mutableStateOf<AppDestination>(AppDestination.Scan)
        // Frozen clock so the opening is observable mid-flight.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                // Fixed size: the Scan slot is weight(1f)-based and needs a
                // bounded parent to lay the postcard out at a real size.
                Box(Modifier.size(360.dp, 640.dp)) {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = destination.value,
                    authenticationContent = {},
                    homeContent = {},
                    scanContent = { AstraPostcard() },
                    capsuleContent = { grantId ->
                        Text(grantId, modifier = Modifier.testTag("capsule_probe"))
                    },
                )
                }
            }
        }
        composeRule.waitForIdle()

        // Scan → Capsule: the published postcard bounds open the carry.
        // Existence proves the handoff (publish → consume → ghost); ghost
        // pixel visibility is pinned by ghostsExistDuringOpeningAndAreGoneWhenItEnds.
        // Frozen clock does not pump the write's recomposition on idle, so
        // step exactly one frame instead of a blind wait.
        composeRule.runOnIdle { destination.value = AppDestination.Capsule("grant-1") }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("astra_opening_front").assertCountEquals(1)
        composeRule.onNodeWithTag("capsule_probe").assertIsDisplayed()
    }

    @Test
    fun capsuleOnlyEntryFallsBackWithoutGhosts() {
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = AppDestination.Capsule("grant-1"),
                    authenticationContent = {},
                    homeContent = {},
                    capsuleContent = { grantId ->
                        Text(grantId, modifier = Modifier.testTag("capsule_probe"))
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // No published bounds exist: plain boundary arrival, no ghosts.
        composeRule.onAllNodesWithTag("astra_opening_bridge").assertCountEquals(0)
        composeRule.onAllNodesWithTag("astra_opening_front").assertCountEquals(0)
        composeRule.onNodeWithTag("capsule_probe").assertIsDisplayed()
    }



}
