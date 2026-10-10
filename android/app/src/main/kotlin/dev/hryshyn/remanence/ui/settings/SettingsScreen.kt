package dev.hryshyn.remanence.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.hold.HoldSpace
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.locale.LanguageSwitchRow

/** Only functioning controls; no fake device-management, purge or deletion buttons. */
@Composable
fun SettingsScreen(handle: String, locale: AppLocale, onLocale: (AppLocale) -> Unit,
                   recoveryReady: Boolean, onBack: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(HoldSpace.Gutter).testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(HoldSpace.Group)) {
        HoldTextButton(onClick = onBack) { Text(stringResource(R.string.hold_back)) }
        Text(stringResource(R.string.mvp_settings), style = MaterialTheme.typography.headlineLarge)
        Text("@$handle", modifier = Modifier.testTag("settings_handle"))
        if (BuildConfig.REMANENCE_RECOVERY_ENABLED) Text(stringResource(R.string.mvp_handle_permanent))
        LanguageSwitchRow(current = locale, onSelect = onLocale)
        Text(stringResource(if (recoveryReady) R.string.mvp_recovery_ready else R.string.mvp_recovery_not_ready))
        Text(stringResource(R.string.mvp_privacy_summary))
        Text("Remanence ${BuildConfig.VERSION_NAME}")
        HoldTextButton(onClick = onLogout, modifier = Modifier.testTag("settings_logout")) {
            Text(stringResource(R.string.hold_logout))
        }
    }
}
