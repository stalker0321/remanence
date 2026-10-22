package dev.hryshyn.remanence.auth

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.recovery.AndroidRecoveryCredentials
import dev.hryshyn.remanence.ui.auth.*
import dev.hryshyn.remanence.ui.hold.*

internal fun atomicRegistrationErrorMessage(reason: String): Int = when (reason) {
    "PRF_UNAVAILABLE" -> R.string.atomic_registration_prf_unavailable
    "CREDENTIAL_PENDING" -> R.string.recovery_verification_pending
    "HANDLE_TAKEN", "HANDLE_INVALID" -> R.string.atomic_registration_handle_error
    "CLIENT_UPGRADE_REQUIRED" -> R.string.atomic_registration_upgrade
    "REGISTRATION_ADMISSION_CLOSED" -> R.string.atomic_registration_closed
    else -> CredentialFailure.entries.firstOrNull { it.reason == reason }?.message
        ?: R.string.atomic_registration_failed
}

/** Public handle only; setup capabilities and private identity remain in the controller. */
@Composable
internal fun AtomicRegistrationPanel(model: AtomicRegistrationViewModel, view: AtomicView, recoveryBusy: Boolean,
    recoveryContent: @Composable (enabled: Boolean, beforeSignIn: () -> Unit) -> Unit) {
    val activity = LocalContext.current as Activity
    val google = remember(activity) { AndroidGoogleCredentials(activity) }
    val passkeys = remember(activity) { AndroidRecoveryCredentials(activity) }
    var handle by remember { mutableStateOf(view.handle) }
    LaunchedEffect(view.phase) { if (view.phase == AtomicPhase.GOOGLE) handle = "" }
    DisposableEffect(model) { onDispose { model.leave() } }
    AtomicRegistrationContent(view, handle, { if (it.length <= 31) handle = it },
        { model.begin(google) }, { model.save(view.handle, passkeys) }, model::restart, model::resolve,
        model::retry, model::cancelReservation, model::leave, recoveryBusy,
        onChooseHandle = { model.chooseHandle(handle) },
        onVerify = { model.verify(view.handle.ifEmpty { handle }, passkeys) },
        recoveryContent = recoveryContent)
}

