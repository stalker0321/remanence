package dev.hryshyn.remanence.pending

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

/**
 * T186: localized secondary code entry for the scan/receive surface.
 *
 * - Rendered ONLY when [PendingFeatureGate.isEnabled]; otherwise returns
 *   nothing so beta/release/default builds are byte-identical in behavior.
 * - Secondary path: the ordinary FRONT scan stays primary and untouched.
 * - Code errors are generic ([R.string.pending_code_invalid]); the input is
 *   normalized via [PendingCodeNormalizer] and never logged.
 * - Server lookup/proof/claim journey is NOT fabricated here: on a
 *   well-formed code the UI reports [R.string.pending_code_unavailable]
 *   until the frozen T185 api-contract lands. Mock claims are never labelled
 *   real encrypted opens.
 * - Visuals/motion preserved: Hold buttons + routine [AstraEnterTransition].
 */
@Composable
fun PendingCodeEntryButton(
    onOpenCodeEntry: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Test seam: production passes the build-time gate (default); device
     * tests force it to mount the real component without an auth session.
     */
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    HoldTextButton(
        onClick = onOpenCodeEntry,
        modifier = modifier.testTag("pending_code_entry"),
    ) {
        Text(stringResource(R.string.pending_code_entry))
    }
}

@Composable
fun PendingCodeEntrySheet(
    onClose: () -> Unit,
    onWellFormedCode: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Test seam, see [PendingCodeEntryButton]. */
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    var raw by remember { mutableStateOf("") }
    var showGenericError by remember { mutableStateOf(false) }
    var showUnavailable by remember { mutableStateOf(false) }

    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("pending_code_sheet"),
        ) {
            Text(
                stringResource(R.string.pending_code_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pending_code_body),
                style = MaterialTheme.typography.bodySmall,
                color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = raw,
                onValueChange = {
                    raw = it
                    showGenericError = false
                    showUnavailable = false
                },
                label = { Text(stringResource(R.string.pending_code_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pending_code_field"),
            )
            if (showGenericError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pending_code_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pending_code_error"),
                )
            }
            if (showUnavailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pending_code_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pending_code_unavailable"),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val normalized = PendingCodeNormalizer.normalize(raw)
                    if (normalized == null) {
                        showGenericError = true
                    } else {
                        // Fail closed: no wire DTOs invented before T185.
                        showUnavailable = true
                        onWellFormedCode(normalized)
                    }
                },
                modifier = Modifier.testTag("pending_code_continue"),
            ) { Text(stringResource(R.string.pending_code_continue)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.testTag("pending_code_cancel"),
            ) { Text(stringResource(R.string.pending_code_cancel)) }
        }
    }
}
