package dev.hryshyn.rv01probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hryshyn.rv01probe.probe.LocalDryRunResult

@Composable
fun ProbeScreen(
    result: LocalDryRunResult?,
    onRunDryRun: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RV-01 Google capability probe", style = MaterialTheme.typography.headlineSmall)
        Text(
            "LOCAL / EMULATOR DRY-RUN ONLY — NOT RV-01 EVIDENCE",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
        Text("This throwaway app never contacts Remanence and never handles an ARK or user data.")
        Text("Provider bridges are unavailable until a genuine capability is implemented and physically tested.")
        Button(onClick = onRunDryRun) {
            Text("Run fake P1-P3 / P7 dry-run")
        }
        result?.let {
            Text("Dry-run tuples: ${it.records.joinToString { record -> "${record.tuple.name}=${record.result.name}" }}")
            Text("The JSON output contains enum tuples/results only and is not an RV-01 evidence result.")
        }
    }
}
