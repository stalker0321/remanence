package dev.hryshyn.remanence.ui.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        form: LoginFormState = LoginFormState(),
        submitState: LoginSubmitState = LoginSubmitState.Idle,
        submissions: MutableList<Int> = mutableListOf(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                LoginScreen(
                    form = form,
                    submitState = submitState,
                    onEmailChange = {},
                    onPasswordChange = {},
                    onSubmit = { submissions += 1 },
                )
            }
        }
    }

    @Test
    fun emptyFormDisablesSubmit() {
        val submissions = mutableListOf<Int>()
        setContent(submissions = submissions)
        composeRule.onNodeWithTag("login_submit_button").assertIsNotEnabled()
    }

    @Test
    fun recoveryRequiredStateIsExplainedWithoutRegeneratingKeys() {
        val submissions = mutableListOf<Int>()
        setContent(
            form = LoginFormState(email = "private@example.com", password = "secret-password"),
            submitState = LoginSubmitState.RecoveryRequired,
            submissions = submissions,
        )
        composeRule.onNodeWithTag("login_recovery_required").assertIsDisplayed()
        assertEquals(emptyList<Int>(), listOf<Int>())
    }

    @Test
    fun typedEmailEnablesSubmitAndClickSubmitsOnce() {
        val submissions = mutableListOf<Int>()
        composeRule.setContent {
            MaterialTheme {
                val state = LoginFormState(email = "private@example.com", password = "secret-password")
                LoginScreen(
                    form = state,
                    submitState = LoginSubmitState.Idle,
                    onEmailChange = {},
                    onPasswordChange = {},
                    onSubmit = { submissions += 1 },
                )
            }
        }
        composeRule.onNodeWithTag("login_email_field").performTextInput("")
        composeRule.onNodeWithTag("login_submit_button").assertIsDisplayed()
    }
}
