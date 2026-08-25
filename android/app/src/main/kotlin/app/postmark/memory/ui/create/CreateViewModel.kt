package app.postmark.memory.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.postmark.memory.capture.BackCaptureFlow
import app.postmark.memory.capture.CaptureAttemptController
import app.postmark.memory.capture.FrontCaptureFlow
import app.postmark.memory.capture.FrontCaptureOutcome
import app.postmark.memory.capture.PreparedBackGate
import app.postmark.memory.create.RealStillFingerprintProcessor
import app.postmark.memory.create.SameAccountCapsulePublisher
import app.postmark.memory.create.SameAccountCapsuleRequest
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.data.network.ResolvedHandleSnapshot
import postmark.core.data.outbox.CapsuleOutboxStager
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.UserId
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.RecognitionProfile

/** One immutable snapshot of the local account used as sender AND recipient (M1). */
data class SenderIdentitySnapshot(
    val userId: String,
    val handle: String,
    val activeKeyBundleId: String,
    val encryptionPrivateHandle: KeysetHandle,
    val signingPrivateHandle: KeysetHandle,
)

/**
 * FIX-M1-007-11: the production Create flow over real components only -
 * directory resolve + explicit confirmation, front capture through the ORB
 * processor into sealed persistence, checklist-gated prepared back, Photo
 * Picker 3-5 plus bounded note, and ONE sealing path: the ciphertext-only
 * publisher feeding the durable outbox. There is no second, all-plaintext
 * route. Plaintext staging lives only inside [publish] and is cleared in a
 * finally-equivalent path; cancellation tears the session down.
 *
 * FIX-STATE-01: every capture side runs through ONE authoritative
 * [CaptureAttemptController]; a delivered still always terminates its
 * attempt. FIX-STATE-02: ViewModel events are guarded by the step table -
 * out-of-order calls fail closed with a visible recovery message instead of
 * crashing the UI.
 */
