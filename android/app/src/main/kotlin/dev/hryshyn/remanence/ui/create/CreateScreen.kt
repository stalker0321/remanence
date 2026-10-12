package dev.hryshyn.remanence.ui.create

import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import dev.hryshyn.remanence.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import dev.hryshyn.remanence.ui.hold.HoldFlowHeader
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldInput as OutlinedTextField
import androidx.compose.material3.Text
import dev.hryshyn.remanence.ui.hold.HoldTextButton as TextButton
import androidx.compose.runtime.Composable
import dev.hryshyn.remanence.ui.hold.HoldDestructiveButton
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldSpace
import dev.hryshyn.remanence.ui.hold.HoldInformation
import dev.hryshyn.remanence.ui.hold.HoldActionObject
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.capture.CaptureAttemptSurface
import dev.hryshyn.remanence.ui.create.music.MusicPickerContent
import dev.hryshyn.remanence.ui.capsule.music.rememberTrackServiceAction
import dev.hryshyn.remanence.ui.motion.AstraStepArrival
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

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
    onExit: () -> Unit = {},
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val uploadStatus by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val publishError by viewModel.publishError.collectAsStateWithLifecycle()
    val flowError by viewModel.flowError.collectAsStateWithLifecycle()
    val revokeStatus by viewModel.revokeStatus.collectAsStateWithLifecycle()
    val uploadRetrying by viewModel.uploadRetrying.collectAsStateWithLifecycle()
    var showRevokeConfirmation by remember { mutableStateOf(false) }
    val motion = rememberAstraMotion()

    // The caller distinguishes true route exit from activity recreation. This
    // effect is intentionally not keyed by configuration so rotation does not
    // tear down an in-progress same-epoch Create session.
    DisposableEffect(Unit) {
        onDispose(onScreenDispose)
    }

    if (step == CreateViewModel.Step.CONTENT || step == CreateViewModel.Step.COMPOSE) {
        ContentAndCompositionFlow(viewModel, onExit, modifier)
        return
    }

    BackHandler(enabled = step == CreateViewModel.Step.RECIPIENT_CONFIRM) {
        viewModel.restartLookup()
    }

    // Motion ownership: the root owns the single boundary arrival
    // (Home <-> Create). Steps arrive via AstraStepArrival, which snaps on
    // the first mount (no stacked root alpha) and animates only on step
    // changes; the outgoing step is dropped synchronously.
    //
    // CONTENT/COMPOSE have their own bounded pane above; only later status
    // steps use this scrolling column to keep messages/actions reachable.
    Column(modifier.fillMaxSize()) {
    HoldFlowHeader(
        onBack = {
            if (step == CreateViewModel.Step.RECIPIENT_CONFIRM) viewModel.restartLookup() else onExit()
        },
        onClose = onExit,
    )
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val introductory = step == CreateViewModel.Step.RECIPIENT_LOOKUP ||
            step == CreateViewModel.Step.RECIPIENT_CONFIRM || step == CreateViewModel.Step.FRONT
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("create_screen_scroll")
                .then(
                    if (introductory) {
                        Modifier
                    } else {
                        Modifier.verticalScroll(rememberScrollState())
                    },
                )
                .padding(horizontal = HoldSpace.pageGutter(), vertical = 16.dp),
        ) {
        AstraStepArrival(motion = motion, stepKey = step,
            modifier = if (introductory) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Column(if (introductory) Modifier.fillMaxSize() else Modifier) {
                if (introductory) {
                    Text(stringResource(when (step) {
                        CreateViewModel.Step.RECIPIENT_LOOKUP -> R.string.hold_make
                        CreateViewModel.Step.RECIPIENT_CONFIRM -> R.string.create_check_recipient
                        else -> R.string.create_your_postcard
                    }), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                    Spacer(Modifier.height(if (step == CreateViewModel.Step.FRONT) 10.dp else 16.dp))
                }
                Text(
                    text = when (step) {
                        CreateViewModel.Step.RECIPIENT_LOOKUP -> stringResource(R.string.hold_recipient_title)
                        CreateViewModel.Step.RECIPIENT_CONFIRM -> stringResource(R.string.hold_confirm_title)
                        CreateViewModel.Step.FRONT -> stringResource(R.string.hold_capture_title)
                        CreateViewModel.Step.CONTENT -> stringResource(R.string.hold_content_title)
                        // Spec `selected`: the same surface keeps its frozen
                        // choice visible through the title.
                        CreateViewModel.Step.COMPOSE -> stringResource(
                            if (viewModel.frozenCollageSelection != null) {
                                R.string.hold_selected_title
                            } else {
                                R.string.hold_compose_title
                            },
                        )
                        CreateViewModel.Step.PUBLISHING -> stringResource(R.string.hold_preparing_title)
                        CreateViewModel.Step.UPLOAD_PENDING -> stringResource(R.string.hold_publishing_title)
                        CreateViewModel.Step.PUBLISHED -> stringResource(R.string.hold_ready_title)
                    },
                    style = when {
                        step == CreateViewModel.Step.FRONT -> MaterialTheme.typography.headlineLarge.copy(fontSize = 27.sp, lineHeight = 32.sp)
                        introductory -> MaterialTheme.typography.headlineLarge
                        else -> MaterialTheme.typography.headlineSmall
                    },
                    modifier = Modifier.testTag("create_step_label"),
                )
                Spacer(Modifier.height(16.dp))
                if (introductory) {
                    Text(stringResource(when (step) {
                        CreateViewModel.Step.RECIPIENT_LOOKUP -> R.string.hold_recipient_body
                        CreateViewModel.Step.RECIPIENT_CONFIRM -> R.string.hold_confirm_body
                        else -> R.string.create_capture_body
                    }), style = if (step == CreateViewModel.Step.FRONT) MaterialTheme.typography.bodySmall
                        else MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
                    Spacer(Modifier.height(if (step == CreateViewModel.Step.FRONT) 16.dp else 24.dp))
                }
                when (step) {
                    CreateViewModel.Step.RECIPIENT_LOOKUP -> RecipientLookupContent(viewModel)
                    CreateViewModel.Step.RECIPIENT_CONFIRM -> RecipientConfirmContent(viewModel)
                    CreateViewModel.Step.FRONT -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        CaptureAttemptSurface(
                        controller = viewModel.frontAttempt,
                        shutterTag = "capture_shutter_front",
                        retakeTag = "capture_retake_front",
                        onBeginAttempt = viewModel::beginFrontCapture,
                        onDelivered = viewModel::deliverFrontJpeg,
                        onRetake = viewModel::retakeFront,
                        adapterFactory = adapterFactory,
                        requestPermissionOnAttach = requestPermissionOnAttach,
                        previewMaxHeight = (maxHeight - 72.dp).coerceAtLeast(64.dp),
                        shutterLabel = stringResource(R.string.create_use_postcard),
                        showGuideInstruction = false,
                        )
                    }

                    CreateViewModel.Step.CONTENT, CreateViewModel.Step.COMPOSE -> Unit
                    CreateViewModel.Step.PUBLISHING -> Column {
                        androidx.compose.material3.CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.testTag("create_publishing_spinner"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.create_audit_encrypting), modifier = Modifier.testTag("create_publishing"))
                    }
                    CreateViewModel.Step.UPLOAD_PENDING -> Column {
                    when (val status = if (publishError != null && uploadStatus !is CreateViewModel.CreateUploadStatus.Published &&
                        uploadStatus !is CreateViewModel.CreateUploadStatus.TerminalFailure)
                        CreateViewModel.CreateUploadStatus.RetryableFailure("QUEUE_FAILED") else uploadStatus) {
                        is CreateViewModel.CreateUploadStatus.RetryableFailure -> Text(
                            stringResource(createUploadPendingCopy(status)),
                            modifier = Modifier.testTag("create_upload_retryable_failure"),
                        )
                        is CreateViewModel.CreateUploadStatus.TerminalFailure -> Text(
                            stringResource(createUploadPendingCopy(status)),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("create_upload_terminal_failure"),
                        )
                        else -> Text(
                            stringResource(createUploadPendingCopy(status)),
                            modifier = Modifier.testTag("create_upload_pending"),
                        )
                    }
                    if (uploadStatus !is CreateViewModel.CreateUploadStatus.TerminalFailure &&
                        (uploadStatus is CreateViewModel.CreateUploadStatus.RetryableFailure || publishError != null)) {
                        Spacer(Modifier.height(16.dp))
                        ForwardAction(stringResource(R.string.create_audit_retry), "create_retry_upload",
                            viewModel::retryUpload, enabled = !uploadRetrying, busy = uploadRetrying)
                    } else if (uploadStatus is CreateViewModel.CreateUploadStatus.TerminalFailure) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onExit, modifier = Modifier.testTag("create_terminal_home")) {
                            Text(stringResource(R.string.create_audit_return_home))
                        }
                    }
                    }
                    CreateViewModel.Step.PUBLISHED -> Column {
                        Text(
                            stringResource(R.string.create_audit_published),
                            modifier = Modifier.testTag("create_published"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.create_audit_revoke_disclaimer),
                            modifier = Modifier.testTag("create_revoke_disclaimer"),
                        )
                        Spacer(Modifier.height(8.dp))
                        when (val status = revokeStatus) {
                            CreateViewModel.CapsuleRevokeStatus.Idle -> Unit
                            CreateViewModel.CapsuleRevokeStatus.InFlight -> Text(
                                stringResource(R.string.create_audit_revoke_inflight),
                                modifier = Modifier.testTag("create_revoke_in_flight"),
                            )
                            is CreateViewModel.CapsuleRevokeStatus.Succeeded -> Text(
                                stringResource(R.string.create_audit_revoke_success),
                                modifier = Modifier.testTag("create_revoke_success"),
                            )
                            is CreateViewModel.CapsuleRevokeStatus.Failed -> Text(
                                stringResource(createRevokeCopy(status)),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("create_revoke_error"),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HoldDestructiveButton(
                            onClick = { showRevokeConfirmation = true },
                            enabled = revokeActionEnabled(revokeStatus),
                            modifier = Modifier.testTag("create_revoke_button"),
                        ) {
                            Text(stringResource(R.string.create_audit_revoke_confirm))
                        }
                    }
                }
            }
        }

        // FIX-STATE-02: visible recovery for guarded/out-of-order events.
        val guard = flowError
        if (guard != null && step != CreateViewModel.Step.PUBLISHING) {
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(R.string.create_audit_guard), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("create_guard_error"))
        }

        val error = publishError
        if (error != null && step != CreateViewModel.Step.PUBLISHING && step != CreateViewModel.Step.UPLOAD_PENDING) {
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(R.string.create_audit_publish_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("create_error"))
        }
    }
    }
    }

    if (showRevokeConfirmation && step == CreateViewModel.Step.PUBLISHED) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmation = false },
            title = { Text(stringResource(R.string.create_audit_revoke_title)) },
            text = {
                Text(
                    stringResource(R.string.create_audit_revoke_body),
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
                ) { Text(stringResource(R.string.create_audit_revoke_confirm), color = HoldColors.Destructive) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRevokeConfirmation = false },
                    modifier = Modifier.testTag("create_revoke_dismiss"),
                ) { Text(stringResource(R.string.create_audit_revoke_keep)) }
            },
        )
    }
}

