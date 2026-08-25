package app.postmark.memory.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.postmark.memory.capture.CameraXPreviewBinder
import app.postmark.memory.capture.CapturePermissionStep
import app.postmark.memory.capture.QualityFailureScreen
import app.postmark.memory.capture.SingleStillCaptureShell
import app.postmark.memory.capture.cameraAskAgainPossible
import app.postmark.memory.capture.resolveCapturePermissionStep
import app.postmark.memory.scan.ScanSessionState
import postmark.core.recognition.QualityReason

/**
 * FIX-M1-007-12 / FIX-REVIEW-01: the production Scan surface. Entry renders
 * the honest capture flow - FRONT then BACK stills through the real ORB
 * pipeline - before any matching runs against the encrypted local index; the
 * ambiguity chooser shows only decrypted minimal hints; and a grant exists
 * only after the full crypto gate passes - manual selection included.
 */
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
) {
    val matchState by viewModel.matchState.collectAsStateWithLifecycle()
    val rejection by viewModel.qualityRejection.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { viewModel.resetSession() }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan a postcard", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        when (val current = matchState) {
            is ScanMatchUiState.AwaitingCapture -> CapturePair(viewModel, rejection)
            is ScanMatchUiState.Matching -> Text("Matching...", modifier = Modifier.testTag("scan_matching"))
            is ScanMatchUiState.Accepted -> Text(
                "Verified. Opening the capsule...",
                modifier = Modifier.testTag("scan_verified"),
            )
            is ScanMatchUiState.Chooser -> AmbiguityChooserContent(
                rows = current.rows,
                onSelected = viewModel::onChooserSelected,
            )
            is ScanMatchUiState.GuidedRecapture -> Column {
                Text("One plausible candidate: recapture both sides once more.")
                CapturePair(viewModel, rejection)
            }
            is ScanMatchUiState.ConfirmSingle -> Column {
                Text("Same single candidate again: confirm explicitly.")
                Button(
                    onClick = { viewModel.onChooserSelected(current.row.candidateId) },
                    modifier = Modifier.testTag("scan_confirm_single"),
                ) { Text("Open this capsule") }
                CapturePair(viewModel, rejection)
            }
            is ScanMatchUiState.RecaptureGuidance -> Column {
                Text("No confident match. Recapture the front and back.", modifier = Modifier.testTag("scan_recapture"))
                OutlinedButton(onClick = viewModel::resetSession) { Text("Start over") }
                CapturePair(viewModel, rejection)
            }
        }
    }
}

@Composable
private fun CapturePair(
    viewModel: ScanViewModel,
    rejection: Set<QualityReason>,
) {
    val state = viewModel.captureSession.state

    when (state) {
        ScanSessionState.AWAITING_FRONT -> CaptureAttemptContent(title = "front", viewModel = viewModel, isFront = true)
        ScanSessionState.AWAITING_BACK -> CaptureAttemptContent(title = "back", viewModel = viewModel, isFront = false)
        ScanSessionState.CONSUMED -> Unit
        ScanSessionState.READY_FOR_MATCHING -> Unit
    }

    if (rejection.isNotEmpty()) {
        QualityFailureScreen(reasons = rejection)
    }
}

@Composable
private fun AmbiguityChooserContent(
    rows: List<ChooserRow>,
    onSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.testTag("scan_chooser")) {
        Text("Which capsule matches this postcard?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        rows.forEach { row ->
            Button(
                onClick = { onSelected(row.candidateId) },
                modifier = Modifier.fillMaxWidth().testTag("scan_chooser_row"),
            ) {
                Column {
                    // Minimal locally decrypted hints only (docs/product.md 11).
                    Text(row.senderHandleSnapshot?.let { "@$it" } ?: "Unknown sender")
                    row.createdAtEpochSeconds?.let {
                        Text(java.time.LocalDate.ofEpochDay(it / 86400L).year.toString())
                    }
                    row.placeLabel?.let { Text(it) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** One camera attempt for one scan side; fresh shell per attempt. */
@Composable
private fun CaptureAttemptContent(
    title: String,
    viewModel: ScanViewModel,
    isFront: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var attempt by remember { mutableIntStateOf(0) }
    val rejection by viewModel.qualityRejection.collectAsStateWithLifecycle()
    LaunchedEffect(rejection) { if (rejection.isNotEmpty()) attempt++ }

    key(attempt) {
        val shell = remember(attempt) { SingleStillCaptureShell() }
        var imageCapture by remember(attempt) { mutableStateOf<androidx.camera.core.ImageCapture?>(null) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            // FIX-REVIEW-05: the OS ask-again signal decides retryable vs
            // permanent; a bare denial is no longer treated as retryable
            // forever (PermanentlyDenied is actually reachable).
            shell.onPermissionResolved(
                resolveCapturePermissionStep(
                    granted = granted,
                    shouldShowRationale = cameraAskAgainPossible(context),
                ),
            )
        }

        LaunchedEffect(shell) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) shell.onPermissionResult(true, false) else permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        Column {
            when (shell.permission) {
                CapturePermissionStep.Granted -> {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { pv ->
                                val capture = CameraXPreviewBinder.createImageCapture()
                                imageCapture = capture
                                CameraXPreviewBinder.bind(
                                    ctx,
                                    lifecycleOwner,
                                    pv,
                                    capture,
                                    onBound = { shell.onPreviewBound() },
                                    onError = { reason -> runCatching { shell.onCaptureFailed(reason) } },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(320.dp).testTag("capture_preview"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val capture = imageCapture ?: return@Button
                            shell.onCaptureStarted()
                            val deliver: (ByteArray) -> Unit = { jpeg ->
                                if (isFront) viewModel.onFrontJpeg(jpeg, shell)
                                else viewModel.onBackJpeg(jpeg, shell)
                            }
                            CameraXPreviewBinder.captureOneStill(
                                context,
                                capture,
                                onDelivered = deliver,
                                onError = { reason -> runCatching { shell.onCaptureFailed(reason) } },
                            )
                        },
                        enabled = shell.phase is app.postmark.memory.capture.StillCapturePhase.PreviewReady,
                        modifier = Modifier.testTag("capture_shutter_$title"),
                    ) { Text("Capture $title") }
                }
                CapturePermissionStep.DeniedRetryable -> Column {
                    Text("Camera permission was declined.")
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Ask again")
                    }
                }
                CapturePermissionStep.PermanentlyDenied ->
                    Text("Camera access is permanently denied; enable it in Settings.")
                CapturePermissionStep.NotRequested -> Text("Requesting camera permission...")
            }
        }
    }
}
