package dev.hryshyn.remanence.ui.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun publicHomeActionsChooseIntentEvenWhenHealthIsUnavailable() {
        var opens = 0
        var makes = 0
        composeRule.setContent {
            HoldTheme { HomeScreen(BackendHealthUiState.UNAVAILABLE, publicEntry = true,
                onCreate = { makes++ }, onScan = { opens++ }) }
        }
        composeRule.onNodeWithTag("home_build_label").assertDoesNotExist()
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("create_action").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { opens == 1 && makes == 1 }
        assertEquals(1, opens)
        assertEquals(1, makes)
    }

    @Test fun ordinaryCapabilityGateStillDisablesUnprovenEntry() {
        composeRule.setContent { HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE) } }
        composeRule.onNodeWithTag("scan_action").assertIsNotEnabled()
        composeRule.onNodeWithTag("create_action").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun homeActionsFillProportionallyWithMinimaAsFloors() {
        composeRule.setContent {
            HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) }
        }
        val scanBounds = composeRule.onNodeWithTag("scan_action").getUnclippedBoundsInRoot()
        val createBounds = composeRule.onNodeWithTag("create_action").getUnclippedBoundsInRoot()
        val scanHeight = (scanBounds.bottom - scanBounds.top).value
        val createHeight = (createBounds.bottom - createBounds.top).value
        // Astra floors: 245 / 222.
        assertTrue("scan card must reach Astra's 245dp minimum: $scanHeight", scanHeight >= 245f)
        assertTrue("create card must reach Astra's 222dp minimum: $createHeight", createHeight >= 222f)
        // Astra flex 1.25 vs .83: the scan intent takes the larger share of
        // leftover space — top-led, never bottom-tiny, never greedy.
        val diff = scanHeight - createHeight
        assertTrue("scan must lead create per 1.25/.83 flex: diff=$diff", diff > 0f)
        assertTrue("neither card may greedily absorb the viewport: diff=$diff", diff < 120f)
        // No dead blank band: the language row sits at the bottom padding.
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val langBounds = composeRule.onNodeWithTag("language_switch_row").getUnclippedBoundsInRoot()
        val deadBand = (rootBounds.bottom - langBounds.bottom).value
        assertTrue("no dead band may sit under the language row: $deadBand", deadBand < 48f)
    }

    @Test
    @Config(qualifiers = "w390dp-h1000dp-xhdpi")
    fun tallHomeKeepsProportionalFillWithoutDeadBand() {
        composeRule.setContent {
            HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) }
        }
        val scanBounds = composeRule.onNodeWithTag("scan_action").getUnclippedBoundsInRoot()
        val createBounds = composeRule.onNodeWithTag("create_action").getUnclippedBoundsInRoot()
        val scanHeight = (scanBounds.bottom - scanBounds.top).value
        val createHeight = (createBounds.bottom - createBounds.top).value
        // Both cards grow past their floors on a tall viewport.
        assertTrue("tall scan card must grow past its 245dp floor: $scanHeight", scanHeight > 300f)
        assertTrue("tall create card must grow past its 222dp floor: $createHeight", createHeight > 260f)
        // Leftover splits ~1.25/.83 ≈ 1.5 between the extras above the floors.
        val extraRatio = (scanHeight - 245f) / (createHeight - 222f)
        assertTrue("leftover must split ~1.25/.83: ratio=$extraRatio", extraRatio in 1.0f..2.0f)
        // Language row still pinned to the bottom padding.
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val langBounds = composeRule.onNodeWithTag("language_switch_row").getUnclippedBoundsInRoot()
        val deadBand = (rootBounds.bottom - langBounds.bottom).value
        assertTrue("no dead band may sit under the language row on tall screens: $deadBand", deadBand < 48f)
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun narrowShortHomeKeepsBothActionsReachable() {
        composeRule.setContent {
            HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) }
        }
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed()
        composeRule.onNodeWithTag("create_action").performScrollTo().assertIsDisplayed()
        // Narrow floors 253 / 237 still hold in scroll mode; large content
        // grows the object itself and must not spill into the next action.
        val scanBounds = composeRule.onNodeWithTag("scan_action").getUnclippedBoundsInRoot()
        val createBounds = composeRule.onNodeWithTag("create_action").getUnclippedBoundsInRoot()
        val scanHeight = (scanBounds.bottom - scanBounds.top).value
        val createHeight = (createBounds.bottom - createBounds.top).value
        assertTrue("narrow scan card must reach Astra's 253dp minimum: $scanHeight", scanHeight >= 253f)
        assertTrue("narrow create card must reach Astra's 237dp minimum: $createHeight", createHeight >= 237f)
        assertTrue(
            "create must not spill into scan: scanBottom=${scanBounds.bottom.value} createTop=${createBounds.top.value}",
            createBounds.top.value >= scanBounds.bottom.value - 1f,
        )
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi", fontScale = 1.5f)
    fun largeTypeHomeScrollsInsteadOfShrinkingCopy() {
        composeRule.setContent {
            HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) }
        }
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed()
        composeRule.onNodeWithTag("create_action").performScrollTo().assertIsDisplayed()
        // Large type grows the objects past their floors without overlap.
        val scanBounds = composeRule.onNodeWithTag("scan_action").getUnclippedBoundsInRoot()
        val createBounds = composeRule.onNodeWithTag("create_action").getUnclippedBoundsInRoot()
        val scanHeight = (scanBounds.bottom - scanBounds.top).value
        val createHeight = (createBounds.bottom - createBounds.top).value
        assertTrue("large-type scan card must reach Astra's 245dp minimum: $scanHeight", scanHeight >= 245f)
        assertTrue("large-type create card must reach Astra's 222dp minimum: $createHeight", createHeight >= 222f)
        assertTrue(
            "create must not spill into scan at large type: scanBottom=${scanBounds.bottom.value} createTop=${createBounds.top.value}",
            createBounds.top.value >= scanBounds.bottom.value - 1f,
        )
    }
}
