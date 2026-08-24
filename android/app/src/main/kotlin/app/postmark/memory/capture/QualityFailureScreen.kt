package app.postmark.memory.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import postmark.core.recognition.QualityReason

/** One actionable recapture instruction per documented failure reason. */
fun guidanceFor(reason: QualityReason): String = when (reason) {
    QualityReason.CARD_TOO_SMALL ->
        "Move closer so the postcard fills more of the frame."
    QualityReason.CROP_UNCERTAIN ->
        "Align the card edges with the outline and hold steadier."
    QualityReason.TOO_BLURRY ->
        "Hold still and let the camera focus before capturing."
    QualityReason.TOO_DARK ->
        "Add light or move out of the shadow over the postcard."
    QualityReason.GLARE_EXCESSIVE ->
        "Tilt the postcard away from direct light to remove glare."
}

/**
 * Quality-failure guidance surface (docs/implementation-plan.md M1-R16):
 * every failed gate maps to a specific recapture instruction; UI receives
 * classifications only (docs/recognition.md section 8).
 */
@Composable
fun QualityFailureScreen(
    reasons: Set<QualityReason>,
    onRecapture: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        if (reasons.isEmpty()) {
            Text(
                text = "Capture quality accepted.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("quality_passed_status"),
            )
            return@Column
        }
        Text(
            text = "This capture cannot be used yet:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("quality_failure_header"),
        )
        for (reason in reasons) {
            Text(
                text = guidanceFor(reason),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quality_reason_${reason.name}"),
            )
        }
        Button(
            onClick = onRecapture,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .testTag("quality_recapture_action"),
        ) {
            Text("Try again")
        }
    }
}
