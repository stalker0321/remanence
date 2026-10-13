package dev.hryshyn.remanence.probe

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.ui.capsule.CapsuleContentBinding
import dev.hryshyn.remanence.ui.capsule.CapsuleContentReader
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import dev.hryshyn.remanence.ui.capsule.CapsuleRoute
import dev.hryshyn.remanence.ui.capsule.DecryptedPhoto
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.pending.PendingCodeEntryButton
import dev.hryshyn.remanence.pending.PendingCodeEntrySheet
import dev.hryshyn.remanence.pending.PendingHandleRequiredNotice
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T186 M5 native journey fixtures (device, root runs them; no JVM claim).
 *
 * Real components throughout: the pending code entry composables, the
 * handle gate notice, and the REAL [CapsuleRoute] state machine with a
 * decodable-JPEG reader. Honest limits: the route fixture uses a FAKE
 * reader plus no-op grant validation, so it proves UI-only behavior
 * (terminal states, callbacks, nonblocking display) — NOT full accepted
 * crypto, session, or production-equivalent proof. No auth session exists
 * in these tests and none is bypassed — the entry surfaces need no session
 * by design, and the route fixture never touches the application grant
 * authority. This is explicitly NOT Google physical proof (`.pendingtest`
 * OAuth/DAL are unregistered); no provider, no sender, no network.
 */
@RunWith(AndroidJUnit4::class)
class PendingJourneyDeviceTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "pendingtest-fixture-evidence").apply { mkdirs() }

    private fun localized(tag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    private fun mountEntry(tag: String, content: @Composable () -> Unit) {
        val localized = localized(tag)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                HoldTheme { content() }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun codeEntryEnglishOpensSheetAndBackReturns() {
        var opened = false
        mountEntry("en") {
            PendingCodeEntryButton(onOpenCodeEntry = { opened = true }, enabled = true)
        }
        rule.onNodeWithText("the card has a code").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(opened) }
        capture("pending-code-entry-en")
    }

    @Test fun codeEntrySheetBackNavigates() {
        var closed = false
        mountEntry("en") {
            // Real Compose state: closing recomposes the sheet away.
            var shown by remember { mutableStateOf(true) }
            if (shown) {
                PendingCodeEntrySheet(onClose = { shown = false; closed = true }, enabled = true)
            }
        }
        rule.onNodeWithText("enter the code from the card").assertIsDisplayed()
        capture("pending-code-sheet-en")
        rule.onNodeWithTag("pending_code_cancel").performClick()
        rule.runOnIdle { assertTrue(closed) }
        rule.onNodeWithTag("pending_code_sheet").assertDoesNotExist()
    }

    @Test fun codeEntryRussianLabel() {
        mountEntry("ru") {
            PendingCodeEntryButton(onOpenCodeEntry = {}, enabled = true)
        }
        rule.onNodeWithText("На открытке есть код").assertIsDisplayed()
        rule.onNodeWithText("the card has a code").assertDoesNotExist()
        capture("pending-code-entry-ru")
    }

    @Test fun codeEntryUkrainianLabel() {
        mountEntry("uk") {
            PendingCodeEntryButton(onOpenCodeEntry = {}, enabled = true)
        }
        rule.onNodeWithText("На листівці є код").assertIsDisplayed()
        capture("pending-code-entry-uk")
    }

    @Test fun malformedCodeShowsGenericErrorOnly() {
        mountEntry("en") {
            PendingCodeEntrySheet(onClose = {}, enabled = true)
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("?????")
        rule.onNodeWithTag("pending_code_continue").performClick()
        // Generic error, never an existence oracle; no lookup attempted.
        rule.onNodeWithTag("pending_code_error").assertIsDisplayed()
        rule.onNodeWithTag("pending_code_unavailable").assertDoesNotExist()
        capture("pending-code-error-en")
    }

    @Test fun wellFormedCodeStaysFailClosed() {
        var normalized: String? = null
        mountEntry("en") {
            PendingCodeEntrySheet(onClose = {}, onWellFormedCode = { normalized = it }, enabled = true)
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("a3k9q")
        rule.onNodeWithTag("pending_code_continue").performClick()
        // Normalized per the frozen contract, but no wire invented: the
        // unavailable note shows and the sheet stays (no fake opening).
        rule.runOnIdle { assertEquals("A3K9Q", normalized) }
        rule.onNodeWithTag("pending_code_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("pending_code_sheet").assertIsDisplayed()
        capture("pending-code-unavailable-en")
    }

    @Test fun handleNoticeBackFires() {
        var backed = false
        mountEntry("en") {
            PendingHandleRequiredNotice(onBack = { backed = true }, enabled = true)
        }
        rule.onNodeWithTag("pending_handle_required").assertIsDisplayed()
        capture("pending-handle-required-en")
        rule.onNodeWithTag("pending_handle_required_back").performClick()
        rule.runOnIdle { assertTrue(backed) }
    }

    /** Decodable-JPEG reader so the Ready path renders real pixels. */
    private class FakePhotos : CapsuleContentReader {
        override suspend fun photoCount(capsuleId: String): Int = 3
        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff336699.toInt() + ordinal)
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
            bitmap.recycle()
            return DecryptedPhoto(ordinal, bytes)
        }
        override suspend fun noteText(capsuleId: String): String? = "hello"
        override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission =
            CapsulePresentationAdmission.LegacyV1
    }

    @Test fun readyDisplayNeverWaitsForRetirement() {
        var retireSignalled = false
        var closed = false
        rule.setContent {
            HoldTheme {
                // UI-only fixture: FAKE reader + no-op grant validation.
                // Proves terminal display states and callback timing — not
                // crypto, session, or production proof (see class KDoc).
                CapsuleRoute(
                    grantId = "fixture-grant",
                    contentFactory = { CapsuleContentBinding("fixture-capsule", FakePhotos()) },
                    validateLiveGrant = {},
                    revocations = MutableSharedFlow(),
                    onClose = { closed = true },
                    // Production-equivalent hook: the container persists +
                    // schedules here WITHOUT gating the viewer below.
                    onSupportedReady = { retireSignalled = true },
                )
            }
        }
        // Real content is on screen (decodable pixels, indicator)...
        rule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        capture("pending-ready-display")
        // ...and the retirement signal fired in the background without any
        // wait, tap, or second step from the viewer.
        rule.runOnIdle { assertTrue(retireSignalled) }
        assertFalse(closed)
    }
}
