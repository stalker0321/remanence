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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

/**
 * T186 M12: v2 sender sheet (isolated EMAIL/PHONE destination → R1
 * freeze → R3/R4/R5 publication → handwritten 5-char code). Real
 * states from [PendingSenderViewModel]; failures stay generic, the
 * destination is preserved for retry. A known-v1 freeze hands the
 * frozen identity to the existing zero-escrow pipeline (no code, no
 * escrow, no fallback). Visuals/motion follow the Hold surfaces.
 */
@Composable
fun PendingSenderEntryButton(
    onOpenSender: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    HoldTextButton(
        onClick = onOpenSender,
        modifier = modifier.testTag("pending_sender_entry"),
    ) {
        Text(stringResource(R.string.pending_sender_entry))
    }
}

@Composable
fun PendingSenderSheet(
    viewModel: PendingSenderViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // The system contact picker hands back one number and needs no permission.
    val pickContact = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val column = android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) viewModel.onDestinationChange(cursor.getString(0).orEmpty())
        }
    }

    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("pending_sender_sheet"),
        ) {
            Text(
                stringResource(R.string.pending_sender_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pending_sender_body),
                style = MaterialTheme.typography.bodySmall,
                color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
            )
            Spacer(Modifier.height(12.dp))
            when (val current = state) {
                is PendingSenderViewModel.State.Idle,
                is PendingSenderViewModel.State.Failed,
                -> {
                    if (current is PendingSenderViewModel.State.Failed) {
                        Text(
                            stringResource(current.message.stringRes()),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("pending_sender_error"),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    val finalizationStopped = current is PendingSenderViewModel.State.Failed &&
                        current.message in setOf(PendingSenderViewModel.Message.SEND_LIMIT_REACHED,
                            PendingSenderViewModel.Message.SEND_ACCESS_UNAVAILABLE, PendingSenderViewModel.Message.FINALIZE_UNKNOWN)
                    if (finalizationStopped) Text(stringResource(R.string.send_publication_pending_recovery))
                    OutlinedTextField(
                        value = destination,
                        onValueChange = viewModel::onDestinationChange,
                        label = { Text(stringResource(R.string.pending_sender_phone_hint)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("pending_sender_field"),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            pickContact.launch(
                                android.content.Intent(
                                    android.content.Intent.ACTION_PICK,
                                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                ),
                            )
                        },
                        modifier = Modifier.testTag("pending_sender_contacts"),
                    ) { Text(stringResource(R.string.pending_sender_contacts)) }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val number = PendingPhoneInput.toE164(destination, PendingPhoneInput.region(context))
                            if (number != null) viewModel.onDestinationChange(number)
                            viewModel.send()
                        },
                        enabled = !finalizationStopped,
                        modifier = Modifier.testTag("pending_sender_send"),
                    ) { Text(stringResource(R.string.pending_sender_send)) }
                }
                is PendingSenderViewModel.State.Freezing,
                is PendingSenderViewModel.State.Publishing,
                -> {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.testTag("pending_sender_working"))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pending_sender_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                    )
                }
                is PendingSenderViewModel.State.KnownV1 -> {
                    Text(
                        stringResource(R.string.pending_sender_known_v1),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("pending_sender_known_v1"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onClose,
                        modifier = Modifier.testTag("pending_sender_done"),
                    ) { Text(stringResource(R.string.pending_sender_done)) }
                }
                is PendingSenderViewModel.State.DeferredForAuthoring -> {
                    Text(
                        stringResource(R.string.pending_sender_deferred),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("pending_sender_deferred"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onClose,
                        modifier = Modifier.testTag("pending_sender_done"),
                    ) { Text(stringResource(R.string.pending_sender_done)) }
                }
                is PendingSenderViewModel.State.Published -> {
                    Text(
                        current.locatorCode,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("pending_sender_code"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        current.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                        modifier = Modifier.testTag("pending_sender_hint"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pending_sender_code_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onClose,
                        modifier = Modifier.testTag("pending_sender_done"),
                    ) { Text(stringResource(R.string.pending_sender_done)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.testTag("pending_sender_cancel"),
            ) { Text(stringResource(R.string.pending_sender_cancel)) }
        }
    }
}

private fun PendingSenderViewModel.Message.stringRes(): Int = when (this) {
    PendingSenderViewModel.Message.SEND_LIMIT_REACHED -> R.string.send_publication_limit
    PendingSenderViewModel.Message.SEND_ACCESS_UNAVAILABLE -> R.string.send_publication_unavailable
    PendingSenderViewModel.Message.FINALIZE_UNKNOWN -> R.string.send_publication_unknown
    PendingSenderViewModel.Message.NETWORK -> R.string.pending_sender_network
    PendingSenderViewModel.Message.SESSION_CHANGED -> R.string.pending_sender_session
    PendingSenderViewModel.Message.CONFLICT -> R.string.pending_sender_conflict
    PendingSenderViewModel.Message.BLOCKED -> R.string.pending_sender_blocked
    PendingSenderViewModel.Message.RECOVERY_REQUIRED -> R.string.pending_sender_recovery
    PendingSenderViewModel.Message.SERVICE_UNAVAILABLE -> R.string.pending_sender_service
    PendingSenderViewModel.Message.VALIDATION -> R.string.pending_sender_invalid
    PendingSenderViewModel.Message.GENERIC -> R.string.pending_sender_generic
}
