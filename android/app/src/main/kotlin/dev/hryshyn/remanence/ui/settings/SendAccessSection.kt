package dev.hryshyn.remanence.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.SendAccessFailure
import dev.hryshyn.remanence.core.data.network.SendAccessMode
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton

@Composable
fun SendAccessSection(state: SendAccessUiState, onRefresh: () -> Unit, onSessionRequired: () -> Unit) {
    if (state.hidden) return
    Column(Modifier.fillMaxWidth().testTag("send_access_section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.send_access_title), style = MaterialTheme.typography.labelSmall,
            color = HoldColors.Muted, modifier = Modifier.testTag("send_access_title").semantics { heading() })
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val value = state.value
                when {
                    state.loading -> AccessText(R.string.send_access_loading, "send_access_loading")
                    state.error != null || value == null -> {
                        val auth = state.error == SendAccessFailure.SIGN_IN
                        AccessText(if (auth) R.string.send_access_sign_in else R.string.send_access_unavailable, "send_access_unavailable")
                        HoldTextButton(onClick = if (auth) onSessionRequired else onRefresh,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("send_access_retry")) {
                            Text(stringResource(if (auth) R.string.send_access_return else R.string.send_access_refresh))
                        }
                    }
                    value.mode == SendAccessMode.RECORD -> AccessText(R.string.send_access_beta, "send_access_beta")
                    value.lifetime -> AccessText(R.string.send_access_lifetime, "send_access_lifetime")
                    else -> {
                        Text(stringResource(R.string.send_access_self, value.self.remaining), Modifier.testTag("send_access_self"), color = HoldColors.Ink)
                        Text(stringResource(R.string.send_access_other, value.other.remaining), Modifier.testTag("send_access_other"), color = HoldColors.Ink)
                    }
                }
                if (value != null) {
                    Text(stringResource(R.string.send_access_advisory), Modifier.testTag("send_access_advisory"),
                        style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                    HoldTextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp).testTag("send_access_refresh")) {
                        Text(stringResource(R.string.send_access_refresh))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessText(@androidx.annotation.StringRes id: Int, tag: String) {
    Text(stringResource(id), Modifier.fillMaxWidth().testTag(tag),
        style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink)
}
