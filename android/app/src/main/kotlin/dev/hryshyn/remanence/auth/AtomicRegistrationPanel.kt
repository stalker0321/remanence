package dev.hryshyn.remanence.auth

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.recovery.AndroidRecoveryCredentials

/** No saved state for setup/keys. Only public handle text lives in this composition. */
@Composable
internal fun AtomicRegistrationPanel(model: AtomicRegistrationViewModel, view: AtomicView) {
    val activity = LocalContext.current as Activity
    val google = remember(activity) { AndroidGoogleCredentials(activity) }
    val passkeys = remember(activity) { AndroidRecoveryCredentials(activity) }
    var handle by remember { mutableStateOf("") }
    DisposableEffect(model) { onDispose { model.leave() } }
    val choosing = view.phase in setOf(AtomicPhase.HANDLE, AtomicPhase.HANDLE_CONFLICT, AtomicPhase.ASSERTION_REQUIRED, AtomicPhase.SAVING)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.atomic_registration_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(when (view.phase) {
            AtomicPhase.UNKNOWN -> R.string.atomic_registration_unknown
            AtomicPhase.RESTORE_REQUIRED -> R.string.atomic_registration_restore
            AtomicPhase.REAUTHENTICATE -> R.string.atomic_registration_reauthenticate
            AtomicPhase.TERMINAL -> R.string.atomic_registration_terminal
            AtomicPhase.READY -> R.string.atomic_registration_ready
            else -> R.string.atomic_registration_intro
        }))
        if (view.error != null) Text(stringResource(when (view.error) {
            "PRF_UNAVAILABLE" -> R.string.recovery_prf_missing
            "CREDENTIAL_PENDING" -> R.string.recovery_verification_pending
            "PROVIDER_CANCELLED" -> R.string.auth_passkey_cancelled
            "HANDLE_TAKEN", "HANDLE_INVALID" -> R.string.atomic_registration_handle_error
            "CLIENT_UPGRADE_REQUIRED" -> R.string.atomic_registration_upgrade
            "REGISTRATION_ADMISSION_CLOSED" -> R.string.atomic_registration_closed
            else -> R.string.auth_invalid_response
        }), color = MaterialTheme.colorScheme.error)
        if (choosing) {
            OutlinedTextField(handle, { if (it.length <= 31) handle = it }, enabled = !view.busy,
                label = { Text(stringResource(R.string.atomic_registration_handle)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !view.busy && Build.VERSION.SDK_INT >= 28, onClick = { model.save(handle, passkeys) }) {
                Text(stringResource(R.string.atomic_registration_save))
            }
            if (Build.VERSION.SDK_INT < 28) Text(stringResource(R.string.recovery_prf_missing))
        } else if (view.phase == AtomicPhase.TERMINAL) {
            Button(enabled = !view.busy, onClick = model::restart) { Text(stringResource(R.string.atomic_registration_restart)) }
        } else if (!view.sessionEstablished) {
            Button(enabled = !view.busy, onClick = { model.begin(google) }) { Text(stringResource(R.string.atomic_registration_google)) }
        }
        if (view.phase == AtomicPhase.UNKNOWN) {
            OutlinedButton(enabled = !view.busy, onClick = model::resolve) { Text(stringResource(R.string.atomic_registration_check)) }
            OutlinedButton(enabled = !view.busy, onClick = model::retry) { Text(stringResource(R.string.atomic_registration_retry)) }
        }
        if (choosing && !view.busy) OutlinedButton(onClick = model::cancelReservation) {
            Text(stringResource(R.string.atomic_registration_cancel))
        }
        if (view.busy) CircularProgressIndicator()
        Text(stringResource(R.string.atomic_registration_leave_hint))
        TextButton(onClick = model::leave) { Text(stringResource(R.string.atomic_registration_leave)) }
    }
}
