package dev.hryshyn.remanence.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Signed-out public home shows ONE compact localized Language trigger
 * opening the language menu — never the full option row. Tag-based,
 * locale-independent (self-names are stable; the trigger line is
 * asserted in the test-default English locale). NOT run in this tick
 * (no Gradle execution per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeLanguageMenuTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun mountPublicHome(
        current: AppLocale,
        onSelect: (AppLocale) -> Unit,
    ) {
        composeRule.setContent {
            HoldTheme {
                HomeScreen(
                    state = BackendHealthUiState.AVAILABLE,
                    publicEntry = true,
                    appLocale = current,
                    onLocaleSelected = onSelect,
                    showLanguageSwitch = true,
                )
            }
        }
    }

    @Test
    fun publicHomeShowsCompactTriggerNotFullRow() {
        mountPublicHome(AppLocale.SYSTEM) {}

        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        composeRule.onNodeWithTag("language_switch_row").assertDoesNotExist()
        composeRule.onNodeWithTag("language_select_group").assertDoesNotExist()
        // Menu options exist only once the menu opens.
        composeRule.onNodeWithTag("language_option_russian").assertDoesNotExist()
    }

    @Test
    fun menuOpensSelectsAndDismisses() {
        var selected: AppLocale? = null
        var current by mutableStateOf(AppLocale.SYSTEM)
        composeRule.setContent {
            HoldTheme {
                HomeScreen(
                    state = BackendHealthUiState.AVAILABLE,
                    publicEntry = true,
                    appLocale = current,
                    onLocaleSelected = {
                        selected = it
                        current = it
                    },
                    showLanguageSwitch = true,
                )
            }
        }

        composeRule.onNodeWithTag("access_language").performClick()
        composeRule.onNodeWithTag("language_option_system").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_english").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_russian").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_ukrainian").assertIsDisplayed()

        composeRule.onNodeWithTag("language_option_russian").performClick()
        assertEquals(AppLocale.RUSSIAN, selected)
        // Selecting dismisses the menu; the trigger now names the choice
        // (test locale is English, so the caption stays English).
        composeRule.onNodeWithTag("language_option_english").assertDoesNotExist()
        composeRule.onNodeWithTag("access_language").assertTextEquals("language")
    }

    @Test
    fun openMenuMarksCurrentOptionSelected() {
        mountPublicHome(AppLocale.RUSSIAN) {}

        composeRule.onNodeWithTag("access_language").performClick()
        // Selected menu-item semantics (radio + selected) ride on the
        // option itself; only the current choice carries them.
        composeRule.onNodeWithTag("language_option_russian").assertIsSelected()
        composeRule.onNodeWithTag("language_option_english").assertIsDisplayed()
    }

    @Test
    fun reselectingCurrentReportsWithoutStateChange() {
        var calls = 0
        mountPublicHome(AppLocale.RUSSIAN) { calls++ }

        composeRule.onNodeWithTag("access_language").performClick()
        composeRule.onNodeWithTag("language_option_russian").performClick()

        // The menu itself only reports taps; the production funnel turns
        // the re-tap into a real no-op (no AppCompat write, covered by
        // the MainActivity locale tests).
        assertEquals(1, calls)
        composeRule.onNodeWithTag("language_option_russian").assertDoesNotExist()
    }

    @Test
    fun repeatedSwitchesResolveLastWins() {
        val selected = mutableListOf<AppLocale>()
        var current by mutableStateOf(AppLocale.SYSTEM)
        composeRule.setContent {
            HoldTheme {
                HomeScreen(
                    state = BackendHealthUiState.AVAILABLE,
                    publicEntry = true,
                    appLocale = current,
                    onLocaleSelected = {
                        selected += it
                        current = it
                    },
                    showLanguageSwitch = true,
                )
            }
        }

        for (option in listOf(AppLocale.ENGLISH, AppLocale.UKRAINIAN, AppLocale.RUSSIAN)) {
            composeRule.onNodeWithTag("access_language").performClick()
            composeRule.onNodeWithTag("language_option_${option.name.lowercase()}").performClick()
        }

        assertEquals(listOf(AppLocale.ENGLISH, AppLocale.UKRAINIAN, AppLocale.RUSSIAN), selected)
        composeRule.onNodeWithTag("access_language").assertTextEquals("language")
    }
}
