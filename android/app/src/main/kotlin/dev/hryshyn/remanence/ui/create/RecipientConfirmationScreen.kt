package dev.hryshyn.remanence.ui.create

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.hryshyn.remanence.ui.hold.HoldActionObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot

/**
 * Explicit recipient confirmation (docs/security.md section 8). Shows the
 * resolved handle plus the immutable account identifier so the sender binds
 * the capsule to stable IDs, never to a mutable handle string alone.
 */
@Composable
fun RecipientConfirmationScreen(
    snapshot: ResolvedHandleSnapshot,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = "Account ${snapshot.userId.toRestString()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("confirm_account_cue_text"),
        )
        Text(
            text = "Only this account can open what you send. Check the handle before continuing.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        HoldActionObject(
            title = snapshot.handle.toDisplayString(),
            titleModifier = Modifier.testTag("confirm_handle_text"),
            detail = stringResource(R.string.hold_confirm_body),
            action = stringResource(R.string.hold_confirm), onClick = onConfirm,
            compact = true, modifier = Modifier.fillMaxWidth().testTag("confirm_button"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cancel_button"),
        ) {
            Text(stringResource(R.string.hold_change))
        }
    }
}