@Composable
private fun RecipientLookupContent(viewModel: CreateViewModel) {
    val handle by viewModel.pickerVm.handle.collectAsStateWithLifecycle()
    val state by viewModel.pickerVm.state.collectAsStateWithLifecycle()
    val selfAccount by viewModel.selfRecipientAccount.collectAsStateWithLifecycle()
    val selfRejected by viewModel.selfRecipientLookupRejected.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = handle, onValueChange = viewModel::onHandleChange,
                label = { Text(stringResource(R.string.hold_recipient_handle), style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.titleLarge,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().testTag("create_handle_input"),
            )
            if (handle.isNotEmpty() && runCatching {
                    dev.hryshyn.remanence.core.model.NormalizedHandle.parse(handle)
                }.isFailure) {
                Text(stringResource(R.string.create_audit_handle_rule), color = HoldColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("create_handle_rule"))
            }
            selfAccount?.let { account ->
                Text(stringResource(R.string.recipient_design_or),
                    color = HoldColors.Muted, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        .testTag("create_recipient_or"))
                HoldActionObject(
                    title = stringResource(R.string.recipient_design_self_title),
                    detail = "\u2068@" + account.handle + "\u2069",
                    action = stringResource(R.string.recipient_design_self_action),
                    onClick = viewModel::lookupPostcardForMyself,
                    modifier = Modifier.fillMaxWidth().testTag("create_self_recipient"),
                    enabled = state !is RecipientLookupUiState.LookingUp,
                    compact = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (selfRejected) Text(stringResource(R.string.recipient_design_self_unavailable),
                color = HoldColors.Error, modifier = Modifier.testTag("create_self_recipient_error"))
            when (val current = state) {
                is RecipientLookupUiState.Resolved -> LaunchedEffect(current.snapshot) { viewModel.onResolved(current.snapshot) }
                RecipientLookupUiState.NotFound -> Text(stringResource(R.string.hold_no_handle))
                is RecipientLookupUiState.Failed -> Text(stringResource(createLookupFailureCopy(current.reason)), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
        Spacer(Modifier.height(16.dp))
        ForwardAction(
            stringResource(if (state is RecipientLookupUiState.LookingUp) R.string.hold_looking_up else R.string.hold_find),
            "create_lookup_button", viewModel::lookupRecipient, enabled = viewModel.pickerVm.canLookup,
        )
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
                stringResource(R.string.create_audit_lookup_missing),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("create_confirm_missing_pending"),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::restartLookup,
                modifier = Modifier.testTag("create_confirm_back_to_lookup"),
            ) { Text(stringResource(R.string.create_audit_lookup_back)) }
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
private fun ContentStepContent(
    viewModel: CreateViewModel,
    preparing: Boolean = false,
    error: String? = null,
    onContinue: () -> Unit = { viewModel.goToCompose() },
) {
    val selectedIds = viewModel.photoSelection.selectedIds
    val pickedIds by viewModel.pickedPhotoIds.collectAsStateWithLifecycle()
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PhotoSelectionState.MAX_PHOTOS),
    ) { uris -> viewModel.onPhotosPicked(uris.map { it.toString() }) }
    val pickPhotos = { pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val recipient by viewModel.confirmedRecipient.collectAsStateWithLifecycle()
    val thumbnails by viewModel.pickedThumbnails.collectAsStateWithLifecycle()
    val selection by viewModel.musicSelection.collectAsStateWithLifecycle()
    val artwork by viewModel.musicArtworkBitmap.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val repository = remember(context) {
        (context.applicationContext as RemanenceApplication).container.apiStack.musicSearchRepository
    }
    Column(Modifier.fillMaxSize()) {
        recipient?.let { PersonChip(it.handle.toDisplayString()) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("create_content_scroll")) {
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp).testTag("create_error"))
            }
            Text(stringResource(R.string.create_for_one_person), color = HoldColors.Muted,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.hold_content_title), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.testTag("create_step_label"))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.create_choose_photographs), color = HoldColors.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.create_photographs_count, selectedIds.size),
                    style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted,
                    modifier = Modifier.weight(1f).testTag("create_selection_count"))
                if (pickedIds.isNotEmpty()) {
                    TextButton(onClick = pickPhotos, enabled = !preparing, modifier = Modifier.testTag("create_pick_photos")) {
                        Text(stringResource(R.string.create_change_photographs),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (pickedIds.isEmpty()) {
                Button(onClick = pickPhotos, modifier = Modifier.fillMaxWidth().testTag("create_pick_photos")) {
                    Text(stringResource(R.string.hold_choose_photos))
                }
            } else PickedPhotoTiles(pickedIds, thumbnails, selectedIds.toSet(),
                onToggle = viewModel::togglePickedPhoto, enabled = !preparing)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = viewModel.noteEditor.text,
                onValueChange = viewModel.noteEditor::onChange,
                label = { Text(stringResource(R.string.hold_note), style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 3,
                isError = viewModel.noteEditor.limitReached || !viewModel.noteEditor.canIncludeInCapsule,
                modifier = Modifier.fillMaxWidth().testTag("create_note_input"),
            )
            if (viewModel.noteEditor.limitReached || !viewModel.noteEditor.canIncludeInCapsule) {
                Text(stringResource(R.string.create_note_too_long), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("create_note_limit_error"))
            }
            Spacer(Modifier.height(12.dp))
            MusicPickerContent(
                search = repository::search, vmSelection = selection, selectedArtwork = artwork,
                onSelect = viewModel::setMusicSelection, onClearSelection = viewModel::clearMusicSelection,
            )
            val preview by viewModel.generatorPreview.collectAsStateWithLifecycle()
            val notice = createPreviewNotice(preview)?.let { stringResource(it) }
                ?: viewModel.collageChoices.notice?.let { stringResource(R.string.create_no_fitting_expression) }
            if (notice != null) Text(notice, color = HoldColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp).testTag("collage_notice"))
        }
        Spacer(Modifier.height(16.dp))
        ForwardAction(
            text = stringResource(if (preparing) R.string.create_preparing_expressions else R.string.create_continue),
            tag = "create_next", onClick = onContinue,
            enabled = !preparing && viewModel.photoSelection.canProceed && viewModel.noteEditor.canIncludeInCapsule,
            busy = preparing,
        )
    }
}

