package dev.hryshyn.remanence.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.recognition.QualityReason

/** String resource for the one actionable recapture instruction per failure reason. */
internal fun guidanceResource(reason: QualityReason): Int = when (reason) {
    QualityReason.CARD_TOO_SMALL -> R.string.hold_capture_reason_card_too_small
    QualityReason.CROP_UNCERTAIN -> R.string.hold_capture_reason_crop_uncertain
    QualityReason.ANGLE_UNCERTAIN -> R.string.hold_capture_reason_angle_uncertain
    QualityReason.RESOLUTION_INSUFFICIENT -> R.string.hold_capture_reason_resolution_insufficient
    QualityReason.TOO_BLURRY -> R.string.hold_capture_reason_too_blurry
    QualityReason.TOO_DARK -> R.string.hold_capture_reason_too_dark
    QualityReason.GLARE_EXCESSIVE -> R.string.hold_capture_reason_glare_excessive
    QualityReason.FEATURES_INSUFFICIENT -> R.string.hold_capture_reason_features_insufficient
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
            text = stringResource(R.string.hold_capture_rejected_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("quality_failure_header"),
        )
        Spacer(Modifier.height(4.dp))
        for (reason in reasons) {
            Text(
                text = stringResource(guidanceResource(reason)),
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
            Text(stringResource(R.string.hold_retry))
        }
    }
}
