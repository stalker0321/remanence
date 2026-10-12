package dev.hryshyn.remanence.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.hold.HoldFlowHeader
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shared frozen Create/Scan back/close controls retain
 * localized accessible names in EN, RU and UK.
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
        exitLabel = "close",
        titleLabel = "back",
    )

    @Test
    fun englishScanFlowHeaderKeepsTheAcceptedLabels() = assertHeader(
        destination = AppDestination.Scan,
        testKey = "scan",
        exitLabel = "close",
        titleLabel = "back",
    )

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianCreateFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Create,
        testKey = "create",
        exitLabel = "закрыть",
        titleLabel = "назад",
    )

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianScanFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Scan,
        testKey = "scan",
        exitLabel = "закрыть",
        titleLabel = "назад",
    )

    @Test
    @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun ukrainianCreateFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Create,
        testKey = "create",
        exitLabel = "закрити",
        titleLabel = "назад",
    )

    @Test
    @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun ukrainianScanFlowHeaderRendersTranslatedLabels() = assertHeader(
        destination = AppDestination.Scan,
        testKey = "scan",
        exitLabel = "закрити",
        titleLabel = "назад",
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
                    createContent = { HoldFlowHeader(onBack = {}, onClose = {}) },
                    scanContent = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flow_exit_$testKey").assertIsDisplayed()
        composeRule.onNodeWithTag("flow_exit_$testKey").assertContentDescriptionEquals(exitLabel)
        composeRule.onNodeWithTag("flow_back_$testKey").assertIsDisplayed().assertContentDescriptionEquals(titleLabel)
        composeRule.onNodeWithTag("flow_title_$testKey").assertDoesNotExist()
    }
}
