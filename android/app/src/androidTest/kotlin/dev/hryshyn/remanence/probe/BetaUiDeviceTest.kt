package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.recovery.RecoveryPanel
import dev.hryshyn.remanence.ui.auth.AccessPage
import dev.hryshyn.remanence.ui.capsule.*
import dev.hryshyn.remanence.ui.hold.*
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.settings.SettingsScreen
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native UI + GPU export fixtures. No login bypass, real account or backend writes. */
@RunWith(AndroidJUnit4::class)
class BetaUiDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "t169-fixture-evidence").apply { mkdirs() }

    @Test fun settingsSelectsLanguageAndLogoutWithoutCrowding() {
        var locale by mutableStateOf(AppLocale.SYSTEM)
        var loggedOut = false
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            SettingsScreen("qa_fixture", locale, { locale = it }, true, {}, { loggedOut = true })
        } } }
        rule.onNodeWithTag("settings_title").assertIsDisplayed()
        rule.onNodeWithTag("language_option_ukrainian").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(AppLocale.UKRAINIAN, locale) }
        capture("settings-fixture")
        rule.onNodeWithTag("settings_logout").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(loggedOut) }
    }

    @Test fun passkeySetupHasNoUnverifiedContinue() {
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            AccessPage {
                RecoveryPanel(true, false, true, true, false, false, false, 0, false, false, "",
                    {}, {}, {}, {}, {}, {}, {}, {})
            }
        } } }
        rule.onNodeWithTag("recovery_save").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("recovery_continue").assertDoesNotExist()
        rule.onNodeWithTag("recovery_show_code").assertDoesNotExist()
        capture("passkey-setup-fixture")
    }

    @Test fun raisedSelfActionUsesCurrentTranslationAndPressCallback() {
        var calls = 0
        rule.setContent { HoldTheme { Surface(Modifier.fillMaxSize()) {
            HoldActionObject(stringResource(R.string.recipient_design_self_title), "@qa_fixture",
                stringResource(R.string.recipient_design_self_action), { calls++ },
                Modifier.testTag("self_fixture"), compact = true)
        } } }
        rule.onNodeWithTag("self_fixture").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, calls) }
        capture("self-action-fixture")
    }

    @Test fun hardwareExportContainsCurrentPageNotFooterAndChooserCancelsCleanly() {
        val state = fixtureCapsule()
        try {
            rule.setContent { HoldTheme { CapsuleScreen(state, validateExport = { check(state.isOpen) }) {} } }
            waitForPage()
            capture("capsule-before-share-fixture")
            val existing = exportFiles().map { it.name }.toSet()
            rule.onNodeWithTag("capsule_share").performClick()
            rule.onNodeWithTag("capsule_export_apps").assertIsDisplayed().performClick()
            await { exportFiles().any { it.name !in existing } }
            val file = exportFiles().first { it.name !in existing }
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try {
                assertTrue(bitmap.width > 100 && bitmap.height > 100)
                assertTrue("app footer must be outside PNG", bitmap.height < rule.activity.window.decorView.height)
                var red = 0
                var blue = 0
                for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > 180 && Color.blue(pixel) < 80) red++
                    if (Color.blue(pixel) > 180 && Color.red(pixel) < 80) blue++
                }
                assertTrue("red half missing from real GPU capture", red > 100)
                assertTrue("blue half missing from real GPU capture", blue > 100)
                file.copyTo(File(evidence, "export-current-page-fixture.png"), overwrite = true)
            } finally { bitmap.recycle() }
            await { shell("dumpsys activity activities").contains("ChooserActivity") }
            capture("system-share-chooser-fixture")
            shell("input keyevent BACK")
            rule.onNodeWithTag("capsule_share").assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithTag("capsule_page_0").assertIsDisplayed()
        } finally { rule.runOnIdle { state.close() } }
    }

    @Test fun documentPickerCancelClearsBusyAndAllowsAnotherExport() {
        val state = fixtureCapsule()
        try {
            rule.setContent { HoldTheme { CapsuleScreen(state, validateExport = { check(state.isOpen) }) {} } }
            waitForPage()
            rule.onNodeWithTag("capsule_share").performClick()
            rule.onNodeWithTag("capsule_export_file").performClick()
            await { shell("dumpsys activity activities").contains("com.android.documentsui") }
            capture("system-save-picker-fixture")
            shell("input keyevent BACK")
            rule.onNodeWithTag("capsule_share").assertIsDisplayed().assertIsEnabled().performClick()
            rule.onNodeWithTag("capsule_export_file").assertIsDisplayed()
        } finally { rule.runOnIdle { state.close() } }
    }

    @Test fun closedGrantCannotExportPlaintext() {
        var revoked = false
        val state = fixtureCapsule()
        try {
            rule.setContent { HoldTheme { CapsuleScreen(state, validateExport = { check(!revoked) }) {} } }
            waitForPage()
            val existing = exportFiles().map { it.name }.toSet()
            rule.onNodeWithTag("capsule_share").performClick()
            rule.runOnIdle { revoked = true }
            rule.onNodeWithTag("capsule_export_apps").performClick()
            rule.onNodeWithText(context.getString(R.string.capsule_export_failed)).assertIsDisplayed()
            assertEquals(existing, exportFiles().map { it.name }.toSet())
        } finally { rule.runOnIdle { state.close() } }
    }

    private fun fixtureCapsule(): CapsulePresentationState {
        val state = CapsulePresentationState(CapsulePhotoLoader { ordinal ->
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.RED)
            canvas.drawRect(160f, 0f, 320f, 240f, android.graphics.Paint().apply { color = Color.BLUE })
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            bitmap.recycle()
            DecryptedPhoto(ordinal, stream.toByteArray())
        })
        state.open(3, "Synthetic test content, not user media")
        return state
    }

    private fun waitForPage() = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag("capsule_page_0").fetchSemanticsNodes().isNotEmpty()
    }
    private fun exportFiles() = File(context.cacheDir, "capsule-exports").listFiles()?.toList().orEmpty()
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "system UI transition timed out" }
            SystemClock.sleep(100)
        }
    }
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun capture(name: String) {
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(evidence, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
}