/** Each provider operation has its own intentional screen and user action. */
@Composable
internal fun AtomicRegistrationContent(view: AtomicView, handle: String, onHandleChange: (String) -> Unit,
    onBegin: () -> Unit, onSave: () -> Unit, onRestart: () -> Unit, onResolve: () -> Unit,
    onRetry: () -> Unit, onCancel: () -> Unit, onLeave: () -> Unit, recoveryBusy: Boolean,
    onChooseHandle: () -> Unit = {}, onVerify: () -> Unit = {},
    recoveryContent: @Composable (enabled: Boolean, beforeSignIn: () -> Unit) -> Unit) {
    val enabled = !view.busy && !recoveryBusy
    var existing by remember { mutableStateOf(false) }
    val restore = existing || view.phase == AtomicPhase.RESTORE_REQUIRED
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(existing && !recoveryBusy) { existing = false }
    val step = when (view.phase) {
        AtomicPhase.HANDLE, AtomicPhase.HANDLE_CONFLICT -> 2
        AtomicPhase.CREATE_REQUIRED, AtomicPhase.CREATING, AtomicPhase.CREDENTIAL_UNKNOWN -> 3
        AtomicPhase.VERIFY_REQUIRED, AtomicPhase.VERIFYING, AtomicPhase.ASSERTION_REQUIRED,
        AtomicPhase.SAVING, AtomicPhase.UNKNOWN, AtomicPhase.READY -> 4
        else -> 1
    }
    val title = when {
        restore -> R.string.rework_signin_title
        step == 2 -> R.string.mvp_handle_title
        view.phase == AtomicPhase.CREDENTIAL_UNKNOWN -> R.string.rework_creation_unknown_title
        step == 3 -> R.string.rework_create_key_title
        view.phase == AtomicPhase.UNKNOWN -> R.string.rework_finishing_title
        step == 4 -> R.string.rework_verify_title
        else -> R.string.rework_welcome_title
    }
    val detail = when {
        restore -> R.string.rework_signin_detail
        step == 2 -> R.string.rework_handle_detail
        view.phase == AtomicPhase.CREDENTIAL_UNKNOWN -> R.string.rework_creation_unknown_detail
        step == 3 -> R.string.rework_create_key_detail
        view.phase == AtomicPhase.UNKNOWN -> R.string.atomic_registration_unknown
        step == 4 -> R.string.rework_verify_detail
        else -> R.string.rework_welcome_detail
    }
    val scroll = rememberScrollState()
    LaunchedEffect(step, restore) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = HoldSpace.Section),
        verticalArrangement = Arrangement.spacedBy(HoldSpace.Section)) {
        Column(verticalArrangement = Arrangement.spacedBy(HoldSpace.Related)) {
            Text("remanence", style = MaterialTheme.typography.titleMedium, color = HoldColors.Muted)
            if (!restore) Text(stringResource(R.string.rework_setup_step, step, 4),
                style = MaterialTheme.typography.labelSmall, color = HoldColors.Muted,
                modifier = Modifier.testTag("atomic_step"))
            AccessHeading(stringResource(title), stringResource(detail))
        }
        if (restore) {
            // Stable composition site for the lifetime of the sign-in operation.
            recoveryContent(!view.busy, onLeave)
            if (existing) HoldTextButton(enabled = enabled, onClick = { existing = false },
                modifier = Modifier.testTag("atomic_new_account")) { Text(stringResource(R.string.rework_new_account)) }
        } else {
            if (view.error != null) Text(stringResource(atomicRegistrationErrorMessage(view.error)),
                color = HoldColors.Error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("atomic_error").semantics { liveRegion = LiveRegionMode.Polite })
            when (view.phase) {
                AtomicPhase.HANDLE, AtomicPhase.HANDLE_CONFLICT -> {
                    val valid = handle.isNotBlank() && RegistrationFormValidator.handleError(handle) == null
                    Column(verticalArrangement = Arrangement.spacedBy(HoldSpace.Related)) {
                        HoldInput(handle, onHandleChange, enabled = enabled, singleLine = true,
                            label = { Text(stringResource(R.string.mvp_handle_label)) },
                            modifier = Modifier.fillMaxWidth().testTag("atomic_handle"))
                        Text(stringResource(R.string.auth_handle_rule), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                        Text(stringResource(R.string.mvp_handle_permanent), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                    }
                    HoldButton(enabled = enabled && valid, onClick = { keyboard?.hide(); onChooseHandle() },
                        modifier = Modifier.fillMaxWidth().testTag("atomic_handle_continue")) { Text(stringResource(R.string.mvp_continue)) }
                }
                AtomicPhase.CREATE_REQUIRED, AtomicPhase.CREATING -> {
                    KeyStepNote(view.handle, R.string.rework_key_create_note)
                    HoldButton(enabled = enabled && Build.VERSION.SDK_INT >= 28, onClick = onSave,
                        modifier = Modifier.fillMaxWidth().testTag("atomic_create")) { Text(stringResource(R.string.rework_create_key)) }
                    if (Build.VERSION.SDK_INT < 28) Text(stringResource(R.string.auth_credential_unavailable))
                }
                AtomicPhase.VERIFY_REQUIRED, AtomicPhase.VERIFYING, AtomicPhase.ASSERTION_REQUIRED, AtomicPhase.SAVING -> {
                    KeyStepNote(view.handle, R.string.rework_key_saved)
                    HoldButton(enabled = enabled, onClick = onVerify,
                        modifier = Modifier.fillMaxWidth().testTag("atomic_verify")) { Text(stringResource(R.string.rework_verify_key)) }
                    Text(stringResource(R.string.rework_verify_retry), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                }
                AtomicPhase.CREDENTIAL_UNKNOWN -> {
                    HoldButton(enabled = enabled, onClick = onResolve, modifier = Modifier.fillMaxWidth().testTag("atomic_check_creation")) {
                        Text(stringResource(R.string.atomic_registration_check))
                    }
                }
                AtomicPhase.UNKNOWN -> {
                    HoldButton(enabled = enabled, onClick = onResolve, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.atomic_registration_check)) }
                    HoldTextButton(enabled = enabled, onClick = onRetry) { Text(stringResource(R.string.atomic_registration_retry)) }
                }
                AtomicPhase.TERMINAL -> {
                    Text(stringResource(R.string.atomic_registration_terminal), style = MaterialTheme.typography.bodyMedium)
                    HoldButton(enabled = enabled, onClick = onRestart) { Text(stringResource(R.string.atomic_registration_restart)) }
                }
                AtomicPhase.READY, AtomicPhase.EXISTING, AtomicPhase.RESTORE_REQUIRED -> Unit
                else -> {
                    if (view.phase == AtomicPhase.REAUTHENTICATE) Text(stringResource(R.string.atomic_registration_reauthenticate))
                    Box(Modifier.testTag("atomic_google")) { GoogleSignInButton(onClick = onBegin, enabled = enabled, busy = view.busy) }
                    HoldTextButton(enabled = enabled, onClick = { onLeave(); existing = true },
                        modifier = Modifier.fillMaxWidth().testTag("atomic_existing")) { Text(stringResource(R.string.rework_existing_account)) }
                }
            }
            if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("atomic_busy"))
        }
    }
}

@Composable
private fun KeyStepNote(handle: String, detail: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HoldSpace.Related)) {
        if (handle.isNotEmpty()) Text("\u2068@$handle\u2069", style = MaterialTheme.typography.titleMedium, color = HoldColors.Ink)
        Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted,
            modifier = Modifier.testTag("atomic_key_status").semantics { liveRegion = LiveRegionMode.Polite })
    }
}
