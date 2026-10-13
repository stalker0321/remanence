package dev.hryshyn.remanence.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.*
import dev.hryshyn.remanence.ui.locale.AppLocale

/** Overview first; values are not buttons and secondary detail stays behind one row. */
@Composable
fun SettingsScreen(handle: String?, locale: AppLocale, onLocale: (AppLocale) -> Unit,
                   recoveryReady: Boolean, onBack: () -> Unit, onLogout: () -> Unit,
                   telemetryContent: @Composable () -> Unit = {},
                   safetyContent: @Composable () -> Unit = {}) {
    val gutter = HoldSpace.pageGutter()
    var languages by rememberSaveable { mutableStateOf(false) }
    var access by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var logoutConfirmation by rememberSaveable { mutableStateOf(false) }
    val backLabel = stringResource(R.string.hold_back)
    Column(Modifier.fillMaxSize().background(HoldColors.Paper).testTag("settings_screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("settings_back")
                .semantics { contentDescription = backLabel }) {
                Text("←", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, top = 12.dp, bottom = 32.dp).testTag("settings_content"),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(stringResource(R.string.settings_design_title), style = MaterialTheme.typography.headlineLarge,
                color = HoldColors.Ink, modifier = Modifier.testTag("settings_title").semantics { heading() })
            Column(Modifier.fillMaxWidth().testTag("settings_section_account"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsLabel(stringResource(R.string.settings_design_account))
                // T186: handleless receive-only accounts show no handle row.
                if (handle != null) {
                    Text("\u2068@$handle\u2069", Modifier.fillMaxWidth().testTag("settings_handle"),
                        style = MaterialTheme.typography.titleMedium, color = HoldColors.Ink)
                }
                if (BuildConfig.REMANENCE_RECOVERY_ENABLED) Text(stringResource(R.string.mvp_handle_permanent),
                    Modifier.testTag("settings_handle_permanent"), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
            }
            SettingsGroup {
                SettingsActionRow(stringResource(R.string.settings_design_language), stringResource(localeLabel(locale)),
                    { languages = true }, Modifier.testTag("language_switch_row"))
                SettingsActionRow(stringResource(R.string.settings_design_access),
                    stringResource(if (recoveryReady) R.string.settings_access_saved else R.string.settings_access_needed),
                    { access = !access }, Modifier.testTag("settings_access_row"), expanded = access)
                if (access) Text(stringResource(if (recoveryReady) R.string.mvp_recovery_ready else R.string.mvp_recovery_not_ready),
                    Modifier.fillMaxWidth().padding(16.dp).testTag("settings_recovery_status"),
                    style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsLabel(stringResource(R.string.settings_design_privacy))
                SettingsGroup {
                    SettingsActionRow(stringResource(R.string.settings_privacy_detail), onClick = { privacy = !privacy },
                        modifier = Modifier.testTag("settings_privacy_row"), expanded = privacy)
                    if (privacy) {
                        Text(stringResource(R.string.mvp_privacy_summary), Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
                        BetaLegalLinks(handle)
                    }
                    safetyContent()
                }
            }
            telemetryContent()
            SettingsGroup {
                SettingsActionRow(stringResource(R.string.settings_design_logout), onClick = { logoutConfirmation = true },
                    modifier = Modifier.testTag("settings_logout"), destructive = true)
            }
            Text("Remanence " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
        }
    }
    if (logoutConfirmation) AlertDialog(onDismissRequest = { logoutConfirmation = false },
        title = { Text(stringResource(R.string.home_audit_logout_title)) },
        text = { Text(stringResource(R.string.home_audit_logout_detail)) },
        confirmButton = {
            HoldTextButton(onClick = { logoutConfirmation = false; onLogout() },
                modifier = Modifier.testTag("settings_logout_confirm")) {
                Text(stringResource(R.string.settings_design_logout), color = HoldColors.Destructive)
            }
        }, dismissButton = {
            HoldTextButton(onClick = { logoutConfirmation = false },
                modifier = Modifier.testTag("settings_logout_cancel")) { Text(stringResource(R.string.home_audit_logout_cancel)) }
        })
    if (languages) AlertDialog(onDismissRequest = { languages = false },
        title = { Text(stringResource(R.string.settings_design_language)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup().testTag("language_select_group")) {
                AppLocale.options.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("language_option_" + option.name.lowercase())
                        .selectable(locale == option, role = Role.RadioButton, onClick = { languages = false; onLocale(option) })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = locale == option, onClick = null)
                        Text(stringResource(localeLabel(option)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }, confirmButton = {}, dismissButton = {
            HoldTextButton(onClick = { languages = false }, modifier = Modifier.testTag("language_dialog_close")) { Text(stringResource(R.string.hold_close)) }
        })
}

internal fun localeLabel(locale: AppLocale) = when (locale) {
    AppLocale.SYSTEM -> R.string.hold_language_system
    AppLocale.ENGLISH -> R.string.hold_language_english
    AppLocale.RUSSIAN -> R.string.hold_language_russian
    AppLocale.UKRAINIAN -> R.string.hold_language_ukrainian
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HoldColors.Field), content = content)
}

@Composable
internal fun SettingsActionRow(title: String, detail: String? = null, onClick: () -> Unit,
    modifier: Modifier = Modifier, expanded: Boolean? = null, destructive: Boolean = false) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (destructive) HoldColors.Destructive else HoldColors.Ink)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted) }
        }
        Text(if (expanded == true) "−" else if (expanded == false) "+" else "→",
            Modifier.clearAndSetSemantics {}, color = HoldColors.Muted, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsLabel(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelSmall, color = HoldColors.Muted)
}
