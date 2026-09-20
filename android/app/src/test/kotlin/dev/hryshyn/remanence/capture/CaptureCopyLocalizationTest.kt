package dev.hryshyn.remanence.capture

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.recognition.QualityReason
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Item 5 capture copy: the shared still surface renders its instruction,
 * shutter, permission, binding, capture, processing, failure and retry copy
 * from `hold_strings.xml` so EN/RU/UK stay authored and honest. Geometry,
 * binding order, and every existing test tag stay untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CaptureCopyLocalizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var controller: CaptureAttemptController
    private val live = AtomicReference<FakeStillCameraAdapter?>(null)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        controller = CaptureAttemptController()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun mount() {
        composeRule.setContent {
            MaterialTheme {
                CaptureAttemptSurface(
                    controller = controller,
                    shutterTag = "capture_shutter_front",
                    retakeTag = "capture_retake_front",
                    onBeginAttempt = { true },
                    onDelivered = {},
                    onRetake = { controller.startRetake() },
                    adapterFactory = { FakeStillCameraAdapter().also { live.set(it) } },
                    requestPermissionOnAttach = false,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun grantAndReady() {
        composeRule.runOnIdle { controller.onPermissionResolved(CapturePermissionStep.Granted) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()
    }

    private fun string(id: Int): String = context.getString(id)

    /**
     * Drives the ready surface into a processor failure and then a quality
     * rejection, asserting the current locale's safe failure body, the
     * debug-only raw diagnostic tag, and one localized quality reason.
     */
    private fun assertFailureAndRejectionCopy(rawMessage: String) {
        composeRule.runOnIdle { controller.beginAttempt() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_capturing_status")
            .assertTextEquals(string(R.string.hold_capture_capturing))

        composeRule.runOnIdle {
            controller.markProcessing()
            controller.fail(rawMessage)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_failed_header")
            .assertTextEquals(string(R.string.hold_capture_failed_title))
        composeRule.onNodeWithTag("capture_failed_message")
            .assertTextEquals(string(R.string.hold_capture_failed_body))
        if (BuildConfig.DEBUG) {
            composeRule.onNodeWithTag("capture_debug_failure_message")
                .assertTextEquals(rawMessage)
        }
        composeRule.onNodeWithText(string(R.string.hold_retry)).assertIsDisplayed()

        composeRule.runOnIdle { controller.startRetake() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            controller.beginAttempt()
            controller.reject(setOf(QualityReason.TOO_BLURRY))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quality_failure_header")
            .assertTextEquals(string(R.string.hold_capture_rejected_title))
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY")
            .assertTextEquals(string(R.string.hold_capture_reason_too_blurry))
        composeRule.onNodeWithText(string(R.string.hold_retry)).assertIsDisplayed()
    }

    @Test
    fun englishPhaseCopyIsQuietAndHonest() {
        mount()

        composeRule.onNodeWithTag("capture_permission_progress")
            .assertTextEquals(string(R.string.hold_capture_permission_needed))

        composeRule.runOnIdle { controller.onPermissionResolved(CapturePermissionStep.DeniedRetryable) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.hold_capture_permission_denied_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.hold_capture_permission_allow)).assertIsDisplayed()

        composeRule.runOnIdle { controller.reset() }
        composeRule.runOnIdle { controller.onPermissionResolved(CapturePermissionStep.PermanentlyDenied) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_permanently_denied")
            .assertTextEquals(string(R.string.hold_capture_permission_blocked_title))
        composeRule.onNodeWithTag("capture_open_settings").assertIsDisplayed()

        composeRule.runOnIdle { controller.reset() }
        composeRule.runOnIdle { controller.onPermissionResolved(CapturePermissionStep.Granted) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_binding_status")
            .assertTextEquals(string(R.string.hold_capture_binding))

        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("postcard_guide_instruction")
            .assertTextEquals(string(R.string.hold_capture_guide_instruction))
        composeRule.onNodeWithText(string(R.string.hold_capture_shutter)).assertIsDisplayed()

        composeRule.runOnIdle { controller.beginAttempt() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_capturing_status")
            .assertTextEquals(string(R.string.hold_capture_capturing))

        composeRule.runOnIdle { controller.markProcessing() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_processing_status")
            .assertTextEquals(string(R.string.hold_capture_processing))

        composeRule.runOnIdle { controller.fail("sift extraction failed") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_failed_header")
            .assertTextEquals(string(R.string.hold_capture_failed_title))
        composeRule.onNodeWithTag("capture_failed_message")
            .assertTextEquals(string(R.string.hold_capture_failed_body))
        if (BuildConfig.DEBUG) {
            composeRule.onNodeWithTag("capture_debug_failure_message")
                .assertTextEquals("sift extraction failed")
        }
        composeRule.onNodeWithText(string(R.string.hold_retry)).assertIsDisplayed()

        composeRule.runOnIdle { controller.startRetake() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            controller.beginAttempt()
            controller.reject(setOf(QualityReason.TOO_BLURRY))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quality_failure_header")
            .assertTextEquals(string(R.string.hold_capture_rejected_title))
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY")
            .assertTextEquals(string(R.string.hold_capture_reason_too_blurry))
        composeRule.onNodeWithText(string(R.string.hold_retry)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianCaptureCopyIsAuthored() {
        mount()
        composeRule.onNodeWithTag("capture_permission_progress")
            .assertTextEquals(string(R.string.hold_capture_permission_needed))

        grantAndReady()

        composeRule.onNodeWithTag("postcard_guide_instruction")
            .assertTextEquals(string(R.string.hold_capture_guide_instruction))
        composeRule.onNodeWithText(string(R.string.hold_capture_shutter)).assertIsDisplayed()

        assertFailureAndRejectionCopy(rawMessage = "processor boom")
    }

    @Test
    @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun ukrainianCaptureCopyIsAuthored() {
        mount()
        composeRule.onNodeWithTag("capture_permission_progress")
            .assertTextEquals(string(R.string.hold_capture_permission_needed))

        grantAndReady()

        composeRule.onNodeWithTag("postcard_guide_instruction")
            .assertTextEquals(string(R.string.hold_capture_guide_instruction))
        composeRule.onNodeWithText(string(R.string.hold_capture_shutter)).assertIsDisplayed()

        assertFailureAndRejectionCopy(rawMessage = "processor boom")
    }
}
