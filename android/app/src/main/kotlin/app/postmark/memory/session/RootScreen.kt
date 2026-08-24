package app.postmark.memory.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.postmark.memory.ui.navigation.AuthUiState

/**
 * I03 root renderer: picks exactly one surface from the guarded pair.
 * The authentication surface hosts login and registration slots so
 * MainActivity can bind its real ViewModels without this file knowing them.
 */
@Composable
fun RootScreen(
    authState: AuthUiState,
    authenticationContent: @Composable () -> Unit,
    homeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (authState is AuthUiState.Authenticated) {
        homeContent()
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (authState is AuthUiState.RecoveryRequired) {
            Text(
                "Local keys are missing on this device. Sign in to complete account recovery.",
                modifier = Modifier.padding(16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        authenticationContent()
    }
}

/** Home chrome including the logout action, rendered for authenticated users. */
@Composable
fun AuthenticatedHomeChrome(
    handle: String,
    onLogout: () -> Unit,
    homeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "Signed in as @$handle",
            modifier = Modifier.testTag("root_signed_in_as"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.testTag("root_logout_button"),
        ) { Text("Log out") }
        Spacer(Modifier.height(8.dp))
        homeContent()
    }
}
