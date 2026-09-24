package dev.hryshyn.remanence.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.recognition.PostcardGuideGeometry
import dev.hryshyn.remanence.core.recognition.QualityReason

/**
 * Preview height budget for one capture attempt. The surface uses the largest
 * practical portrait frame (about 65--68% of the display height) and leaves
 * the existing scrollable flow layouts responsible for reaching the status,
 * shutter, and recovery actions on short phones.
 */
val CAPTURE_PREVIEW_MAX_HEIGHT = 520.dp

/**
 * FIX-STATE-09: THE deterministic nonzero floor of the camera area. A bare
 * `heightIn(max)` lets the hosted PreviewView measure to zero height; the
 * floor guarantees an always-visible viewfinder on every screen size while
 * the scrollable flow layouts keep recovery panels reachable below it.
 */
val CAPTURE_PREVIEW_MIN_HEIGHT = 180.dp

/** Width/height used by the responsive capture frame on portrait displays. */
private const val PORTRAIT_PREVIEW_ASPECT_RATIO = 3f / 4f

/** Width/height used by the responsive capture frame on landscape displays. */
private const val LANDSCAPE_PREVIEW_ASPECT_RATIO = 4f / 3f

private const val PORTRAIT_PREVIEW_HEIGHT_FRACTION = 0.68f

internal data class CapturePreviewSize(
    val width: Dp,
    val height: Dp,
)

internal fun capturePreviewMaxHeight(screenHeight: Dp): Dp {
    require(screenHeight > 0.dp)
    return maxOf(
        CAPTURE_PREVIEW_MIN_HEIGHT,
        minOf(CAPTURE_PREVIEW_MAX_HEIGHT, screenHeight * PORTRAIT_PREVIEW_HEIGHT_FRACTION),
    )
}

/**
 * Chooses the largest frame that fits the available width/height budget while
 * preserving the display-oriented camera frame ratio. The width is reduced
 * when the height cap binds; otherwise a full-width frame is retained.
 */
internal fun capturePreviewSize(
    maxWidth: Dp,
    effectiveMaxHeight: Dp,
    targetAspectRatio: Float,
): CapturePreviewSize {
    require(maxWidth > 0.dp)
    require(effectiveMaxHeight > 0.dp)
    require(targetAspectRatio > 0f)

    val lowerBound = minOf(CAPTURE_PREVIEW_MIN_HEIGHT, effectiveMaxHeight)
    val desiredHeight = maxWidth / targetAspectRatio
    val height = desiredHeight.coerceIn(lowerBound, effectiveMaxHeight)
    val width = minOf(maxWidth, height * targetAspectRatio)
    return CapturePreviewSize(width = width, height = height)
}

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
    controller: CaptureAttemptController,
    shutterTag: String,
    retakeTag: String,
    onBeginAttempt: () -> Boolean,
    onDelivered: (ByteArray) -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
    adapterFactory: (() -> StillCameraAdapter)? = null,
    /**
     * Capture-once display frame for the Processing phase. When non-null the
     * surface holds THIS still; when null a neutral fallback shows the same
     * status with no live preview. Either way the camera releases on accept
     * and no second capture can fire. Defaults to null so callers without a
     * decoded frame keep a safe fallback.
     */
    processingStill: ImageBitmap? = null,
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

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted && controller.permission != CapturePermissionStep.Granted) {
            // A denied camera has no live attempt. Re-enter through the same
            // permission and binding transitions; never manufacture Ready.
            controller.reset()
            controller.onPermissionResolved(CapturePermissionStep.Granted)
        }
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
        when (controller.permission) {
            CapturePermissionStep.NotRequested ->
                Text(
                    stringResource(R.string.hold_capture_permission_needed),
                    modifier = Modifier.testTag("capture_permission_progress"),
                )

            CapturePermissionStep.DeniedRetryable -> Column {
                Text(stringResource(R.string.hold_capture_permission_denied_title))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.hold_capture_permission_denied_body))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.hold_capture_permission_allow))
                }
            }

            CapturePermissionStep.PermanentlyDenied -> Column {
                Text(
                    stringResource(R.string.hold_capture_permission_blocked_title),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("capture_permanently_denied"),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.hold_capture_permission_blocked_body))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    settingsLauncher.launch(android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ))
                }, modifier = Modifier.testTag("capture_open_settings")) { Text(stringResource(R.string.hold_capture_open_settings)) }
            }

            CapturePermissionStep.Granted -> GrantedAttemptContent(
                controller = controller,
                shutterTag = shutterTag,
                retakeTag = retakeTag,
                onBeginAttempt = onBeginAttempt,
                onDelivered = onDelivered,
                onRetake = onRetake,
                adapterFactory = adapterFactory,
                processingStill = processingStill,
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
    processingStill: ImageBitmap?,
) {
    when (val phase = controller.phase) {
        is CaptureAttemptPhase.Rejected -> TerminalPanel(
            reasons = phase.reasons,
            diagnostic = phase.diagnostic,
            failedMessage = null,
            retakeTag = retakeTag,
            onRetake = onRetake,
        )

        is CaptureAttemptPhase.Failed -> TerminalPanel(
            reasons = emptySet(),
            diagnostic = null,
            failedMessage = phase.message,
            retakeTag = retakeTag,
            onRetake = onRetake,
        )

        CaptureAttemptPhase.Accepted -> Text(
            stringResource(R.string.hold_capture_accepted),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("capture_accepted_status"),
        )

        // Capture-once: the accepted frame is held while the pipeline runs.
        // LivePreviewContent is NOT composed here, so its adapter disposes
        // and the camera releases the moment capture is accepted — also when
        // the frame proved undecodable (neutral fallback, same release). No
        // shutter action exists in this state, so no second capture can fire.
        CaptureAttemptPhase.Processing ->
            ProcessingContent(still = processingStill, shutterTag = shutterTag)

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
        CaptureFrame(frameTag = "capture_preview") {
            // Composed in the SAME commit that created the adapter, before
            // the bind effect runs, so the hosted PreviewView always exists.
            adapter.preview(Modifier.matchParentSize())
            PostcardGuideOverlay(Modifier.matchParentSize())
            Text(
                stringResource(R.string.hold_capture_guide_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.70f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("postcard_guide_instruction"),
            )
        }
        Spacer(Modifier.height(8.dp))
        when (phase) {
            CaptureAttemptPhase.Binding ->
                Text(
                    stringResource(R.string.hold_capture_binding),
                    modifier = Modifier.testTag("capture_binding_status"),
                )

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
                Text(
                    stringResource(R.string.hold_capture_capturing),
                    modifier = Modifier.testTag("capture_capturing_status"),
                )
                Spacer(Modifier.height(8.dp))
                ShutterButton(tag = shutterTag, enabled = false, onClick = {})
            }

            // Processing never reaches the live preview: GrantedAttemptContent
            // routes it to ProcessingContent (still or neutral fallback).
            else -> Unit
        }
    }
}

