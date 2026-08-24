package app.postmark.memory.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * One chooser hint row (docs/recognition.md section 9): only the locally
 * decrypted sender handle snapshot, year/date, and optional place label.
 * No thumbnails, notes, photo counts, or any gallery affordance exists here.
 */
data class ChooserHintRow(
    val candidateId: String,
    val senderHandleSnapshot: String,
    val yearAndDateLabel: String,
    val placeLabel: String?,
)

/**
 * M1-M13 scan-scoped ambiguity chooser. Rows are ordered by score (the
 * ViewModel guarantees it) and reveal the minimum hints required to let the
 * recipient recognize their own postcard - nothing more.
 */
@Composable
fun AmbiguityChooserScreen(
    rows: List<ChooserHintRow>,
    onSelected: (ChooserHintRow) -> Unit,
    onRecapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text(
            "Which postcard is this?",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("chooser_title"),
        )
        Spacer(Modifier.height(12.dp))
        rows.forEach { row ->
            Button(
                onClick = { onSelected(row) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chooser_row_${row.candidateId}"),
            ) {
                Column {
                    Text(
                        "From ${row.senderHandleSnapshot}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        row.yearAndDateLabel,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!row.placeLabel.isNullOrEmpty()) {
                        Text(
                            row.placeLabel,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = onRecapture,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chooser_recapture_button"),
        ) {
            Text("Scan again instead")
        }
    }
}
