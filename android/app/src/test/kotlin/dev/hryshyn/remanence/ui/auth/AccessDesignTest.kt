package dev.hryshyn.remanence.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.recovery.RecoveryPanel
import dev.hryshyn.remanence.ui.hold.*
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.settings.SettingsScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native-rendered UI fixtures, not Google/provider or real-device acceptance. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessDesignTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun entryUsesGoogleAndIndependentPasskeyActions() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage(onBack = {}, footer = { AccessLanguageControl(AppLocale.SYSTEM, {}) }) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    AccessHeading("let’s get you in", "Sign in to leave a memory for someone.")
                    GoogleSignInButton({})
                    panel()
                    HoldTextButton({}) { androidx.compose.material3.Text("Other sign-in options") }
                }
            }
        } } }
        rule.onNodeWithTag("access_google").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("recovery_restore").assertIsDisplayed().assertIsEnabled()
        capture("access-entry-en")
    }

    @Test fun handleOnlyContinuesWhenValidAndNeverWhileWorking() {
        var calls = 0
        var handle by mutableStateOf("")
        var busy by mutableStateOf(false)
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage { HandleSetupContent(handle, {}, busy, { calls++ }) }
        } } }
        rule.onNodeWithTag("access_handle_continue").assertIsNotEnabled()
        rule.runOnIdle { handle = "vodkolyan" }
        rule.onNodeWithTag("access_handle_continue").assertIsEnabled()
        rule.runOnIdle { busy = true }
        rule.onNodeWithTag("access_handle_continue").assertIsNotEnabled()
        assertEquals(0, calls)
    }

    @Test fun handleFormHasPinnedActionAndFlatField() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage(onBack = {}) { HandleSetupContent("vodkolyan", {}, false, {}) }
        } } }
        rule.onNodeWithTag("access_handle_input").assertIsDisplayed()
        rule.onNodeWithTag("access_handle_continue").assertIsDisplayed().assertIsEnabled()
        capture("access-handle-en")
    }

    @Test fun setupCannotContinueBeforeVerification() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage { panel(identity = true, mandatory = true) }
        } } }
        rule.onNodeWithTag("recovery_save").assertIsDisplayed()
        rule.onNodeWithTag("recovery_continue").assertDoesNotExist()
        rule.onNodeWithTag("recovery_show_code").assertDoesNotExist()
        capture("access-passkey-setup-en")
    }

    @Test fun verifiedSetupOffersContinueAndOptionalCode() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage { panel(identity = true, mandatory = true, verified = true, codeAvailable = true) }
        } } }
        rule.onNodeWithTag("recovery_continue").assertIsEnabled()
        rule.onNodeWithTag("recovery_save").assertDoesNotExist()
        rule.onNodeWithTag("recovery_show_code").assertIsDisplayed()
        capture("access-passkey-verified-en")
    }

    @Test fun manualRecoveryIsCollapsedMaskedAndLengthGated() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage { panel(required = true) }
        } } }
        rule.onNodeWithTag("recovery_code_input").assertDoesNotExist()
        rule.onNodeWithTag("recovery_code_option").performClick()
        rule.onNodeWithTag("recovery_code_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        rule.onNodeWithTag("recovery_code_restore").assertIsNotEnabled()
        capture("access-recovery-en")
    }

    @Test fun busyDisablesAllCredentialActions() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage { panel(identity = true, busy = true, other = true) }
        } } }
        rule.onNodeWithTag("recovery_save").assertIsNotEnabled()
        rule.onNodeWithTag("recovery_other_manager").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "ru-w320dp-h640dp-xhdpi")
    fun narrowLargeTextKeepsHandleActionReachable() {
        rule.setContent { HoldTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Surface(Modifier.fillMaxSize()) {
                    AccessPage(onBack = {}, footer = { AccessLanguageControl(AppLocale.RUSSIAN, {}) }) {
                        HandleSetupContent("vodkolyan", {}, false, {})
                    }
                }
            }
        } }
        rule.onNodeWithTag("access_handle_continue").assertIsDisplayed()
        rule.onNodeWithTag("access_language").assertIsDisplayed()
        capture("access-handle-ru-large")
    }

    @Test fun languageControlExposesAllFourChoices() {
        var selected = AppLocale.SYSTEM
        rule.setContent { HoldTheme { AccessLanguageControl(AppLocale.SYSTEM, { selected = it }) } }
        rule.onNodeWithTag("access_language").performClick()
        AppLocale.options.forEach { rule.onNodeWithTag("language_option_${it.name.lowercase()}").assertIsDisplayed() }
        rule.onNodeWithTag("language_option_ukrainian").performClick()
        assertEquals(AppLocale.UKRAINIAN, selected)
    }

    @Test fun settingsVisualFixture() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            SettingsScreen("vodkolyan", AppLocale.SYSTEM, {}, true, {}, {})
        } } }
        capture("access-settings-en")
    }

    @Composable
    private fun panel(identity: Boolean = false, required: Boolean = false, mandatory: Boolean = false,
                      verified: Boolean = false, codeAvailable: Boolean = false, busy: Boolean = false, other: Boolean = false) {
        RecoveryPanel(identity, required, mandatory, true, busy, verified, other, 0, codeAvailable, false, "",
            {}, {}, {}, {}, {}, {}, {}, {})
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = File("build/hold-review/$name.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
