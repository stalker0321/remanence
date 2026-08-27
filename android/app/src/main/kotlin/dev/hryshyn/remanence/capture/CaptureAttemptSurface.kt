package dev.hryshyn.remanence.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hryshyn.remanence.core.recognition.PostcardGuideGeometry
import dev.hryshyn.remanence.core.recognition.QualityReason

/**
 * Preview height budget for one capture attempt. FIX-STATE-04: the preview is
 * capped so rejection/failure panels, guidance text, and actions stay visible
 * and reachable on small phones instead of being pushed under the camera.
 */
val CAPTURE_PREVIEW_MAX_HEIGHT = 320.dp

/**
 * FIX-STATE-09: THE deterministic nonzero floor of the camera area. A bare
 * `heightIn(max)` lets the hosted PreviewView measure to zero height; the
 * floor guarantees an always-visible viewfinder on every screen size while
 * the cap keeps recovery panels reachable below it.
 */
val CAPTURE_PREVIEW_MIN_HEIGHT = 180.dp

/**
 * FIX-STATE-01/04: THE shared production rendering of one capture attempt.
 * It renders exclusively from the authoritative [CaptureAttemptController]:
 *
 * - Processing is always shown while the pipeline runs;
 * - Rejected shows the reasons INSTEAD of the camera plus a real Retake
 *   action; repeating an identical rejection still works because terminal
 *   payloads carry distinct monotonic attempt ids;
 * - Failed shows a display-safe message plus the same Retake action;
 * - the camera adapter lives exactly one binding cycle
 *   ([CaptureAttemptController.bindEpoch]) and is released on dispose or
 *   step transition; late hardware callbacks are inert after release;
 *   its preview composes in the SAME commit that creates it and bind()
 *   only runs after that commit, so the hosted PreviewView always exists;
 * - permission recovery (ask again / settings note) stays available.
 *
 * The [adapterFactory] seam lets tests drive the same production callbacks
 * without camera hardware (FIX-STATE-08).
 */
@Composable
fun CaptureAttemptSurface(
    title: String,
    controller: CaptureAttemptController,
    shutterTag: String,
    retakeTag: String,
    onBeginAttempt: () -> Boolean,
    onDelivered: (ByteArray) -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
    adapterFactory: (() -> StillCameraAdapter)? = null,
    /**
     * FIX-STATE-08: when true (production), the surface resolves the system
     * camera permission itself on attach; tests set false and resolve the
     * controller's permission explicitly.
     */
    requestPermissionOnAttach: Boolean = true,
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onPermissionResolved(
            resolveCapturePermissionStep(
                granted = granted,
                shouldShowRationale = cameraAskAgainPossible(context),
            ),
        )
    }

    LaunchedEffect(controller) {
        if (requestPermissionOnAttach && controller.permission == CapturePermissionStep.NotRequested) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                controller.onPermissionResult(granted = true, canAskAgain = false)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Capture the $title", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when (controller.permission) {
            CapturePermissionStep.NotRequested ->
                Text(
                    "Requesting camera permission...",
                    modifier = Modifier.testTag("capture_permission_progress"),
                )

            CapturePermissionStep.DeniedRetryable -> Column {
                Text("Camera permission was declined.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Ask again")
                }
            }

            CapturePermissionStep.PermanentlyDenied ->
                Text(
                    "Camera access is permanently denied; enable it in Settings.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("capture_permanently_denied"),
                )

            CapturePermissionStep.Granted -> GrantedAttemptContent(
                controller = controller,
                shutterTag = shutterTag,
                retakeTag = retakeTag,
                onBeginAttempt = onBeginAttempt,
                onDelivered = onDelivered,
                onRetake = onRetake,
                adapterFactory = adapterFactory,
            )
        }
    }
}

@Composable
private fun GrantedAttemptContent(
    controller: CaptureAttemptController,
    shutterTag: String,
    retakeTag: String,
    onBeginAttempt: () -> Boolean,
    onDelivered: (ByteArray) -> Unit,
    onRetake: () -> Unit,
    adapterFactory: (() -> StillCameraAdapter)?,
) {
    when (val phase = controller.phase) {
        is CaptureAttemptPhase.Rejected -> TerminalPanel(
            reasons = phase.reasons,
            failedMessage = null,
            retakeTag = retakeTag,
            onRetake = onRetake,
        )

        is CaptureAttemptPhase.Failed -> TerminalPanel(
            reasons = emptySet(),
            failedMessage = phase.message,
            retakeTag = retakeTag,
            onRetake = onRetake,
        )

        CaptureAttemptPhase.Accepted -> Text(
            "Captured.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("capture_accepted_status"),
        )

        else -> LivePreviewContent(
            controller = controller,
            phase = phase,
            shutterTag = shutterTag,
            onBeginAttempt = onBeginAttempt,
            onDelivered = onDelivered,
            adapterFactory = adapterFactory,
        )
    }
}

