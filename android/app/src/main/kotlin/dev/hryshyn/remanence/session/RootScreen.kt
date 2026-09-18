package dev.hryshyn.remanence.session

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.hryshyn.remanence.ui.hold.HoldTextButton as OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState

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
    capsuleContent: @Composable (grantId: String) -> Unit = {},
    onExitFlow: () -> Unit = {},
    showPublicHome: Boolean = false,
) {
    if (authState == AuthUiState.SignedOut && showPublicHome) {
        homeContent()
        return
    }
    if (authState !is AuthUiState.Authenticated) {
        // FIX-STATE-07: the auth surface scrolls and stays keyboard-reachable
        // on small screens - every field and the submit buttons are reachable.
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (authState is AuthUiState.RecoveryRequired) {
                Text(
                    "The private keys for this account are missing on this device. Signing in alone cannot restore them.",
                    modifier = Modifier.padding(16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            authenticationContent()
        }
        return
    }

    BackHandler(
        enabled = destination == AppDestination.Create || destination == AppDestination.Scan,
        onBack = onExitFlow,
    )

    when (destination) {
        // FIX-STATE-12: the root owns the FULL available size; the header is
        // laid out first and the flow body receives the REMAINING height via
        // weight(1f), so a full-size flow screen can never push the header's
        // controls out of reach or measure itself against the whole window.
        AppDestination.Create -> Column(modifier = modifier.fillMaxSize()) {
            FlowHeader(title = "Create", onExit = onExitFlow)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                createContent()
            }
        }

        AppDestination.Scan -> Column(modifier = modifier.fillMaxSize()) {
            FlowHeader(title = "Scan", onExit = onExitFlow)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                scanContent()
            }
        }

        is AppDestination.Capsule ->
            capsuleContent(destination.grantId)

        // Until a live grant exists, Home remains the fallback surface.
        else -> homeContent()
    }
}

/** Shared exit chrome for the two reachable flows; leaving drops flow state. */
@Composable
private fun FlowHeader(title: String, onExit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onExit, modifier = Modifier.testTag("flow_exit_${title.lowercase()}")) {
            Text("Back to Home")
        }
        Spacer(Modifier.weight(1f))
        Text(if (title == "Create") stringResource(R.string.hold_make) else stringResource(R.string.hold_scan),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 12.dp).testTag("flow_title_${title.lowercase()}"))
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
    Column(modifier = modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("@$handle", modifier = Modifier.weight(1f).testTag("root_signed_in_as"), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = onLogout, modifier = Modifier.testTag("root_logout_button")) { Text(stringResource(R.string.hold_logout)) }
        }
        Box(Modifier.weight(1f)) { homeContent() }
    }
}
