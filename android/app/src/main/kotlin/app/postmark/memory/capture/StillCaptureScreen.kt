package app.postmark.memory.capture

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner

/**
 * UI seams so state tests never touch camera hardware; production passes null.
 */
class CaptureTestHooks(
    val previewHost: @Composable (
        modifier: Modifier,
        onBound: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    val onCaptureRequested: () -> Unit,
)

/**
 * Shared permission/preview shell for exactly one deliberate still capture
 * (docs/implementation-plan.md M1-R13). Renders guidance for every
 * [CapturePermissionStep]/[StillCapturePhase] combination and keeps raw bytes
 * out of composition state: delivered JPEGs travel only through
 * [onStillCaptured].
 */
@Composable
fun StillCaptureScreen(
    shell: SingleStillCaptureShell,
    onStillCaptured: (ByteArray) -> Unit,
    onOpenAppSettings: () -> Unit = {},
    testHooks: CaptureTestHooks? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { CameraXPreviewBinder.createImageCapture() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // A denial without the system's ask-again affordance counts as
        // permanent; refining this requires instrumentation evidence (M1).
        val canAskAgain = granted ||
            (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true
        shell.onPermissionResult(granted, canAskAgain)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when (shell.permission) {
            CapturePermissionStep.NotRequested -> {
                GuidanceText(
                    text = "Postmark needs the camera to photograph your postcard once.",
                    tag = "capture_permission_rationale",
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.testTag("capture_request_permission"),
                ) {
                    Text("Allow camera")
                }
            }

            CapturePermissionStep.DeniedRetryable -> {
                GuidanceText(
                    text = "Camera access was declined. Capture is impossible without it.",
                    tag = "capture_denied_note",
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.testTag("capture_request_permission"),
                ) {
                    Text("Ask again")
                }
            }

            CapturePermissionStep.PermanentlyDenied -> {
                GuidanceText(
                    text = "Camera access is blocked in system settings. Enable it there to continue.",
                    tag = "capture_blocked_note",
                )
                Button(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.testTag("capture_open_settings"),
                ) {
                    Text("Open settings")
                }
            }

            CapturePermissionStep.Granted -> GrantedCaptureContent(
                shell = shell,
                context = context,
                lifecycleOwner = lifecycleOwner,
                imageCapture = imageCapture,
                onStillCaptured = onStillCaptured,
                testHooks = testHooks,
            )
        }
    }
}

@Composable
private fun GrantedCaptureContent(
    shell: SingleStillCaptureShell,
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
    imageCapture: ImageCapture,
    onStillCaptured: (ByteArray) -> Unit,
    testHooks: CaptureTestHooks?,
) {
    // Hosted once for the whole granted lifetime; phase changes never
    // recreate the underlying PreviewView or rebind the camera.
    if (testHooks == null) {
        RealCameraSurface(
            shell = shell,
            context = context,
            lifecycleOwner = lifecycleOwner,
            imageCapture = imageCapture,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        testHooks.previewHost(
            Modifier.fillMaxWidth(),
            { shell.onPreviewBound() },
            { reason -> shell.onCaptureFailed(reason) },
        )
    }
    when (val phase = shell.phase) {
        StillCapturePhase.BindingPreview ->
            GuidanceText(text = "Starting camera…", tag = "capture_binding_status")

        StillCapturePhase.PreviewReady -> Button(
            onClick = {
                shell.onCaptureStarted()
                if (testHooks == null) {
                    CameraXPreviewBinder.captureOneStill(
                        context = context,
                        imageCapture = imageCapture,
                        onDelivered = { bytes ->
                            shell.onStillDelivered()
                            onStillCaptured(bytes)
                        },
                        onError = { reason -> shell.onCaptureFailed(reason) },
                    )
                } else {
                    testHooks.onCaptureRequested()
                }
            },
            modifier = Modifier.testTag("capture_still_action"),
        ) {
            Text("Capture one photo")
        }

        StillCapturePhase.Capturing ->
            GuidanceText(text = "Capturing…", tag = "capture_capturing_status")

        StillCapturePhase.Delivered ->
            GuidanceText(text = "Still captured.", tag = "capture_delivered_status")

        is StillCapturePhase.Failed ->
            GuidanceText(text = phase.reason, tag = "capture_failed_status")

        null -> GuidanceText(text = "Preparing…", tag = "capture_binding_status")
    }
}

@Composable
private fun RealCameraSurface(
    shell: SingleStillCaptureShell,
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
    imageCapture: ImageCapture,
    modifier: Modifier,
) {
    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                CameraXPreviewBinder.bind(
                    context = viewContext,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    imageCapture = imageCapture,
                    onBound = { shell.onPreviewBound() },
                    onError = { reason -> shell.onCaptureFailed(reason) },
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun GuidanceText(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(tag),
    )
}
