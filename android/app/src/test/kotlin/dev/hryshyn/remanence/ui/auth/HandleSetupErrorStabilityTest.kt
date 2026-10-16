package dev.hryshyn.remanence.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Handle-registration errors live next to the input inside a stable
 * host (heading + input + change-account action + CTA): toggling a
 * realistic wrapping server failure moves nothing at default or
 * narrow/large-font layout, carries no content while absent, and
 * typing/busy toggles never reset the draft. Tag-, text- and
 * bounds-based, no backend. NOT run in this tick (no Gradle execution
 * per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HandleSetupErrorStabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun mountHost(
        handle: String,
        onHandleChange: (String) -> Unit,
        busy: Boolean,
        serverError: String?,
        widthDp: Int = 390,
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
                    Box(Modifier.width(widthDp.dp)) {
                        AccessPage {
                            AccessHeading("Choose your handle", null)
                            HandleSetupContent(
                                handle = handle,
                                onChange = onHandleChange,
                                busy = busy,
                                onContinue = {},
                                serverError = serverError,
                            )
                            HoldTextButton(
                                onClick = {},
                                modifier = Modifier.testTag("change_account_probe"),
                            ) {
                                Text("Use a different account")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun topOf(tag: String): Float =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun titleTop(): Float =
        composeRule.onNodeWithText("Choose your handle").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun titlesInputActionAndCtaDoNotMoveWhenErrorToggles() {
        var serverError by mutableStateOf<String?>(null)
        mountHost(handle = "takenhandle", onHandleChange = {}, busy = false, serverError = serverError)
        composeRule.onNodeWithTag("access_handle_continue").assertIsDisplayed()
        // Reserved slot carries no content while absent.
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        val titleRest = titleTop()
        val inputRest = topOf("access_handle_input")
        val actionRest = topOf("change_account_probe")
        val ctaRest = topOf("access_handle_continue")

        composeRule.runOnIdle { serverError = "Handle already taken" }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error")
            .assertTextEquals("Handle already taken")
        assertEquals(titleRest, titleTop(), 0f)
        assertEquals(inputRest, topOf("access_handle_input"), 0f)
        assertEquals(actionRest, topOf("change_account_probe"), 0f)
        assertEquals(ctaRest, topOf("access_handle_continue"), 0f)

        composeRule.runOnIdle { serverError = null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        assertEquals(titleRest, titleTop(), 0f)
        assertEquals(actionRest, topOf("change_account_probe"), 0f)
        assertEquals(ctaRest, topOf("access_handle_continue"), 0f)
    }

    @Test
    fun narrowLargeFontRussianErrorStillMovesNothing() {
        var serverError by mutableStateOf<String?>(null)
        mountHost(
            handle = "takenhandle",
            onHandleChange = {},
            busy = false,
            serverError = serverError,
            widthDp = 320,
            fontScale = 1.3f,
        )
        composeRule.onNodeWithTag("access_handle_continue").assertIsDisplayed()
        val titleRest = titleTop()
        val inputRest = topOf("access_handle_input")
        val actionRest = topOf("change_account_probe")
        val ctaRest = topOf("access_handle_continue")

        // Realistic long Russian conflict message: wraps freely instead
        // of clipping, while everything around the slot holds still.
        composeRule.runOnIdle {
            serverError = "Это имя уже занято — выберите другое, более длинное имя"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error").assertIsDisplayed()
        assertEquals(titleRest, titleTop(), 0f)
        assertEquals(inputRest, topOf("access_handle_input"), 0f)
        assertEquals(actionRest, topOf("change_account_probe"), 0f)
        assertEquals(ctaRest, topOf("access_handle_continue"), 0f)
    }

    @Test
    fun typingSurvivesErrorAndBusyToggles() {
        var text by mutableStateOf("")
        var serverError by mutableStateOf<String?>(null)
        var busy by mutableStateOf(false)
        mountHost(
            handle = text,
            onHandleChange = { if (it.length <= 30) text = it },
            busy = busy,
            serverError = serverError,
        )

        composeRule.onNodeWithTag("access_handle_input").performTextInput("takenhandle")
        composeRule.runOnIdle {
            serverError = "Handle already taken"
            busy = true
        }
        composeRule.waitForIdle()
        // Draft intact, CTA disabled only by busy (valid handle kept).
        composeRule.onNodeWithTag("access_handle_input").assertTextEquals("takenhandle")
        composeRule.runOnIdle {
            busy = false
            serverError = null
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_input").assertTextEquals("takenhandle")
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        assertTrue(topOf("access_handle_continue") > 0f)
    }
}