/**
 * Picker inventory in picker order; tapping reversibly changes inclusion.
 * Unchecked items stay visible. Only selected IDs enter sealing/generation.
 * A thumbnail decode failure never discards the underlying selection.
 */
@Composable
internal fun PickedPhotoTiles(
    selectedIds: List<String>,
    thumbnails: Map<String, androidx.compose.ui.graphics.ImageBitmap?>,
    includedIds: Set<String> = selectedIds.toSet(),
    onToggle: ((String) -> Unit)? = null,
    enabled: Boolean = true,
) {
    if (selectedIds.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("create_photo_tiles"),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        selectedIds.chunked(3).forEachIndexed { row, ids ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                repeat(3) { column ->
                    val uri = ids.getOrNull(column)
                    if (uri == null) Spacer(Modifier.weight(1f)) else {
                        val index = row * 3 + column
                        val included = uri in includedIds
                        val label = stringResource(R.string.create_photo_selection_label, index + 1, selectedIds.size)
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .background(HoldColors.Field).testTag("create_photo_tile_$index")
                            .then(if (onToggle != null) Modifier.toggleable(value = included,
                                enabled = enabled, role = Role.Checkbox,
                                onValueChange = { onToggle(uri) }) else Modifier)
                            .semantics {
                                selected = included
                                contentDescription = label
                            }) {
                            val bitmap = thumbnails[uri]
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap, contentDescription = null,
                                    modifier = Modifier.fillMaxSize().testTag("create_photo_tile_image_$index"),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else if (!thumbnails.containsKey(uri)) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    strokeWidth = 1.dp,
                                    modifier = Modifier.size(20.dp).align(Alignment.Center)
                                        .testTag("create_photo_tile_pending_$index"),
                                )
                            } else {
                                Text(stringResource(R.string.create_audit_thumbnail_failed),
                                    style = MaterialTheme.typography.labelSmall, color = HoldColors.Muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.Center).padding(6.dp)
                                        .testTag("create_photo_tile_failed_$index"))
                            }
                            Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp)
                                .background(if (included) HoldColors.Accent else HoldColors.Paper, RoundedCornerShape(50))
                                .border(1.5.dp, HoldColors.Accent, RoundedCornerShape(50))
                                .testTag("create_photo_tile_indicator_$index"),
                                contentAlignment = Alignment.Center) {
                                if (included) Text("✓", color = HoldColors.OnAccent, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("create_photo_tile_check_$index"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * T150 explicit composition browsing surface (spec `expression`/`selected`):
 * the whole-capsule chooser fills this distinct screen — never embedded in
 * the content form. Select freezes the displayed candidate; publish needs a
 * frozen choice; back returns to the form with every input retained.
 */
@Composable
private fun ContentAndCompositionFlow(
    viewModel: CreateViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val cropSlot by viewModel.cropSlot.collectAsStateWithLifecycle()
    val preview by viewModel.generatorPreview.collectAsStateWithLifecycle()
    val flowError by viewModel.flowError.collectAsStateWithLifecycle()
    val publishError by viewModel.publishError.collectAsStateWithLifecycle()
    var awaitingComposition by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val session = viewModel.collageChoices
    val frozen = viewModel.frozenCollageSelection
    val scope = rememberCoroutineScope()
    val sources = when (val current = preview) {
        is GeneratorPreviewState.Ready -> current.sources
        is GeneratorPreviewState.NotePending -> current.sources
        else -> emptyList()
    }
    val source = sources.firstOrNull { it.contentId == cropSlot }
    val geometry = cropSlot?.let(viewModel::cropSlotGeometry)
    val staged = source != null && geometry != null
    val corrections by viewModel.cropCorrections.collectAsStateWithLifecycle()

    // Stay with the user's content until there is a drawable first sheet.
    // The chooser below remains mounted across this boundary.
    LaunchedEffect(awaitingComposition, session.isPreparing, session.candidates, session.notice, preview,
        viewModel.isResolvingMusicArtwork) {
        if (awaitingComposition) {
            if (!session.isPreparing && !viewModel.isResolvingMusicArtwork && session.candidates.isNotEmpty()) {
                viewModel.goToCompose()
                awaitingComposition = false
            } else if (preview is GeneratorPreviewState.Idle || session.notice != null || preview is GeneratorPreviewState.Unavailable ||
                preview is GeneratorPreviewState.Rejected || preview is GeneratorPreviewState.Incompatible) {
                awaitingComposition = false
            }
        }
    }
    LaunchedEffect(frozen) { if (frozen == null) reviewing = false }
    val back = {
        when {
            cropSlot != null -> { viewModel.closeCrop(); Unit }
            awaitingComposition -> { awaitingComposition = false; Unit }
            reviewing -> reviewing = false
            step == CreateViewModel.Step.COMPOSE && frozen != null -> viewModel.clearFrozenCollageSelection()
            step == CreateViewModel.Step.COMPOSE -> { viewModel.backToContent(); Unit }
            else -> { awaitingComposition = false; viewModel.backToFront(); Unit }
        }
    }
    BackHandler(onBack = back)
    Column(modifier.fillMaxSize().testTag("create_screen_scroll")) {
        HoldFlowHeader(onBack = back, onClose = onExit)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal =
            if (step == CreateViewModel.Step.COMPOSE) 18.dp else HoldSpace.pageGutter())
            .padding(bottom = 16.dp)) {
            if (step == CreateViewModel.Step.CONTENT) {
                ContentStepContent(viewModel, awaitingComposition,
                    if (publishError != null) stringResource(R.string.create_audit_publish_error)
                    else if (flowError != null) stringResource(R.string.create_audit_guard) else null) { awaitingComposition = true }
            }
            CollageStepChooser(
                viewModel,
                modifier = if (step == CreateViewModel.Step.COMPOSE) Modifier.fillMaxSize() else Modifier.size(0.dp),
                onSlotTap = { viewModel.openCrop(it); Unit },
                contentWrapper = { canvas, nav ->
                    if (step == CreateViewModel.Step.COMPOSE) {
                        if (staged) {
                            Column(Modifier.fillMaxSize()) {
                                CropStageContent(
                                    source = requireNotNull(source), slotAspect = requireNotNull(geometry).slotAspect,
                                    baseCrop = geometry.baseCrop, correction = corrections[cropSlot],
                                    onUpdate = viewModel::updateCrop, onReset = viewModel::resetCrop,
                                    onDone = { scope.launch { viewModel.applyCropAndClose() } },
                                    onBack = { viewModel.closeCrop() },
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                                if (flowError != null) Text(stringResource(R.string.create_audit_guard), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("create_guard_error"))
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                Text(
                                    stringResource(if (reviewing) R.string.create_review_title else if (frozen != null)
                                        R.string.hold_selected_title else R.string.hold_compose_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(bottom = 12.dp).testTag("create_step_label"),
                                )
                                Box(Modifier.weight(1f).fillMaxWidth().testTag("compose_art_space")) { canvas() }
                                if (reviewing) {
                                    Text(stringResource(R.string.create_review_body),
                                        style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted,
                                        modifier = Modifier.padding(vertical = 16.dp))
                                    ForwardAction(stringResource(R.string.create_attach_capsule), "create_publish",
                                        viewModel::startPublishing, enabled = frozen != null)
                                    TextButton(onClick = { reviewing = false }, modifier = Modifier.fillMaxWidth()
                                        .testTag("create_review_back")) { Text(stringResource(R.string.create_back_to_expression)) }
                                } else {
                                    nav()
                                    Text(stringResource(R.string.hold_crop_hint), textAlign = TextAlign.Center,
                                        color = HoldColors.Muted, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("crop_hint"))
                                    ForwardAction(
                                        stringResource(if (frozen == null) R.string.create_this_one else R.string.create_continue),
                                        if (frozen == null) "collage_select" else "create_compose_continue",
                                        onClick = {
                                            if (frozen != null) reviewing = true
                                            else if (viewModel.selectCollageCandidate(session.displayIndex))
                                                viewModel.freezeCollageSelection()
                                        },
                                        enabled = !session.isPreparing && session.displayed != null,
                                    )
                                }
                                val error = flowError ?: publishError
                                if (error != null) Text(stringResource(if (publishError != null) R.string.create_audit_publish_error else R.string.create_audit_guard), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("create_guard_error"))
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ForwardAction(
    text: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(text, Modifier.weight(1f), textAlign = TextAlign.Start)
        if (busy) androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text("→", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PersonChip(handle: String) {
    Text(stringResource(R.string.hold_for_handle, handle),
        style = MaterialTheme.typography.bodySmall, color = HoldColors.Accent,
        modifier = Modifier.padding(bottom = 17.dp)
            .background(HoldColors.Lilac.copy(alpha = .65f), RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp))
}

/**
 * Production collage chooser for the generator content step (T002 cutover).
 * A Ready (or note-pending) preview mounts the real [CollageChoiceHost] with
 * the session input, preview sources, and the production crop source; any
 * other preview state renders a typed notice. Never mounts
 * `GeneratorBer1PreviewHost` and never exposes `generator_preview_*`
 * semantics for this flow. Selection is explicit-only: the Select action
 * selects the displayed candidate and freezes it against the exact input
 * object the host built from (publish stays fail-closed on its own guard).
 */
@Composable
private fun CollageStepChooser(
    viewModel: CreateViewModel,
    modifier: Modifier = Modifier,
    onSlotTap: ((String) -> Unit)? = null,
    contentWrapper: @Composable (CanvasParcel, NavParcel) -> Unit,
) {
    val preview by viewModel.generatorPreview.collectAsStateWithLifecycle()
    val musicSelection by viewModel.musicSelection.collectAsStateWithLifecycle()
    val artwork by viewModel.musicArtwork.collectAsStateWithLifecycle()
    val artworkBitmap by viewModel.musicArtworkBitmap.collectAsStateWithLifecycle()
    val input = viewModel.collageChooserInputFor(musicSelection)
    val track = viewModel.collageChooserTrackFor(musicSelection)
    val musicAction = rememberTrackServiceAction(track)
    val musicActionLabel = track?.let {
        stringResource(R.string.track_open_action, it.title, it.artistDisplay)
    }
    val sources = when (val current = preview) {
        is GeneratorPreviewState.Ready -> current.sources
        is GeneratorPreviewState.NotePending -> current.sources
        else -> emptyList()
    }
    if (input != null && sources.isNotEmpty()) {
        CollageChoiceHost(
            session = viewModel.collageChoices, input = input, sources = sources,
            note = viewModel.noteEditor.text.takeIf { it.isNotEmpty() },
            track = track,
            cropSource = viewModel.collageCropSource ?: remember { dev.hryshyn.remanence.ui.collage.CollageCropSource { null } },
            artwork = artwork, artworkBitmap = artworkBitmap,
            frozenCandidateId = { viewModel.frozenCollageSelection?.candidateId },
            onNavigate = viewModel::clearFrozenCollageSelection, onMeasured = viewModel::onPreviewMeasured,
            onPrepared = viewModel::onCandidatePrepared, prepareInputs = viewModel.prepareInputs(),
            onSlotTap = onSlotTap, candidateBuilder = { viewModel.buildAllFamilySealedCandidates(it) },
            onMusicClick = musicAction, musicActionLabel = musicActionLabel,
            showSelectInNavigation = false, contentWrapper = contentWrapper, modifier = modifier,
        )
    }
}

@StringRes
internal fun createPreviewNotice(preview: GeneratorPreviewState): Int? = when (preview) {
    is GeneratorPreviewState.Rejected -> when {
        "select 3..5 photos" in preview.reasons -> R.string.create_audit_photos_count
        "photos must be unique" in preview.reasons -> R.string.create_audit_photos_duplicate
        "the note exceeds its byte limit" in preview.reasons -> R.string.create_note_too_long
        else -> R.string.create_no_fitting_expression
    }
    is GeneratorPreviewState.Unavailable -> if (preview.reason == "could not read a selected photo")
        R.string.create_audit_photos_unreadable else R.string.create_audit_preview_unavailable
    is GeneratorPreviewState.Incompatible -> R.string.create_no_fitting_expression
    else -> null
}

@StringRes
internal fun createLookupFailureCopy(reason: RecipientLookupUiState.Failure): Int = when (reason) {
    RecipientLookupUiState.Failure.NETWORK -> R.string.create_audit_lookup_network
    RecipientLookupUiState.Failure.SIGN_IN -> R.string.create_audit_lookup_signin
    RecipientLookupUiState.Failure.SERVER -> R.string.create_audit_lookup_server
    else -> R.string.create_audit_lookup_failed
}

/**
 * Fixed user-facing copy for the mounted Create send state. Typed
 * [CreateViewModel.CreateUploadStatus] error codes stay on the status object
 * for logic and diagnostics and must never be interpolated here.
 */
@StringRes
internal fun createUploadPendingCopy(status: CreateViewModel.CreateUploadStatus): Int =
    when (status) {
        is CreateViewModel.CreateUploadStatus.RetryableFailure -> R.string.create_audit_upload_retry
        is CreateViewModel.CreateUploadStatus.TerminalFailure -> R.string.create_audit_upload_terminal
        else -> R.string.create_audit_upload_pending
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

@StringRes
internal fun createRevokeCopy(status: CreateViewModel.CapsuleRevokeStatus.Failed): Int =
    when (status.reason) {
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.NETWORK ->
            R.string.create_audit_revoke_network
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.WINDOW_EXPIRED,
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.CAPSULE_STATE_INVALID,
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.CAPSULE_NOT_FOUND,
        -> R.string.create_audit_revoke_expired
        dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure.AUTH_INVALID ->
            R.string.create_audit_session_expired
        else -> R.string.create_audit_revoke_failed
    }
