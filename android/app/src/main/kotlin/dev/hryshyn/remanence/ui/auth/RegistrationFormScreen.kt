package dev.hryshyn.remanence.ui.auth

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldFormScaffold
import dev.hryshyn.remanence.ui.hold.HoldInput as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    HoldFormScaffold(
        modifier = modifier,
        primary = {
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = onSubmit,
                    enabled = submitState !is RegistrationSubmitState.Submitting &&
                        submitState !is RegistrationSubmitState.Completed &&
                        RegistrationFormValidator.canSubmit(form),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reg_submit_button"),
                ) {
                    Text(stringResource(R.string.hold_create_account))
                }
                if (submitState is RegistrationSubmitState.Submitting) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.testTag("reg_submit_progress"),
                    )
                }
            }
        },
    ) {
        AuthFieldGroup(if (RegistrationFieldError.EMAIL_INVALID in errors) stringResource(R.string.auth_email_invalid) else null,
            "reg_error_email") { focusModifier ->
            OutlinedTextField(
                value = form.email,
                onValueChange = { onFieldChange(RegistrationField.EMAIL, it) },
                label = { Text(stringResource(R.string.hold_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                isError = form.email.isNotEmpty() && RegistrationFormValidator.emailError(form.email) != null,
                modifier = focusModifier.fillMaxWidth().testTag("reg_email_field"),
            )
        }
        val passwordError = RegistrationFormValidator.passwordError(form.password)
        val passwordMessage = when (passwordError) {
            RegistrationFieldError.PASSWORD_TOO_SHORT ->
                stringResource(R.string.auth_password_short, RegistrationFormValidator.MIN_PASSWORD_CODE_POINTS)
            RegistrationFieldError.PASSWORD_TOO_LONG ->
                stringResource(R.string.auth_password_long, RegistrationFormValidator.MAX_PASSWORD_CODE_POINTS)
            else -> null
        }
        AuthFieldGroup(passwordMessage, "reg_error_password") { focusModifier ->
            OutlinedTextField(
                value = form.password,
                onValueChange = { onFieldChange(RegistrationField.PASSWORD, it) },
                label = { Text(stringResource(R.string.hold_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                isError = form.password.isNotEmpty() && RegistrationFormValidator.passwordError(form.password) != null,
                modifier = focusModifier.fillMaxWidth().testTag("reg_password_field"),
            )
        }
        AuthFieldGroup(if (RegistrationFormValidator.handleError(form.handle) == null) null
            else stringResource(R.string.auth_handle_rule), "reg_error_handle") { focusModifier ->
            OutlinedTextField(
                value = form.handle,
                onValueChange = { onFieldChange(RegistrationField.HANDLE, it) },
                label = { Text(stringResource(R.string.hold_handle)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                isError = form.handle.isNotEmpty() && RegistrationFormValidator.handleError(form.handle) != null,
                modifier = focusModifier.fillMaxWidth().testTag("reg_handle_field"),
            )
        }

        when (submitState) {
            is RegistrationSubmitState.Failed -> Text(
                text = stringResource(submitState.message.resourceId),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("reg_error_message"),
            )
            RegistrationSubmitState.Completed -> Text(
                text = stringResource(R.string.auth_account_created),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("reg_completed_message"),
            )
            else -> Unit
        }
    }
}
