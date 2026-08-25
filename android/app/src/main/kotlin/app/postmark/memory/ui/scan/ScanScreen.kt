package app.postmark.memory.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.postmark.memory.capture.CaptureAttemptSurface
import app.postmark.memory.scan.ScanSessionState

/**
 * FIX-M1-007-12 / FIX-REVIEW-01: the production Scan surface. Entry renders
 * the honest capture flow - FRONT then BACK stills through the real ORB
 * pipeline - before any matching runs against the encrypted local index; the
 * ambiguity chooser shows only decrypted minimal hints; and a grant exists
 * only after the full crypto gate passes - manual selection included.
 *
 * FIX-STATE-01/04: capture attempts render exclusively from the authoritative
 * controllers with visible Processing and real Retake recovery; the step
 * content scrolls so errors and actions stay reachable on small phones.
 */
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
    /**
     * FIX-STATE-08: optional camera driver seam so transition tests excite
     * the same production callbacks without hardware; production passes null.
     */
    adapterFactory: (() -> app.postmark.memory.capture.StillCameraAdapter)? = null,
    requestPermissionOnAttach: Boolean = true,
) {
    val matchState by viewModel.matchState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { viewModel.resetSession() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Scan a postcard", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        when (val current = matchState) {
            is ScanMatchUiState.AwaitingCapture ->
                CapturePair(viewModel, Modifier.fillMaxWidth(), adapterFactory, requestPermissionOnAttach)
            is ScanMatchUiState.Matching -> Text("Matching...", modifier = Modifier.testTag("scan_matching"))
            is ScanMatchUiState.Accepted -> Text(
                "Verified. Opening the capsule...",
                modifier = Modifier.testTag("scan_verified"),
            )
            is ScanMatchUiState.Chooser -> AmbiguityChooserScreen(
                rows = current.rows.map { row ->
                    ChooserHintRow(
                        candidateId = row.candidateId,
                        senderHandleSnapshot = row.senderHandleSnapshot ?: "Unknown sender",
                        yearAndDateLabel = row.createdAtEpochSeconds?.let {
                            java.time.LocalDate.ofEpochDay(it / 86400L).year.toString()
                        } ?: "Unknown date",
                        placeLabel = row.placeLabel,
                    )
                },
                onSelected = { hint -> viewModel.onChooserSelected(hint.candidateId) },
                onRecapture = {
                    // FIX-STATE-05: recapture resets the WHOLE flow and
                    // invalidates in-flight matching work.
                    viewModel.resetSession()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            is ScanMatchUiState.RecaptureGuidance -> Column {
                Text("No confident match. Recapture the front and back.", modifier = Modifier.testTag("scan_recapture"))
                OutlinedButton(onClick = viewModel::resetSession) { Text("Start over") }
                Spacer(Modifier.height(8.dp))
                CapturePair(viewModel, Modifier.fillMaxWidth(), adapterFactory, requestPermissionOnAttach)
            }
        }
    }
}

/** The side currently awaiting capture, rendered from its own controller. */
@Composable
private fun CapturePair(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
    adapterFactory: (() -> app.postmark.memory.capture.StillCameraAdapter)? = null,
    requestPermissionOnAttach: Boolean = true,
) {
    when (viewModel.captureSession.state) {
        ScanSessionState.AWAITING_FRONT -> CaptureAttemptSurface(
            title = "postcard front",
            controller = viewModel.frontAttempt,
            shutterTag = "capture_shutter_front",
            retakeTag = "capture_retake_front",
            onBeginAttempt = viewModel::beginFrontCapture,
            onDelivered = viewModel::deliverFrontJpeg,
            onRetake = viewModel::retakeFront,
            modifier = modifier,
            adapterFactory = adapterFactory,
            requestPermissionOnAttach = requestPermissionOnAttach,
        )

        ScanSessionState.AWAITING_BACK -> CaptureAttemptSurface(
            title = "prepared back",
            controller = viewModel.backAttempt,
            shutterTag = "capture_shutter_back",
            retakeTag = "capture_retake_back",
            onBeginAttempt = viewModel::beginBackCapture,
            onDelivered = viewModel::deliverBackJpeg,
            onRetake = viewModel::retakeBack,
            modifier = modifier,
            adapterFactory = adapterFactory,
            requestPermissionOnAttach = requestPermissionOnAttach,
        )

        ScanSessionState.CONSUMED -> Unit
        ScanSessionState.READY_FOR_MATCHING -> Unit
    }
}
