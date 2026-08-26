package dev.hryshyn.remanence.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

enum class BackendHealthUiState {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * Combined account capability driving Home actions. Create/Scan stay disabled
 * unless the user is authenticated AND the local crypto identity is ready;
 * recovery-required accounts can browse nowhere and are told why.
 */
sealed interface AccountCapabilityState {
    data object NotAuthenticated : AccountCapabilityState

    /** Authenticated on the server, but private identity keys are absent locally. */
    data object RecoveryRequired : AccountCapabilityState

    data class CryptoReady(
        val userId: String,
        val handle: String,
    ) : AccountCapabilityState

    val actionsEnabled: Boolean
        get() = this is CryptoReady
}

@Composable
fun HomeScreen(
    state: BackendHealthUiState,
    accountCapability: AccountCapabilityState = AccountCapabilityState.NotAuthenticated,
    onCreate: () -> Unit = {},
    onScan: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Remanence")
        Spacer(Modifier.height(12.dp))
        Text(
            text = when (state) {
                BackendHealthUiState.CHECKING -> "Architecture approved · API checking"
                BackendHealthUiState.AVAILABLE -> "Architecture approved · API available"
                BackendHealthUiState.UNAVAILABLE -> "Architecture approved · API unavailable"
            },
            modifier = Modifier.testTag("home_build_label"),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            enabled = accountCapability.actionsEnabled,
            modifier = Modifier.testTag("create_action"),
        ) {
            Text("Create")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onScan,
            enabled = accountCapability.actionsEnabled,
            modifier = Modifier.testTag("scan_action"),
        ) {
            Text("Scan")
        }
        when (accountCapability) {
            AccountCapabilityState.RecoveryRequired -> Text(
                text = "Private keys for this account are not on this device; recovery required.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("home_recovery_note"),
            )
            else -> Unit
        }
    }
}
