package dev.hryshyn.rv01probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hryshyn.rv01probe.probe.BackupEligibility
import dev.hryshyn.rv01probe.probe.OperatorConfirmedLockKind
import dev.hryshyn.rv01probe.probe.OperatorP1Confirmation
import dev.hryshyn.rv01probe.probe.ProbeControllerPhase
import dev.hryshyn.rv01probe.probe.ProbeControllerReason
import dev.hryshyn.rv01probe.probe.ProbeControllerState

@Composable
fun ProbeScreen(
    state: ProbeControllerState,
    confirmation: OperatorP1Confirmation,
    onStart: () -> Unit,
    onRecheck: () -> Unit,
    onConfirmLock: (OperatorConfirmedLockKind) -> Unit,
    onClearLock: () -> Unit,
    onConfirmBackup: (BackupEligibility) -> Unit,
    onClearBackup: () -> Unit,
    onConfirmBackupNow: () -> Unit,
    onClearBackupNow: () -> Unit,
    onExport: () -> Unit,
    onExportD2: () -> Unit,
    onImportAndVerify: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onCleanup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RV-01 Google capability probe", style = MaterialTheme.typography.headlineSmall)
        Text(
            "PRE-WIPE HARNESS ONLY — NO RV-01 EVIDENCE OR CAPABILITY GO",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Status: ${state.status.name}")
        Text("Phase: ${state.phase.name}")
        Text("Operator state: ${safeReason(state.reason)}")
        Text("This throwaway app never contacts Remanence and never handles an ARK or user data.")
        Text("A PASS is only pre-wipe local U/P verification; it is not provider evidence.")

        Text(
            "P1 physical inputs (UNKNOWN unless you confirm on this device): " +
                "lock=${confirmation.lockKind.name} " +
                "backup=${confirmation.backupEligibility.name} " +
                "backupNow=${confirmation.backupNowCompleted}",
        )
        Text("Confirm only what you verified on this device; secure lock alone proves nothing.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onConfirmLock(OperatorConfirmedLockKind.PIN) }) {
                Text("Lock: PIN")
            }
            Button(onClick = { onConfirmLock(OperatorConfirmedLockKind.PATTERN) }) {
                Text("Lock: Pattern")
            }
            Button(onClick = { onConfirmLock(OperatorConfirmedLockKind.PASSWORD) }) {
                Text("Lock: Password")
            }
            Button(onClick = onClearLock) { Text("Clear lock") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onConfirmBackup(BackupEligibility.ELIGIBLE) }) {
                Text("Backup: eligible")
            }
            Button(onClick = { onConfirmBackup(BackupEligibility.INELIGIBLE) }) {
                Text("Backup: ineligible")
            }
            Button(onClick = onClearBackup) { Text("Clear backup") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirmBackupNow) { Text("Backup Now: done") }
            Button(onClick = onClearBackupNow) { Text("Clear Backup Now") }
        }

        if (state.phase == ProbeControllerPhase.NOT_RUN ||
            state.phase == ProbeControllerPhase.TERMINAL
        ) {
            Button(onClick = onStart) {
                Text("Start new pre-wipe run")
            }
        }
        if (state.phase == ProbeControllerPhase.CHECK_ENVIRONMENT && !state.canCancel) {
            Button(onClick = onRecheck) {
                Text("Re-check eligibility with current inputs")
            }
        }
        if (state.canCancel) {
            Button(onClick = onCancel) { Text("Cancel bounded operation") }
        }
        if (state.canExport) {
            Button(onClick = onExport) { Text("Choose SAF file and export opaque P (D1, pre-wipe check)") }
        }
        if (state.canExportD2 &&
            (state.phase == ProbeControllerPhase.WAITING_FOR_SAF_EXPORT ||
                state.phase == ProbeControllerPhase.READY_TO_VERIFY)
        ) {
            Button(onClick = onExportD2) { Text("Choose SAF file and export D2 handoff P") }
        }
        state.d2Handoff?.let { handoff ->
            Text("D1→D2 handoff (copy to D2; never evidence):")
            Text(handoff.copyText())
        }
        if (state.canVerify) {
            Button(onClick = onImportAndVerify) {
                Text("Choose SAF file and verify before wipe")
            }
        }
        if (state.canRetry) {
            Button(onClick = onRetry) { Text("Retry same bounded case") }
        }
        if (state.canCleanup) {
            Button(onClick = onCleanup) { Text("Prepare cleanup: delete exact provider slot") }
        }
        Text("Redacted evidence JSON: ${state.evidenceJson}")
    }
}

private fun safeReason(reason: ProbeControllerReason): String = when (reason) {
    ProbeControllerReason.NONE -> "ready"
    ProbeControllerReason.PLAY_SERVICES_UNAVAILABLE -> "Google Play services unavailable"
    ProbeControllerReason.LOCK_NOT_QUALIFIED -> "qualifying PIN/pattern/password lock not proven"
    ProbeControllerReason.E2EE_UNAVAILABLE -> "provider E2EE availability not true"
    ProbeControllerReason.BACKUP_NOT_ELIGIBLE -> "cloud backup eligibility not proven"
    ProbeControllerReason.PROVIDER_UNAVAILABLE -> "provider unavailable"
    ProbeControllerReason.RETRYABLE_UNAVAILABLE -> "retryable interruption; same case retained"
    ProbeControllerReason.INCOMPLETE -> "required operator fixture or result incomplete"
    ProbeControllerReason.SAF_NOT_SELECTED -> "select the independent local SAF fixture"
    ProbeControllerReason.SAF_INVALID -> "sidecar rejected by bounded authenticated parsing"
    ProbeControllerReason.FAIL_CLOSED -> "bounded fail-closed result"
    ProbeControllerReason.INDETERMINATE -> "unknown result shape; stopped fail-closed"
    ProbeControllerReason.CANCELLED -> "operation cancelled; success not claimed"
    ProbeControllerReason.PROCESS_RECREATED_INCOMPLETE -> "process recreated; sensitive case was discarded"
    ProbeControllerReason.PROVIDER_ENTRY_MAY_REMAIN ->
        "process recreated; provider entry may remain; cleanup not claimed"
    ProbeControllerReason.ILLEGAL_TRANSITION -> "action is not valid for the current phase"
    ProbeControllerReason.CLEANUP_COMPLETE -> "exact-key cleanup completed"
}
