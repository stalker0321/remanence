package dev.hryshyn.remanence.ui.locale

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose contract for the discoverable language switch: all four entries
 * render with self-name labels under a group caption, taps report the
 * matching [AppLocale], options expose radio-button selected semantics in one
 * selectable group for TalkBack, and no new destination is introduced.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LanguageSwitchRowTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allOptionsRenderWithSelfNameLabelsUnderGroupCaption() {
        composeRule.setContent {
            HoldTheme { LanguageSwitchRow(current = AppLocale.SYSTEM, onSelect = {}) }
        }
        composeRule.onNodeWithTag("language_switch_row").assertIsDisplayed()
        composeRule.onNodeWithTag("language_group_label").assertIsDisplayed()
        composeRule.onNodeWithText("language").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_system").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_english").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_russian").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_ukrainian").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithText("Русский").assertIsDisplayed()
        composeRule.onNodeWithText("Українська").assertIsDisplayed()
    }

    @Test
    fun optionsFormOneSelectableGroupOfRadioButtons() {
        composeRule.setContent {
            HoldTheme { LanguageSwitchRow(current = AppLocale.SYSTEM, onSelect = {}) }
        }
        composeRule.onNodeWithTag("language_select_group")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        for (option in listOf("system", "english", "russian", "ukrainian")) {
            composeRule.onNodeWithTag("language_option_$option")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
    }

    @Test
    fun selectedStateTracksCurrentLocaleForTalkBack() {
        composeRule.setContent {
            HoldTheme { LanguageSwitchRow(current = AppLocale.RUSSIAN, onSelect = {}) }
        }
        composeRule.onNodeWithTag("language_option_russian")
            .assertIsSelected()
        composeRule.onNodeWithTag("language_option_system")
            .assertIsNotSelected()
        composeRule.onNodeWithTag("language_option_english")
            .assertIsNotSelected()
        composeRule.onNodeWithTag("language_option_ukrainian")
            .assertIsNotSelected()
    }

    @Test
    fun optionsAnnounceSelfNameLabels() {
        composeRule.setContent {
            HoldTheme { LanguageSwitchRow(current = AppLocale.SYSTEM, onSelect = {}) }
        }
        composeRule.onNodeWithTag("language_option_english")
            .assertTextContains("English")
        composeRule.onNodeWithTag("language_option_russian")
            .assertTextContains("Русский")
        composeRule.onNodeWithTag("language_option_ukrainian")
            .assertTextContains("Українська")
    }

    @Test
    fun tappingRussianReportsRussian() {
        val selected = mutableListOf<AppLocale>()
        composeRule.setContent {
            HoldTheme { LanguageSwitchRow(current = AppLocale.SYSTEM, onSelect = selected::add) }
        }
        composeRule.onNodeWithTag("language_option_russian").performClick()
        assertEquals(listOf(AppLocale.RUSSIAN), selected)
    }

    @Test
    fun tappingEnglishUkrainianAndSystemReportsEach() {
        val selected = mutableListOf<AppLocale>()
        composeRule.setContent {
            HoldTheme { LanguageSwitchRow(current = AppLocale.ENGLISH, onSelect = selected::add) }
        }
        composeRule.onNodeWithTag("language_option_ukrainian").performClick()
        composeRule.onNodeWithTag("language_option_english").performClick()
        composeRule.onNodeWithTag("language_option_system").performClick()
        assertEquals(
            listOf(AppLocale.UKRAINIAN, AppLocale.ENGLISH, AppLocale.SYSTEM),
            selected,
        )
    }
}
