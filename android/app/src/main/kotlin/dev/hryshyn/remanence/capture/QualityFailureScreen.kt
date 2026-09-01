package dev.hryshyn.remanence.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.core.recognition.QualityReason

/** One actionable recapture instruction per documented failure reason. */
fun guidanceFor(reason: QualityReason): String = when (reason) {
    QualityReason.CARD_TOO_SMALL ->
        "Move closer so the postcard fills more of the frame."
    QualityReason.CROP_UNCERTAIN ->
        "Keep all four postcard edges inside the on-screen outline."
    QualityReason.TOO_BLURRY ->
        "Hold still and let the camera focus before capturing."
    QualityReason.TOO_DARK ->
        "Add light or move out of the shadow over the postcard."
    QualityReason.GLARE_EXCESSIVE ->
        "Tilt the postcard away from direct light to remove glare."
    QualityReason.FEATURES_INSUFFICIENT ->
        "Use a well-lit postcard with visible print and keep the whole card inside the outline."
}

/**
 * FIX-STATE-04: THE production rejection panel shared by every capture
 * surface. Reasons render as one actionable instruction each and the Retake
 * action is real - callers MUST hand a working callback; there is no default
 * no-op. Rendered INSTEAD of the camera so nothing hides below the preview,
 * and reachable on small phones through the scrollable step layout.
 */
@Composable
fun QualityRejectionPanel(
    reasons: Set<QualityReason>,
    onRecapture: () -> Unit,
    modifier: Modifier = Modifier,
    recaptureTag: String = "quality_recapture_action",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "This capture cannot be used yet:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("quality_failure_header"),
        )
        Spacer(Modifier.height(4.dp))
        for (reason in reasons) {
            Text(
                text = guidanceFor(reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quality_reason_${reason.name}"),
            )
        }
        OutlinedButton(
            onClick = onRecapture,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .testTag(recaptureTag),
        ) {
            Text("Retake")
        }
    }
}