@Composable
private fun LivePreviewContent(
    controller: CaptureAttemptController,
    phase: CaptureAttemptPhase?,
    shutterTag: String,
    onBeginAttempt: () -> Boolean,
    onDelivered: (ByteArray) -> Unit,
    adapterFactory: (() -> StillCameraAdapter)?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // First-camera-entry crash fix: THE ADAPTER EXISTS DURING COMPOSITION,
    // created exactly once per binding epoch (plus context/lifecycle owner).
    // The factory lambda itself is deliberately NOT a remember key: callers
    // may pass a fresh lambda identity on every recomposition, and keying on
    // it would churn adapters. The epoch is the sole binding-cycle key.
    val realFactory: () -> StillCameraAdapter =
        adapterFactory ?: { CameraXStillCameraAdapter(context, lifecycleOwner) }
    val adapter = remember(context, lifecycleOwner, controller.bindEpoch) { realFactory() }

    // Releases EXACTLY this adapter when the epoch replaces it or this
    // content leaves composition; late hardware callbacks stay inert by
    // the adapter's own release contract.
    DisposableEffect(adapter) {
        onDispose { adapter.release() }
    }

    // Binds ONLY from an effect that runs after the composition commit that
    // composed adapter.preview below, so the hosted PreviewView already
    // exists - CameraXStillCameraAdapter.bind() requires it.
    LaunchedEffect(adapter) {
        val phaseAtBind = controller.phase
        val allowAlreadyReady = phaseAtBind is CaptureAttemptPhase.Ready
        if (!allowAlreadyReady && phaseAtBind !is CaptureAttemptPhase.Binding) return@LaunchedEffect
        val callbackToken = controller.currentBindingCallbackToken()
        adapter.bind(
            // The token makes a callback queued across reset/re-entry inert,
            // while current binding violations remain loud in the controller.
            onReady = { controller.onPreviewBound(callbackToken, allowAlreadyReady) },
            onError = { reason -> controller.onBindFailed(callbackToken, reason, allowAlreadyReady) },
        )
    }

    Column {
        // FIX-STATE-09: the viewfinder area is DETERMINISTIC - a 3:4 portrait
        // fraction of the available width, clamped to [MIN, MAX], and further
        // capped by a fraction of the SCREEN height so on short phones the
        // shutter stays visible without scrolling.
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp
        val effectiveMax = minOf(CAPTURE_PREVIEW_MAX_HEIGHT, screenHeight * 0.45f)
        androidx.compose.foundation.layout.BoxWithConstraints {
            val desired = maxWidth * 4f / 3f
            val lowerBound = minOf(CAPTURE_PREVIEW_MIN_HEIGHT, effectiveMax)
            val previewHeight = desired.coerceIn(lowerBound, effectiveMax)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .testTag("capture_preview"),
            ) {
                // Fills the guaranteed area exactly; the hosted surface can
                // never measure to zero height. Composed in the SAME commit
                // that created the adapter, before the bind effect runs.
                adapter.preview(Modifier.matchParentSize())
                PostcardGuideOverlay(modifier = Modifier.matchParentSize())
                Text(
                    "Keep all four postcard edges inside the landscape outline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.70f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("postcard_guide_instruction"),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when (phase) {
            CaptureAttemptPhase.Binding ->
                Text("Starting camera…", modifier = Modifier.testTag("capture_binding_status"))

            CaptureAttemptPhase.Ready -> ShutterButton(
                tag = shutterTag,
                enabled = true,
                onClick = {
                    if (onBeginAttempt()) {
                        adapter.captureStill(
                            onDelivered = onDelivered,
                            onError = { reason -> controller.fail(reason) },
                        )
                    }
                },
            )

            CaptureAttemptPhase.Capturing -> Column {
                Text("Capturing…", modifier = Modifier.testTag("capture_capturing_status"))
                Spacer(Modifier.height(8.dp))
                ShutterButton(tag = shutterTag, enabled = false, onClick = {})
            }

            CaptureAttemptPhase.Processing -> ProcessingStatus(shutterTag)

            else -> Unit
        }
    }
}

/**
 * Visible capture guide. Its normalized rectangle is the same geometry the
 * still processor uses when contour detection has no credible quadrilateral.
 */
@Composable
private fun PostcardGuideOverlay(modifier: Modifier) {
    Canvas(modifier = modifier.testTag("postcard_guide_overlay")) {
        val guide = PostcardGuideGeometry.normalizedFor(size.width.toDouble(), size.height.toDouble())
        val left = (guide.left * size.width).toFloat()
        val top = (guide.top * size.height).toFloat()
        val right = (guide.right * size.width).toFloat()
        val bottom = (guide.bottom * size.height).toFloat()
        // The dark under-stroke keeps the guide legible over both bright and
        // dark camera content; the white line is the user-facing outline.
        drawRect(
            color = Color.Black.copy(alpha = 0.85f),
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = Stroke(width = 7f),
        )
        drawRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = Stroke(width = 3f),
        )
    }
}

@Composable
private fun ShutterButton(tag: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text("Capture")
    }
}

@Composable
private fun ProcessingStatus(shutterTag: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.testTag("capture_processing_spinner"),
            )
            Spacer(Modifier.width(8.dp))
            Text("Processing…", modifier = Modifier.testTag("capture_processing_status"))
        }
        Spacer(Modifier.height(8.dp))
        ShutterButton(tag = shutterTag, enabled = false, onClick = {})
    }
}

/**
 * FIX-STATE-04: rejection/failure REPLACE the camera so nothing hides under a
 * large preview; the Retake action is real, visible, and always reachable.
 */
@Composable
private fun TerminalPanel(
    reasons: Set<QualityReason>,
    failedMessage: String?,
    retakeTag: String,
    onRetake: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("capture_terminal_panel")) {
        if (reasons.isNotEmpty()) {
            // The panel owns the single real Retake action.
            QualityRejectionPanel(
                reasons = reasons,
                onRecapture = onRetake,
                recaptureTag = retakeTag,
            )
        } else {
            Text(
                text = "Capture failed:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("capture_failed_header"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = failedMessage ?: "unknown failure",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("capture_failed_message"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth().testTag(retakeTag)) {
                Text("Retake")
            }
        }
    }
}
