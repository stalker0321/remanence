package dev.hryshyn.remanence.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun permanentHandleLanguageAndLogoutAreWorkingControls() {
        var selected = AppLocale.SYSTEM; var loggedOut = false
        compose.setContent { HoldTheme {
            SettingsScreen("permanent", AppLocale.SYSTEM, { selected = it }, true, {}, { loggedOut = true })
        } }
        compose.onNodeWithTag("settings_handle").assertExists()
        compose.onNodeWithTag("language_switch_row").performScrollTo().performClick()
        compose.onNodeWithTag("language_option_russian").performScrollTo().performClick()
        assertEquals(AppLocale.RUSSIAN, selected)
        compose.onNodeWithTag("settings_logout").performScrollTo().performClick()
        assertFalse(loggedOut)
        compose.onNodeWithTag("settings_logout_confirm").performClick()
        compose.waitForIdle()
        assertTrue(loggedOut)
    }

    @Test fun languageRowsAreFullWidthSelectableAndBackRemainsReachable() {
        var selected by mutableStateOf(AppLocale.SYSTEM)
        var back = false
        compose.setContent { HoldTheme {
            SettingsScreen("person", selected, { selected = it }, true, { back = true }, {})
        } }
        for (option in AppLocale.options) {
            compose.onNodeWithTag("language_switch_row").performScrollTo().performClick()
            val node = compose.onNodeWithTag("language_option_" + option.name.lowercase())
            node.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            val groupWidth = compose.onNodeWithTag("language_select_group").fetchSemanticsNode().boundsInRoot.width
            assertEquals(groupWidth, node.fetchSemanticsNode().boundsInRoot.width, 0.1f)
            if (selected == option) node.assertIsSelected() else node.assertIsNotSelected()
            node.performClick()
            assertEquals(option, selected)
        }
        compose.onNodeWithTag("settings_back").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(back)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longHandleAndLargeTextWrapWithoutEllipsisAndLogoutIsReachable() {
        val handle = "a_very_long_unique_handle_".repeat(8)
        var loggedOut = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                HoldTheme { SettingsScreen(handle, AppLocale.UKRAINIAN, {}, true, {}, { loggedOut = true }) }
            }
        }
        val handleNode = compose.onNodeWithTag("settings_handle").performScrollTo()
            .assertTextEquals("\u2068@" + handle + "\u2069")
        val layouts = mutableListOf<TextLayoutResult>()
        handleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("long handle must wrap", layouts.single().lineCount > 1)
        assertFalse("handle text must not clip", layouts.single().hasVisualOverflow)
        for (line in 0 until layouts.single().lineCount) assertFalse(layouts.single().isLineEllipsized(line))
        compose.onNodeWithTag("language_switch_row").performScrollTo().performClick()
        compose.onNodeWithTag("language_option_ukrainian")
            .assertIsDisplayed().assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("language_dialog_close").performClick()
        compose.onNodeWithTag("settings_access_row").performScrollTo().performClick()
        compose.onNodeWithTag("settings_recovery_status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_logout").performScrollTo().assertIsDisplayed().performClick()
        assertFalse(loggedOut)
        compose.onNodeWithTag("settings_logout_confirm").performClick()
        compose.waitForIdle()
        assertTrue(loggedOut)
    }

    @Test fun accessStatusIsInformationAndReadyFlagChangesOnlyItsCopy() {
        var ready by mutableStateOf(false)
        compose.setContent { HoldTheme {
            SettingsScreen("person", AppLocale.SYSTEM, {}, ready, {}, {})
        } }
        compose.onNodeWithTag("settings_recovery_status").assertDoesNotExist()
        compose.onNodeWithTag("settings_access_row").performScrollTo().performClick()
        val status = compose.onNodeWithTag("settings_recovery_status").performScrollTo()
        val before = status.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        status.assert(hasClickAction().not())
        compose.runOnIdle { ready = true }
        val after = status.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertNotEquals(before, after)
        status.assert(hasClickAction().not())
        compose.onNodeWithTag("settings_handle_permanent").assertDoesNotExist()
        compose.onNodeWithTag("settings_handle").assertExists()
    }

    @Test fun cancellingLogoutLeavesSessionAndSettingsOpen() {
        var logout = 0
        compose.setContent { HoldTheme { SettingsScreen("person", AppLocale.SYSTEM, {}, true, {}, { logout++ }) } }
        compose.onNodeWithTag("settings_logout").performScrollTo().performClick()
        compose.onNodeWithTag("settings_logout_cancel").performClick()
        assertEquals(0, logout)
        compose.onNodeWithTag("settings_logout_confirm").assertDoesNotExist()
        compose.onNodeWithTag("settings_screen").assertExists()
    }

    @Test fun settingsLabelsHaveEnglishRussianUkrainianParity() {
        val files = listOf("values", "values-ru", "values-uk")
        val values = files.map { locale ->
            val path = moduleFile("src/main/res/" + locale + "/settings_design_strings.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path)
                .getElementsByTagName("string")
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index) as org.w3c.dom.Element
                node.getAttribute("name") to node.textContent
            }
        }
        assertTrue(values.first().isNotEmpty())
        values.forEach { assertEquals(values.first().keys, it.keys); assertTrue(it.values.all(String::isNotBlank)) }
    }

    @Test fun homeAuditLabelsHaveEnglishRussianUkrainianParity() {
        val values = listOf("values", "values-ru", "values-uk").map { locale ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(moduleFile("src/main/res/$locale/home_audit_strings.xml")).getElementsByTagName("string")
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index) as org.w3c.dom.Element
                node.getAttribute("name") to node.textContent
            }
        }
        assertEquals(4, values.first().size)
        values.forEach { assertEquals(values.first().keys, it.keys); assertTrue(it.values.all(String::isNotBlank)) }
    }

    private fun moduleFile(relative: String): File {
        var directory: File? = File("").absoluteFile
        repeat(6) {
            directory?.let { base ->
                for (prefix in listOf("", "app/", "android/app/")) {
                    val candidate = File(base, prefix + relative)
                    if (candidate.exists()) return candidate
                }
            }
            directory = directory?.parentFile
        }
        error("Missing module file " + relative)
    }
}
