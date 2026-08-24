package app.postmark.memory.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private fun labelFor(item: PreparedBackItem): String = when (item) {
    PreparedBackItem.MESSAGE_WRITTEN -> "Message written"
    PreparedBackItem.ADDRESS_WRITTEN -> "Recipient address written"
    PreparedBackItem.SIGNATURE_ADDED -> "Signature added"
    PreparedBackItem.POSTAGE_APPLIED -> "Stamp / postage code applied"
}

/**
 * Prepared-back checklist gate (docs/implementation-plan.md M1-R18): the
 * back capture action is enabled only after the user explicitly confirms the
 * postcard is fully prepared (message, address, signature, postage).
 */
@Composable
fun PreparedBackScreen(
    gate: PreparedBackGate,
    onBackCaptureRequested: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "Finish preparing the postcard before capturing its back.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("prepared_header"),
        )
        for (item in PreparedBackItem.entries) {
            Row(modifier = Modifier.testTag("prepared_item_${item.name}")) {
                Checkbox(
                    checked = gate.checked[item] == true,
                    onCheckedChange = { gate.setChecked(item, it) },
                    modifier = Modifier.testTag("prepared_checkbox_${item.name}"),
                )
                Text(text = labelFor(item), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            text = if (gate.ready) "The postcard is ready." else "Confirm every step to unlock the back capture.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("prepared_status"),
        )
        Button(
            onClick = onBackCaptureRequested,
            enabled = gate.ready,
            modifier = Modifier
                .padding(top = 12.dp)
                .testTag("back_capture_action"),
        ) {
            Text("Capture the back")
        }
    }
}
