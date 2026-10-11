package dev.hryshyn.remanence.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.auth.AccessHeading
import dev.hryshyn.remanence.ui.hold.*

/** Presentation only. No credentials, persisted state, or recovery decisions live here. */
@Composable
fun RecoveryPanel(hasIdentity: Boolean, recoveryRequired: Boolean, mandatorySetup: Boolean,
                  passkeysAvailable: Boolean, busy: Boolean, verified: Boolean, offerAnotherManager: Boolean,
                  message: Int, codeAvailable: Boolean, showCode: Boolean, codeInput: String,
                  onProtect: () -> Unit, onAnotherManager: () -> Unit, onRestore: () -> Unit,
                  onContinue: () -> Unit, onToggleCode: () -> Unit, onCodeChange: (String) -> Unit,
                  onRestoreCode: () -> Unit, onHideCode: () -> Unit,
                  codeContent: @Composable () -> Unit = {}) {
    var manualCode by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HoldSpace.Group)) {
        if (mandatorySetup) {
            AccessHeading(stringResource(R.string.recovery_title), stringResource(R.string.access_setup_detail))
            Text(stringResource(R.string.access_manager_detail), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
        } else if (recoveryRequired) {
            Text(stringResource(R.string.access_keys_detail), style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
        }
        if (!passkeysAvailable) Text(stringResource(R.string.recovery_old_android), color = HoldColors.Muted)
        else if (hasIdentity) {
            if (!verified) HoldButton(enabled = !busy, onClick = onProtect,
                modifier = Modifier.fillMaxWidth().testTag("recovery_save")) {
                RecoveryActionLabel(busy, R.string.recovery_save)
            }
            if (verified) {
                Text(stringResource(R.string.access_verified_detail), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("recovery_verified").semantics { liveRegion = LiveRegionMode.Polite })
                HoldButton(onClick = onContinue, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("recovery_continue")) {
                    Text(stringResource(R.string.mvp_continue))
                }
                if (codeAvailable) HoldTextButton(onClick = onToggleCode, enabled = !busy,
                    modifier = Modifier.testTag("recovery_show_code")) { Text(stringResource(R.string.recovery_show_code)) }
            }
            if (offerAnotherManager && !verified) HoldSecondaryButton(enabled = !busy, onClick = onAnotherManager,
                modifier = Modifier.fillMaxWidth().testTag("recovery_other_manager")) {
                Text(stringResource(R.string.recovery_another_manager))
            }
        } else {
            val label = if (recoveryRequired) R.string.recovery_restore_signed_in else R.string.recovery_sign_in
            if (recoveryRequired) HoldButton(enabled = !busy, onClick = onRestore,
                modifier = Modifier.fillMaxWidth().testTag("recovery_restore")) { RecoveryActionLabel(busy, label) }
            else {
                HoldSecondaryButton(enabled = !busy, onClick = onRestore,
                    modifier = Modifier.fillMaxWidth().testTag("recovery_restore")) { RecoveryActionLabel(busy, label) }
                Text(stringResource(R.string.access_passkey_detail), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
            }
        }
        if (recoveryRequired) {
            HoldTextButton(onClick = { manualCode = !manualCode }, enabled = !busy,
                modifier = Modifier.testTag("recovery_code_option")) { Text(stringResource(R.string.access_code_option)) }
            if (manualCode) {
                HoldInput(value = codeInput, onValueChange = onCodeChange, enabled = !busy,
                    label = { Text(stringResource(R.string.recovery_code_label)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("recovery_code_input"))
                HoldSecondaryButton(enabled = !busy && codeInput.length == 43, onClick = onRestoreCode,
                    modifier = Modifier.fillMaxWidth().testTag("recovery_code_restore")) { Text(stringResource(R.string.recovery_code_restore)) }
            }
        }
        if (showCode && codeAvailable) {
            codeContent()
            Text(stringResource(R.string.recovery_code_hint), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
            HoldTextButton(onClick = onHideCode, enabled = !busy) { Text(stringResource(R.string.recovery_hide_code)) }
        }
        if (busy) Text(stringResource(R.string.mvp_working), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (message != 0 && !(verified && message == R.string.recovery_verified)) Text(stringResource(message),
            color = if (message == R.string.recovery_failed || message == R.string.recovery_prf_missing) HoldColors.Error else HoldColors.Muted,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("recovery_message").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun RowScope.RecoveryActionLabel(busy: Boolean, label: Int) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp).clearAndSetSemantics {}, color = androidx.compose.material3.LocalContentColor.current,
            strokeWidth = 2.dp)
        Spacer(Modifier.width(HoldSpace.Related))
    }
    Text(stringResource(if (busy) R.string.mvp_working else label))
}