/**
 * FIX-STATE-09: the viewfinder area is DETERMINISTIC - it follows the
 * display orientation, preserves its 3:4 portrait (or 4:3 landscape)
 * shape when the height cap binds, and keeps the shutter reachable on
 * short phones. Shared by the live preview and the capture-once still so
 * both fill the exact same frame. [frameTag] identifies the frame node;
 * the still passes null so preview-absence assertions stay meaningful.
 */
@Composable
private fun CaptureFrame(
    frameTag: String?,
    content: @Composable BoxScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val effectiveMax = capturePreviewMaxHeight(screenHeight)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val targetAspectRatio = if (configuration.screenWidthDp < configuration.screenHeightDp) {
            PORTRAIT_PREVIEW_ASPECT_RATIO
        } else {
            LANDSCAPE_PREVIEW_ASPECT_RATIO
        }
        val preview = capturePreviewSize(
            maxWidth = maxWidth,
            effectiveMaxHeight = effectiveMax,
            targetAspectRatio = targetAspectRatio,
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(preview.width)
                    .height(preview.height)
                    .then(if (frameTag != null) Modifier.testTag(frameTag) else Modifier),
                content = content,
            )
        }
    }
}

/**
 * Capture-once Processing surface: when the accepted still decoded, it is
 * held in the same frame the viewfinder used; otherwise a neutral fallback
 * shows the same status with no live preview at all. Either way the camera
 * is released (the preview is not composed), the phone-may-move copy shows,
 * and no capture action exists, so exactly one still was taken. The bitmap
 * is display-only: it never reaches the recognition pipeline, which keeps
 * using the delivered JPEG bytes from the same capture.
 */
@Composable
private fun ProcessingContent(
    still: ImageBitmap?,
    shutterTag: String,
) {
    Column {
        if (still != null) {
            CaptureFrame(frameTag = null) {
                // Fills the guaranteed area exactly like the live preview did.
                Image(
                    bitmap = still,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().testTag("capture_processing_still"),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            stringResource(R.string.hold_scan_still_hold),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("capture_still_hold_copy"),
        )
        Spacer(Modifier.height(8.dp))
        ProcessingStatus(shutterTag)
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
        Text(stringResource(R.string.hold_capture_shutter))
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
            Text(stringResource(R.string.hold_capture_processing), modifier = Modifier.testTag("capture_processing_status"))
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
    diagnostic: CaptureDiagnostic?,
    failedMessage: String?,
    retakeTag: String,
    onRetake: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("capture_terminal_panel")) {
        if (reasons.isNotEmpty()) {
            // The panel owns the single real Retake action.
            QualityRejectionPanel(
                reasons = reasons,
                diagnostic = diagnostic,
                onRecapture = onRetake,
                recaptureTag = retakeTag,
            )
        } else {
            Text(
                text = stringResource(R.string.hold_capture_failed_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("capture_failed_header"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.hold_capture_failed_body),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("capture_failed_message"),
            )
            if (BuildConfig.DEBUG) {
                failedMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("capture_debug_failure_message"),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth().testTag(retakeTag)) {
                Text(stringResource(R.string.hold_retry))
            }
        }
    }
}
