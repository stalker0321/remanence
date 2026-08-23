package app.postmark.memory.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeSemanticsAreWired() {
        composeRule.setContent {
            MaterialTheme {
                HomeScreen(BackendHealthUiState.CHECKING)
            }
        }

        composeRule.onNodeWithTag("home_build_label")
            .assertTextEquals("Architecture approved · API checking")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("create_action")
            .assertTextEquals("Create")
            .assertIsDisplayed()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag("scan_action")
            .assertTextEquals("Scan")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun availableStateShowsAvailableBuildLabel() {
        composeRule.setContent {
            MaterialTheme {
                HomeScreen(BackendHealthUiState.AVAILABLE)
            }
        }

        composeRule.onNodeWithTag("home_build_label")
            .assertTextEquals("Architecture approved · API available")
            .assertIsDisplayed()
    }

    @Test
    fun unavailableStateShowsUnavailableBuildLabel() {
        composeRule.setContent {
            MaterialTheme {
                HomeScreen(BackendHealthUiState.UNAVAILABLE)
            }
        }

        composeRule.onNodeWithTag("home_build_label")
            .assertTextEquals("Architecture approved · API unavailable")
            .assertIsDisplayed()
    }
}
