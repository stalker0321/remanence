package dev.hryshyn.remanence.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun permanentHandleLanguageAndLogoutAreWorkingControls() {
        var selected = AppLocale.SYSTEM; var loggedOut = false
        compose.setContent { HoldTheme {
            SettingsScreen("permanent", AppLocale.SYSTEM, { selected = it }, true, {}, { loggedOut = true })
        } }
        compose.onNodeWithTag("settings_handle").assertExists()
        compose.onNodeWithTag("language_option_russian").performClick()
        assertEquals(AppLocale.RUSSIAN, selected)
        compose.onNodeWithTag("settings_logout").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(loggedOut)
    }
}
