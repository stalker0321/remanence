package dev.hryshyn.remanence.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.capture.BackCaptureFlow
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.FrontCaptureFlow
import dev.hryshyn.remanence.capture.FrontCaptureOutcome
import dev.hryshyn.remanence.capture.PreparedBackGate
import dev.hryshyn.remanence.create.RealStillFingerprintProcessor
import dev.hryshyn.remanence.create.CreateCaptureSessionStore
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.CapsulePublishRequest
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
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleStatus
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.AccountStorageRetention
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

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
 * publisher feeding the durable outbox and account-scoped upload work. There is no second, all-plaintext
 * route. Plaintext staging lives only inside [publish] and is cleared in a
 * finally-equivalent path; cancellation tears the session down.
 *
 * FIX-STATE-13: every publication owns the isolated staging subdirectory
 * `accounts/<owner>/temp/create/<capsule UUID>`; neither a superseded publish
 * nor session teardown can remove another account's or session's staged
 * artifacts, and abandoned directories from process death are swept only by
 * scoped owner + UUID matching.
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
    /**
     * M2-P04/LUNA-01: immutable account-scoped file roots. Create plaintext
     * is resolved beneath the captured owner's TEMP/create root; there is no
     * global staging root or fallback.
     */
    private val accountScopedFileRoots: AccountScopedFileRoots,
    private val openPhotoSource: (pickerId: String) -> dev.hryshyn.remanence.create.PhotoSource,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /**
     * FIX-STATE-08: injectable still processors so production-shaped tests
     * drive the same delivery callbacks without camera hardware; production
     * wiring keeps the real OpenCV pipeline.
     */
    frontProcessor: dev.hryshyn.remanence.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.FRONT),
    backProcessor: dev.hryshyn.remanence.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.BACK),
    /** Session-memory-only BACK handoff; injectable only for lifecycle tests. */
    captureStore: CreateCaptureSessionStore = CreateCaptureSessionStore(),
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * FIX-STATE-03/08: the photo normalization step is a port; production
     * keeps the real OpenCV normalizer on the CPU dispatcher, tests inject a
     * deterministic one so publishing stays fully exercisable off-hardware.
     */
    private val photoNormalizer: dev.hryshyn.remanence.create.PhotoNormalizerPort = { jpeg ->
        val normalized = withContext(cpuDispatcher) {
            dev.hryshyn.remanence.core.recognition.PhotoNormalizer().normalize(jpeg)
        }
        dev.hryshyn.remanence.create.NormalizedPhotoDto(
            normalized.jpegBytes,
            normalized.width,
            normalized.height,
        )
    },
    /**
     * M2-P08: the sender-retry keyset wrapper and dedicated KEK alias.
     * The publisher wraps the freshly generated capsule keyset through
     * these; the wrapper MUST be injected, not created internally, so
     * tests can substitute an [InMemoryKekBoundary]-backed wrapper.
     */
    private val senderRetryKeysetWrapper: dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper,
    private val senderRetryKekAlias: String,
    private val enqueueUpload: suspend (UserId, CapsuleId) -> Unit,
    /** Exact owner + capsule current-send projection for the mounted flow. */
    private val outboxCapsuleDao: OutboxCapsuleDao? = null,
    /** Stable account fence for asynchronous recipient-directory completion. */
    recipientLookupOwnerProvider: suspend () -> String? = { accessTokenProvider() },
    recipientLookupBoundaryEpoch: () -> Long = { 0L },
    registerRecipientLookupBoundary: (((() -> Unit)) -> (() -> Unit))? = null,
) : ViewModel() {

    /** Current-send state; deliberately contains no history or inbox projection. */
    sealed interface CreateUploadStatus {
        data object NotStarted : CreateUploadStatus
        data class Pending(val state: OutboxCapsuleState) : CreateUploadStatus
        data class RetryableFailure(val errorCode: String?) : CreateUploadStatus
        data class TerminalFailure(val errorCode: String?) : CreateUploadStatus
        data object Published : CreateUploadStatus
    }

    enum class Step {
        RECIPIENT_LOOKUP,
        RECIPIENT_CONFIRM,
        FRONT,
        BACK_CHECKLIST,
        BACK,
        CONTENT,
        PUBLISHING,
        UPLOAD_PENDING,
        PUBLISHED,
    }

    val pickerVm = RecipientPickerViewModel(
        directory = directory,
        accessTokenProvider = accessTokenProvider,
        sessionOwnerProvider = recipientLookupOwnerProvider,
        sessionBoundaryEpoch = recipientLookupBoundaryEpoch,
        registerSessionBoundary = registerRecipientLookupBoundary,
        scope = viewModelScope,
    )

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
    private val backFlow = BackCaptureFlow(
        checklistGate = backGate,
        processor = backProcessor,
        cpuDispatcher = cpuDispatcher,
        ioDispatcher = ioDispatcher,
        captureStore = captureStore,
    )

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
    private var outboxObservationJob: Job? = null
    private var createSessionGeneration: Long = 0L

    private val _uploadStatus = MutableStateFlow<CreateUploadStatus>(CreateUploadStatus.NotStarted)
    val uploadStatus: StateFlow<CreateUploadStatus> = _uploadStatus.asStateFlow()

    /** Owner captured synchronously at session entry, before publish suspends. */
    private var sessionOwner: UserId? = null

    /**
     * FIX-STATE-13: capsule ids whose publication job is still alive and
     * therefore OWNS its staging subdirectory. A cancelled-but-still-running
     * (non-cooperative normalization) publish keeps exclusive cleanup rights;
     * nothing else may delete that directory while the id is listed here.
     */
    private val inFlightPublications: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Immutable snapshot of ONE session's publish inputs, captured before
     * any suspend boundary so no long-running step can read live state that a
     * newer session already replaced. */
    private data class PublishInputs(
        val capsuleId: String,
        val owner: UserId,
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
     * FIX-REVIEW-02/LUNA-01: every fresh entry starts a NEW session - a new capsule
     * ID, RECIPIENT_LOOKUP, and empty recipient/photos/note/checklist/errors/
     * capture refs. Persisted sender fingerprints and outbox rows are never
     * touched. The authenticated owner is parsed and captured before any
     * publication suspension; a missing or malformed owner is retained as a
     * fail-closed null and can never select a storage root. A same-epoch call
     * with the same owner is a no-op (rotation safety).
     *
     * FIX-STATE-13: staging is session-owned. The replaced session's
     * directory is removed here only when NO publication still owns it; an
     * in-flight (possibly cancellation-delayed) publish keeps exclusive
     * cleanup rights over its own directory. Process-death leftovers under
     * `accounts/<owner>/temp/create/<capsule UUID>` are recovered by the
     * authenticated startup sweep, not by this entry path.
     */
    fun beginSession(epoch: Long, ownerUserId: String? = sessionOwner?.toRestString()) {
        val nextOwner = ownerUserId?.let { raw ->
            runCatching { UserId.parseRest(raw) }.getOrNull()
        }
        if (begunEpoch == epoch && sessionOwner == nextOwner) return
        val previousOwner = sessionOwner
        val previousCapsuleId = _capsuleId
        begunEpoch = epoch
        createSessionGeneration += 1
        deliveryGeneration += 1
        cancelPublishingLocked()
        outboxObservationJob?.cancel()
        outboxObservationJob = null
        // FIX-STATE-13: ownership is tracked by the in-flight ledger, NOT by
        // the local job handle - endSession()/an earlier beginSession() may
        // already have detached a publication that is still running its
        // non-cooperative work and owns its directory until it terminates.
        if (previousCapsuleId !in inFlightPublications) {
            deleteSessionStaging(previousOwner, previousCapsuleId)
        }
        _capsuleId = UUID.randomUUID().toString()
        sessionOwner = nextOwner
        _step.value = Step.RECIPIENT_LOOKUP
        // FIX-M1-ONDEVICE-01: pending and confirmed recipient material both die.
        recipientFlow.clearTransientMaterial()
        pickerVm.reset()
        photoSelection.clear()
        noteEditor.reset()
        backGate.reset()
        backFlow.clearStagedMaterial()
        frontAttempt.reset()
        backAttempt.reset()
        frontFingerprintId = null
        backFingerprintId = null
        _flowError.value = null
        _publishError.value = null
        _uploadStatus.value = CreateUploadStatus.NotStarted
        observeCurrentOutbox(sessionOwner, _capsuleId, createSessionGeneration)
    }

    /**
     * Observes only the row owned by this session's authenticated owner and
     * generated capsule. A stale emission is ignored by both owner and
     * generation checks, so a previous session cannot repaint a later one.
     */
    private fun observeCurrentOutbox(owner: UserId?, capsuleId: String, generation: Long) {
        val dao = outboxCapsuleDao ?: return
        if (owner == null) return
        outboxObservationJob = viewModelScope.launch {
            dao.observeStatusByCapsuleIdAndOwner(capsuleId, owner.toRestString()).collect { status ->
                applyOutboxStatus(status, generation, owner, capsuleId)
            }
        }
    }

    private fun applyOutboxStatus(
        status: OutboxCapsuleStatus?,
        generation: Long,
        owner: UserId,
        capsuleId: String,
    ) {
        if (!outboxObservationStillCurrent(generation, owner, capsuleId)) return
        val mapped = when (status?.state) {
            null -> CreateUploadStatus.NotStarted
            OutboxCapsuleState.PREPARING,
            OutboxCapsuleState.ENCRYPTED,
            OutboxCapsuleState.UPLOADING,
            OutboxCapsuleState.FINALIZING,
            -> CreateUploadStatus.Pending(status.state)
            OutboxCapsuleState.RETRYABLE_FAILURE ->
                CreateUploadStatus.RetryableFailure(status.lastErrorCode)
            OutboxCapsuleState.TERMINAL_FAILURE ->
                CreateUploadStatus.TerminalFailure(status.lastErrorCode)
            OutboxCapsuleState.PUBLISHED -> CreateUploadStatus.Published
        }
        if (!outboxObservationStillCurrent(generation, owner, capsuleId)) return
        _uploadStatus.value = mapped
        if (_step.value == Step.UPLOAD_PENDING || _step.value == Step.PUBLISHED) {
            if (!outboxObservationStillCurrent(generation, owner, capsuleId)) return
            _step.value = if (mapped is CreateUploadStatus.Published) {
                Step.PUBLISHED
            } else {
                Step.UPLOAD_PENDING
            }
        }
    }

    private fun outboxObservationStillCurrent(
        generation: Long,
        owner: UserId,
        capsuleId: String,
    ): Boolean =
        generation == createSessionGeneration && owner == sessionOwner && capsuleId == _capsuleId

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
        if (!frontAttempt.hasActiveAttempt) {
            jpegBytes.fill(0)
            return
        }
        if (!requireStep(Step.FRONT, "front delivery")) {
            jpegBytes.fill(0)
            return
        }
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
        if (!backAttempt.hasActiveAttempt) {
            jpegBytes.fill(0)
            return
        }
        if (!requireStep(Step.BACK, "back delivery")) {
            jpegBytes.fill(0)
            return
        }
        val generation = deliveryGeneration
        viewModelScope.launch {
            val outcome = backFlow.onJpegDelivered(jpegBytes, capsuleId, persistence, backAttempt)
            if (generation != deliveryGeneration) {
                if (outcome is FrontCaptureOutcome.Captured) {
                    backFlow.takeStagedBack(outcome.fingerprintId)?.fill(0)
                }
                return@launch
            }
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
                if (_step.value != Step.BACK) {
                    backFlow.takeStagedBack(outcome.fingerprintId)?.fill(0)
                    return
                }
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
        // M2-P07: the publisher must NEVER receive a publication request without
        // an attributable, explicitly confirmed recipient. The flow normally
        // gates this via the step table, but a defensive fail-closed guard
        // here makes the contract explicit and prevents a synchronously
        // thrown NPE from the requireNotNull below reaching the UI.
        val boundRecipient = confirmedRecipient.value
        if (boundRecipient == null) {
            failGuard("a recipient must be confirmed before publishing")
            return
        }
        val owner = sessionOwner
        if (owner == null) {
            failGuard("authenticated owner is unavailable; publishing is disabled")
            return
        }
        clearGuardError()
        // FIX-STATE-11: immutable inputs of THIS session, captured before any
        // suspend boundary.
        val inputs = PublishInputs(
            capsuleId = capsuleId,
            owner = owner,
            recipient = boundRecipient,
            noteText = if (noteEditor.isEmpty) null else noteEditor.text,
            frontFingerprintId = requireNotNull(frontFingerprintId),
            backFingerprintId = requireNotNull(backFingerprintId),
            photoIds = photoSelection.selectedIds.toList(),
        )
        _step.value = Step.PUBLISHING
        val generation = createSessionGeneration
        // FIX-STATE-13: THIS publication owns its staging directory from here
        // until its job reaches a terminal state (success, failure,
        // supersession, or cancellation cleanup).
        inFlightPublications.add(inputs.capsuleId)
        publishJob = viewModelScope.launch { publish(generation, inputs) }
    }

    /** Cancels an in-flight publication; returns whether one existed. */
    private fun cancelPublishingLocked(): Boolean {
        val job = publishJob ?: return false
        publishJob = null
        job.cancel()
        return true
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

    private suspend fun clearStagedPhotosGuarded(owner: UserId, capsuleId: String) {
        // FIX-STATE-11: guaranteed plaintext removal even on cancellation -
        // but NEVER any state publication from this path.
        // FIX-STATE-13: only the OWNING session's directory is touched.
        withContext(NonCancellable + ioDispatcher) {
            deleteSessionStaging(owner, capsuleId)
        }
    }

    private suspend fun publish(generation: Long, inputs: PublishInputs) {
        _publishError.value = null
        try {
            publishSealed(generation, inputs)
        } catch (superseded: PublishSuperseded) {
            // The owning session is gone: ITS staging dies, nothing is
            // published, and no newer session's artifacts are touched.
            clearStagedPhotosGuarded(inputs.owner, inputs.capsuleId)
        } catch (cancelled: CancellationException) {
            // Session teardown: staged plaintext dies with this scope below.
            clearStagedPhotosGuarded(inputs.owner, inputs.capsuleId)
            throw cancelled
        } catch (failure: Exception) {
            if (!isPublishCurrent(generation)) return
            _publishError.value = failure.message ?: "publishing failed"
            _step.value = Step.CONTENT
        } finally {
            inFlightPublications.remove(inputs.capsuleId)
        }
    }

    private suspend fun publishSealed(generation: Long, inputs: PublishInputs) {
        fun ensureCurrent() {
            if (!isPublishCurrent(generation)) throw PublishSuperseded()
        }
        // M2-P07: the recipient identity, recipient key-bundle identity, and
        // recipient encryption public keyset come ONLY from the explicitly
        // confirmed immutable [ResolvedHandleSnapshot] captured into
        // [PublishInputs] before any suspend boundary. The sender identity
        // (user id, signing key, handle, owner) remains the authenticated
        // local account. A self-send is a valid publication because the user
        // may confirm their own handle - the request builder then receives
        // EQUAL VALUES for sender and recipient, not a default.
        val snapshot = inputs.recipient
        val sender = identityProvider()
        ensureCurrent()
        if (sender == null) {
            failPublishing("local identity is unavailable; recovery required", generation)
            return
        }
        val senderOwner = runCatching { UserId.parseRest(sender.userId) }.getOrNull()
        if (senderOwner != inputs.owner) {
            failPublishing("authenticated owner changed; publishing cancelled", generation)
            return
        }
        var frontBytes: ByteArray? = null
        var backBytes: ByteArray? = null
        try {
            try {
                // Capture the decrypt result while still inside the suspend
                // boundary. If cancellation is observed while resuming from
                // decrypt, the outer finally still owns and wipes this array.
                withContext(ioDispatcher) {
                    frontBytes = persistence.decrypt(inputs.frontFingerprintId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Leave the owned handoff null and fail closed below.
            }
            ensureCurrent()
            // BACK is a session-memory-only handoff. Taking it removes the
            // store copy; there is intentionally no persistence fallback.
            backBytes = try {
                backFlow.takeStagedBack(inputs.backFingerprintId)
            } catch (_: Exception) {
                null
            }
            ensureCurrent()
            val frontForPublish = frontBytes
            val backForPublish = backBytes
            if (frontForPublish == null || backForPublish == null) {
                failPublishing("sealed captures are unreadable; recapture required", generation, Step.FRONT)
                return
            }

            // FIX-STATE-13/LUNA-01: normalized plaintext photos live ONLY
            // inside this call and ONLY inside THIS publication's own
            // account-scoped directory; every staged artifact is deleted
            // before this method returns or throws, and no other account or
            // session's directory is ever touched.
            val pipeline = dev.hryshyn.remanence.create.PhotoStagingPipeline(
                stagingDirectoryFor(inputs.owner, inputs.capsuleId),
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
                CapsulePublisher(
                    senderRetryKeysetWrapper = senderRetryKeysetWrapper,
                    alias = senderRetryKekAlias,
                ).publish(
                    CapsulePublishRequest(
                        capsuleId = CapsuleId(UUID.fromString(inputs.capsuleId)),
                        senderUserId = UserId(UUID.fromString(sender.userId)),
                        // M2-P07: the recipient identity, recipient key-bundle
                        // identity, and recipient encryption public keyset come
                        // ONLY from the explicitly confirmed immutable
                        // [ResolvedHandleSnapshot] captured before any suspend
                        // boundary. The sender fields (including ownerUserId)
                        // remain the authenticated local account; a self-send
                        // passes equal values explicitly.
                        recipientUserId = snapshot.userId,
                        senderKeyBundleId = KeyBundleId(UUID.fromString(sender.activeKeyBundleId)),
                        recipientKeyBundleId = snapshot.keyBundleId,
                        ownerUserId = inputs.owner.toRestString(),
                        senderHandleSnapshot = sender.handle,
                        createdAtEpochSeconds = clockMillis() / 1000L,
                        photoJpegs = photoBytes,
                        photoWidthsPx = staged.map { it.width },
                        photoHeightsPx = staged.map { it.height },
                        noteUtf8 = inputs.noteText,
                        frontFingerprintBytes = frontForPublish,
                        backFingerprintBytes = backForPublish,
                        signingKeyset = sender.signingPrivateHandle,
                        recipientEncryptionPublicKeyset =
                            parsePublicHandle(snapshot.encryptionPublicKeysetB64Url),
                    ),
                )
                }
                ensureCurrent()
                outboxStager.stage(prepared)
                ensureCurrent()
                val currentSender = identityProvider()
                ensureCurrent()
                val currentOwner = currentSender?.userId?.let { raw ->
                    runCatching { UserId.parseRest(raw) }.getOrNull()
                }
                if (currentOwner != inputs.owner) {
                    failPublishing("authenticated owner changed; publishing cancelled", generation)
                    return
                }
                try {
                    enqueueUpload(inputs.owner, CapsuleId.parseRest(inputs.capsuleId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failPublishing("upload could not be queued; retry available", generation)
                    return
                }
                ensureCurrent()
                _step.value = if (_uploadStatus.value is CreateUploadStatus.Published) {
                    Step.PUBLISHED
                } else {
                    Step.UPLOAD_PENDING
                }
            } finally {
                // Normalized plaintext staging never survives this call.
                withContext(ioDispatcher) {
                    deleteSessionStaging(inputs.owner, inputs.capsuleId)
                }
            }
        } finally {
            // The decrypt/take results are caller-owned handoff buffers. The
            // publisher consumes them synchronously; every return, failure,
            // cancellation, or stale-session path wipes them here.
            frontBytes?.fill(0)
            backBytes?.fill(0)
            // If the attempt returned before taking BACK (for example an
            // unavailable sender identity), discard that session handoff too.
            backFlow.takeStagedBack(inputs.backFingerprintId)?.fill(0)
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

    /**
     * FIX-STATE-13/LUNA-01: staging is account + session-owned. Every
     * publication stages plaintext only inside its own
     * `accounts/<owner>/temp/create/<capsule UUID>` directory, so concurrent
     * sessions, account switches, and cancellation-delayed stale publishes
     * can never delete each other's files.
     */
    private fun stagingDirectoryFor(owner: UserId, capsuleId: String): File =
        File(accountScopedFileRoots.createStagingRoot(owner), capsuleId)

    /**
     * Removes ONE session's own staging directory without following symbolic
     * links. A missing directory is already clean. A leaf symlink is unlinked
     * and its target is left untouched.
     */
    private fun deleteSessionStaging(owner: UserId?, capsuleId: String) {
        if (owner == null) return
        AccountStorageRetention(accountScopedFileRoots)
            .deleteNoFollow(stagingDirectoryFor(owner, capsuleId))
    }

    private fun parsePublicHandle(b64Url: String): KeysetHandle =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(Base64.urlSafeDecode(b64Url))

    /**
     * Leaving the create surface drops its transient session immediately.
     * Cleanup stays synchronous ON PURPOSE: it runs during teardown
     * (onDispose/onCleared). In-memory picker/photos/note/checklist/capture
     * fields, step, errors, and session-owned identity are reset here;
     * beginSession of the same epoch remains a rotation-safe no-op because
     * rotation never calls this. FIX-STATE-13: the session's OWN staging
     * directory is removed here only when no publication still owns it - an
     * in-flight publish keeps the exclusive right (and the NonCancellable
     * obligation) to remove its own directory, so a stale coroutine can never
     * delete another session's artifacts and plaintext can never outlive its
     * owner.
     */
    fun endSession() {
        // FIX-M1-ONDEVICE-01 / FIX-STATE-11: pending resolved material AND an
        // in-flight publication never outlive the surface; the cancelled job's
        // own NonCancellable cleanup guarantees staged plaintext removal.
        createSessionGeneration += 1
        deliveryGeneration += 1
        val owner = sessionOwner
        val capsuleId = _capsuleId
        cancelPublishingLocked()
        outboxObservationJob?.cancel()
        outboxObservationJob = null
        recipientFlow.clearTransientMaterial()
        pickerVm.reset()
        photoSelection.clear()
        noteEditor.reset()
        backGate.reset()
        backFlow.clearStagedMaterial()
        frontAttempt.reset()
        backAttempt.reset()
        frontFingerprintId = null
        backFingerprintId = null
        _step.value = Step.RECIPIENT_LOOKUP
        _flowError.value = null
        _publishError.value = null
        // FIX-STATE-13: only when no live publication still owns THIS session's
        // directory may teardown remove it directly.
        if (capsuleId !in inFlightPublications) deleteSessionStaging(owner, capsuleId)
        begunEpoch = null
        sessionOwner = null
        _uploadStatus.value = CreateUploadStatus.NotStarted
    }

    override fun onCleared() {
        // Cancellation cleanup: transient session state never outlives the VM.
        pickerVm.close()
        endSession()
        super.onCleared()
    }
}
