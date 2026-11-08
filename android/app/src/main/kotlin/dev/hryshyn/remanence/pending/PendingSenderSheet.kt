package dev.hryshyn.remanence.pending

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
/**
 * The phone side of "who's it for?": the same field, button and rhythm as a
 * username, then an explicit check of the number, since a typo sends a
 * postcard nobody can open.
 */
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
    var confirming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var badNumber by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
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
    val muted = dev.hryshyn.remanence.ui.hold.HoldColors.Muted

    @Composable
    fun Primary(text: String, tag: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
        Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(text, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            if (busy) CircularProgressIndicator(Modifier.size(18.dp).testTag("pending_sender_working"), strokeWidth = 2.dp)
            else Text("→", style = MaterialTheme.typography.titleLarge)
        }
    }

    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(modifier = modifier.fillMaxWidth().testTag("pending_sender_sheet")) {
            when (val current = state) {
                is PendingSenderViewModel.State.Idle,
                is PendingSenderViewModel.State.Failed,
                is PendingSenderViewModel.State.Freezing,
                is PendingSenderViewModel.State.Publishing,
                -> {
                    val working = current is PendingSenderViewModel.State.Freezing ||
                        current is PendingSenderViewModel.State.Publishing
                    val finalizationStopped = current is PendingSenderViewModel.State.Failed &&
                        current.message in setOf(PendingSenderViewModel.Message.SEND_LIMIT_REACHED,
                            PendingSenderViewModel.Message.SEND_ACCESS_UNAVAILABLE, PendingSenderViewModel.Message.FINALIZE_UNKNOWN)
                    if (confirming && current !is PendingSenderViewModel.State.Failed || working) {
                        // One look at the full number before anything is sent.
                        Text(stringResource(R.string.pending_sender_confirm_title), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(android.telephony.PhoneNumberUtils.formatNumber(destination, PendingPhoneInput.region(context)) ?: destination,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.testTag("pending_sender_confirm_number"))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.pending_sender_confirm_body), style = MaterialTheme.typography.bodyMedium, color = muted)
                        Spacer(Modifier.height(20.dp))
                        Primary(stringResource(R.string.pending_sender_confirm_send), "pending_sender_send", busy = working) { viewModel.send() }
                        HoldTextButton(onClick = { confirming = false }, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("pending_sender_change")) {
                            Text(stringResource(R.string.pending_sender_confirm_change))
                        }
                    } else {
                        Text(stringResource(R.string.pending_sender_phone_body), style = MaterialTheme.typography.bodyMedium, color = muted)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { viewModel.onDestinationChange(it); badNumber = false },
                            label = { Text(stringResource(R.string.pending_sender_phone_hint), style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.titleLarge,
                            singleLine = true, isError = badNumber,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("pending_sender_field"),
                        )
                        if (badNumber) Text(stringResource(R.string.pending_sender_invalid),
                            style = MaterialTheme.typography.bodySmall, color = dev.hryshyn.remanence.ui.hold.HoldColors.Error)
                        if (current is PendingSenderViewModel.State.Failed) {
                            Text(stringResource(current.message.stringRes()), style = MaterialTheme.typography.bodySmall,
                                color = dev.hryshyn.remanence.ui.hold.HoldColors.Error, modifier = Modifier.testTag("pending_sender_error"))
                            if (finalizationStopped) Text(stringResource(R.string.send_publication_pending_recovery),
                                style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        Spacer(Modifier.height(16.dp))
                        Primary(stringResource(R.string.pending_sender_next), "pending_sender_next",
                            enabled = destination.isNotBlank() && !finalizationStopped) {
                            val number = PendingPhoneInput.toE164(destination, PendingPhoneInput.region(context))
                            if (number == null) badNumber = true
                            else { viewModel.onDestinationChange(number); confirming = true }
                        }
                        HoldTextButton(
                            onClick = {
                                pickContact.launch(android.content.Intent(android.content.Intent.ACTION_PICK,
                                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                            },
                            modifier = Modifier.fillMaxWidth().testTag("pending_sender_contacts"),
                        ) { Text(stringResource(R.string.pending_sender_contacts)) }
                    }
                }
                is PendingSenderViewModel.State.KnownV1 -> {
                    Text(stringResource(R.string.pending_sender_known_v1), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("pending_sender_known_v1"))
                    Spacer(Modifier.height(16.dp))
                    Primary(stringResource(R.string.pending_sender_done), "pending_sender_done", onClick = onClose)
                }
                is PendingSenderViewModel.State.DeferredForAuthoring -> {
                    Text(stringResource(R.string.pending_sender_deferred), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("pending_sender_deferred"))
                    Spacer(Modifier.height(16.dp))
                    Primary(stringResource(R.string.pending_sender_done), "pending_sender_done", onClick = onClose)
                }
                is PendingSenderViewModel.State.Published -> {
                    Text(current.locatorCode, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("pending_sender_code"))
                    Spacer(Modifier.height(8.dp))
                    Text(current.hint, style = MaterialTheme.typography.bodySmall, color = muted,
                        modifier = Modifier.testTag("pending_sender_hint"))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pending_sender_code_note), style = MaterialTheme.typography.bodySmall, color = muted)
                    Spacer(Modifier.height(16.dp))
                    Primary(stringResource(R.string.pending_sender_done), "pending_sender_done", onClick = onClose)
                }
            }
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
