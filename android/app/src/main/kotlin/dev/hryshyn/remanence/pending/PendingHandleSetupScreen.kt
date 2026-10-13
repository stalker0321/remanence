package dev.hryshyn.remanence.pending

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

/**
 * T186 M7a: first-send handle form (frozen R17). Real commit through
 * [PendingHandleSetupViewModel]: one-time immutable handle on the SAME
 * account (UUID/keys/tokens preserved, no registration regen). All
 * failures are generic and redacted; the form keeps its input for retry.
 * Visuals/motion follow the Hold surfaces.
 */
@Composable
fun PendingHandleSetupScreen(
    viewModel: PendingHandleSetupViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Test seam: production passes the build-time gate (default). */
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    val field by viewModel.field.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val working = state is PendingHandleSetupViewModel.SetupState.Working
    val failed = state as? PendingHandleSetupViewModel.SetupState.Failed

    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("pending_handle_setup"),
        ) {
            Text(
                stringResource(R.string.pending_handle_setup_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pending_handle_setup_body),
                style = MaterialTheme.typography.bodySmall,
                color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = field,
                onValueChange = viewModel::onFieldChange,
                label = { Text(stringResource(R.string.pending_handle_setup_label)) },
                singleLine = true,
                enabled = !working,
                isError = failed?.message ==
                    PendingHandleSetupViewModel.PendingHandleSetupMessage.INVALID_HANDLE,
                modifier = Modifier.fillMaxWidth().testTag("pending_handle_setup_field"),
            )
            if (failed != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(failed.message.stringRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("pending_handle_setup_error"),
                )
            }
            if (state is PendingHandleSetupViewModel.SetupState.Committed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pending_handle_setup_committed),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pending_handle_setup_committed"),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::save,
                enabled = !working && field.isNotBlank(),
                modifier = Modifier.testTag("pending_handle_setup_continue"),
            ) { Text(stringResource(R.string.pending_handle_setup_continue)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBack,
                enabled = !working,
                modifier = Modifier.testTag("pending_handle_setup_back"),
            ) { Text(stringResource(R.string.pending_handle_setup_back)) }
        }
    }
}

private fun PendingHandleSetupViewModel.PendingHandleSetupMessage.stringRes(): Int =
    when (this) {
        PendingHandleSetupViewModel.PendingHandleSetupMessage.INVALID_HANDLE ->
            R.string.pending_handle_setup_invalid
        PendingHandleSetupViewModel.PendingHandleSetupMessage.CONFLICT ->
            R.string.pending_handle_setup_conflict
        PendingHandleSetupViewModel.PendingHandleSetupMessage.NETWORK ->
            R.string.pending_handle_setup_network
        PendingHandleSetupViewModel.PendingHandleSetupMessage.SESSION_CHANGED ->
            R.string.pending_handle_setup_session
        PendingHandleSetupViewModel.PendingHandleSetupMessage.GENERIC ->
            R.string.pending_handle_setup_generic
    }
