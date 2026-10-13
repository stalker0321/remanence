package dev.hryshyn.remanence.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.telemetry.TelemetryConsent
import dev.hryshyn.remanence.telemetry.TelemetryCoordinator
import dev.hryshyn.remanence.ui.hold.HoldColors

@Composable
internal fun TelemetryPreferences(coordinator: TelemetryCoordinator) {
    val consent by coordinator.consent.collectAsStateWithLifecycle()
    val saveFailed by coordinator.saveFailed.collectAsStateWithLifecycle()
    TelemetryPreferencesPanel(consent, coordinator::setDesign, coordinator::setCrops, saveFailed)
}

@Composable
internal fun TelemetryPreferencesPanel(consent: TelemetryConsent, onDesign: (Boolean) -> Unit, onCrops: (Boolean) -> Unit,
    saveFailed: Boolean = false) {
    Column(Modifier.fillMaxWidth().testTag("telemetry_preferences"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.telemetry_title), style = MaterialTheme.typography.labelLarge,
            color = HoldColors.Ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.telemetry_optional), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
        SettingsGroup {
            TelemetryCheck(consent.design, onDesign, R.string.telemetry_design, "telemetry_design")
            TelemetryCheck(consent.crops, onCrops, R.string.telemetry_crops, "telemetry_crops")
        }
        Text(stringResource(R.string.telemetry_disclosure), style = MaterialTheme.typography.bodySmall,
            color = HoldColors.Muted, modifier = Modifier.testTag("telemetry_disclosure"))
        if (saveFailed) Text(stringResource(R.string.telemetry_save_failed), style = MaterialTheme.typography.bodySmall,
            color = HoldColors.Destructive, modifier = Modifier.testTag("telemetry_save_failed"))
    }
}

@Composable
private fun TelemetryCheck(checked: Boolean, change: (Boolean) -> Unit, text: Int, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag).semantics(mergeDescendants = true) {}
        .toggleable(checked, role = Role.Checkbox, onValueChange = change).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(stringResource(text), Modifier.weight(1f).testTag(tag + "_label"), style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink)
    }
}