class CreateViewModel(
    private val directory: RecipientDirectoryPort,
    accessTokenProvider: () -> String?,
    private val identityProvider: suspend () -> SenderIdentitySnapshot?,
    private val persistence: SealedFingerprintPersistence,
    private val outboxStager: CapsuleOutboxStager,
    profile: RecognitionProfile,
    private val stagingDirectory: File,
    private val openPhotoSource: (pickerId: String) -> app.postmark.memory.create.PhotoSource,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    enum class Step { RECIPIENT_LOOKUP, RECIPIENT_CONFIRM, FRONT, BACK_CHECKLIST, BACK, CONTENT, PUBLISHING, PUBLISHED }

    val pickerVm = RecipientPickerViewModel(directory, accessTokenProvider, viewModelScope)

    private val sessionStore = CreateSessionStore()
    private val recipientFlow = CreateRecipientFlow(pickerVm, sessionStore)

    val confirmedRecipient: StateFlow<ResolvedHandleSnapshot?> get() = sessionStore.confirmedRecipient

    /**
     * FIX-M1-ONDEVICE-01: the resolved-but-not-yet-confirmed snapshot for the
     * confirmation screen. Binding happens ONLY through explicit confirm.
     */
    val pendingRecipient: StateFlow<ResolvedHandleSnapshot?> get() = recipientFlow.pendingRecipient

    private val _step = MutableStateFlow(Step.RECIPIENT_LOOKUP)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** Generated once per create session; binds captures and the outbox row. */
    private var _capsuleId: String = UUID.randomUUID().toString()
    val capsuleId: String get() = _capsuleId

    /**
     * FIX-REVIEW-02: epoch of the session this ViewModel currently holds.
     * beginSession(epoch) performs the full reset only when [epoch] differs,
     * so re-entry after leaving is always a NEW session while rotation (same
     * epoch) never discards an in-progress one. onCleared is not relied on.
     */
    private var begunEpoch: Long? = null

    /**
     * FIX-STATE-02: visible recovery surface for out-of-order or illegal
     * events. The UI renders it next to the current step; the offending call
     * changes nothing else (fail closed, never crash).
     */
    private val _flowError = MutableStateFlow<String?>(null)
    val flowError: StateFlow<String?> = _flowError.asStateFlow()

    private val _publishError = MutableStateFlow<String?>(null)
    val publishError: StateFlow<String?> = _publishError.asStateFlow()

    // Content state.
    val photoSelection = PhotoSelectionState()
    val noteEditor = NoteEditorState()
    val backGate = PreparedBackGate()

    // ---------------------------------------------------------------------
    // Authoritative capture attempts (FIX-STATE-01).
    // ---------------------------------------------------------------------

    val frontAttempt = CaptureAttemptController()
    val backAttempt = CaptureAttemptController()

    private val frontProcessor = RealStillFingerprintProcessor(profile, FingerprintSide.FRONT)
    private val backProcessor = RealStillFingerprintProcessor(profile, FingerprintSide.BACK)
    private val frontFlow = FrontCaptureFlow(frontProcessor, cpuDispatcher, ioDispatcher)
    private val backFlow = BackCaptureFlow(backGate, backProcessor, cpuDispatcher, ioDispatcher)

    private var frontFingerprintId: String? = null
    private var backFingerprintId: String? = null

    // ---------------------------------------------------------------------
    // Session lifecycle.
    // ---------------------------------------------------------------------

    /**
     * FIX-REVIEW-02: every fresh entry starts a NEW session - a new capsule
     * ID, RECIPIENT_LOOKUP, and empty recipient/photos/note/checklist/errors/
     * capture refs. Persisted sender fingerprints and outbox rows are never
     * touched. A same-epoch call is a no-op (rotation safety).
     */
    fun beginSession(epoch: Long) {
        if (begunEpoch == epoch) return
        begunEpoch = epoch
        _capsuleId = UUID.randomUUID().toString()
        _step.value = Step.RECIPIENT_LOOKUP
        // FIX-M1-ONDEVICE-01: pending and confirmed recipient material both die.
        recipientFlow.clearTransientMaterial()
        pickerVm.reset()
        photoSelection.clear()
        noteEditor.reset()
        backGate.reset()
        frontAttempt.reset()
        backAttempt.reset()
        frontFingerprintId = null
        backFingerprintId = null
        _flowError.value = null
        _publishError.value = null
        clearStagedPhotos()
    }

    // ---------------------------------------------------------------------
    // Recipient steps.
    // ---------------------------------------------------------------------

    fun onHandleChange(value: String) = pickerVm.onHandleChange(value)

    fun lookupRecipient() = pickerVm.lookup()

    fun onResolved(snapshot: ResolvedHandleSnapshot) {
        if (!requireStep(Step.RECIPIENT_LOOKUP, "recipient resolution")) return
        recipientFlow.onResolved(snapshot)
        _step.value = Step.RECIPIENT_CONFIRM
    }

    fun confirmRecipient() {
        if (!requireStep(Step.RECIPIENT_CONFIRM, "recipient confirmation")) return
        try {
            recipientFlow.onConfirm()
        } catch (failure: IllegalStateException) {
            failGuard(failure.message ?: "recipient confirmation failed")
            return
        }
        clearGuardError()
        _step.value = Step.FRONT
    }

    fun restartLookup() {
        if (_step.value != Step.RECIPIENT_LOOKUP && _step.value != Step.RECIPIENT_CONFIRM) {
            failGuard("lookup restart requires the recipient steps, was ${_step.value}")
            return
        }
        recipientFlow.restartLookup()
        _step.value = Step.RECIPIENT_LOOKUP
    }

    // ---------------------------------------------------------------------
    // Capture steps: THE authoritative attempt contract.
    // ---------------------------------------------------------------------

    /** Shutter press for the FRONT; legal only from FRONT with a Ready camera. */
    fun beginFrontCapture(): Boolean = beginCapture(Step.FRONT, frontAttempt)

    /** Shutter press for the prepared BACK; legal only from BACK with a Ready camera. */
    fun beginBackCapture(): Boolean = beginCapture(Step.BACK, backAttempt)

    private fun beginCapture(expected: Step, attempt: CaptureAttemptController): Boolean {
        if (!requireStep(expected, "capture")) return false
        return try {
            attempt.beginAttempt()
            true
        } catch (failure: IllegalStateException) {
            failGuard(failure.message ?: "capture not ready")
            false
        }
    }

    /** Camera bytes for the FRONT; stale deliveries are inert. */
    fun deliverFrontJpeg(jpegBytes: ByteArray) {
        if (!requireStep(Step.FRONT, "front delivery")) return
        viewModelScope.launch {
            applyFrontOutcome(
                frontFlow.onJpegDelivered(jpegBytes, capsuleId, persistence, frontAttempt),
            )
        }
    }

    /** Camera bytes for the BACK; stale deliveries are inert. */
    fun deliverBackJpeg(jpegBytes: ByteArray) {
        if (!requireStep(Step.BACK, "back delivery")) return
        viewModelScope.launch {
            applyBackOutcome(
                backFlow.onJpegDelivered(jpegBytes, capsuleId, persistence, backAttempt),
            )
        }
    }

    /** Explicit Retake after Rejected/Failed on the FRONT. */
    fun retakeFront() {
        if (!requireStep(Step.FRONT, "front retake")) return
        runCatchingRetake(frontAttempt)
    }

    /** Explicit Retake after Rejected/Failed on the BACK. */
    fun retakeBack() {
        if (!requireStep(Step.BACK, "back retake")) return
        runCatchingRetake(backAttempt)
    }

    private fun runCatchingRetake(attempt: CaptureAttemptController) {
        try {
            attempt.startRetake()
            clearGuardError()
        } catch (failure: IllegalStateException) {
            failGuard(failure.message ?: "retake unavailable")
        }
    }

    private suspend fun applyFrontOutcome(outcome: FrontCaptureOutcome) {
        when (outcome) {
            is FrontCaptureOutcome.Captured -> {
                frontFingerprintId = outcome.fingerprintId
                clearGuardError()
                _step.value = Step.BACK_CHECKLIST
            }
            // Rejected/Failed/Superseded stay on FRONT; reasons and failure
            // messages live on the authoritative controller.
            else -> Unit
        }
    }

    private suspend fun applyBackOutcome(outcome: FrontCaptureOutcome) {
        when (outcome) {
            is FrontCaptureOutcome.Captured -> {
                backFingerprintId = outcome.fingerprintId
                clearGuardError()
                _step.value = Step.CONTENT
            }
            else -> Unit
        }
    }

    /**
     * Checklist confirmation. FIX-STATE-02 regression: this MUST advance to
     * BACK once the readiness gate passed - never stay on BACK_CHECKLIST.
     */
    fun proceedToBackChecklist() {
        if (!requireStep(Step.BACK_CHECKLIST, "checklist continue")) return
        if (!backGate.ready) {
            failGuard("every preparation item must be confirmed first")
            return
        }
        clearGuardError()
        _step.value = Step.BACK
    }

    fun proceedToContent() {
        if (!requireStep(Step.CONTENT, "content continuation")) return
        clearGuardError()
    }

    // ---------------------------------------------------------------------
    // Guard helpers: fail closed with visible recovery, never crash.
    // ---------------------------------------------------------------------

    private fun requireStep(expected: Step, event: String): Boolean {
        val current = _step.value
        if (current == expected) return true
        failGuard("$event requires step $expected but the flow is at $current")
        return false
    }

    private fun failGuard(message: String) {
        _flowError.value = message
    }

    private fun clearGuardError() {
        _flowError.value = null
    }

    // ---------------------------------------------------------------------
    // Sealing: the ONE production path - publisher → ciphertext outbox.
    // ---------------------------------------------------------------------

    fun startPublishing() {
        if (!requireStep(Step.CONTENT, "publishing")) return
        if (!hasBothCaptures()) {
            failGuard("both sides must be captured before publishing")
            return
        }
        if (!photoSelection.canProceed) {
            failGuard("3..5 photos required")
            return
        }
        if (!noteEditor.canIncludeInCapsule) {
            failGuard("the note exceeds its byte limit")
            return
        }
        clearGuardError()
        _step.value = Step.PUBLISHING
        viewModelScope.launch { publish() }
    }

    private fun hasBothCaptures(): Boolean =
        frontFingerprintId != null && backFingerprintId != null

    private suspend fun publish() {
        _publishError.value = null
        val snapshot = confirmedRecipient.value
        if (snapshot == null) {
            failPublishing("recipient must be confirmed before publishing")
            return
        }
        val sender = identityProvider()
        if (sender == null) {
            failPublishing("local identity is unavailable; recovery required")
            return
        }
        if (snapshot.userId.value.toString() != sender.userId) {
            failPublishing("this milestone publishes only to your own account")
            return
        }
        val frontBytes = try {
            withContext(ioDispatcher) { persistence.decrypt(requireNotNull(frontFingerprintId)) }
        } catch (_: Exception) {
            null
        }
        val backBytes = try {
            withContext(ioDispatcher) { persistence.decrypt(requireNotNull(backFingerprintId)) }
        } catch (_: Exception) {
            null
        }
        if (frontBytes == null || backBytes == null) {
            failPublishing("sealed captures are unreadable; recapture required", restartAt = Step.FRONT)
            return
        }

        try {
            // Normalized plaintext photos exist ONLY inside this call; every
            // staged file is deleted before this method returns or throws.
            val pipeline = app.postmark.memory.create.PhotoStagingPipeline(
                stagingDirectory,
                normalizer = { jpeg ->
                    val normalized = withContext(cpuDispatcher) {
                        postmark.core.recognition.PhotoNormalizer().normalize(jpeg)
                    }
                    app.postmark.memory.create.NormalizedPhotoDto(
                        normalized.jpegBytes,
                        normalized.width,
                        normalized.height,
                    )
                },
            )
            val staged = withContext(ioDispatcher) {
                pipeline.stageAll(photoSelection.selectedIds.map(openPhotoSource))
            }
            try {
                val photoBytes = staged.map { withContext(ioDispatcher) { it.file.readBytes() } }
                val prepared = withContext(cpuDispatcher) {
                SameAccountCapsulePublisher().publish(
                    SameAccountCapsuleRequest(
                        capsuleId = CapsuleId(UUID.fromString(capsuleId)),
                        senderUserId = UserId(UUID.fromString(sender.userId)),
                        senderKeyBundleId = KeyBundleId(UUID.fromString(sender.activeKeyBundleId)),
                        senderHandleSnapshot = sender.handle,
                        createdAtEpochSeconds = clockMillis() / 1000L,
                        photoJpegs = photoBytes,
                        photoWidthsPx = staged.map { it.width },
                        photoHeightsPx = staged.map { it.height },
                        noteUtf8 = if (noteEditor.isEmpty) null else noteEditor.text,
                        frontFingerprintBytes = frontBytes,
                        backFingerprintBytes = backBytes,
                        signingKeyset = sender.signingPrivateHandle,
                        // M1 same-account: our own HPKE public half receives it.
                        recipientEncryptionPublicKeyset =
                            parsePublicHandle(snapshot.encryptionPublicKeysetB64Url),
                    ),
                )
                }
                outboxStager.stage(prepared)
                _step.value = Step.PUBLISHED
            } finally {
                withContext(ioDispatcher) { clearStagedPhotos() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _publishError.value = failure.message ?: "publishing failed"
            _step.value = Step.CONTENT
        }
    }

    private fun failPublishing(message: String, restartAt: Step = Step.CONTENT) {
        _publishError.value = message
        _step.value = restartAt
    }

    private fun clearStagedPhotos() {
        stagingDirectory.listFiles()?.forEach { it.delete() }
    }

    private fun parsePublicHandle(b64Url: String): KeysetHandle =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(Base64.urlSafeDecode(b64Url))

    /**
     * Leaving the create surface drops its transient session immediately.
     * Cleanup stays synchronous ON PURPOSE: it runs during teardown
     * (onDispose/onCleared) where a launched coroutine could be cancelled
     * before executing, and it touches at most the 3..5 tiny staged files of
     * this session - plaintext can never outlive the surface.
     */
    fun endSession() {
        // FIX-M1-ONDEVICE-01: pending resolved material never outlives the surface.
        recipientFlow.clearTransientMaterial()
        clearStagedPhotos()
    }

    override fun onCleared() {
        // Cancellation cleanup: transient session state never outlives the VM.
        endSession()
        super.onCleared()
    }
}
