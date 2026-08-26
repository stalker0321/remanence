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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    /**
     * FIX-STATE-08: injectable still processors so production-shaped tests
     * drive the same delivery callbacks without camera hardware; production
     * wiring keeps the real OpenCV pipeline.
     */
    frontProcessor: app.postmark.memory.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.FRONT),
    backProcessor: app.postmark.memory.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.BACK),
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * FIX-STATE-03/08: the photo normalization step is a port; production
     * keeps the real OpenCV normalizer on the CPU dispatcher, tests inject a
     * deterministic one so publishing stays fully exercisable off-hardware.
     */
    private val photoNormalizer: app.postmark.memory.create.PhotoNormalizerPort = { jpeg ->
        val normalized = withContext(cpuDispatcher) {
            postmark.core.recognition.PhotoNormalizer().normalize(jpeg)
        }
        app.postmark.memory.create.NormalizedPhotoDto(
            normalized.jpegBytes,
            normalized.width,
            normalized.height,
        )
    },
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

    private val frontFlow = FrontCaptureFlow(frontProcessor, cpuDispatcher, ioDispatcher)
    private val backFlow = BackCaptureFlow(backGate, backProcessor, cpuDispatcher, ioDispatcher)

    private var frontFingerprintId: String? = null
    private var backFingerprintId: String? = null

    /**
     * FIX-STATE-01: monotonic guard for delivered-still continuations. A new
     * session invalidates every queued outcome - the late coroutine may still
     * finish its work, but its RESULT can never be applied to the new session.
     */
    private var deliveryGeneration: Long = 0L

    /**
     * FIX-STATE-11: THE owning Job of the active publication plus the
     * monotonic create-session generation it belongs to. endSession() and
     * beginSession(new epoch) cancel the job and invalidate every queued
     * publish continuation; a superseded publish can neither stage into the
     * outbox nor mutate step/error of any later session.
     */
    private var publishJob: Job? = null
    private var createSessionGeneration: Long = 0L

    /** Immutable snapshot of ONE session's publish inputs, captured before
     * any suspend boundary so no long-running step can read live state that a
     * newer session already replaced. */
    private data class PublishInputs(
        val capsuleId: String,
        val recipient: ResolvedHandleSnapshot,
        val noteText: String?,
        val frontFingerprintId: String,
        val backFingerprintId: String,
        val photoIds: List<String>,
    )

    /** Thrown when the publishing session was replaced mid-flight. */
    private class PublishSuperseded : Exception()

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
        createSessionGeneration += 1
        deliveryGeneration += 1
        cancelPublishingLocked()
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

    /**
     * Camera bytes for the FRONT. A late hardware callback with no active
     * attempt (dispose/reset won the race) is silently inert - it is device
     * timing, not a user action, so it must not raise the recovery banner.
     */
    fun deliverFrontJpeg(jpegBytes: ByteArray) {
        if (!frontAttempt.hasActiveAttempt) return
        if (!requireStep(Step.FRONT, "front delivery")) return
        val generation = deliveryGeneration
        viewModelScope.launch {
            val outcome = frontFlow.onJpegDelivered(jpegBytes, capsuleId, persistence, frontAttempt)
            // A session reset supersedes this continuation entirely.
            if (generation != deliveryGeneration) return@launch
            applyFrontOutcome(outcome)
        }
    }

    /** Camera bytes for the BACK; late callbacks without an attempt are inert. */
    fun deliverBackJpeg(jpegBytes: ByteArray) {
        if (!backAttempt.hasActiveAttempt) return
        if (!requireStep(Step.BACK, "back delivery")) return
        val generation = deliveryGeneration
        viewModelScope.launch {
            val outcome = backFlow.onJpegDelivered(jpegBytes, capsuleId, persistence, backAttempt)
            if (generation != deliveryGeneration) return@launch
            applyBackOutcome(outcome)
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
                if (_step.value != Step.FRONT) return
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
                if (_step.value != Step.BACK) return
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

    /**
     * FIX-STATE-06: THE production sink for Photo Picker results. The UI
     * hands the picked ids here; tests call the same method, so the 3..5 gate
     * behaves identically in both worlds. A fresh picker result replaces the
     * previous selection (the system picker is authoritative per attempt).
     */
    fun onPhotosPicked(ids: List<String>) {
        photoSelection.clear()
        ids.forEach { id -> photoSelection.toggle(id) }
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
        // FIX-STATE-11: immutable inputs of THIS session, captured before any
        // suspend boundary.
        val inputs = PublishInputs(
            capsuleId = capsuleId,
            recipient = requireNotNull(confirmedRecipient.value),
            noteText = if (noteEditor.isEmpty) null else noteEditor.text,
            frontFingerprintId = requireNotNull(frontFingerprintId),
            backFingerprintId = requireNotNull(backFingerprintId),
            photoIds = photoSelection.selectedIds.toList(),
        )
        _step.value = Step.PUBLISHING
        val generation = createSessionGeneration
        publishJob = viewModelScope.launch { publish(generation, inputs) }
    }

    /** Cancels an in-flight publication; safe to call repeatedly. */
    private fun cancelPublishingLocked() {
        publishJob?.cancel()
        publishJob = null
    }

    private fun hasBothCaptures(): Boolean =
        frontFingerprintId != null && backFingerprintId != null

    /**
     * FIX-STATE-06: EVERY publish failure - including identity resolution or
     * any unexpected exception - terminates visibly back at CONTENT. Nothing
     * can leave the flow stuck on the PUBLISHING spinner.
     */
    private fun isPublishCurrent(generation: Long): Boolean =
        generation == createSessionGeneration && _step.value == Step.PUBLISHING

    private suspend fun clearStagedPhotosGuarded() {
        // FIX-STATE-11: guaranteed plaintext removal even on cancellation -
        // but NEVER any state publication from this path.
        withContext(NonCancellable + ioDispatcher) { clearStagedPhotos() }
    }

    private suspend fun publish(generation: Long, inputs: PublishInputs) {
        _publishError.value = null
        try {
            publishSealed(generation, inputs)
        } catch (superseded: PublishSuperseded) {
            // The owning session is gone: staging dies, nothing is published.
            clearStagedPhotosGuarded()
        } catch (cancelled: CancellationException) {
            // Session teardown: staged plaintext dies with this scope below.
            clearStagedPhotosGuarded()
            throw cancelled
        } catch (failure: Exception) {
            if (!isPublishCurrent(generation)) return
            _publishError.value = failure.message ?: "publishing failed"
            _step.value = Step.CONTENT
        }
    }

    private suspend fun publishSealed(generation: Long, inputs: PublishInputs) {
        fun ensureCurrent() {
            if (!isPublishCurrent(generation)) throw PublishSuperseded()
        }
        val snapshot = inputs.recipient
        val sender = identityProvider()
        ensureCurrent()
        if (sender == null) {
            failPublishing("local identity is unavailable; recovery required", generation)
            return
        }
        if (snapshot.userId.value.toString() != sender.userId) {
            failPublishing("this milestone publishes only to your own account", generation)
            return
        }
        val frontBytes = try {
            withContext(ioDispatcher) { persistence.decrypt(inputs.frontFingerprintId) }
        } catch (_: Exception) {
            null
        }
        ensureCurrent()
        val backBytes = try {
            withContext(ioDispatcher) { persistence.decrypt(inputs.backFingerprintId) }
        } catch (_: Exception) {
            null
        }
        ensureCurrent()
        if (frontBytes == null || backBytes == null) {
            failPublishing("sealed captures are unreadable; recapture required", generation, Step.FRONT)
            return
        }

            // Normalized plaintext photos exist ONLY inside this call; every
            // staged file is deleted before this method returns or throws.
            val pipeline = app.postmark.memory.create.PhotoStagingPipeline(
                stagingDirectory,
                // The port owns its dispatcher hop; stageAll below adds IO.
                normalizer = photoNormalizer,
            )
            val staged = withContext(ioDispatcher) {
                pipeline.stageAll(inputs.photoIds.map(openPhotoSource))
            }
            ensureCurrent()
            try {
                val photoBytes = staged.map { withContext(ioDispatcher) { it.file.readBytes() } }
                ensureCurrent()
                val prepared = withContext(cpuDispatcher) {
                SameAccountCapsulePublisher().publish(
                    SameAccountCapsuleRequest(
                        capsuleId = CapsuleId(UUID.fromString(inputs.capsuleId)),
                        senderUserId = UserId(UUID.fromString(sender.userId)),
                        senderKeyBundleId = KeyBundleId(UUID.fromString(sender.activeKeyBundleId)),
                        senderHandleSnapshot = sender.handle,
                        createdAtEpochSeconds = clockMillis() / 1000L,
                        photoJpegs = photoBytes,
                        photoWidthsPx = staged.map { it.width },
                        photoHeightsPx = staged.map { it.height },
                        noteUtf8 = inputs.noteText,
                        frontFingerprintBytes = frontBytes,
                        backFingerprintBytes = backBytes,
                        signingKeyset = sender.signingPrivateHandle,
                        // M1 same-account: our own HPKE public half receives it.
                        recipientEncryptionPublicKeyset =
                            parsePublicHandle(snapshot.encryptionPublicKeysetB64Url),
                    ),
                )
                }
                ensureCurrent()
                outboxStager.stage(prepared)
                _step.value = Step.PUBLISHED
            } finally {
                // Normalized plaintext staging never survives this call.
                withContext(ioDispatcher) { clearStagedPhotos() }
            }
    }

    private fun failPublishing(
        message: String,
        generation: Long,
        restartAt: Step = Step.CONTENT,
    ) {
        // A superseded publish never writes into a newer session's state.
        if (!isPublishCurrent(generation)) return
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
        // FIX-M1-ONDEVICE-01 / FIX-STATE-11: pending resolved material AND an
        // in-flight publication never outlive the surface; the cancelled job's
        // own NonCancellable cleanup guarantees staged plaintext removal.
        createSessionGeneration += 1
        deliveryGeneration += 1
        cancelPublishingLocked()
        recipientFlow.clearTransientMaterial()
        clearStagedPhotos()
    }

    override fun onCleared() {
        // Cancellation cleanup: transient session state never outlives the VM.
        endSession()
        super.onCleared()
    }
}
