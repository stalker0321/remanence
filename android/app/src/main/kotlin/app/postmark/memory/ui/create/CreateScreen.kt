package app.postmark.memory.ui.create

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.postmark.memory.capture.CapturePermissionStep
import app.postmark.memory.capture.CameraXPreviewBinder
import app.postmark.memory.capture.PreparedBackItem
import app.postmark.memory.capture.QualityFailureScreen
import app.postmark.memory.capture.SingleStillCaptureShell
import app.postmark.memory.capture.cameraAskAgainPossible
import app.postmark.memory.capture.resolveCapturePermissionStep

/**
 * FIX-M1-007-11: the production Create surface. Every control is bound to the
 * real [CreateViewModel] - directory resolve and explicit confirmation,
 * CameraX stills through the ORB processor into sealed persistence, the
 * prepared-back checklist, the Photo Picker (exactly 3-5), the bounded note,
 * and the single sealing path into the ciphertext outbox. No step advances
 * without its real gate.
 */
@Composable
fun CreateScreen(
    viewModel: CreateViewModel,
    modifier: Modifier = Modifier,
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val publishError by viewModel.publishError.collectAsStateWithLifecycle()

    // Leaving the create flow (exit, logout, navigation away) tears the
    // session down: confirmed recipient and staged plaintext never linger.
    DisposableEffect(Unit) {
        onDispose { viewModel.endSession() }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Create a capsule", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (step) {
                CreateViewModel.Step.RECIPIENT_LOOKUP -> "1 - Resolve the recipient handle"
                CreateViewModel.Step.RECIPIENT_CONFIRM -> "2 - Confirm the resolved recipient"
                CreateViewModel.Step.FRONT -> "3 - Capture the postcard FRONT"
                CreateViewModel.Step.BACK_CHECKLIST -> "4 - Prepare the back completely"
                CreateViewModel.Step.BACK -> "5 - Capture the prepared BACK"
                CreateViewModel.Step.CONTENT -> "6 - Choose 3-5 photos and an optional note"
                CreateViewModel.Step.PUBLISHING -> "7 - Encrypting and staging"
                CreateViewModel.Step.PUBLISHED -> "Done - Capsule sealed into the outbox"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("create_step_label"),
        )
        Spacer(Modifier.height(12.dp))

        when (step) {
            CreateViewModel.Step.RECIPIENT_LOOKUP -> RecipientLookupContent(viewModel)
            CreateViewModel.Step.RECIPIENT_CONFIRM -> RecipientConfirmContent(viewModel)
            CreateViewModel.Step.FRONT -> CaptureAttemptContent(
                title = "front",
                viewModel = viewModel,
                onJpeg = viewModel::onFrontJpeg,
            )
            CreateViewModel.Step.BACK_CHECKLIST -> PreparedBackChecklist(viewModel)
            CreateViewModel.Step.BACK -> CaptureAttemptContent(
                title = "prepared back",
                viewModel = viewModel,
                onJpeg = viewModel::onBackJpeg,
            )
            CreateViewModel.Step.CONTENT -> ContentStepContent(viewModel)
            CreateViewModel.Step.PUBLISHING -> Text("Encrypting locally...", modifier = Modifier.testTag("create_publishing"))
            CreateViewModel.Step.PUBLISHED -> Text(
                "Capsule sealed. Send the physical postcard.",
                modifier = Modifier.testTag("create_published"),
            )
        }

        val error = publishError
        if (error != null && step != CreateViewModel.Step.PUBLISHING) {
            Spacer(Modifier.height(8.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("create_error"))
        }
    }
}

