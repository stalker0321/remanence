package dev.hryshyn.remanence.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.capture.CaptureAttemptSurface

/**
 * FIX-M1-007-11: the production Create surface. Every control is bound to the
 * real [CreateViewModel] - directory resolve and explicit confirmation,
 * CameraX stills through the SIFT processor into sealed persistence, the
 * Photo Picker (exactly 3-5), the bounded note, and the single sealing path
 * into the ciphertext outbox. No step advances without its real gate.
 *
 * FIX-STATE-01/04: capture attempts render exclusively from the authoritative
 * controllers (Processing shown; Rejected/Failed replace the camera with
 * reasons plus a real Retake). FIX-STATE-04: the whole step content is
 * scrollable so errors and actions stay reachable on small phones.
 */
@Composable
fun CreateScreen(
    viewModel: CreateViewModel,
    modifier: Modifier = Modifier,
    /**
     * FIX-STATE-08: optional camera driver seam so transition tests excite
     * the same production callbacks without hardware; production passes null.
     */
    adapterFactory: (() -> dev.hryshyn.remanence.capture.StillCameraAdapter)? = null,
    requestPermissionOnAttach: Boolean = true,
    onScreenDispose: () -> Unit = {},
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val uploadStatus by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val publishError by viewModel.publishError.collectAsStateWithLifecycle()
    val flowError by viewModel.flowError.collectAsStateWithLifecycle()
    val revokeStatus by viewModel.revokeStatus.collectAsStateWithLifecycle()
    var showRevokeConfirmation by remember { mutableStateOf(false) }

    // The caller distinguishes true route exit from activity recreation. This
    // effect is intentionally not keyed by configuration so rotation does not
    // tear down an in-progress same-epoch Create session.
    DisposableEffect(Unit) {
        onDispose(onScreenDispose)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("create_screen_scroll")
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Create a capsule", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (step) {
                CreateViewModel.Step.RECIPIENT_LOOKUP -> "1 - Resolve the recipient handle"
                CreateViewModel.Step.RECIPIENT_CONFIRM -> "2 - Confirm the resolved recipient"
                CreateViewModel.Step.FRONT -> "3 - Capture the postcard FRONT"
                CreateViewModel.Step.CONTENT -> "4 - Choose 3-5 photos and an optional note"
                CreateViewModel.Step.PUBLISHING -> "5 - Encrypting and staging"
                CreateViewModel.Step.UPLOAD_PENDING -> "5 - Encrypted capsule queued for upload"
                CreateViewModel.Step.PUBLISHED -> "Done - Capsule published and ready"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("create_step_label"),
        )
        Spacer(Modifier.height(12.dp))

        when (step) {
            CreateViewModel.Step.RECIPIENT_LOOKUP -> RecipientLookupContent(viewModel)
            CreateViewModel.Step.RECIPIENT_CONFIRM -> RecipientConfirmContent(viewModel)
            CreateViewModel.Step.FRONT -> CaptureAttemptSurface(
                title = "postcard front",
                controller = viewModel.frontAttempt,
                shutterTag = "capture_shutter_front",
                retakeTag = "capture_retake_front",
                onBeginAttempt = viewModel::beginFrontCapture,
                onDelivered = viewModel::deliverFrontJpeg,
                onRetake = viewModel::retakeFront,
                adapterFactory = adapterFactory,
                requestPermissionOnAttach = requestPermissionOnAttach,
            )

            CreateViewModel.Step.CONTENT -> ContentStepContent(viewModel)
            CreateViewModel.Step.PUBLISHING -> Column {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.testTag("create_publishing_spinner"),
                )
                Spacer(Modifier.height(8.dp))
                Text("Encrypting locally...", modifier = Modifier.testTag("create_publishing"))
            }
            CreateViewModel.Step.UPLOAD_PENDING -> when (val status = uploadStatus) {
                is CreateViewModel.CreateUploadStatus.RetryableFailure -> Text(
                    createUploadPendingCopy(status),
                    modifier = Modifier.testTag("create_upload_retryable_failure"),
                )
                is CreateViewModel.CreateUploadStatus.TerminalFailure -> Text(
                    createUploadPendingCopy(status),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("create_upload_terminal_failure"),
                )
                else -> Text(
                    createUploadPendingCopy(status),
                    modifier = Modifier.testTag("create_upload_pending"),
                )
            }
            CreateViewModel.Step.PUBLISHED -> Column {
                Text(
                    "Capsule sealed. Send the physical postcard.",
                    modifier = Modifier.testTag("create_published"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cancellation stops future delivery when possible. A copy already received or decrypted by the recipient cannot be removed.",
                    modifier = Modifier.testTag("create_revoke_disclaimer"),
                )
                Spacer(Modifier.height(8.dp))
                when (val status = revokeStatus) {
                    CreateViewModel.CapsuleRevokeStatus.Idle -> Unit
                    CreateViewModel.CapsuleRevokeStatus.InFlight -> Text(
                        "Cancelling capsule...",
                        modifier = Modifier.testTag("create_revoke_in_flight"),
                    )
                    is CreateViewModel.CapsuleRevokeStatus.Succeeded -> Text(
                        "Capsule cancelled for future delivery. A copy already received or decrypted by the recipient is not removed.",
                        modifier = Modifier.testTag("create_revoke_success"),
                    )
                    is CreateViewModel.CapsuleRevokeStatus.Failed -> Text(
                        createRevokeCopy(status),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("create_revoke_error"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showRevokeConfirmation = true },
                    enabled = revokeActionEnabled(revokeStatus),
                    modifier = Modifier.testTag("create_revoke_button"),
                ) {
                    Text("Cancel capsule")
                }
            }
        }

        // FIX-STATE-02: visible recovery for guarded/out-of-order events.
        val guard = flowError
        if (guard != null && step != CreateViewModel.Step.PUBLISHING) {
            Spacer(Modifier.height(8.dp))
            Text(text = guard, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("create_guard_error"))
        }

        val error = publishError
        if (error != null && step != CreateViewModel.Step.PUBLISHING) {
            Spacer(Modifier.height(8.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("create_error"))
        }
    }

    if (showRevokeConfirmation && step == CreateViewModel.Step.PUBLISHED) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmation = false },
            title = { Text("Cancel this capsule?") },
            text = {
                Text(
                    "This stops future delivery if the recipient has not already received it. A copy already received or decrypted by the recipient cannot be removed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeConfirmation = false
                        viewModel.revokePublished()
                    },
                    enabled = revokeActionEnabled(revokeStatus),
                    modifier = Modifier.testTag("create_revoke_confirm"),
                ) { Text("Cancel capsule") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRevokeConfirmation = false },
                    modifier = Modifier.testTag("create_revoke_dismiss"),
                ) { Text("Keep capsule") }
            },
        )
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

