package dev.hryshyn.remanence.recovery

import android.app.Activity
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto
import dev.hryshyn.remanence.ui.auth.isCredentialUserCancellation
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
    fun launch(protecting: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        busy = true; onBusyChange(true); message = 0
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RecoveryCapabilityUnavailable) {
                offerAnotherManager = hasIdentity
                message = R.string.recovery_prf_missing
            }
            catch (pending: RecoveryVerificationPending) {
                // Saved but not yet verifiable: explicit retry runs the
                // existing save action below (verified stays false, so no
                // Continue/code), which re-reads PENDING status and uses
                // normal discovery for the same enrollment. Never offer
                // another manager merely because this continuation
                // occurred.
                val presentation = presentRecoveryPending()
                verified = presentation.verified
                offerAnotherManager = presentation.offerAnotherManager
                message = presentation.message
            }
            catch (failure: Exception) {
                message = recoveryFailureMessage(failure, protecting)
                if (!isCredentialUserCancellation(failure)) offerAnotherManager = hasIdentity
            }
            finally { busy = false; onBusyChange(false) }
        }
    }
    RecoveryPanel(hasIdentity, recoveryRequired, mandatorySetup, passkeysAvailable, busy, verified,
        offerAnotherManager, message, code != null, showCode, codeInput,
        onProtect = { launch(protecting = true) {
                code = recovery.protect(credentials)
                verified = true
                message = R.string.recovery_verified
        } },
        onContinue = { code = null; onRecovered() },
        onToggleCode = { showCode = !showCode },
        onAnotherManager = { launch(protecting = true) {
                code = recovery.protect(credentials, chooseAnotherManager = true)
                verified = true; offerAnotherManager = false
                message = R.string.recovery_verified
        } },
        onRestore = { launch {
                if (recoveryRequired) recovery.restoreSignedIn(credentials) else recovery.signIn(credentials)
                message = R.string.recovery_restored; onRecovered()
        } },
        onCodeChange = { if (it.length <= 43) codeInput = it },
        onRestoreCode = { launch {
                val entered = AccountRecoveryCrypto.decode(codeInput); codeInput = ""
                try { recovery.restoreWithCode(entered); onRecovered() } finally { entered.fill(0) }
        } },
        onHideCode = { code = null },
        codeContent = { code?.let { Text(AccountRecoveryCrypto.encode(it)) } },
    )
}

/** Only typed provider cancellation is neutral. Timeouts and unknown failures still fail closed. */
internal fun recoveryFailureMessage(failure: Exception, protecting: Boolean): Int = when {
    isCredentialUserCancellation(failure) -> R.string.auth_passkey_cancelled
    protecting -> R.string.auth_passkey_setup_failed
    else -> R.string.recovery_failed
}

/**
 * Presentation for the typed saved-but-pending continuation, shared by
 * the save and another-manager actions above. Pending is NOT verified
 * (no Continue, no code) and never offers another manager merely
 * because the continuation occurred. Same-enrollment retry routing is
 * NOT a flag here: it is established by the existing save action
 * (`protect()` with default `chooseAnotherManager=false`, which
 * re-reads PENDING status) plus the connected orchestration test.
 */
internal data class RecoveryPendingPresentation(
    val verified: Boolean,
    val message: Int,
    val offerAnotherManager: Boolean,
)

internal fun presentRecoveryPending(): RecoveryPendingPresentation = RecoveryPendingPresentation(
    verified = false,
    message = R.string.recovery_verification_pending,
    offerAnotherManager = false,
)
