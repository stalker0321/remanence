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
import dev.hryshyn.remanence.ui.motion.AstraStepArrival
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

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
    existingAccount: Boolean, onExistingAccount: (Boolean) -> Unit,
    onExitSetup: () -> Unit,
    recoveryContent: @Composable (enabled: Boolean, beforeSignIn: () -> Unit) -> Unit) {
    val activity = LocalContext.current as Activity
    val google = remember(activity) { AndroidGoogleCredentials(activity) }
    val passkeys = remember(activity) { AndroidRecoveryCredentials(activity) }
    val handle = view.handle
    // Rotation keeps the ViewModel ceremony. Explicit exits and onCleared retire its owner.
    AtomicRegistrationContent(view, handle, model::editHandle,
        { model.begin(google) }, { model.save(view.handle, passkeys) }, model::restart, model::resolve,
        model::retry, model::cancelReservation, model::leave, recoveryBusy,
        onChooseHandle = { model.chooseHandle(handle) },
        onVerify = { model.verify(view.handle.ifEmpty { handle }, passkeys) },
        onExitSetup = onExitSetup,
        existingAccount = existingAccount, onExistingAccount = onExistingAccount,
        recoveryContent = recoveryContent)
}

/** Each provider operation has its own intentional screen and user action. */
@Composable
internal fun AtomicRegistrationContent(view: AtomicView, handle: String, onHandleChange: (String) -> Unit,
    onBegin: () -> Unit, onSave: () -> Unit, onRestart: () -> Unit, onResolve: () -> Unit,
    onRetry: () -> Unit, onCancel: () -> Unit, onLeave: () -> Unit, recoveryBusy: Boolean,
    onChooseHandle: () -> Unit = {}, onVerify: () -> Unit = {},
    onExitSetup: () -> Unit = onLeave,
    existingAccount: Boolean? = null, onExistingAccount: ((Boolean) -> Unit)? = null,
    recoveryContent: @Composable (enabled: Boolean, beforeSignIn: () -> Unit) -> Unit) {
    val enabled = !view.busy && !recoveryBusy
    var localExisting by remember { mutableStateOf(false) }
    val existing = existingAccount ?: localExisting
    fun showExisting(value: Boolean) { if (onExistingAccount != null) onExistingAccount(value) else localExisting = value }
    val restore = existing || view.phase == AtomicPhase.RESTORE_REQUIRED
    val capabilityBlocked = view.error == "PRF_UNAVAILABLE"
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(existing && !recoveryBusy) { showExisting(false) }
    val step = when (view.phase) {
        AtomicPhase.HANDLE, AtomicPhase.HANDLE_CONFLICT -> 2
        AtomicPhase.CREATE_REQUIRED, AtomicPhase.CREATING, AtomicPhase.CREDENTIAL_UNKNOWN -> 3
        AtomicPhase.VERIFY_REQUIRED, AtomicPhase.VERIFYING, AtomicPhase.ASSERTION_REQUIRED,
        AtomicPhase.SAVING, AtomicPhase.UNKNOWN, AtomicPhase.READY -> 4
        else -> 1
    }
    val title = when {
        restore -> R.string.rework_signin_title
        capabilityBlocked -> R.string.rework_capability_title
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
    LaunchedEffect(step, restore, capabilityBlocked) { scroll.scrollTo(0) }
    // Busy/error ticks do not replay the page. Provider operations remain at
    // the same composition site while intentional semantic steps arrive.
    AstraStepArrival(rememberAstraMotion(), Triple(step, restore, capabilityBlocked)) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = HoldSpace.Section),
        verticalArrangement = Arrangement.spacedBy(HoldSpace.Section)) {
        Column(verticalArrangement = Arrangement.spacedBy(HoldSpace.Related)) {
            Text("remanence", style = MaterialTheme.typography.titleMedium, color = HoldColors.Muted)
            if (!restore) Text(stringResource(R.string.rework_setup_step, step, 4),
                style = MaterialTheme.typography.labelSmall, color = HoldColors.Muted,
                modifier = Modifier.testTag("atomic_step"))
            AccessHeading(stringResource(title), if (capabilityBlocked) null else stringResource(detail))
        }
        if (restore) {
            // Stable composition site for the lifetime of the sign-in operation.
            recoveryContent(!view.busy, onLeave)
            if (existing) HoldTextButton(enabled = enabled, onClick = { showExisting(false) },
                modifier = Modifier.testTag("atomic_new_account")) { Text(stringResource(R.string.rework_new_account)) }
        } else {
            if (view.error != null) Text(stringResource(atomicRegistrationErrorMessage(view.error)),
                color = HoldColors.Error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("atomic_error").semantics { liveRegion = LiveRegionMode.Polite })
            if (capabilityBlocked) {
                HoldButton(enabled = enabled, onClick = onExitSetup,
                    modifier = Modifier.fillMaxWidth().testTag("atomic_leave_setup")) {
                    Text(stringResource(R.string.atomic_registration_leave))
                }
                HoldTextButton(enabled = enabled, onClick = onVerify,
                    modifier = Modifier.fillMaxWidth().testTag("atomic_verify")) {
                    Text(stringResource(R.string.rework_verify_key))
                }
            } else when (view.phase) {
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
                    KeyIdentity(view.handle)
                    HoldButton(enabled = enabled && Build.VERSION.SDK_INT >= 28, onClick = onSave,
                        modifier = Modifier.fillMaxWidth().testTag("atomic_create")) { Text(stringResource(R.string.rework_create_key)) }
                    if (Build.VERSION.SDK_INT < 28) Text(stringResource(R.string.auth_credential_unavailable))
                }
                AtomicPhase.VERIFY_REQUIRED, AtomicPhase.VERIFYING, AtomicPhase.ASSERTION_REQUIRED, AtomicPhase.SAVING -> {
                    KeyIdentity(view.handle)
                    HoldButton(enabled = enabled, onClick = onVerify,
                        modifier = Modifier.fillMaxWidth().testTag("atomic_verify")) { Text(stringResource(R.string.rework_verify_key)) }
                    if (view.error != null) Text(stringResource(R.string.rework_verify_retry),
                        style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
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
                    HoldTextButton(enabled = enabled, onClick = { onLeave(); showExisting(true) },
                        modifier = Modifier.fillMaxWidth().testTag("atomic_existing")) { Text(stringResource(R.string.rework_existing_account)) }
                }
            }
            if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("atomic_busy"))
        }
    }
    }
}

@Composable
private fun KeyIdentity(handle: String) {
    if (handle.isNotEmpty()) Text("\u2068@$handle\u2069", style = MaterialTheme.typography.titleMedium, color = HoldColors.Ink,
        modifier = Modifier.testTag("atomic_key_status"))
}

/** Shared with the root and its rendered journey tests. Leaving never deletes a provider key. */
@Composable
internal fun AtomicLeaveDialog(onDismiss: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rework_leave_title)) },
        text = { Text(stringResource(R.string.rework_leave_detail), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { HoldTextButton(onClick = onLeave, modifier = Modifier.testTag("atomic_leave_confirm")) {
            Text(stringResource(R.string.atomic_registration_leave))
        } },
        dismissButton = { HoldTextButton(onClick = onDismiss, modifier = Modifier.testTag("atomic_leave_cancel")) {
            Text(stringResource(R.string.rework_keep_setup))
        } })
}
