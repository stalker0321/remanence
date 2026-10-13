package dev.hryshyn.remanence.pending

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

/**
 * T186: first-send handle gate (T184 contract + T185 R17 scaffold).
 *
 * Shown INSTEAD of the Create flow when the account is handleless: sending
 * requires the one-time immutable handle, and there is no backend to commit
 * it to yet (R17 unmounted), so this screen explains and returns — it never
 * fabricates a handle, never mints a placeholder, and never claims the send
 * happened. Wired from the Create entry in `MainActivity`; the one-time
 * commit itself lands with the R17 repository (milestone 4).
 */
@Composable
fun PendingHandleRequiredNotice(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Test seam: production passes the build-time gate (default). */
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("pending_handle_required"),
        ) {
            Text(
                stringResource(R.string.pending_handle_required_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pending_handle_required_body),
                style = MaterialTheme.typography.bodySmall,
                color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.testTag("pending_handle_required_back"),
            ) { Text(stringResource(R.string.pending_handle_required_back)) }
        }
    }
}
