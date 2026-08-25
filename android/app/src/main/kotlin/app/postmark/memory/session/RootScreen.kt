package app.postmark.memory.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AuthUiState

/**
 * I03/FIX-M1-007-10 root renderer: picks exactly one surface from the guarded
 * set. Authentication hosts login/registration slots; authenticated users
 * land on Home and can reach ONLY the Create and Scan entry points (plus the
 * grant-gated capsule presentation). There is deliberately no gallery,
 * inbox, history, or feed surface anywhere in this hierarchy.
 */
@Composable
fun RootScreen(
    authState: AuthUiState,
    destination: AppDestination,
    authenticationContent: @Composable () -> Unit,
    homeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    createContent: @Composable () -> Unit = {},
    scanContent: @Composable () -> Unit = {},
    capsuleContent: @Composable (grantId: String, capsuleId: String) -> Unit = { _, _ -> },
    capsuleIdResolver: (String) -> String? = { null },
    onExitFlow: () -> Unit = {},
) {
    if (authState !is AuthUiState.Authenticated) {
        // FIX-STATE-07: the auth surface scrolls and stays keyboard-reachable
        // on small screens - every field and the submit buttons are reachable.
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            if (authState is AuthUiState.RecoveryRequired) {
                Text(
                    "Local keys are missing on this device. Sign in to complete account recovery.",
                    modifier = Modifier.padding(16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            authenticationContent()
        }
        return
    }

    when (destination) {
        AppDestination.Create -> Column(modifier = modifier.fillMaxWidth()) {
            FlowHeader(title = "Create", onExit = onExitFlow)
            createContent()
        }

        AppDestination.Scan -> Column(modifier = modifier.fillMaxWidth()) {
            FlowHeader(title = "Scan", onExit = onExitFlow)
            scanContent()
        }

        is AppDestination.Capsule ->
            capsuleContent(destination.grantId, capsuleIdResolver(destination.grantId) ?: "")

        // Until a live grant exists, Home remains the fallback surface.
        else -> homeContent()
    }
}

/** Shared exit chrome for the two reachable flows; leaving drops flow state. */
@Composable
private fun FlowHeader(title: String, onExit: () -> Unit) {
    OutlinedButton(
        onClick = onExit,
        modifier = Modifier
            .padding(16.dp)
            .testTag("flow_exit_${title.lowercase()}"),
    ) { Text("Back to Home") }
    Text(
        text = title,
        modifier = Modifier.testTag("flow_title_${title.lowercase()}"),
    )
    Spacer(Modifier.height(8.dp))
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
