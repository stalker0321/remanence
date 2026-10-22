package dev.hryshyn.remanence.auth

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.auth.AccessPage
import dev.hryshyn.remanence.ui.auth.AccessLanguageControl
import dev.hryshyn.remanence.ui.history.HistoryScreen
import dev.hryshyn.remanence.ui.history.HistoryUiState
import dev.hryshyn.remanence.ui.hold.*
import dev.hryshyn.remanence.ui.home.*
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.settings.SettingsScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Real Compose screens; synthetic states/callbacks only, no provider/network/account operations. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductReworkJourneyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var phase by mutableStateOf(AtomicPhase.IDLE)
    private var handle by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var creates = 0
    private var verifies = 0

    @Test fun englishOnboardingStagesAreDistinct() = onboarding("en", 1f)
    @Test @Config(qualifiers = "ru-w320dp-h640dp-xhdpi")
    fun russianLargeOnboardingKeepsActionsReachable() = onboarding("ru-large", 1.8f)
    @Test @Config(qualifiers = "uk-w320dp-h640dp-xhdpi")
    fun ukrainianLargeOnboardingKeepsActionsReachable() = onboarding("uk-large", 1.8f)

    private fun onboarding(locale: String, scale: Float) {
        var exitRequested by mutableStateOf(false)
        var left by mutableStateOf(false)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            HoldTheme { Surface(Modifier.fillMaxSize()) {
            if (left) HomeScreen(BackendHealthUiState.AVAILABLE, AccountCapabilityState.NotAuthenticated,
                publicEntry = true, showLanguageSwitch = false)
            else AccessPage(onBack = { exitRequested = true },
                footer = { AccessLanguageControl(AppLocale.SYSTEM, {}) }) {
                AtomicRegistrationContent(AtomicView(phase, error = error, handle = handle), handle,
                    { handle = it }, { phase = AtomicPhase.HANDLE }, { creates++; phase = AtomicPhase.VERIFY_REQUIRED },
                    {}, {}, {}, {}, {}, false,
                    onChooseHandle = { phase = AtomicPhase.CREATE_REQUIRED }, onVerify = { verifies++ },
                    onExitSetup = { exitRequested = true }) { _, _ -> }
            }
            if (exitRequested) AtomicLeaveDialog({ exitRequested = false }, { exitRequested = false; left = true })
            } }
        } }
        capture("$locale-welcome")
        rule.onNodeWithTag("atomic_handle").assertDoesNotExist()
        rule.onNodeWithTag("atomic_verify").assertDoesNotExist()
        rule.onNodeWithTag("access_google").performScrollTo().performClick()
        rule.onNodeWithTag("access_google").assertDoesNotExist()
        rule.onNodeWithTag("atomic_handle").performScrollTo().performTextInput("someone")
        capture("$locale-name")
        rule.onNodeWithTag("atomic_handle_continue").performScrollTo().performClick()
        rule.onNodeWithTag("atomic_handle").assertDoesNotExist()
        rule.onNodeWithTag("atomic_create").performScrollTo().assertIsDisplayed()
        capture("$locale-create")
        rule.onNodeWithTag("atomic_create").performClick()
        assertEquals(1, creates); assertEquals(0, verifies)
        rule.onNodeWithTag("atomic_create").assertDoesNotExist()
        rule.onNodeWithTag("atomic_verify").performScrollTo().assertIsDisplayed()
        capture("$locale-verify")
        rule.onNodeWithTag("atomic_verify").performClick(); assertEquals(1, verifies)
        rule.onNodeWithTag("access_back").performClick()
        rule.onNodeWithTag("atomic_leave_confirm").assertIsDisplayed()
        capture("$locale-back-confirmation", dialog = true)
        rule.onNodeWithTag("atomic_leave_cancel").performClick()
        rule.runOnIdle { error = "PRF_UNAVAILABLE" }
        rule.onNodeWithTag("atomic_error").performScrollTo().assertIsDisplayed()
        capture("$locale-capability-stop")
        rule.onNodeWithTag("atomic_verify").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("atomic_create").assertDoesNotExist()
        rule.onNodeWithTag("atomic_leave_setup").performScrollTo().assertIsEnabled()
        capture("$locale-capability-actions")
        rule.onNodeWithTag("atomic_leave_setup").performClick()
        capture("$locale-leave-confirmation", dialog = true)
        rule.onNodeWithTag("atomic_leave_confirm").performClick()
        rule.onNodeWithTag("scan_action").assertExists()
        assertEquals(1, creates); assertEquals(1, verifies)
    }

    @Test fun englishNavigationAndHistoryAction() = utilities("en", 1f)
    @Test @Config(qualifiers = "ru-w320dp-h640dp-xhdpi")
    fun russianLargeNavigationAndSettings() = utilities("ru-large", 1.8f)
    @Test @Config(qualifiers = "uk-w320dp-h640dp-xhdpi")
    fun ukrainianLargeNavigationAndSettings() = utilities("uk-large", 1.8f)

    private fun utilities(locale: String, scale: Float) {
        var section by mutableStateOf(ProductSection.HOME)
        var made = 0
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            HoldTheme { Surface(Modifier.fillMaxSize()) {
                ProductFrame(section, { section = ProductSection.HOME }, { section = ProductSection.SENT }, { section = ProductSection.SETTINGS }) {
                    when (section) {
                        ProductSection.HOME -> HomeScreen(BackendHealthUiState.AVAILABLE,
                            AccountCapabilityState.CryptoReady("synthetic", "someone"), showLanguageSwitch = false)
                        ProductSection.SENT -> HistoryScreen(HistoryUiState(loaded = true), {}, {}, {}, {}, {}, { false }, {}, {}, {}, { null },
                            onCreate = { made++ }, showHeader = false)
                        ProductSection.SETTINGS -> SettingsScreen("someone", AppLocale.SYSTEM, {}, true, {}, {}, showHeader = false)
                    }
                }
            } }
        } }
        capture("$locale-home")
        rule.onNodeWithTag("product_navigation").assertHeightIsAtMost(96.dp)
        rule.onNodeWithTag("root_home_button", useUnmergedTree = true).assertIsSelected()
        navigate(ProductSection.SENT)
        rule.onNodeWithTag("root_history_button", useUnmergedTree = true).assertIsSelected()
        rule.onNodeWithTag("history_create").performScrollTo().performClick()
        assertEquals(1, made); capture("$locale-sent-empty")
        navigate(ProductSection.SETTINGS)
        rule.onNodeWithTag("root_settings_button", useUnmergedTree = true).assertIsSelected()
        capture("$locale-settings")
        rule.onNodeWithTag("settings_feedback_row").performScrollTo().performClick()
        rule.onNodeWithTag("language_switch_row").performScrollTo().performClick()
        capture("$locale-language", dialog = true)
        rule.onNodeWithTag("language_dialog_close").performClick()
        navigate(ProductSection.HOME)
        rule.onNodeWithTag("scan_action").assertExists()
    }

    private fun navigate(section: ProductSection) {
        if (rule.onAllNodesWithTag("product_navigation_menu").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("product_navigation_menu").performClick()
            rule.onNodeWithTag("navigation_destination_" + section.name).performClick()
        } else rule.onNodeWithTag(when (section) {
            ProductSection.HOME -> "root_home_button"
            ProductSection.SENT -> "root_history_button"
            ProductSection.SETTINGS -> "root_settings_button"
        }).performClick()
    }

    private fun capture(name: String, dialog: Boolean = false) {
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        rule.runOnIdle {
            val decor = if (dialog) requireNotNull(ShadowDialog.getLatestDialog().window).decorView else rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
            val target = File("build/t454-pixels/$name.png"); target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
