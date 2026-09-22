package dev.hryshyn.remanence.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.FrontCaptureFlow
import dev.hryshyn.remanence.capture.FrontCaptureOutcome
import dev.hryshyn.remanence.create.RealStillFingerprintProcessor
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.CapsulePublishRequest
import dev.hryshyn.remanence.create.GeneratorCreateBridge
import dev.hryshyn.remanence.create.GeneratorExifDecoder
import dev.hryshyn.remanence.create.PhotoStagingPipeline
import dev.hryshyn.remanence.core.crypto.readBoundedBytes
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorStaging
import java.security.MessageDigest
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
import dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure
import dev.hryshyn.remanence.core.data.network.CapsuleRevokePort
import dev.hryshyn.remanence.core.data.network.CapsuleRevokeResult
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
 * directory resolve + explicit confirmation, front capture through the SIFT
 * processor into sealed persistence, Photo Picker 3-5 plus bounded note,
 * and ONE sealing path: the ciphertext-only
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
    private val accessTokenProvider: () -> String?,
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
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
    /** Server-authoritative sender cancellation; absent only in legacy test fixtures. */
    private val capsuleRevoke: CapsuleRevokePort? = null,
    /** Foreground connectivity admission for the no-offline-queue revoke action. */
    private val networkConnected: () -> Boolean = { true },
    /** Stable account fence for asynchronous recipient-directory completion. */
    recipientLookupOwnerProvider: suspend () -> String? = { accessTokenProvider() },
    recipientLookupBoundaryEpoch: () -> Long = { 0L },
    registerRecipientLookupBoundary: (((() -> Unit)) -> (() -> Unit))? = null,
    /**
     * C2 generator seam: per-owner C1 bridge provider from the factory.
     * Null means publishing fails closed (no bridge, no fallback); every
     * invalidation hook below is then a no-op.
     */
    private val generatorBridgeProvider: ((UserId) -> GeneratorCreateBridge.Bridge)? = null,
) : ViewModel() {

    /** Current-send state; deliberately contains no history or inbox projection. */
    sealed interface CreateUploadStatus {
        data object NotStarted : CreateUploadStatus
        data class Pending(val state: OutboxCapsuleState) : CreateUploadStatus
        data class RetryableFailure(val errorCode: String?) : CreateUploadStatus
        data class TerminalFailure(val errorCode: String?) : CreateUploadStatus
        data object Published : CreateUploadStatus
    }

    sealed interface CapsuleRevokeStatus {
        data object Idle : CapsuleRevokeStatus
        data object InFlight : CapsuleRevokeStatus
        data class Succeeded(val isReplay: Boolean) : CapsuleRevokeStatus
        data class Failed(
            val reason: CapsuleRevokeFailure,
            val retryable: Boolean,
        ) : CapsuleRevokeStatus
    }

    enum class Step {
        RECIPIENT_LOOKUP,
        RECIPIENT_CONFIRM,
        FRONT,
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
    val photoSelection = PhotoSelectionState().also { it.onEdit = { invalidateGeneratorForPhotoEdit() } }
    val noteEditor = NoteEditorState().also { it.onEdit = { invalidateGeneratorForNoteEdit() } }

    // ---------------------------------------------------------------------
    // Authoritative capture attempt (FIX-STATE-01).
    // ---------------------------------------------------------------------

    val frontAttempt = CaptureAttemptController()

    private val frontFlow = FrontCaptureFlow(frontProcessor, cpuDispatcher, ioDispatcher)

    private var frontFingerprintId: String? = null

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
    private var revokeJob: Job? = null
    private var outboxObservationJob: Job? = null
    private var createSessionGeneration: Long = 0L

    private val _uploadStatus = MutableStateFlow<CreateUploadStatus>(CreateUploadStatus.NotStarted)
    val uploadStatus: StateFlow<CreateUploadStatus> = _uploadStatus.asStateFlow()

    private val _revokeStatus = MutableStateFlow<CapsuleRevokeStatus>(CapsuleRevokeStatus.Idle)
    val revokeStatus: StateFlow<CapsuleRevokeStatus> = _revokeStatus.asStateFlow()

    /** Owner captured synchronously at session entry, before publish suspends. */
    private var sessionOwner: UserId? = null

    /**
     * C2 bridge session-sync: the bridge resolved for the session owner in
     * [beginSession] (null fails publishing closed) plus the epoch retained
     * for the bridge context. The bridge owns the monotonic content
     * revision; [createSessionGeneration] never substitutes for it.
     */
    private var generatorBridge: GeneratorCreateBridge.Bridge? = null
    private var generatorSessionEpoch: Long? = null

    /** One bound bridge generation: exact immutable context + G3 session. */
    private data class GeneratorBoundSession(
        val bridge: GeneratorCreateBridge.Bridge,
        val context: GeneratorCreateBridge.GenerationContext,
        val sessionId: String,
    )

    private var generatorBound: GeneratorBoundSession? = null

    /**
     * C3-prep: the publish generation that owns [generatorBound]. Lets
     * failure/cancel/supersede paths revoke exactly their own binding and
     * lets a retry revoke a leftover before re-beginning — never a newer
     * session's binding. Null exactly when [generatorBound] is null.
     */
    private var generatorBoundGeneration: Long? = null

    /** One pre-read original for bridge session-sync (D2: pre-read allowed). */
    private data class PrereadOriginal(val bytes: ByteArray, val widthPx: Int, val heightPx: Int)

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
        cancelRevokeLocked()
        outboxObservationJob?.cancel()
        outboxObservationJob = null
        // C2: owner/epoch change revokes any bound bridge session on its
        // exact context before its fields are overwritten below.
        dropGeneratorBound { bridge, context, sessionId ->
            bridge.onOwnerOrEpochChange(context, sessionId)
        }
        // FIX-STATE-13: ownership is tracked by the in-flight ledger, NOT by
        // the local job handle - endSession()/an earlier beginSession() may
        // already have detached a publication that is still running its
        // non-cooperative work and owns its directory until it terminates.
        if (previousCapsuleId !in inFlightPublications) {
            deleteSessionStaging(previousOwner, previousCapsuleId)
        }
        _capsuleId = UUID.randomUUID().toString()
        sessionOwner = nextOwner
        // C2: session-sync for the bridge — the epoch is retained for the
        // context and the bridge is resolved per session owner (a null
        // owner or null provider fails publishing closed at publish).
        generatorSessionEpoch = epoch
        generatorBridge = nextOwner?.let { owner -> generatorBridgeProvider?.invoke(owner) }
        _step.value = Step.RECIPIENT_LOOKUP
        // FIX-M1-ONDEVICE-01: pending and confirmed recipient material both die.
        recipientFlow.clearTransientMaterial()
        pickerVm.reset()
        photoSelection.clear()
        noteEditor.reset()
        frontAttempt.reset()
        frontFingerprintId = null
        _flowError.value = null
        _publishError.value = null
        _uploadStatus.value = CreateUploadStatus.NotStarted
        _revokeStatus.value = CapsuleRevokeStatus.Idle
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

    /** Explicit Retake after Rejected/Failed on the FRONT. */
    fun retakeFront() {
        if (!requireStep(Step.FRONT, "front retake")) return
        runCatchingRetake(frontAttempt)
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
                _step.value = Step.CONTENT
            }
            // Rejected/Failed/Superseded stay on FRONT; reasons and failure
            // messages live on the authoritative controller.
            else -> Unit
        }
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
        // C2: a fresh picker result is a photo edit — it revokes any bound
        // bridge session on its exact context before the selection changes.
        dropGeneratorBound { bridge, context, sessionId ->
            bridge.onPhotoEdit(context, sessionId)
        }
        photoSelection.clear()
        ids.forEach { id -> photoSelection.toggle(id) }
    }

    // ---------------------------------------------------------------------
    // Sealing: the ONE production path - publisher → ciphertext outbox.
    // ---------------------------------------------------------------------

    fun startPublishing() {
        if (!requireStep(Step.CONTENT, "publishing")) return
        if (frontFingerprintId == null) {
            failGuard("front must be captured before publishing")
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

    /** Starts one explicit, online-only sender cancellation for the published capsule. */
    fun revokePublished() {
        if (!requireStep(Step.PUBLISHED, "cancellation")) return
        if (_uploadStatus.value !is CreateUploadStatus.Published) {
            failGuard("cancellation requires a published capsule")
            return
        }
        if (_revokeStatus.value is CapsuleRevokeStatus.InFlight ||
            _revokeStatus.value is CapsuleRevokeStatus.Succeeded
        ) {
            return
        }
        val repository = capsuleRevoke ?: run {
            _revokeStatus.value = CapsuleRevokeStatus.Failed(
                CapsuleRevokeFailure.INTERNAL_ERROR,
                retryable = false,
            )
            return
        }
        val requestedCapsuleId = runCatching { CapsuleId.parseRest(capsuleId) }.getOrNull()
        if (requestedCapsuleId == null) {
            _revokeStatus.value = CapsuleRevokeStatus.Failed(
                CapsuleRevokeFailure.INVALID_RESPONSE,
                retryable = false,
            )
            return
        }
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            _revokeStatus.value = CapsuleRevokeStatus.Failed(
                CapsuleRevokeFailure.AUTH_INVALID,
                retryable = false,
            )
            return
        }
        if (!networkConnected()) {
            _revokeStatus.value = CapsuleRevokeStatus.Failed(
                CapsuleRevokeFailure.NETWORK,
                retryable = true,
            )
            return
        }
        clearGuardError()
        _revokeStatus.value = CapsuleRevokeStatus.InFlight
        val generation = createSessionGeneration
        revokeJob = viewModelScope.launch {
            try {
                when (val result = repository.revoke(requestedCapsuleId, accessToken)) {
                    is CapsuleRevokeResult.Success -> {
                        if (isRevokeCurrent(generation, requestedCapsuleId)) {
                            _revokeStatus.value = CapsuleRevokeStatus.Succeeded(result.revoke.isReplay)
                        }
                    }
                    is CapsuleRevokeResult.Failure -> {
                        if (isRevokeCurrent(generation, requestedCapsuleId)) {
                            _revokeStatus.value = CapsuleRevokeStatus.Failed(
                                result.reason,
                                result.retryable,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isRevokeCurrent(generation, requestedCapsuleId)) {
                    _revokeStatus.value = CapsuleRevokeStatus.Failed(
                        CapsuleRevokeFailure.INTERNAL_ERROR,
                        retryable = true,
                    )
                }
            }
        }
    }

    /** Cancels an in-flight publication; returns whether one existed. */
    private fun cancelPublishingLocked(): Boolean {
        val job = publishJob ?: return false
        publishJob = null
        job.cancel()
        return true
    }

    private fun cancelRevokeLocked(): Boolean {
        val job = revokeJob ?: return false
        revokeJob = null
        job.cancel()
        return true
    }

    private fun isRevokeCurrent(generation: Long, capsuleId: CapsuleId): Boolean =
        generation == createSessionGeneration &&
            _step.value == Step.PUBLISHED &&
            capsuleId.toRestString() == _capsuleId

    /**
     * FIX-STATE-06: EVERY publish failure - including identity resolution or
     * any unexpected exception - terminates visibly back at CONTENT. Nothing
     * can leave the flow stuck on the PUBLISHING spinner.
     */
    private fun isPublishCurrent(generation: Long): Boolean =
        generation == createSessionGeneration && _step.value == Step.PUBLISHING

    // ---------------------------------------------------------------------
    // C2 generator session-sync + invalidation hooks.
    // ---------------------------------------------------------------------

    /**
     * Revokes the bound bridge session through [invalidator] on its exact
     * immutable context, then drops it. A missing bound session is a no-op,
     * so missing sessions and teardown ordering never matter here.
     * Revocation is best-effort cleanup: authoring must never break because
     * teardown of a dead session failed.
     */
    private fun dropGeneratorBound(
        invalidator: (
            GeneratorCreateBridge.Bridge,
            GeneratorCreateBridge.GenerationContext,
            String,
        ) -> Unit,
    ) {
        val bound = generatorBound ?: return
        generatorBound = null
        generatorBoundGeneration = null
        try {
            invalidator(bound.bridge, bound.context, bound.sessionId)
        } catch (_: Exception) {
        }
    }

    /** Photo edit after begin (picker result or direct toggle/remove). */
    private fun invalidateGeneratorForPhotoEdit() {
        dropGeneratorBound { bridge, context, sessionId ->
            bridge.onPhotoEdit(context, sessionId)
        }
    }

    /**
     * Revokes the bound session only when it still belongs to
     * [generation]. A stale failure of a superseded job must never drop a
     * newer session's binding.
     */
    private fun dropGeneratorBoundForGeneration(
        generation: Long,
        invalidator: (
            GeneratorCreateBridge.Bridge,
            GeneratorCreateBridge.GenerationContext,
            String,
        ) -> Unit,
    ) {
        if (generatorBound == null || generatorBoundGeneration != generation) return
        dropGeneratorBound(invalidator)
    }

    /**
     * Note edit after begin. An in-place note edit never amends a bound
     * generation: the old session is revoked, and the next startPublishing
     * begins a fresh content revision — regeneration is mandatory.
     */
    private fun invalidateGeneratorForNoteEdit() {
        dropGeneratorBound { bridge, context, sessionId ->
            bridge.onNoteEdit(context, sessionId)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * C3 session-sync: binds one bridge generation for THIS publication,
     * synchronizing the bridge context (canonical owner, session epoch,
     * monotonic content revision) with the publish inputs. There is no
     * legacy fallback: a missing bridge, epoch, sender, or unreadable
     * source fails closed to CONTENT.
     *
     * Owner rulings applied: `generationId` is the session capsule id; the
     * sender snapshot arrives captured exactly once per publication (see
     * [publish]) and is used here for begin; the input carries opaque-UUID
     * content ids with SHA-256 pre-reads of the selected originals
     * (pre-read/hash at selection allowed) and the authored note. No
     * bind/freeze happens here — binds run in [bindGeneratorPhotos]; the
     * begun session holds no staged bytes and dies by revocation below or
     * by G3 TTL/sweep.
     *
     * Returns false (fail closed, back at CONTENT) when the bridge is
     * absent, the sources pre-read cleanly yet the bridge rejects the
     * session, or the pre-read itself fails. Cancellation propagates.
     */
    private suspend fun beginGeneratorSession(
        generation: Long,
        inputs: PublishInputs,
        capturedSender: SenderIdentitySnapshot,
    ): Boolean {
        val bridge = generatorBridge
        if (generatorBridgeProvider == null || bridge == null) {
            failPublishing("generator bridge is unavailable; publishing cancelled", generation)
            return false
        }
        val epoch = generatorSessionEpoch
        if (epoch == null) {
            failPublishing("generator session has no epoch; publishing cancelled", generation)
            return false
        }
        val sender = capturedSender
        val preread: List<PrereadOriginal>? = withContext(ioDispatcher) {
            try {
                inputs.photoIds.map { pickerId ->
                    val bytes = openPhotoSource(pickerId).openInputStream().use { stream ->
                        stream.readBoundedBytes(PhotoStagingPipeline.MAX_SOURCE_BYTES)
                    }
                    val upright = GeneratorExifDecoder.decodeUpright(bytes)
                    PrereadOriginal(bytes, upright.widthPx, upright.heightPx)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        if (preread == null) {
            failPublishing("generator photo sources are unreadable; publishing cancelled", generation)
            return false
        }
        // Retry/re-begin must never orphan a previous binding: revoke any
        // leftover from this or an older generation on its exact context
        // before opening a new G3 session. A newer generation's binding (a
        // stale job racing a live session) is never touched.
        if (generatorBoundGeneration?.let { it <= generation } == true) {
            dropGeneratorBound { bridge, context, sessionId ->
                bridge.cancel(context, sessionId)
            }
        }
        try {
            val input = GeneratorExpression.GeneratorInput(
                ownerId = inputs.owner.toRestString(),
                epoch = epoch,
                photos = preread.mapIndexed { index, original ->
                    GeneratorExpression.PhotoRef(
                        contentId = UUID.randomUUID().toString(),
                        ordinal = index,
                        widthPx = original.widthPx,
                        heightPx = original.heightPx,
                        contentHash = sha256Hex(original.bytes),
                    )
                },
                note = inputs.noteText,
                music = null,
            )
            val begun = bridge.begin(
                owner = inputs.owner,
                sessionEpoch = epoch,
                generationId = inputs.capsuleId,
                input = input,
                sender = GeneratorStaging.SenderSnapshot(sender.userId, sender.handle),
            )
            if (begun == null) {
                failPublishing("generator session was rejected; publishing cancelled", generation)
                return false
            }
            if (!isPublishCurrent(generation)) {
                bridge.cancel(begun.context, begun.sessionId)
                throw PublishSuperseded()
            }
            generatorBound = GeneratorBoundSession(bridge, begun.context, begun.sessionId)
            generatorBoundGeneration = generation
            return true
        } finally {
            preread.forEach { it.bytes.fill(0) }
        }
    }

    /** One bound authored photo for the publish request (derived bytes/dims). */
    private data class BoundPhotoForPublish(val bytes: ByteArray, val widthPx: Int, val heightPx: Int)

    /**
     * C3 generator path: binds every selected source in authored order
     * through the frozen bridge and reuses the normalized derived
     * bytes/dims from those same bind results (no second normalize).
     *
     * Any non-Bound result fails closed: the bridge already revoked the
     * whole session fail-closed internally, so our slot is dropped on the
     * exact context (idempotent) and publishing returns to CONTENT with no
     * partial handoff. On success the one-shot handoff is frozen and
     * verified (same context, generationId == capsuleId, inputHash matches
     * the canonical hash) before the ordered photos are returned.
     * Cancellation propagates (the bridge revokes the matched session
     * first, then our catch in [publish] drops the slot).
     *
     * Returns null after failPublishing (the caller returns immediately).
     */
    private suspend fun bindGeneratorPhotos(
        generation: Long,
        inputs: PublishInputs,
        bound: GeneratorBoundSession,
    ): List<BoundPhotoForPublish>? {
        val bridge = bound.bridge
        val photos = ArrayList<BoundPhotoForPublish>(inputs.photoIds.size)
        for ((ordinal, pickerId) in inputs.photoIds.withIndex()) {
            val result = try {
                bridge.bindSlot(bound.context, bound.sessionId, ordinal, openPhotoSource(pickerId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val slot = result as? GeneratorCreateBridge.SlotResult.Bound
            if (slot == null) {
                dropGeneratorBoundForGeneration(generation) { b, c, s -> b.cancel(c, s) }
                failPublishing("generator bind rejected slot $ordinal; publishing cancelled", generation)
                return null
            }
            photos += BoundPhotoForPublish(slot.normalized.bytes, slot.normalized.widthPx, slot.normalized.heightPx)
        }
        val frozen = bridge.freeze(bound.context, bound.sessionId)
        val handoff = (frozen as? GeneratorCreateBridge.FreezeResult.Frozen)?.handoff
        if (handoff == null ||
            handoff.context != bound.context ||
            handoff.context.generationId != inputs.capsuleId ||
            handoff.inputHash != GeneratorExpression.canonicalHash(handoff.input)
        ) {
            dropGeneratorBoundForGeneration(generation) { b, c, s -> b.cancel(c, s) }
            failPublishing("generator freeze rejected; publishing cancelled", generation)
            return null
        }
        return photos
    }

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
            // C3 authoritative cutover: the single sender snapshot is read
            // unconditionally here. There is no legacy fallback left: every
            // later step requires the bridge below.
            val capturedSender = identityProvider() ?: run {
                failPublishing("local identity is unavailable; recovery required", generation)
                return
            }
            if (!beginGeneratorSession(generation, inputs, capturedSender)) return
            publishSealed(generation, inputs, capturedSender)
        } catch (superseded: PublishSuperseded) {
            // The owning session is gone: ITS staging dies, nothing is
            // published, and no newer session's artifacts are touched. Its
            // bridge binding dies too, but only when still ours.
            dropGeneratorBoundForGeneration(generation) { bridge, context, sessionId ->
                bridge.cancel(context, sessionId)
            }
            clearStagedPhotosGuarded(inputs.owner, inputs.capsuleId)
        } catch (cancelled: CancellationException) {
            // Session teardown: staged plaintext dies with this scope below.
            dropGeneratorBoundForGeneration(generation) { bridge, context, sessionId ->
                bridge.cancel(context, sessionId)
            }
            clearStagedPhotosGuarded(inputs.owner, inputs.capsuleId)
            throw cancelled
        } catch (failure: Exception) {
            if (!isPublishCurrent(generation)) return
            dropGeneratorBoundForGeneration(generation) { bridge, context, sessionId ->
                bridge.cancel(context, sessionId)
            }
            _publishError.value = failure.message ?: "publishing failed"
            _step.value = Step.CONTENT
        } finally {
            inFlightPublications.remove(inputs.capsuleId)
        }
    }

    private suspend fun publishSealed(
        generation: Long,
        inputs: PublishInputs,
        capturedSender: SenderIdentitySnapshot,
    ) {
        fun ensureCurrent() {
            if (!isPublishCurrent(generation)) throw PublishSuperseded()
        }
        // M2-P07: the recipient identity, recipient key-bundle identity, and
        // recipient encryption public keyset come ONLY from the explicitly
        // confirmed immutable [ResolvedHandleSnapshot] captured into
        // [PublishInputs] before any suspend boundary. The sender identity
        // (user id, signing key, handle, owner) is the single snapshot
        // captured per publication ([capturedSender]). A self-send is a
        // valid publication because the user may confirm their own handle -
        // the request builder then receives EQUAL VALUES for sender and
        // recipient, not a default.
        val snapshot = inputs.recipient
        val sender = capturedSender
        ensureCurrent()
        val senderOwner = runCatching { UserId.parseRest(sender.userId) }.getOrNull()
        if (senderOwner != inputs.owner) {
            failPublishing("authenticated owner changed; publishing cancelled", generation)
            return
        }
        var frontBytes: ByteArray? = null
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
            val frontForPublish = frontBytes
            if (frontForPublish == null) {
                failPublishing("sealed captures are unreadable; recapture required", generation, Step.FRONT)
                return
            }

            // FIX-STATE-13/LUNA-01: normalized plaintext lives ONLY inside
            // this call and is deleted before it returns or throws; no other
            // account or session's material is ever touched. The C3
            // generator path stages no legacy photo directory at all: the
            // normalized bytes arrive in memory from the bridge binds, and
            // only the G3 staging leases (revoked on invalidation, logout,
            // or TTL) hold bytes outside this call.
            // C3 authoritative cutover: a bridge session bound for THIS
            // publication binds every authored source in order through the
            // frozen bridge; the normalized derived bytes/dims come from
            // those same bind results (no second normalize). A lost binding
            // fails closed — there is no legacy fallback and no shadow.
            val boundForPublish = generatorBound ?: run {
                failPublishing("generator session was lost; publishing cancelled", generation)
                return
            }
            val boundPhotos = bindGeneratorPhotos(generation, inputs, boundForPublish) ?: return
            val photoJpegs: List<ByteArray> = boundPhotos.map { it.bytes }
            val photoWidthsPx: List<Int> = boundPhotos.map { it.widthPx }
            val photoHeightsPx: List<Int> = boundPhotos.map { it.heightPx }
            ensureCurrent()
            try {
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
                        photoJpegs = photoJpegs,
                        photoWidthsPx = photoWidthsPx,
                        photoHeightsPx = photoHeightsPx,
                        noteUtf8 = inputs.noteText,
                        frontFingerprintBytes = frontForPublish,
                        frontFingerprintProfileId = RecognitionProfile.SIFT_ROOTSIFT_V1_ID,
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
            // The FRONT decrypt result is a caller-owned handoff buffer. The
            // publisher consumes it synchronously; every return, failure,
            // cancellation, or stale-session path wipes it here.
            frontBytes?.fill(0)
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
        cancelRevokeLocked()
        outboxObservationJob?.cancel()
        outboxObservationJob = null
        // C2: leaving the surface revokes any bound bridge session on its
        // exact context.
        dropGeneratorBound { bridge, context, sessionId ->
            bridge.cancel(context, sessionId)
        }
        recipientFlow.clearTransientMaterial()
        pickerVm.reset()
        photoSelection.clear()
        noteEditor.reset()
        frontAttempt.reset()
        frontFingerprintId = null
        _step.value = Step.RECIPIENT_LOOKUP
        _flowError.value = null
        _publishError.value = null
        // FIX-STATE-13: only when no live publication still owns THIS session's
        // directory may teardown remove it directly.
        if (capsuleId !in inFlightPublications) deleteSessionStaging(owner, capsuleId)
        begunEpoch = null
        sessionOwner = null
        generatorBridge = null
        generatorSessionEpoch = null
        _uploadStatus.value = CreateUploadStatus.NotStarted
        _revokeStatus.value = CapsuleRevokeStatus.Idle
    }

    override fun onCleared() {
        // Cancellation cleanup: transient session state never outlives the VM.
        pickerVm.close()
        endSession()
        super.onCleared()
    }
}
