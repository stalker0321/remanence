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
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.recognition.QualityReason

/** One actionable recapture instruction per documented failure reason. */
fun guidanceFor(reason: QualityReason): String = when (reason) {
    QualityReason.CARD_TOO_SMALL ->
        "Move closer while keeping all four postcard edges visible."
    QualityReason.CROP_UNCERTAIN ->
        "Show all four postcard corners and edges; remove any occlusion."
    QualityReason.ANGLE_UNCERTAIN ->
        "Hold the phone parallel to the postcard and align its edges."
    QualityReason.RESOLUTION_INSUFFICIENT ->
        "Move closer, use the highest still resolution, and keep the full card visible."
    QualityReason.TOO_BLURRY ->
        "Hold the phone steady while the camera focuses."
    QualityReason.TOO_DARK ->
        "Use brighter, even light and remove shadows."
    QualityReason.GLARE_EXCESSIVE ->
        "Tilt the postcard or move the light to remove glare."
    QualityReason.FEATURES_INSUFFICIENT ->
        "Use printed detail, focus, good light, and keep the full card inside the outline."
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
    diagnostic: CaptureDiagnostic? = null,
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
        if (BuildConfig.DEBUG) {
            diagnostic?.let {
                Text(
                    text = it.summary(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("capture_debug_diagnostic"),
                )
            }
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
