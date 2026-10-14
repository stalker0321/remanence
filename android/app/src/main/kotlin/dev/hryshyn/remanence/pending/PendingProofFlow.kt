package dev.hryshyn.remanence.pending

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * T186 M7c: receiving proof + claim flow UI (frozen R7/R8/R9/R10).
 * Real states from [PendingProofViewModel]; every failure is generic and
 * redacted, inputs are preserved for retry. A verified, signed intent
 * ends at [PendingProofViewModel.ProofState.IntentReady] — R11 submit
 * waits the session-id handoff (explicit block, disclosed in-app).
 * Visuals/motion follow the Hold surfaces.
 */
@Composable
fun PendingProofFlow(
    viewModel: PendingProofViewModel,
    giftReference: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val code by viewModel.code.collectAsStateWithLifecycle()

    LaunchedEffect(giftReference) { viewModel.start(giftReference) }

    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("pending_proof_flow"),
        ) {
            when (val current = state) {
                PendingProofViewModel.ProofState.Idle,
                PendingProofViewModel.ProofState.Continuing,
                PendingProofViewModel.ProofState.RequestingAttempt,
                PendingProofViewModel.ProofState.CheckingCode,
                PendingProofViewModel.ProofState.LoadingOptions,
                -> {
                    CircularProgressIndicator(modifier = Modifier.testTag("pending_proof_working"))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pending_proof_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                    )
                }
                is PendingProofViewModel.ProofState.SyncReady -> {
                    Text(
                        stringResource(R.string.pending_proof_sync_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("pending_proof_sync"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDone,
                        modifier = Modifier.testTag("pending_proof_done"),
                    ) { Text(stringResource(R.string.pending_proof_done)) }
                }
                is PendingProofViewModel.ProofState.ProofRequired -> {
                    Text(
                        stringResource(R.string.pending_proof_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pending_proof_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = destination,
                        onValueChange = viewModel::onDestinationChange,
                        label = { Text(stringResource(R.string.pending_proof_destination)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pending_proof_destination"),
                    )
                    // T186 alias-bind: explicit opt-in to save the
                    // verified address to this account. Default
                    // unchecked, never derived, independent of SMS
                    // consent. Frozen with the destination at request
                    // time; not editable afterwards.
                    val aliasConsent by viewModel.aliasConsent.collectAsStateWithLifecycle()
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = aliasConsent,
                            onCheckedChange = viewModel::onAliasConsentChange,
                            // No deceptive enabled look while the setter
                            // must ignore taps (blank destination can
                            // carry no origin).
                            enabled = destination.isNotBlank(),
                            modifier = Modifier.testTag("pending_proof_bind_alias"),
                        )
                        Text(
                            stringResource(R.string.pending_proof_bind_alias),
                            style = MaterialTheme.typography.bodySmall,
                            color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                        )
                    }
                    // T186 M10: explicit affirmative SMS consent, phone
                    // channels only — never derived from the channel. The
                    // timestamp is captured at request time in the VM.
                    val smsConsent by viewModel.smsConsent.collectAsStateWithLifecycle()
                    if (!current.channel.equals("email", ignoreCase = true)) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = smsConsent,
                                onCheckedChange = viewModel::onSmsConsentChange,
                                modifier = Modifier.testTag("pending_proof_sms_consent"),
                            )
                            Text(
                                stringResource(R.string.pending_proof_sms_consent),
                                style = MaterialTheme.typography.bodySmall,
                                color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::requestAttempt,
                        enabled = destination.isNotBlank(),
                        modifier = Modifier.testTag("pending_proof_send_code"),
                    ) { Text(stringResource(R.string.pending_proof_send_code)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.testTag("pending_proof_cancel"),
                    ) { Text(stringResource(R.string.pending_proof_cancel)) }
                }
                is PendingProofViewModel.ProofState.CodeRequired -> {
                    Text(
                        stringResource(R.string.pending_proof_code_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = viewModel::onCodeChange,
                        label = { Text(stringResource(R.string.pending_proof_code_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pending_proof_code"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::checkCode,
                        enabled = code.isNotBlank(),
                        modifier = Modifier.testTag("pending_proof_verify"),
                    ) { Text(stringResource(R.string.pending_proof_verify)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.testTag("pending_proof_cancel"),
                    ) { Text(stringResource(R.string.pending_proof_cancel)) }
                }
                is PendingProofViewModel.ProofState.IntentReady -> {
                    Text(
                        stringResource(R.string.pending_proof_intent_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pending_proof_intent_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                        modifier = Modifier.testTag("pending_proof_intent"),
                    )
                    // T186 alias-bind: noneditable summary of the frozen
                    // choice carried by the signed intent (read-only —
                    // the toggle lives only on ProofRequired).
                    val intentAlias by viewModel.aliasConsent.collectAsStateWithLifecycle()
                    if (intentAlias) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pending_proof_intent_alias),
                            style = MaterialTheme.typography.bodySmall,
                            color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                            modifier = Modifier.testTag("pending_proof_intent_alias"),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::submitClaim,
                        modifier = Modifier.testTag("pending_proof_claim"),
                    ) { Text(stringResource(R.string.pending_proof_claim)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.testTag("pending_proof_cancel"),
                    ) { Text(stringResource(R.string.pending_proof_cancel)) }
                }
                is PendingProofViewModel.ProofState.Claiming -> {
                    CircularProgressIndicator(modifier = Modifier.testTag("pending_proof_working"))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pending_proof_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                    )
                }
                is PendingProofViewModel.ProofState.Claimed -> {
                    Text(
                        stringResource(R.string.pending_proof_claimed_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            if (current.serviceVerified) {
                                R.string.pending_proof_claimed_body
                            } else {
                                R.string.pending_proof_claimed_partial
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                        modifier = Modifier.testTag("pending_proof_claimed"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDone,
                        modifier = Modifier.testTag("pending_proof_done"),
                    ) { Text(stringResource(R.string.pending_proof_done)) }
                }
                is PendingProofViewModel.ProofState.Failed -> {
                    Text(
                        stringResource(current.message.stringRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("pending_proof_error"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::retry,
                        modifier = Modifier.testTag("pending_proof_retry"),
                    ) { Text(stringResource(R.string.pending_proof_retry)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.testTag("pending_proof_cancel"),
                    ) { Text(stringResource(R.string.pending_proof_cancel)) }
                }
            }
        }
    }
}

private fun PendingProofViewModel.PendingProofMessage.stringRes(): Int =
    when (this) {
        PendingProofViewModel.PendingProofMessage.NETWORK -> R.string.pending_proof_network
        PendingProofViewModel.PendingProofMessage.NOT_FOUND -> R.string.pending_proof_notfound
        PendingProofViewModel.PendingProofMessage.SESSION_CHANGED -> R.string.pending_proof_session
        PendingProofViewModel.PendingProofMessage.EXPIRED -> R.string.pending_proof_expired
        PendingProofViewModel.PendingProofMessage.CONFLICT -> R.string.pending_proof_conflict
        PendingProofViewModel.PendingProofMessage.BLOCKED -> R.string.pending_proof_blocked
        PendingProofViewModel.PendingProofMessage.GENERIC -> R.string.pending_proof_generic
    }
