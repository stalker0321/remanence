package dev.hryshyn.remanence.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Handle-registration errors live next to the input inside a stable
 * form: the reserved error slot means toggling a server failure moves
 * neither the CTA nor the guidance, and typing/busy toggles never reset
 * the draft. Tag- and bounds-based, locale-independent. NOT run in this
 * tick (no Gradle execution per the task brief).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HandleSetupErrorStabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ctaTop(): Float =
        composeRule.onNodeWithTag("access_handle_continue").fetchSemanticsNode().boundsInRoot.top

    private fun guidanceTop(): Float =
        composeRule.onNodeWithTag("access_handle_guidance").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun titlesAndCtaDoNotMoveWhenErrorToggles() {
        var serverError by mutableStateOf<String?>(null)
        composeRule.setContent {
            HoldTheme {
                AccessPage {
                    HandleSetupContent(
                        handle = "takenhandle",
                        onChange = {},
                        busy = false,
                        onContinue = {},
                        serverError = serverError,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("access_handle_continue").assertIsDisplayed()
        // Reserved slot carries no content while absent.
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        val ctaRest = ctaTop()
        val guidanceRest = guidanceTop()

        composeRule.runOnIdle { serverError = "Handle already taken" }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("Handle already taken")
        assertEquals(guidanceRest, guidanceTop(), 0f)
        assertEquals(ctaRest, ctaTop(), 0f)

        composeRule.runOnIdle { serverError = null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_handle_server_error").assertTextEquals("")
        assertEquals(guidanceRest, guidanceTop(), 0f)
        assertEquals(ctaRest, ctaTop(), 0f)
    }

    @Test
    fun typingSurvivesErrorAndBusyToggles() {
        var text by mutableStateOf("")
        var serverError by mutableStateOf<String?>(null)
        var busy by mutableStateOf(false)
        composeRule.setContent {
            HoldTheme {
                AccessPage {
                    HandleSetupContent(
                        handle = text,
                        onChange = { if (it.length <= 30) text = it },
                        busy = busy,
                        onContinue = {},
                        serverError = serverError,
                    )
                }
            }
        }

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
        assertTrue(ctaTop() > 0f)
    }
}
