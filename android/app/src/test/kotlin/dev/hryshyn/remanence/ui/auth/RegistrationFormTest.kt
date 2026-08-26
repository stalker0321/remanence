package dev.hryshyn.remanence.ui.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class RegistrationFormValidatorTest {

    @Test
    fun emailShapeCheckedAsEarlyUxOnly() {
        assertNull(RegistrationFormValidator.emailError(""))
        assertNull(RegistrationFormValidator.emailError("private@example.com"))
        assertEquals(RegistrationFieldError.EMAIL_INVALID, RegistrationFormValidator.emailError("no-at-sign"))
        assertEquals(RegistrationFieldError.EMAIL_INVALID, RegistrationFormValidator.emailError("a b@c.io"))
        assertEquals(RegistrationFieldError.EMAIL_INVALID, RegistrationFormValidator.emailError("user@nodot"))
    }

    @Test
    fun passwordLimitsCountUnicodeCodePointsNotUtf16Units() {
        assertNull(RegistrationFormValidator.passwordError(""))
        val eleven = "abcdefghijk"
        val twelve = "abcdefghijkl"
        assertEquals(RegistrationFieldError.PASSWORD_TOO_SHORT, RegistrationFormValidator.passwordError(eleven))
        assertNull(RegistrationFormValidator.passwordError(twelve))
        // 1 surrogate pair = 2 UTF-16 units but 1 code point: 11 chars + 2 surrogates = 13 code points.
        val multibyteTwelveCodePoints = "abcdefghij" + "\uD83D\uDE00" + "x"
        assertNull(RegistrationFormValidator.passwordError(multibyteTwelveCodePoints))
        assertEquals(129, ("x".repeat(129)).length)
        assertEquals(RegistrationFieldError.PASSWORD_TOO_LONG, RegistrationFormValidator.passwordError("x".repeat(129)))
        assertNull(RegistrationFormValidator.passwordError("x".repeat(128)))
    }

    @Test
    fun handleValidationMirrorsNormalizationRules() {
        assertNull(RegistrationFormValidator.handleError(""))
        assertNull(RegistrationFormValidator.handleError("mykola"))
        assertNull(RegistrationFormValidator.handleError("@My_Kola.01"))
        assertEquals(RegistrationFieldError.HANDLE_INVALID, RegistrationFormValidator.handleError("ab"))
        assertEquals(RegistrationFieldError.HANDLE_INVALID, RegistrationFormValidator.handleError("has space"))
        assertEquals(RegistrationFieldError.HANDLE_INVALID, RegistrationFormValidator.handleError("x".repeat(31)))
    }

    @Test
    fun canSubmitRequiresAllFieldsValidAndNonEmpty() {
        val empty = RegistrationFormState()
        assertEquals(false, RegistrationFormValidator.canSubmit(empty))
        val valid = RegistrationFormState(
            email = "private@example.com",
            password = "long-enough-password",
            handle = "@mykola",
        )
        assertEquals(true, RegistrationFormValidator.canSubmit(valid))
        val shortPassword = valid.copy(password = "short")
        assertEquals(false, RegistrationFormValidator.canSubmit(shortPassword))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RegistrationFormScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: RegistrationFormState,
        submissions: MutableList<Int>,
        submitState: RegistrationSubmitState = RegistrationSubmitState.Idle,
    ) {
        composeRule.setContent {
            MaterialTheme {
                RegistrationFormScreen(
                    form = state,
                    submitState = submitState,
                    onFieldChange = { _, _ -> },
                    onSubmit = { submissions += 1 },
                )
            }
        }
    }

    @Test
    fun submitButtonDisabledWhileFieldsInvalid() {
        val submissions = mutableListOf<Int>()
        setContent(RegistrationFormState(email = "not-an-email"), submissions)
        composeRule.onNodeWithTag("reg_submit_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("reg_error_email").assertExists()
    }

    @Test
    fun handleGuidanceShownForInvalidHandle() {
        val submissions = mutableListOf<Int>()
        setContent(RegistrationFormState(handle = "ab"), submissions)
        composeRule.onNodeWithTag("reg_error_handle").assertExists()
    }

    @Test
    fun validFormEnablesSubmitAndReportsSingleSubmission() {
        val submissions = mutableListOf<Int>()
        setContent(
            RegistrationFormState(
                email = "private@example.com",
                password = "long-enough-password",
                handle = "@mykola",
            ),
            submissions,
        )
        val button = composeRule.onNodeWithTag("reg_submit_button")
        button.assertIsEnabled()
        button.performClick()
        assertEquals(listOf(1), submissions)
    }
}
