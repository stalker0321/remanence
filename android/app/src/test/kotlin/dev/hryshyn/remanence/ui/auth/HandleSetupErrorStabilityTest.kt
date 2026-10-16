package dev.hryshyn.remanence.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Handle-registration errors live next to the input inside the ACTUAL
 * shared ticket host ([TicketClaimContent], the exact tree MainActivity
 * mounts): heading + input + retry action + CTA hold still when a
 * realistic wrapping server failure toggles — at default and narrow /
 * large-font layout — carry no content while absent, and typing/busy
 * toggles never reset the draft. Tag-, text- and bounds-based against
 * real `R.string` values (no brittle literals); no backend. NOT run in
 * this tick (no Gradle execution per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HandleSetupErrorStabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun appString(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    private fun mountHost(
        handle: androidx.compose.runtime.State<String>,
        onHandleChange: (String) -> Unit,
        busy: androidx.compose.runtime.State<Boolean>,
        serverError: androidx.compose.runtime.State<String?>,
        widthDp: Int? = null,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            HoldTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = LocalDensity.current.density,
                        fontScale = fontScale,
                    ),
                ) {
                    // Real AccessPage host (header row + content + footer),
                    // exactly like production: the shared ticket tree keeps
                    // its button-first, weighted-form shape, and the real
                    // language footer is mounted (never the default empty
                    // footer). A bounded width goes BEFORE fill —
                    // fillMaxSize first would tighten the min width and
                    // make the bound a no-op.
                    val host: @Composable () -> Unit = {
                        AccessPage(
                            footer = {
                                AccessLanguageControl(
                                    current = AppLocale.SYSTEM,
                                    onSelect = {},
                                )
                            },
                        ) {
                            TicketClaimContent(
                                handle = handle.value,
                                onHandleChange = onHandleChange,
                                busy = busy.value,
                                serverError = serverError.value,
                                onRetryAccount = {},
                                onContinue = {},
                            )
                        }
                    }
                    if (widthDp == null) {
                        Box(Modifier.fillMaxSize().testTag("narrow_probe_host")) { host() }
                    } else {
                        Box(
                            Modifier.width(widthDp.dp).fillMaxHeight()
                                .testTag("narrow_probe_host"),
                        ) {
                            host()
                        }
                    }
                }
            }
        }
    }

    /** Measured host width in px — proves the narrow bound actually held. */
    private fun hostWidthPx(): Float =
        composeRule.onNodeWithTag("narrow_probe_host").fetchSemanticsNode().boundsInRoot.width

    private fun topOf(tag: String): Float =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun titleTop(): Float =
        composeRule.onNodeWithText(appString(R.string.mvp_handle_title))
            .fetchSemanticsNode().boundsInRoot.top

    /** Rendered editable text, re-fetched on every call (never a cached
    node): proves what the field actually displays, not outer state. */
    private fun renderedDraft(): String =
        composeRule.onNodeWithTag("access_handle_input")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun titlesInputActionAndCtaDoNotMoveWhenErrorToggles() {
        val serverError = mutableStateOf<String?>(null)
        mountHost(
            handle = mutableStateOf("takenhandle"),
            onHandleChange = {},
            busy = mutableStateOf(false),
            serverError = serverError,
        )
        composeRule.onNodeWithTag("access_handle_continue").assertIsDisplayed()
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        // Reserved slot carries no content while absent.
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        val titleRest = titleTop()
        val inputRest = topOf("access_handle_input")
        val actionRest = topOf("access_google_retry")
        val ctaRest = topOf("access_handle_continue")
        val footerRest = topOf("access_language")

        composeRule.runOnIdle { serverError.value = "Handle already taken" }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error")
            .assertTextEquals("Handle already taken")
        assertEquals(titleRest, titleTop(), 0f)
        assertEquals(inputRest, topOf("access_handle_input"), 0f)
        assertEquals(actionRest, topOf("access_google_retry"), 0f)
        assertEquals(ctaRest, topOf("access_handle_continue"), 0f)
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        assertEquals(footerRest, topOf("access_language"), 0f)

        composeRule.runOnIdle { serverError.value = null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        assertEquals(titleRest, titleTop(), 0f)
        assertEquals(actionRest, topOf("access_google_retry"), 0f)
        assertEquals(ctaRest, topOf("access_handle_continue"), 0f)
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        assertEquals(footerRest, topOf("access_language"), 0f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
    fun narrowLargeFontRussianErrorStillMovesNothing() {
        val serverError = mutableStateOf<String?>(null)
        mountHost(
            handle = mutableStateOf("takenhandle"),
            onHandleChange = {},
            busy = mutableStateOf(false),
            serverError = serverError,
            widthDp = 320,
            fontScale = 1.3f,
        )
        composeRule.onNodeWithTag("access_handle_continue").assertIsDisplayed()
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        // The 320 dp bound actually held inside the 360 dp window.
        val expectedPx = with(composeRule.density) { 320.dp.toPx() }
        assertEquals(expectedPx, hostWidthPx(), 1f)
        val titleRest = titleTop()
        val inputRest = topOf("access_handle_input")
        val actionRest = topOf("access_google_retry")
        val ctaRest = topOf("access_handle_continue")
        val footerRest = topOf("access_language")

        // Realistic long Russian conflict message: wraps freely instead
        // of clipping, while everything around the slot holds still.
        composeRule.runOnIdle {
            serverError.value = "Это имя уже занято — выберите другое, более длинное имя"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error").assertIsDisplayed()
        assertEquals(titleRest, titleTop(), 0f)
        assertEquals(inputRest, topOf("access_handle_input"), 0f)
        assertEquals(actionRest, topOf("access_google_retry"), 0f)
        assertEquals(ctaRest, topOf("access_handle_continue"), 0f)
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        assertEquals(footerRest, topOf("access_language"), 0f)
    }

    @Test
    fun typingSurvivesErrorAndBusyToggles() {
        val text = mutableStateOf("")
        val captured = mutableListOf<String>()
        val serverError = mutableStateOf<String?>(null)
        val busy = mutableStateOf(false)
        mountHost(
            handle = text,
            onHandleChange = {
                captured += it
                if (it.length <= 30) text.value = it
            },
            busy = busy,
            serverError = serverError,
        )

        composeRule.onNodeWithTag("access_handle_input").performTextInput("takenhandle")
        composeRule.waitForIdle()
        // Input path works (callback observed); draft state owned here.
        // onChange may fire per edit, so only the final draft is asserted.
        assertTrue(captured.isNotEmpty())
        assertEquals("takenhandle", captured.last())
        // And the rendered editable field actually holds it (label text
        // excluded by construction — no merged-text guessing).
        assertEquals("takenhandle", renderedDraft())
        composeRule.runOnIdle {
            serverError.value = "Handle already taken"
            busy.value = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error")
            .assertTextEquals("Handle already taken")
        // Rendered draft holds DURING busy/error, not just outer state.
        assertEquals("takenhandle", renderedDraft())
        composeRule.runOnIdle {
            busy.value = false
            serverError.value = null
        }
        composeRule.waitForIdle()
        // Draft intact AFTER both toggles, re-fetched from the field;
        // slot empty again.
        assertEquals("takenhandle", renderedDraft())
        assertEquals("takenhandle", text.value)
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        assertTrue(topOf("access_handle_continue") > 0f)
    }
}
