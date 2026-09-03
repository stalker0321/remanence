package dev.hryshyn.remanence.ui.scan

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
import dev.hryshyn.remanence.capture.CaptureAttemptSurface
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.scan.ScanSessionState
import dev.hryshyn.remanence.sync.IncomingAcceptanceDiagnostics

/**
 * M2-F0-07: the production Scan surface. Entry renders the honest FRONT-only
 * capture flow - one FRONT still through the real ORB pipeline - before any
 * matching runs against the encrypted local index; the ambiguity chooser
 * shows only decrypted minimal hints; and a grant exists only after the full
 * crypto gate passes - manual selection included.
 *
 * FIX-STATE-01/04: capture attempts render exclusively from the authoritative
 * controller with visible Processing and real Retake recovery; the step
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
    adapterFactory: (() -> dev.hryshyn.remanence.capture.StillCameraAdapter)? = null,
    requestPermissionOnAttach: Boolean = true,
    onScreenDispose: () -> Unit = viewModel::resetSession,
) {
    val matchState by viewModel.matchState.collectAsStateWithLifecycle()
    val acceptanceDiagnostic by IncomingAcceptanceDiagnostics.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose(onScreenDispose)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // FIX-STATE-14: named scroll container so layout tests prove the
            // step content scrolls INSIDE the bounded root region.
            .testTag("scan_screen_scroll")
            .padding(16.dp),
    ) {
        Text("Scan a postcard", style = MaterialTheme.typography.titleLarge)
        if (BuildConfig.DEBUG) {
            Text(
                text = "Sync: $acceptanceDiagnostic",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("scan_sync_diagnostic"),
            )
        }
        Spacer(Modifier.height(8.dp))

        when (val current = matchState) {
            is ScanMatchUiState.AwaitingCapture ->
                FrontCapture(viewModel, Modifier.fillMaxWidth(), adapterFactory, requestPermissionOnAttach)
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
                Text("No confident match. Recapture the front.", modifier = Modifier.testTag("scan_recapture"))
                OutlinedButton(onClick = viewModel::resetSession) { Text("Start over") }
                Spacer(Modifier.height(8.dp))
                FrontCapture(viewModel, Modifier.fillMaxWidth(), adapterFactory, requestPermissionOnAttach)
            }
            is ScanMatchUiState.MaterialPending -> Text(
                text = if (current.connected) {
                    "Postcard recognized. Downloading it now…"
                } else {
                    "Postcard recognized. Connect to the internet to download it."
                },
                modifier = Modifier.testTag(
                    if (current.connected) "scan_material_pending_online" else "scan_material_pending_offline",
                ),
            )
        }
    }
}

/** The FRONT capture, rendered from its authoritative controller. */
@Composable
private fun FrontCapture(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
    adapterFactory: (() -> dev.hryshyn.remanence.capture.StillCameraAdapter)? = null,
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

        ScanSessionState.CONSUMED -> Unit
        ScanSessionState.READY_FOR_MATCHING -> Unit
    }
}
