package dev.hryshyn.remanence.ui.auth

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Executable resource/render assertions, not XML parity alone or provider acceptance. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AuthLocalizationTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun englishFieldsAreHumanReadable() = checkRegistration("enter a valid email")
    @Test @Config(qualifiers = "ru")
    fun russianFieldsAndNetworkErrorAreLocalized() = checkRegistration("введите правильный адрес почты")
    @Test @Config(qualifiers = "uk")
    fun ukrainianFieldsAndNetworkErrorAreLocalized() = checkRegistration("введіть правильну адресу пошти")

    private fun checkRegistration(expectedEmail: String) {
        rule.setContent { MaterialTheme {
            RegistrationFormScreen(
                RegistrationFormState("invalid", "short", "ab"),
                RegistrationSubmitState.Failed(AuthMessage.NETWORK), { _, _ -> }, {},
            )
        } }
        rule.onNodeWithTag("reg_error_email").assertTextEquals(expectedEmail)
        rule.onNodeWithTag("reg_error_password").assertTextEquals(
            context.getString(R.string.auth_password_short, RegistrationFormValidator.MIN_PASSWORD_CODE_POINTS))
        rule.onNodeWithTag("reg_error_handle").assertTextEquals(context.getString(R.string.auth_handle_rule))
        rule.onNodeWithTag("reg_error_message").assertTextEquals(context.getString(R.string.auth_network))
        assertFalse(context.getString(R.string.auth_email_invalid).contains("EMAIL_INVALID"))
    }

    @Test @Config(qualifiers = "uk")
    fun loginFailureUsesTypedLocalizedCopy() {
        rule.setContent { MaterialTheme {
            LoginScreen(LoginFormState("invalid", "password"),
                LoginSubmitState.Failed(AuthMessage.WRONG_CREDENTIALS), {}, {}, {})
        } }
        rule.onNodeWithTag("login_error_email").assertTextEquals("введіть правильну адресу пошти")
        rule.onNodeWithTag("login_error_message").assertTextEquals("неправильна пошта або пароль")
    }

    @Test fun focusingFieldScrollsItsHelperIntoShortViewportWithoutChangingFocus() {
        rule.setContent { MaterialTheme {
            Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(150.dp))
                AuthFieldGroup("3–30 characters", "focus_helper") { focusModifier ->
                    OutlinedTextField("ab", {}, modifier = focusModifier.testTag("focus_field"))
                }
            }
        } }
        rule.onNodeWithTag("focus_helper").assertIsNotDisplayed()
        rule.onNodeWithTag("focus_field").performClick()
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        rule.onNodeWithTag("focus_helper").assertIsDisplayed()
    }
}
