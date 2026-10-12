package dev.hryshyn.remanence.ui.auth

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import androidx.compose.foundation.layout.fillMaxWidth
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
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

@Composable
fun LoginScreen(
    form: LoginFormState,
    submitState: LoginSubmitState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HoldFormScaffold(
        modifier = modifier,
        primary = {
            Button(
                onClick = onSubmit,
                enabled = LoginFormValidator.canSubmit(form) && submitState !is LoginSubmitState.Submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login_submit_button"),
            ) {
                Text(if (submitState is LoginSubmitState.Submitting) stringResource(R.string.hold_signing_in) else stringResource(R.string.hold_signin))
            }
        },
    ) {
        AuthFieldGroup(if (LoginFormValidator.emailError(form.email) != null) stringResource(R.string.auth_email_invalid) else null,
            "login_error_email") { focusModifier ->
            OutlinedTextField(
                value = form.email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.hold_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                isError = form.email.isNotEmpty() && LoginFormValidator.emailError(form.email) != null,
                modifier = focusModifier.fillMaxWidth().testTag("login_email_field"),
            )
        }

        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.hold_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_password_field"),
        )

        when (submitState) {
            is LoginSubmitState.Failed -> Text(
                text = stringResource(submitState.message.resourceId),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("login_error_message"),
            )
            LoginSubmitState.RecoveryRequired -> Text(
                text = stringResource(R.string.auth_recovery_required),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("login_recovery_required"),
            )
            else -> Unit
        }
    }
}
