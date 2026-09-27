package dev.hryshyn.remanence.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T013 flow-header localization: THE production RootScreen chrome renders the
 * three header labels from `hold_*` resources in EN, RU and UK, keeps the
 * existing `flow_exit_*` / `flow_title_*` hooks, and changes no layout,
 * navigation or motion — only which locale the same labels resolve to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RootFlowHeaderLocalizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun englishCreateFlowHeaderKeepsTheAcceptedLabels() = assertHeader(
        destination = AppDestination.Create,
        testKey = "create",
        exitLabel = "Back to Home",
        titleLabel = "make a remanence",
    )

    @Test
    fun englishScanFlowHeaderKeepsTheAcceptedLabels() = assertHeader(
        destination = AppDestination.Scan,
        testKey = "scan",
        exitLabel = "Back to Home",
        titleLabel = "scan to open",
    )

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianCreateFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Create,
        testKey = "create",
        exitLabel = "Назад на главную",
        titleLabel = "создать remanence",
    )

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianScanFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Scan,
        testKey = "scan",
        exitLabel = "Назад на главную",
        titleLabel = "отсканировать и открыть",
    )

    @Test
    @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun ukrainianCreateFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Create,
        testKey = "create",
        exitLabel = "Назад на головну",
        titleLabel = "створити remanence",
    )

    @Test
    @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun ukrainianScanFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Scan,
        testKey = "scan",
        exitLabel = "Назад на головну",
        titleLabel = "сканувати й відкрити",
    )

    private fun assertHeader(
        destination: AppDestination,
        testKey: String,
        exitLabel: String,
        titleLabel: String,
    ) {
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = destination,
                    authenticationContent = {},
                    homeContent = {},
                    createContent = {},
                    scanContent = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flow_exit_$testKey").assertIsDisplayed()
        composeRule.onNodeWithTag("flow_title_$testKey").assertIsDisplayed()
        composeRule.onNodeWithText(exitLabel).assertIsDisplayed()
        composeRule.onNodeWithText(titleLabel).assertIsDisplayed()
    }
}