@Composable
private fun RecipientLookupContent(viewModel: CreateViewModel) {
    val handle by viewModel.pickerVm.handle.collectAsStateWithLifecycle()
    val state by viewModel.pickerVm.state.collectAsStateWithLifecycle()

    Column {
        OutlinedTextField(
            value = handle,
            onValueChange = viewModel::onHandleChange,
            label = { Text("Recipient handle") },
            modifier = Modifier.fillMaxWidth().testTag("create_handle_input"),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = viewModel::lookupRecipient,
            enabled = viewModel.pickerVm.canLookup,
            modifier = Modifier.testTag("create_lookup_button"),
        ) { Text("Resolve handle") }
        Spacer(Modifier.height(8.dp))
        when (val current = state) {
            is RecipientLookupUiState.Resolved -> LaunchedEffect(current.snapshot) {
                viewModel.onResolved(current.snapshot)
            }
            RecipientLookupUiState.NotFound -> Text("No account uses that handle.")
            is RecipientLookupUiState.Failed ->
                Text(current.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

@Composable
private fun RecipientConfirmContent(viewModel: CreateViewModel) {
    // FIX-M1-ONDEVICE-01: the confirmation screen renders the PENDING resolved
    // snapshot; `confirmedRecipient` stays null until the explicit Confirm.
    val pending by viewModel.pendingRecipient.collectAsStateWithLifecycle()
    val resolved = pending
    if (resolved == null) {
        // RECIPIENT_CONFIRM without a pending resolve is an impossible state;
        // fail closed with an explicit error instead of an eternal blank screen.
        Column {
            Text(
                "No resolved recipient is pending. Resolve the handle again.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("create_confirm_missing_pending"),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::restartLookup,
                modifier = Modifier.testTag("create_confirm_back_to_lookup"),
            ) { Text("Back to handle lookup") }
        }
        return
    }
    RecipientConfirmationScreen(
        snapshot = resolved,
        onConfirm = viewModel::confirmRecipient,
        onCancel = viewModel::restartLookup,
    )
}

/**
 * One camera attempt: fresh [SingleStillCaptureShell], permission request,
 * PreviewView binding through CameraXPreviewBinder, one deliberate still.
 * A quality rejection bumps the attempt counter so a clean shell backs the
 * guided recapture; nothing else resets a consumed capture.
 */
@Composable
private fun CaptureAttemptContent(
    title: String,
    viewModel: CreateViewModel,
    onJpeg: (ByteArray, SingleStillCaptureShell) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var attempt by remember { mutableIntStateOf(0) }
    val rejection by viewModel.qualityRejection.collectAsStateWithLifecycle()
    LaunchedEffect(rejection) { if (rejection.isNotEmpty()) attempt++ }

    key(attempt) {
        val shell = remember(attempt) { SingleStillCaptureShell() }
        var imageCapture by remember(attempt) { mutableStateOf<androidx.camera.core.ImageCapture?>(null) }
        var previewView by remember(attempt) { mutableStateOf<PreviewView?>(null) }

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
            if (granted) {
                shell.onPermissionResult(true, false)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        Column {
            Text("Capture the $title", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            when (shell.permission) {
                CapturePermissionStep.Granted -> {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { pv ->
                                previewView = pv
                                val capture = CameraXPreviewBinder.createImageCapture()
                                imageCapture = capture
                                CameraXPreviewBinder.bind(
                                    ctx,
                                    lifecycleOwner,
                                    pv,
                                    capture,
                                    onBound = { shell.onPreviewBound() },
                                    onError = { reason ->
                                        runCatching { shell.onCaptureFailed(reason) }
                                    },
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
                            CameraXPreviewBinder.captureOneStill(
                                context,
                                capture,
                                onDelivered = { jpeg -> onJpeg(jpeg, shell) },
                                onError = { reason ->
                                    runCatching { shell.onCaptureFailed(reason) }
                                },
                            )
                        },
                        enabled = shell.phase is app.postmark.memory.capture.StillCapturePhase.PreviewReady,
                        modifier = Modifier.testTag("capture_shutter"),
                    ) { Text("Capture") }
                    if (shell.phase is app.postmark.memory.capture.StillCapturePhase.Failed) {
                        Text(
                            "Capture failed: ${(shell.phase as app.postmark.memory.capture.StillCapturePhase.Failed).reason}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PreparedBackChecklist(viewModel: CreateViewModel) {
    Column {
        Text(
            "Prepare the back completely before capturing it",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        PreparedBackItem.entries.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = viewModel.backGate.checked[item] == true,
                    onCheckedChange = { checked -> viewModel.backGate.setChecked(item, checked) },
                )
                Text(item.name.lowercase().replace('_', ' '))
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = viewModel::proceedToBackChecklist,
            enabled = viewModel.backGate.ready,
            modifier = Modifier.testTag("create_back_ready"),
        ) { Text("Continue to back capture") }
    }
}

@Composable
private fun ContentStepContent(viewModel: CreateViewModel) {
    val context = LocalContext.current
    val rejection by viewModel.qualityRejection.collectAsStateWithLifecycle()
    var selectionCount by remember { mutableIntStateOf(viewModel.photoSelection.selectedIds.size) }

    val pickerContract = remember {
        ActivityResultContracts.PickMultipleVisualMedia(PhotoSelectionState.MAX_PHOTOS)
    }
    val pickerLauncher = rememberLauncherForActivityResult(pickerContract) { uris: List<android.net.Uri> ->
        viewModel.photoSelection.clear()
        uris.forEach { uri ->
            viewModel.photoSelection.toggle(uri.toString())
        }
        selectionCount = viewModel.photoSelection.selectedIds.size
    }

    Column {
        Text("Choose 3-5 photos", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                pickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.testTag("create_pick_photos"),
        ) { Text("Open photo picker") }
        Spacer(Modifier.height(4.dp))
        Text(
            "Selected: $selectionCount of 3-5",
            modifier = Modifier.testTag("create_selection_count"),
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = viewModel.noteEditor.text,
            onValueChange = viewModel.noteEditor::onChange,
            label = { Text("Optional note (${NoteEditorState.MAX_NOTE_BYTES} byte limit)") },
            modifier = Modifier.fillMaxWidth().testTag("create_note_input"),
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = viewModel::startPublishing,
            enabled = viewModel.photoSelection.canProceed && viewModel.noteEditor.canIncludeInCapsule,
            modifier = Modifier.testTag("create_publish"),
        ) { Text("Encrypt and stage capsule") }

        if (rejection.isNotEmpty()) {
            QualityFailureScreen(reasons = rejection)
        }
    }
}