@Composable
private fun ContentStepContent(viewModel: CreateViewModel) {
    // FIX-STATE-06: the selection snapshot is observable, so the count label
    // and the 3..5 publish gate recompose immediately after a picker result.
    val selectedIds = viewModel.photoSelection.selectedIds

    val pickerContract = remember {
        ActivityResultContracts.PickMultipleVisualMedia(PhotoSelectionState.MAX_PHOTOS)
    }
    val pickerLauncher = rememberLauncherForActivityResult(pickerContract) { uris: List<android.net.Uri> ->
        // FIX-STATE-06: one production sink for picker results.
        viewModel.onPhotosPicked(uris.map { it.toString() })
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
            "Selected: ${selectedIds.size} of 3-5",
            modifier = Modifier.testTag("create_selection_count"),
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = viewModel.noteEditor.text,
            onValueChange = viewModel.noteEditor::onChange,
            label = { Text("Optional note (${NoteEditorState.MAX_NOTE_BYTES} byte limit)") },
            isError = viewModel.noteEditor.limitReached || !viewModel.noteEditor.canIncludeInCapsule,
            modifier = Modifier.fillMaxWidth().testTag("create_note_input"),
        )
        if (viewModel.noteEditor.limitReached || !viewModel.noteEditor.canIncludeInCapsule) {
            Text(
                "The note exceeds the ${NoteEditorState.MAX_NOTE_BYTES} byte limit.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("create_note_limit_error"),
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = viewModel::startPublishing,
            enabled = viewModel.photoSelection.canProceed && viewModel.noteEditor.canIncludeInCapsule,
            modifier = Modifier.testTag("create_publish"),
        ) { Text("Encrypt and stage capsule") }
    }
}

/**
 * Fixed user-facing copy for the mounted Create send state. Typed
 * [CreateViewModel.CreateUploadStatus] error codes stay on the status
 * object for logic and diagnostics and must never be interpolated here.
 */
internal fun createUploadPendingCopy(status: CreateViewModel.CreateUploadStatus): String =
    when (status) {
        is CreateViewModel.CreateUploadStatus.RetryableFailure -> "Send needs a retry."
        is CreateViewModel.CreateUploadStatus.TerminalFailure -> "Send failed permanently."
        else -> "Encrypted capsule queued. Upload will continue in the background."
    }

private fun revokeActionEnabled(status: CreateViewModel.CapsuleRevokeStatus): Boolean =
    when (status) {
        CreateViewModel.CapsuleRevokeStatus.Idle -> true
        CreateViewModel.CapsuleRevokeStatus.InFlight -> false
        is CreateViewModel.CapsuleRevokeStatus.Succeeded -> false
        is CreateViewModel.CapsuleRevokeStatus.Failed ->
            status.reason != dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.WINDOW_EXPIRED &&
                status.reason != dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.CAPSULE_STATE_INVALID &&
                status.reason != dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.CAPSULE_NOT_FOUND
    }

internal fun createRevokeCopy(status: CreateViewModel.CapsuleRevokeStatus.Failed): String =
    when (status.reason) {
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.NETWORK ->
            "Connect to the internet to cancel this capsule, then try again."
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.WINDOW_EXPIRED,
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.CAPSULE_STATE_INVALID,
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.CAPSULE_NOT_FOUND,
        -> "This capsule can no longer be cancelled."
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.AUTH_INVALID ->
            "Your session expired. Sign in again."
        else -> "Couldn’t cancel the capsule. Try again."
    }
