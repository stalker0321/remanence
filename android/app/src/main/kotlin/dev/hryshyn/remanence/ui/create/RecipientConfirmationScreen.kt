package dev.hryshyn.remanence.ui.create

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    var acknowledged by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text("Send to", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = snapshot.handle.toDisplayString(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("confirm_handle_text"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Account ${snapshot.userId.toRestString()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("confirm_account_cue_text"),
        )
        Text(
            text = "This account is identified by its immutable ID. The capsule will be encrypted only for this account's current key.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = acknowledged,
                onCheckedChange = { acknowledged = it },
                modifier = Modifier.testTag("confirm_ack_checkbox"),
            )
            Text("This is the person I intend to send to")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onConfirm,
            enabled = acknowledged,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("confirm_button"),
        ) {
            Text("Encrypt for this account")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cancel_button"),
        ) {
            Text("Cancel")
        }
    }
}
