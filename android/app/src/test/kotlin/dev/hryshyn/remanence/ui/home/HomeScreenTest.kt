package dev.hryshyn.remanence.ui.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.assertEquals
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
        assertEquals(1, opens)
        assertEquals(1, makes)
    }

    @Test fun ordinaryCapabilityGateStillDisablesUnprovenEntry() {
        composeRule.setContent { HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE) } }
        composeRule.onNodeWithTag("scan_action").assertIsNotEnabled()
        composeRule.onNodeWithTag("create_action").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun narrowShortHomeKeepsBothActionsReachable() {
        composeRule.setContent {
            HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) }
        }
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed()
        composeRule.onNodeWithTag("create_action").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi", fontScale = 1.5f)
    fun largeTypeHomeScrollsInsteadOfShrinkingCopy() {
        composeRule.setContent {
            HoldTheme { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) }
        }
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed()
        composeRule.onNodeWithTag("create_action").performScrollTo().assertIsDisplayed()
    }
}
