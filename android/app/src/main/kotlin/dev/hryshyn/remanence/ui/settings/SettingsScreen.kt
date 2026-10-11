package dev.hryshyn.remanence.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldSpace
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.locale.AppLocale

/** Only functioning controls; no fake device-management, purge or deletion buttons. */
@Composable
fun SettingsScreen(handle: String, locale: AppLocale, onLocale: (AppLocale) -> Unit,
                   recoveryReady: Boolean, onBack: () -> Unit, onLogout: () -> Unit,
                   safetyContent: @Composable () -> Unit = {}) {
    val gutter = HoldSpace.pageGutter()
    val backLabel = stringResource(R.string.hold_back)
    Column(Modifier.fillMaxSize().background(HoldColors.Paper).testTag("settings_screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack,
                modifier = Modifier.size(48.dp).testTag("settings_back")
                    .semantics { contentDescription = backLabel }) {
                Text("←", Modifier.clearAndSetSemantics {},
                    style = MaterialTheme.typography.titleLarge, color = HoldColors.Ink)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, top = HoldSpace.Group, bottom = HoldSpace.Section)
            .testTag("settings_content"),
            verticalArrangement = Arrangement.spacedBy(HoldSpace.Section)) {
            Text(stringResource(R.string.settings_design_title),
                style = MaterialTheme.typography.headlineLarge, color = HoldColors.Ink,
                modifier = Modifier.testTag("settings_title").semantics { heading() })
            SettingsSection(stringResource(R.string.settings_design_account), "settings_section_account") {
                // Full wrapping identity, bidi-isolated from surrounding text.
                Text("\u2068@$handle\u2069", Modifier.fillMaxWidth().testTag("settings_handle"),
                    style = MaterialTheme.typography.bodyLarge, color = HoldColors.Ink)
                if (BuildConfig.REMANENCE_RECOVERY_ENABLED) {
                    Text(stringResource(R.string.mvp_handle_permanent),
                        Modifier.testTag("settings_handle_permanent"),
                        style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                }
            }
            Column(Modifier.fillMaxWidth().testTag("language_switch_row"),
                verticalArrangement = Arrangement.spacedBy(HoldSpace.Small)) {
                SettingsLabel(stringResource(R.string.settings_design_language), "language_group_label")
                Column(Modifier.fillMaxWidth().selectableGroup().testTag("language_select_group")) {
                    AppLocale.options.forEach { option ->
                        val label = stringResource(when (option) {
                            AppLocale.SYSTEM -> R.string.hold_language_system
                            AppLocale.ENGLISH -> R.string.hold_language_english
                            AppLocale.RUSSIAN -> R.string.hold_language_russian
                            AppLocale.UKRAINIAN -> R.string.hold_language_ukrainian
                        })
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("language_option_" + option.name.lowercase())
                            .selectable(selected = locale == option,
                                onClick = { onLocale(option) }, role = Role.RadioButton)
                            .padding(vertical = HoldSpace.Related),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(HoldSpace.Related)) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = HoldColors.Ink)
                            // Decorative check; selection belongs to one row target.
                            Text(if (locale == option) "✓" else "",
                                Modifier.width(24.dp).clearAndSetSemantics {},
                                style = MaterialTheme.typography.bodyMedium, color = HoldColors.Accent)
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_design_access), "settings_section_access") {
                Text(stringResource(if (recoveryReady) R.string.mvp_recovery_ready else R.string.mvp_recovery_not_ready),
                    Modifier.fillMaxWidth().testTag("settings_recovery_status"),
                    style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink)
            }
            SettingsSection(stringResource(R.string.settings_design_privacy), "settings_section_privacy") {
                Text(stringResource(R.string.mvp_privacy_summary), Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink)
                BetaLegalLinks(handle)
            }
            safetyContent()
            SettingsSection(stringResource(R.string.settings_design_about), "settings_section_about") {
                Text("Remanence " + BuildConfig.VERSION_NAME, Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
            }
            HoldTextButton(onClick = onLogout,
                modifier = Modifier.fillMaxWidth().testTag("settings_logout")) {
                Text(stringResource(R.string.settings_design_logout), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = HoldColors.Destructive)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag),
        verticalArrangement = Arrangement.spacedBy(HoldSpace.Small)) {
        SettingsLabel(title, tag + "_label")
        content()
    }
}

@Composable
private fun SettingsLabel(title: String, tag: String) {
    Text(title, Modifier.testTag(tag).semantics { heading() },
        style = MaterialTheme.typography.labelSmall, color = HoldColors.Muted)
}
