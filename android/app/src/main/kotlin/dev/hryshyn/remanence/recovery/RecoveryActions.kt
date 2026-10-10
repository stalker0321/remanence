package dev.hryshyn.remanence.recovery

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Opt-in debug surface. No saved-state secret, provider text, clipboard or logs. */
@Composable
fun RecoveryActions(container: AppContainer, hasIdentity: Boolean, recoveryRequired: Boolean,
                    onRecovered: () -> Unit, onBusyChange: (Boolean) -> Unit = {}) {
    if (!BuildConfig.REMANENCE_RECOVERY_ENABLED) return
    val passkeysAvailable = android.os.Build.VERSION.SDK_INT >= 28
    val activity = LocalContext.current as Activity
    val credentials = remember(activity) { AndroidRecoveryCredentials(activity) }
    val recovery = remember(container) { PasskeyRecovery(container) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<ByteArray?>(null) }
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
        busy = true; onBusyChange(true); message = ""
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RecoveryCapabilityUnavailable) { message = "This passkey cannot unlock encrypted data: PRF is unavailable. Use your recovery code after signing in." }
            catch (_: Exception) { message = "Recovery did not complete. Existing keys were not replaced. Check the connection and recovery method." }
            finally { busy = false; onBusyChange(false) }
        }
    }
    Column {
        Text("Recovery · experimental test build")
        if (!passkeysAvailable) {
            Text("Passkey recovery requires Android 9 or newer. Account password + recovery code remain available.")
        } else if (hasIdentity) {
            HoldTextButton(enabled = !busy && code == null, onClick = { launch {
                code = recovery.protect(credentials)
                message = "Recovery package saved and verified. Keep the code somewhere safe; it is not saved by Remanence."
            } }) { Text("Protect memories with a passkey") }
        } else {
            HoldTextButton(enabled = !busy, onClick = { launch {
                recovery.signIn(credentials); message = "Identity restored."; onRecovered()
            } }) { Text("Sign in and restore with a passkey") }
        }
        if (recoveryRequired) {
            OutlinedTextField(value = codeInput, onValueChange = { if (it.length <= 43) codeInput = it },
                label = { Text("Recovery code") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            HoldTextButton(enabled = !busy && codeInput.length == 43, onClick = { launch {
                val entered = AccountRecoveryCrypto.decode(codeInput); codeInput = ""
                try { recovery.restoreWithCode(entered); onRecovered() } finally { entered.fill(0) }
            } }) { Text("Restore with recovery code") }
        }
        code?.let { secret ->
            Text(AccountRecoveryCrypto.encode(secret))
            Text("A recovery code is a secret. Store it outside this device. Code recovery also requires your account password.")
            HoldTextButton(onClick = { code = null }) { Text("I saved the code — hide") }
        }
        if (busy) Text("Working…")
        if (message.isNotEmpty()) Text(message)
    }
}
