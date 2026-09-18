package dev.hryshyn.remanence.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldInput as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    form: LoginFormState,
    submitState: LoginSubmitState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.email,
            onValueChange = onEmailChange,
            label = { Text("email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            isError = form.email.isNotEmpty() && LoginFormValidator.emailError(form.email) != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_email_field"),
        )
        if (LoginFormValidator.emailError(form.email) != null) {
            Text(
                text = "Enter a valid email",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("login_error_email"),
            )
        }

        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            label = { Text("password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_password_field"),
        )

        when (submitState) {
            is LoginSubmitState.Failed -> Text(
                text = submitState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("login_error_message"),
            )
            LoginSubmitState.RecoveryRequired -> Text(
                text = "Signed in, but the private keys for this account are not on this device. Recovery required; existing encrypted content stays inaccessible until keys are restored.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("login_recovery_required"),
            )
            else -> Unit
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSubmit,
            enabled = LoginFormValidator.canSubmit(form) && submitState !is LoginSubmitState.Submitting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_submit_button"),
        ) {
            Text(if (submitState is LoginSubmitState.Submitting) "signing in…" else "sign in")
        }
    }
}
