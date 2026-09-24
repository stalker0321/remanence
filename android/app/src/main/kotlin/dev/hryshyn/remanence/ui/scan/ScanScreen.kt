package dev.hryshyn.remanence.ui.scan

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.capture.CaptureAttemptSurface
import dev.hryshyn.remanence.scan.ScanSessionState
import dev.hryshyn.remanence.sync.IncomingAcceptanceDiagnostics
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

/**
 * M2-F0-07: the production Scan surface. Entry renders the honest FRONT-only
 * capture flow - one FRONT still through the real SIFT pipeline - before any
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

    DisposableEffect(Unit) {
        onDispose(onScreenDispose)
    }

    // Single route arrival: the root already carries Home -> Scan, so the
    // capture entry snaps on this screen's first mount instead of stacking
    // a second fade on top. Later returns to capture (recapture) arrive
    // with the live regime. Waiting/chooser/result branches mount only via
    // in-Scan transitions, after the root arrival is done, so they keep
    // their own entries. The camera lifecycle below is untouched.
    val entryMotion = rememberScanEntryMotion(rememberAstraMotion())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // FIX-STATE-14: named scroll container so layout tests prove the
            // step content scrolls INSIDE the bounded root region.
            .testTag("scan_screen_scroll")
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(stringResource(R.string.hold_scan_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when (val current = matchState) {
            // ASTRA-FIDELITY: entry snaps on first mount (root arrival
            // covers it) and arrives routinely on later returns.
            // The FRONT camera lifecycle (adapter bind/release, permission,
            // controller) is untouched — only the arrival is wrapped.
            is ScanMatchUiState.AwaitingCapture -> AstraEnterTransition(
                motion = entryMotion,
            ) {
                // Capture-once display frame: shares the delivered pixels
                // (no copy) and converts on change only. Null until the
                // current delivery decodes — and always null again once the
                // attempt leaves Processing (terminal/retake/reset paths in
                // the ViewModel clear it).
                val displayStill by viewModel.frontDisplayStill.collectAsStateWithLifecycle()
                val displayImage = remember(displayStill) { displayStill?.asImageBitmap() }
                FrontCapture(
                    viewModel,
                    Modifier.fillMaxWidth(),
                    adapterFactory,
                    requestPermissionOnAttach,
                    processingStill = displayImage,
                )
            }
            // ASTRA-FIDELITY: post-scan waiting states share one waiting
            // group — the drawn postcard carries the wait while only the
            // status copy switches (design/motion/render.mjs:42-46).
            is ScanMatchUiState.Matching,
            is ScanMatchUiState.Accepted,
            is ScanMatchUiState.MaterialPending -> ScanWaitingGroup(
                state = current,
                motion = rememberAstraMotion(),
                // Real recovery: reset the whole flow, same as recapture.
                onStopScanning = viewModel::resetSession,
                modifier = Modifier.fillMaxWidth(),
            )
            // ASTRA-FIDELITY: chooser arrival goes through the branch
            // wrapper (routine entry transition). Mapping and both actions
            // are verbatim production behavior.
            is ScanMatchUiState.Chooser -> ScanChooserBranch(
                rows = current.rows.map { row ->
                    ChooserHintRow(
                        candidateId = row.candidateId,
                        primarySenderLabel = chooserPrimaryLabel(row.trustedSenderHandle),
                        senderIdentityVerified = chooserTrustedHandle(row.trustedSenderHandle) != null,
                        claimedSenderHandle = row.senderHandleSnapshot,
                        yearAndDateLabel = row.createdAtEpochSeconds?.let {
                            java.time.LocalDate.ofEpochDay(it / 86400L).year.toString()
                        } ?: stringResource(R.string.hold_chooser_unknown_date),
                        placeLabel = row.placeLabel,
                    )
                },
                onSelected = { hint -> viewModel.onChooserSelected(hint.candidateId) },
                onRecapture = {
                    // FIX-STATE-05: recapture resets the WHOLE flow and
                    // invalidates in-flight matching work.
                    viewModel.resetSession()
                },
                motion = rememberAstraMotion(),
                modifier = Modifier.fillMaxWidth(),
            )
            // ASTRA-FIDELITY: result states arrive with the same routine
            // entry transition as the waiting group (study arrival beat).
            // Copy and actions are the honest production ones, unchanged.
            is ScanMatchUiState.RecaptureGuidance -> AstraEnterTransition(
                motion = rememberAstraMotion(),
            ) {
                Column {
                    Text(stringResource(R.string.hold_nomatch), modifier = Modifier.testTag("scan_recapture"))
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::resetSession,
                        modifier = Modifier.testTag("scan_recapture_action"),
                    ) { Text(stringResource(R.string.hold_scan_again)) }
                }
            }
            is ScanMatchUiState.IndexUnavailable -> AstraEnterTransition(
                motion = rememberAstraMotion(),
            ) {
                Column {
                    Text(
                        stringResource(R.string.hold_scan_index_body),
                        modifier = Modifier.testTag("scan_index_unavailable"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::retryIndexSync,
                        modifier = Modifier.testTag("scan_index_retry"),
                    ) { Text(stringResource(R.string.hold_retry)) }
                }
            }
        }
    }
}

/**
 * Single-arrival gate for the capture entry: the first mount of this Scan
 * screen composes under the root Home -> Scan boundary arrival, so the
 * entry reports INSTANT (snap, no stacked fade). Once entered, the live
 * regime applies — including later returns to capture after recapture.
 */
@Composable
internal fun rememberScanEntryMotion(
    liveMotion: AstraMotionSpec.Resolved,
): AstraMotionSpec.Resolved {
    // First mount rides the root Home -> Scan boundary arrival: report
    // INSTANT so the entry snaps instead of doubling the fade. Rotation
    // keeps the latched value, so rotation never replays the arrival.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    return if (entered) liveMotion else AstraMotionSpec.Resolved.INSTANT
}

/** The FRONT capture, rendered from its authoritative controller. */
@Composable
private fun FrontCapture(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
    adapterFactory: (() -> dev.hryshyn.remanence.capture.StillCameraAdapter)? = null,
    requestPermissionOnAttach: Boolean = true,
    processingStill: ImageBitmap? = null,
) {
    when (viewModel.captureSession.state) {
        ScanSessionState.AWAITING_FRONT -> CaptureAttemptSurface(
            controller = viewModel.frontAttempt,
            shutterTag = "capture_shutter_front",
            retakeTag = "capture_retake_front",
            onBeginAttempt = viewModel::beginFrontCapture,
            onDelivered = viewModel::deliverFrontJpeg,
            onRetake = viewModel::retakeFront,
            modifier = modifier,
            adapterFactory = adapterFactory,
            requestPermissionOnAttach = requestPermissionOnAttach,
            processingStill = processingStill,
        )

        ScanSessionState.CONSUMED -> Unit
        ScanSessionState.READY_FOR_MATCHING -> Unit
    }
}
