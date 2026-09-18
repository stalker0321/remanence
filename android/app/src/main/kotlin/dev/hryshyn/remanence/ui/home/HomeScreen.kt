package dev.hryshyn.remanence.ui.home

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.hryshyn.remanence.ui.hold.HoldActionObject
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
    publicEntry: Boolean = false,
) {
    val enabled = accountCapability.actionsEnabled ||
        (publicEntry && accountCapability == AccountCapabilityState.NotAuthenticated)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("remanence", style = MaterialTheme.typography.titleLarge)
        if (accountCapability == AccountCapabilityState.RecoveryRequired) {
            Text("Private keys for this account are not on this device; recovery required.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("home_recovery_note"))
        }
        HoldActionObject(
            title = stringResource(R.string.hold_home_open_title),
            detail = stringResource(R.string.hold_home_open_body),
            action = stringResource(R.string.hold_scan), onClick = onScan, enabled = enabled,
            modifier = Modifier.testTag("scan_action"),
        )
        HoldActionObject(
            title = stringResource(R.string.hold_home_make_title),
            detail = stringResource(R.string.hold_home_make_body),
            action = stringResource(R.string.hold_make), onClick = onCreate, enabled = enabled, secondary = true,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp).testTag("create_action"),
        )

    }
}
