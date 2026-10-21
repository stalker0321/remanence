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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.recovery.AndroidRecoveryCredentials
import dev.hryshyn.remanence.ui.auth.CredentialFailure

/** Shared by the real panel and focused resource/render tests; no provider text. */
internal fun atomicRegistrationErrorMessage(reason: String): Int = when (reason) {
    "PRF_UNAVAILABLE" -> R.string.atomic_registration_prf_unavailable
    "CREDENTIAL_PENDING" -> R.string.recovery_verification_pending
    "HANDLE_TAKEN", "HANDLE_INVALID" -> R.string.atomic_registration_handle_error
    "CLIENT_UPGRADE_REQUIRED" -> R.string.atomic_registration_upgrade
    "REGISTRATION_ADMISSION_CLOSED" -> R.string.atomic_registration_closed
    else -> CredentialFailure.entries.firstOrNull { it.reason == reason }?.message
        ?: R.string.atomic_registration_failed
}

/** No saved state for setup/keys. Only public handle text lives in this composition. */
@Composable
internal fun AtomicRegistrationPanel(model: AtomicRegistrationViewModel, view: AtomicView, recoveryBusy: Boolean,
    recoveryContent: @Composable (enabled: Boolean, beforeSignIn: () -> Unit) -> Unit) {
    val activity = LocalContext.current as Activity
    val google = remember(activity) { AndroidGoogleCredentials(activity) }
    val passkeys = remember(activity) { AndroidRecoveryCredentials(activity) }
    var handle by remember { mutableStateOf("") }
    DisposableEffect(model) { onDispose { model.leave() } }
    AtomicRegistrationContent(view, handle, { if (it.length <= 31) handle = it },
        { model.begin(google) }, { model.save(handle, passkeys) }, model::restart, model::resolve,
        model::retry, model::cancelReservation, model::leave, recoveryBusy, recoveryContent)
}

/** Same signed-out entry used by RootSurface and focused render/interaction tests.
 * Existing passkey sign-in is independent of Google and never exposes legacy registration. */
@Composable
internal fun AtomicRegistrationContent(view: AtomicView, handle: String, onHandleChange: (String) -> Unit,
    onBegin: () -> Unit, onSave: () -> Unit, onRestart: () -> Unit, onResolve: () -> Unit,
    onRetry: () -> Unit, onCancel: () -> Unit, onLeave: () -> Unit, recoveryBusy: Boolean,
    recoveryContent: @Composable (enabled: Boolean, beforeSignIn: () -> Unit) -> Unit) {
    val enabled = !view.busy && !recoveryBusy
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
        if (view.error != null) Text(stringResource(atomicRegistrationErrorMessage(view.error)),
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("atomic_error"))
        if (choosing) {
            OutlinedTextField(handle, onHandleChange, enabled = enabled,
                label = { Text(stringResource(R.string.atomic_registration_handle)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = enabled && Build.VERSION.SDK_INT >= 28, onClick = onSave) {
                Text(stringResource(R.string.atomic_registration_save))
            }
            if (Build.VERSION.SDK_INT < 28) Text(stringResource(R.string.auth_credential_unavailable))
        } else if (view.phase == AtomicPhase.TERMINAL) {
            Button(enabled = enabled, onClick = onRestart) { Text(stringResource(R.string.atomic_registration_restart)) }
        } else if (!view.sessionEstablished) {
            Button(enabled = enabled, onClick = onBegin, modifier = Modifier.testTag("atomic_google")) { Text(stringResource(R.string.atomic_registration_google)) }
        }
        if (view.phase == AtomicPhase.UNKNOWN) {
            OutlinedButton(enabled = enabled, onClick = onResolve) { Text(stringResource(R.string.atomic_registration_check)) }
            OutlinedButton(enabled = enabled, onClick = onRetry) { Text(stringResource(R.string.atomic_registration_retry)) }
        }
        if (choosing && enabled) OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.atomic_registration_cancel))
        }
        if (view.busy) CircularProgressIndicator()
        Text(stringResource(R.string.atomic_registration_leave_hint))
        TextButton(enabled = !recoveryBusy, onClick = onLeave, modifier = Modifier.testTag("atomic_leave")) { Text(stringResource(R.string.atomic_registration_leave)) }
        // Keep this at one composition site while its provider request is busy.
        recoveryContent(!view.busy, onLeave)
    }
}
