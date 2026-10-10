package dev.hryshyn.remanence.recovery

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Opt-in debug surface. No saved-state secret, provider text, clipboard or logs. */
@Composable
fun RecoveryActions(container: AppContainer, hasIdentity: Boolean, recoveryRequired: Boolean,
                    onRecovered: () -> Unit, onBusyChange: (Boolean) -> Unit = {}, mandatorySetup: Boolean = false) {
    if (!BuildConfig.REMANENCE_RECOVERY_ENABLED) return
    val passkeysAvailable = android.os.Build.VERSION.SDK_INT >= 28
    val activity = LocalContext.current as Activity
    val credentials = remember(activity) { AndroidRecoveryCredentials(activity) }
    val recovery = remember(container) { PasskeyRecovery(container) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<ByteArray?>(null) }
    var verified by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var offerAnotherManager by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    DisposableEffect(code) {
        val shown = code
        val originallySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (shown != null) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            shown?.fill(0)
            if (!originallySecure && shown != null) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    fun launch(action: suspend () -> Unit) {
        if (busy) return
        busy = true; onBusyChange(true); message = 0
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RecoveryCapabilityUnavailable) {
                offerAnotherManager = hasIdentity
                message = R.string.recovery_prf_missing
            }
            catch (_: Exception) { message = R.string.recovery_failed; offerAnotherManager = hasIdentity }
            finally { busy = false; onBusyChange(false) }
        }
    }
    Column {
        Text(stringResource(if (mandatorySetup) R.string.recovery_title else R.string.recovery_test_title))
        if (mandatorySetup) Text(stringResource(R.string.recovery_setup_hint))
        if (!passkeysAvailable) {
            Text(stringResource(R.string.recovery_old_android))
        } else if (hasIdentity) {
            HoldTextButton(enabled = !busy && !verified, onClick = { launch {
                code = recovery.protect(credentials)
                verified = true
                message = R.string.recovery_verified
            } }) { Text(stringResource(R.string.recovery_save)) }
            if (verified) {
                if (code != null) HoldTextButton(onClick = { showCode = !showCode }) { Text(stringResource(R.string.recovery_show_code)) }
                HoldTextButton(onClick = { code = null; onRecovered() }) { Text(stringResource(R.string.mvp_continue)) }
            }
            if (offerAnotherManager && !verified) HoldTextButton(enabled = !busy, onClick = { launch {
                code = recovery.protect(credentials, chooseAnotherManager = true)
                verified = true; offerAnotherManager = false
                message = R.string.recovery_verified
            } }) { Text(stringResource(R.string.recovery_another_manager)) }
        } else {
            HoldTextButton(enabled = !busy, onClick = { launch {
                if (recoveryRequired) recovery.restoreSignedIn(credentials) else recovery.signIn(credentials)
                message = R.string.recovery_restored; onRecovered()
            } }) { Text(stringResource(if (recoveryRequired) R.string.recovery_restore_signed_in else R.string.recovery_sign_in)) }
        }
        if (recoveryRequired) {
            OutlinedTextField(value = codeInput, onValueChange = { if (it.length <= 43) codeInput = it },
                label = { Text(stringResource(R.string.recovery_code_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            HoldTextButton(enabled = !busy && codeInput.length == 43, onClick = { launch {
                val entered = AccountRecoveryCrypto.decode(codeInput); codeInput = ""
                try { recovery.restoreWithCode(entered); onRecovered() } finally { entered.fill(0) }
            } }) { Text(stringResource(R.string.recovery_code_restore)) }
        }
        if (showCode) code?.let { secret ->
            Text(AccountRecoveryCrypto.encode(secret))
            Text(stringResource(R.string.recovery_code_hint))
            HoldTextButton(onClick = { code = null }) { Text(stringResource(R.string.recovery_hide_code)) }
        }
        if (busy) Text(stringResource(R.string.mvp_working))
        if (message != 0) Text(stringResource(message))
    }
}
