package dev.hryshyn.remanence.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

enum class RegistrationField { EMAIL, PASSWORD, HANDLE }

/**
 * FIX-STATE-07: the form renders THE authoritative [RegistrationSubmitState]:
 * Submitting disables the button with visible progress, Failed shows a
 * redacted actionable message, and Completed never allows a second submit.
 */
@Composable
fun RegistrationFormScreen(
    form: RegistrationFormState,
    submitState: RegistrationSubmitState,
    onFieldChange: (RegistrationField, String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = RegistrationFormValidator.visibleErrors(form)
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = form.email,
            onValueChange = { onFieldChange(RegistrationField.EMAIL, it) },
            label = { Text("Email") },
            isError = form.email.isNotEmpty() && RegistrationFormValidator.emailError(form.email) != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reg_email_field"),
        )
        FieldErrorText(errors[RegistrationFieldError.EMAIL_INVALID], "reg_error_email")

        OutlinedTextField(
            value = form.password,
            onValueChange = { onFieldChange(RegistrationField.PASSWORD, it) },
            label = { Text("Password") },
            isError = form.password.isNotEmpty() && RegistrationFormValidator.passwordError(form.password) != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reg_password_field"),
        )
        val passwordError = RegistrationFormValidator.passwordError(form.password)
        FieldErrorText(
            when (passwordError) {
                RegistrationFieldError.PASSWORD_TOO_SHORT ->
                    "At least ${RegistrationFormValidator.MIN_PASSWORD_CODE_POINTS} characters"
                RegistrationFieldError.PASSWORD_TOO_LONG ->
                    "At most ${RegistrationFormValidator.MAX_PASSWORD_CODE_POINTS} characters"
                else -> null
            },
            "reg_error_password",
        )

        OutlinedTextField(
            value = form.handle,
            onValueChange = { onFieldChange(RegistrationField.HANDLE, it) },
            label = { Text("Handle") },
            isError = form.handle.isNotEmpty() && RegistrationFormValidator.handleError(form.handle) != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reg_handle_field"),
        )
        FieldErrorText(
            if (RegistrationFormValidator.handleError(form.handle) == null) null
            else "3–30 chars: a–z 0–9 _ .",
            "reg_error_handle",
        )

        Spacer(Modifier.height(16.dp))
        when (submitState) {
            is RegistrationSubmitState.Failed -> Text(
                text = submitState.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("reg_error_message"),
            )
            RegistrationSubmitState.Completed -> Text(
                text = "Account created.",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("reg_completed_message"),
            )
            else -> Unit
        }
        Row {
            Button(
                onClick = onSubmit,
                enabled = submitState !is RegistrationSubmitState.Submitting &&
                    submitState !is RegistrationSubmitState.Completed &&
                    RegistrationFormValidator.canSubmit(form),
                modifier = Modifier
                    .weight(1f)
                    .testTag("reg_submit_button"),
            ) {
                Text("Create account")
            }
            if (submitState is RegistrationSubmitState.Submitting) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.testTag("reg_submit_progress"),
                )
            }
        }
    }
}

@Composable
private fun FieldErrorText(message: String?, tag: String) {
    if (message != null) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(tag),
        )
    }
}
