package dev.hryshyn.remanence.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.FrontCaptureFlow
import dev.hryshyn.remanence.capture.FrontCaptureOutcome
import dev.hryshyn.remanence.create.RealStillFingerprintProcessor
import dev.hryshyn.remanence.create.CapsuleExpressionArtifact
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.CapsulePublishRequest
import dev.hryshyn.remanence.create.GeneratorCreateBridge
import dev.hryshyn.remanence.create.GeneratorPreviewLoader
import dev.hryshyn.remanence.create.OriginalRetainSinkHost
import dev.hryshyn.remanence.ui.collage.AllFamilyCandidateProducer
import dev.hryshyn.remanence.ui.collage.AllFamilyV4Adapter
import dev.hryshyn.remanence.ui.collage.AndroidCollageCropMatrixSource
import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import dev.hryshyn.remanence.ui.collage.toCropWindow
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
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
    /**
     * T186: null for handleless receive-only accounts. Scan/receive paths
     * never read it; publish paths fail closed without it (the Create entry
     * gate already routes handleless accounts to handle setup first).
     */
    val handle: String?,
    val activeKeyBundleId: String,
    val encryptionPrivateHandle: KeysetHandle,
    val signingPrivateHandle: KeysetHandle,
)

/** Metadata only: self selection never reads private key material. */
data class SelfRecipientAccount(val userId: String, val handle: String)

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
    /**
     * T186 M12e-boundary: pending v2 publish ports for the normal
     * publish boundary (real R3/R4/R5 clients + session gate + account
     * + selected pin). Null (legacy/test fixtures) keeps the v1-only
     * path exactly — the pending route can freeze but never publishes.
     */
    private val pendingBoundary: dev.hryshyn.remanence.pending.PendingBoundaryPorts? = null,
    /** Server-authoritative sender cancellation; absent only in legacy test fixtures. */
    private val capsuleRevoke: CapsuleRevokePort? = null,
    /** Foreground connectivity admission for the no-offline-queue revoke action. */
    private val networkConnected: () -> Boolean = { true },
    /** Stable account fence for asynchronous recipient-directory completion. */
    recipientLookupOwnerProvider: suspend () -> String? = { accessTokenProvider() },
    recipientLookupBoundaryEpoch: () -> Long = { 0L },
    registerRecipientLookupBoundary: (((() -> Unit)) -> (() -> Unit))? = null,
    /**
     * BUG-014: bounded single session recovery for null-token lookup starts,
     * invoked by the recipient picker. Defaults to no recovery (historical
     * immediate failure); production wires the session coordinator refresh.
     */
    recipientLookupSessionRefresh: suspend () -> RecipientPickerViewModel.SessionRecovery =
        { RecipientPickerViewModel.SessionRecovery.NO_SESSION },
    /**
     * C2 generator seam: per-owner C1 bridge provider from the factory.
     * Null means publishing fails closed (no bridge, no fallback); every
     * invalidation hook below is then a no-op.
     */
    private val generatorBridgeProvider: ((UserId) -> GeneratorCreateBridge.Bridge)? = null,
    /**
     * Stage1-C preview loader. Null disables preview with a typed
     * Unavailable state; a preview is never faked from a placeholder.
     */
    private val generatorPreviewLoader: GeneratorPreviewLoader? = null,
    /**
     * Production collage analysis factory (T002 cutover). Null disables the
     * chooser source: no runners, no chooser, no candidate population. This
     * replaces the removed diagnostic-only gate; there is no parallel
     * diagnostic engine path.
     */
    private val collageSourceFactory: CollageCropSourceFactory? = null,
    /** Same production policy for assignment and final solve; strict for parity fixtures. */
    private val collageCropPolicy: dev.hryshyn.remanence.ui.collage.CropAdmissionPolicy = dev.hryshyn.remanence.ui.collage.CropAdmissionPolicy.STRICT,
    private val telemetryCoordinator: dev.hryshyn.remanence.telemetry.TelemetryCoordinator? = null,
    /**
     * T062: sender-side descriptor source (direct MusicBrainz/CAA client).
     * Null/absent keeps the pre-T062 behaviour exactly: every selection seals
     * `mode = "text"`, `artwork = null`. No placeholder, no provider guess.
     */
    private val musicArtworkResolver: dev.hryshyn.remanence.core.data.network.MusicArtworkResolver? = null,
    /**
     * CONTENT selected-tiles thumbnail source: already-picked URI string to
     * bounded tile bitmap, loaded off-main on [ioDispatcher]. Null disables
     * image loading (tiles keep their selection checks without images); a
     * per-URI null result is a load failure, never a choice invalidation.
     * Production wires [PickedPhotoThumbnails] over the app ContentResolver.
     */
    private val pickedThumbnailLoader: (suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    /** Actual currently signed-in account, absent means no self shortcut. */
    private val selfRecipientAccountProvider: suspend () -> SelfRecipientAccount? = { null },
) : ViewModel() {
    private val telemetrySession = telemetryCoordinator?.newSession()

    /** Current-send state; deliberately contains no history or inbox projection. */
    sealed interface CreateUploadStatus {
        data object NotStarted : CreateUploadStatus
        data class Pending(val state: OutboxCapsuleState) : CreateUploadStatus
        data class RetryableFailure(val errorCode: String?) : CreateUploadStatus
        data class TerminalFailure(val errorCode: String?) : CreateUploadStatus
        data object Published : CreateUploadStatus
        data class PublicationIssue(val kind: PublicationIssueKind, val durable: Boolean) : CreateUploadStatus
    }
    enum class PublicationIssueKind { LIMIT, ACCESS_UNAVAILABLE, UNKNOWN }
    // This is an invocation fence, not new key persistence or a pending outbox.
    private var pendingFinalizeAttempt: Pair<UserId, String>? = null
    private var finalizingQueueRefused = false

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
        COMPOSE,
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
        sessionRefresh = recipientLookupSessionRefresh,
        scope = viewModelScope,
    )

    private val sessionStore = CreateSessionStore()
    private val recipientFlow = CreateRecipientFlow(pickerVm, sessionStore)
    private var pendingPickerResolution: RecipientLookupUiState.Resolved? = null

    val confirmedRecipient: StateFlow<ResolvedHandleSnapshot?> get() = sessionStore.confirmedRecipient

    /**
     * T186 M12d: binds an R1-frozen pending route (EMAIL/PHONE choice at
     * the recipient step) for the normal publish boundary. The store
     * keeps confirmed-handle and frozen-route mutually exclusive.
     */
    fun onPendingRouteFrozen(identity: dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity) {
        // T186 M14b (T188 B3): in-step gate for BOTH modes BEFORE any
        // store mutation — an off-step callback must not replace the
        // chosen recipient (nor null the confirmed handle via the
        // store's mutual exclusion). Step first, then session: without
        // a live session owner nothing is stored.
        if (!requireStep(Step.RECIPIENT_LOOKUP, "pending route")) return
        if (sessionOwner == null) {
            failGuard("authenticated owner is unavailable; recipient choice disabled")
            return
        }
        sessionStore.freezePendingRoute(identity)
        clearGuardError()
        // T186 M12e-step: a frozen PENDING_V2 route is the explicit
        // recipient decision — advance LOOKUP→FRONT like an explicit
        // confirmation does, so capture can begin. Frozen V1 stays
        // for the resolve+confirm path (no step change here). No fake
        // account, handle or recipient keys are fabricated; the later
        // composition/publish guards still enforce owner, 3..5 photos,
        // note, drawable, handle-readiness and the frozen route itself.
        if (identity is dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity.Pending) {
            _step.value = Step.FRONT
        }
    }

    /** Frozen pending route for the publish boundary (null when handle-bound). */
    val pendingRoute: kotlinx.coroutines.flow.StateFlow<dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity?>
        get() = sessionStore.pendingRoute

    /**
     * T186 M12: authored photo originals for the pending v2 sender (the
     * same retained originals the preview generation keeps). Pure read —
     * no state change. Null unless the pending flag is ON and at least
     * one original is retained; the sender fails closed (BLOCKED) on
     * null instead of fabricating content.
     */
    fun pendingSenderContent(): dev.hryshyn.remanence.pending.PendingSenderContent? {
        if (!dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()) return null
        val photos = retainedOriginals.orderedBytes()
        if (photos.isEmpty()) return null
        return dev.hryshyn.remanence.pending.PendingSenderContent(photos)
    }

    /**
     * FIX-M1-ONDEVICE-01: the resolved-but-not-yet-confirmed snapshot for the
     * confirmation screen. Binding happens ONLY through explicit confirm.
     */
    val pendingRecipient: StateFlow<ResolvedHandleSnapshot?> get() = recipientFlow.pendingRecipient

    private val selfLookupOwnerProvider = recipientLookupOwnerProvider
    private val _selfRecipientAccount = MutableStateFlow<SelfRecipientAccount?>(null)
    val selfRecipientAccount: StateFlow<SelfRecipientAccount?> = _selfRecipientAccount.asStateFlow()
    private val _selfRecipientLookupRejected = MutableStateFlow(false)
    val selfRecipientLookupRejected: StateFlow<Boolean> = _selfRecipientLookupRejected.asStateFlow()
    private var selfAccountJob: Job? = null
    private var selfLookupJob: Job? = null
    private var selfRecipientTargetUserId: String? = null

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

    /**
     * T186 M12e-boundary: the handwritten 5-char locator code of a
     * completed pending v2 publication (null otherwise). Set only on a
     * genuine R5 `PENDING` result; cleared when a new publish begins.
     */
    data class PendingPublishedCode(
        val deliveryId: String,
        val locatorCode: String,
        val hint: String,
    )

    private val _pendingPublication = MutableStateFlow<PendingPublishedCode?>(null)
    val pendingPublication: StateFlow<PendingPublishedCode?> = _pendingPublication.asStateFlow()

    // Content state.
    val photoSelection = PhotoSelectionState().also { it.onEdit = { invalidateGeneratorForPhotoEdit() } }
    // Keep picker inventory separate from the active capsule selection, so a
    // deselected tile can be selected again without reopening the system picker.
    private val _pickedPhotoIds = MutableStateFlow<List<String>>(emptyList())
    val pickedPhotoIds: StateFlow<List<String>> = _pickedPhotoIds.asStateFlow()
    val noteEditor = NoteEditorState().also { it.onEdit = { invalidateGeneratorForNoteEdit() } }

    /**
     * S2b-sender: the picker selection that becomes the sealed
     * track snapshot at publish time (release flow since S4). Null = no snapshot (v1 and
     * snapshot-less v2 flows unchanged). Written only from the debug
     * picker bridge until end-to-end works; release can never select.
     */
    private val _musicSelection =
        MutableStateFlow<dev.hryshyn.remanence.core.data.network.MusicTrackHit?>(null)
    val musicSelection: StateFlow<dev.hryshyn.remanence.core.data.network.MusicTrackHit?> =
        _musicSelection.asStateFlow()

    /**
     * T062: descriptor resolved for the *current* selection only. It is
     * cleared synchronously on every selection change, so a slow provider
     * response can never be sealed under a different track; a response whose
     * epoch has moved on is dropped.
     */
    private val _musicArtwork = MutableStateFlow<GeneratorExpression.ArtworkRef?>(null)
    val musicArtwork: StateFlow<GeneratorExpression.ArtworkRef?> = _musicArtwork.asStateFlow()

    /**
     * T144: bounded public outcome of the latest completed artwork resolution
     * (`RESOLVED` or exactly one failure reason). Lets the next device run
     * distinguish null MBID (H1) from a specific resolver/fetch failure (H2).
     */
    private val _musicArtworkReason =
        MutableStateFlow<dev.hryshyn.remanence.core.data.network.MusicArtworkReason?>(null)
    val musicArtworkReason: StateFlow<dev.hryshyn.remanence.core.data.network.MusicArtworkReason?> =
        _musicArtworkReason.asStateFlow()

    /**
     * T144: preview bitmap decoded from the verified resolved bytes only.
     * Held in active create state, never persisted; null means the text band.
     */
    private val _musicArtworkBitmap =
        MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val musicArtworkBitmap: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> =
        _musicArtworkBitmap.asStateFlow()

    private var musicArtworkEpoch = 0

    fun setMusicSelection(hit: dev.hryshyn.remanence.core.data.network.MusicTrackHit?) {
        if (_musicSelection.value == hit) return
        _musicSelection.value = hit
        collageChoices.clear()
        frozenCollageSelection = null
        frozenDrawableInputs = null
        // Music changes the chooser input: drop per-photo corrections too.
        clearCropState()
        startMusicArtworkResolution(hit)
    }

    fun clearMusicSelection() {
        _musicSelection.value = null
        collageChoices.clear()
        frozenCollageSelection = null
        frozenDrawableInputs = null
        clearCropState()
        startMusicArtworkResolution(null)
    }

    /**
     * Resolve the selected recording to a sealable descriptor, or leave the
     * artwork absent (text mode). Failures are swallowed by design: artwork
     * must never block or fail the music flow. T144: every finish publishes
     * the bounded public reason (DEBUG log carries only the reason token),
     * and a resolved finish additionally publishes the preview bitmap decoded
     * from the verified bytes. Stale epochs publish nothing.
     */
    var isResolvingMusicArtwork: Boolean by mutableStateOf(false)
        private set

    private fun startMusicArtworkResolution(
        hit: dev.hryshyn.remanence.core.data.network.MusicTrackHit?,
    ) {
        val epoch = ++musicArtworkEpoch
        isResolvingMusicArtwork = false
        _musicArtwork.value = null
        _musicArtworkReason.value = null
        _musicArtworkBitmap.value = null
        val resolver = musicArtworkResolver ?: return
        if (hit == null) return
        // A selected hit without a recording id completes synchronously as
        // NO_RECORDING_ID with zero requests — this is the H1 signal.
        val recordingMbid = hit.recordingMbid?.takeIf { it.isNotBlank() }
            ?: run {
                _musicArtworkReason.value =
                    dev.hryshyn.remanence.core.data.network.MusicArtworkReason.NO_RECORDING_ID
                reportArtworkOutcome(
                    dev.hryshyn.remanence.core.data.network.MusicArtworkReason.NO_RECORDING_ID,
                )
                return
            }
        isResolvingMusicArtwork = true
        viewModelScope.launch {
            val outcome = try {
                resolver.resolveOutcome(recordingMbid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dev.hryshyn.remanence.core.data.network.MusicArtworkResolution.Unavailable(
                    dev.hryshyn.remanence.core.data.network.MusicArtworkReason.NETWORK,
                )
            }
            if (epoch == musicArtworkEpoch) {
                isResolvingMusicArtwork = false
                when (outcome) {
                    is dev.hryshyn.remanence.core.data.network.MusicArtworkResolution.Resolved -> {
                        if (_musicArtwork.value != outcome.artwork) {
                            collageChoices.clear()
                            frozenCollageSelection = null
                            clearDrawableProofs()
                            clearCropState()
                        }
                        _musicArtwork.value = outcome.artwork
                        _musicArtworkReason.value =
                            dev.hryshyn.remanence.core.data.network.MusicArtworkReason.RESOLVED
                        _musicArtworkBitmap.value = decodeArtworkBitmap(outcome.bytes)
                        reportArtworkOutcome(
                            dev.hryshyn.remanence.core.data.network.MusicArtworkReason.RESOLVED,
                        )
                    }
                    is dev.hryshyn.remanence.core.data.network.MusicArtworkResolution.Unavailable -> {
                        _musicArtworkReason.value = outcome.reason
                        reportArtworkOutcome(outcome.reason)
                    }
                }
            }
        }
    }

    /** Decodes verified resolved bytes; null keeps the text band. */
    private fun decodeArtworkBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()

    /**
     * Exactly one bounded reason-token line per completed resolution, DEBUG
     * builds only. The formatted string is allowlisted to `reason=<NAME>`: no
     * MBID, title, URL, digest, bytes, identity or exception text can pass.
     */
    private fun reportArtworkOutcome(
        reason: dev.hryshyn.remanence.core.data.network.MusicArtworkReason,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "ArtworkResolve",
                dev.hryshyn.remanence.core.data.network.artworkResolveDiagnostic(reason),
            )
        }
    }

    /**
     * Real-prepare drawable evidence keyed to the CURRENT candidate identity
     * and content/note/music/crop lifetime. The host reports the exact inputs
     * it measured (pass-through bundle, captured at composition); the VM
     * accepts them only on equality with live state, so asynchronous or stale
     * reports can never approve a different candidate or outdated
     * note/music/crop state. Unknown (never reported) is NOT eligible.
     */
    internal fun prepareInputs(): PrepareInputs = PrepareInputs(
        note = noteEditor.text.takeIf { it.isNotEmpty() },
        trackId = collageChooserTrackFor(_musicSelection.value)?.trackId?.toString(),
        artworkSize = _musicArtworkBitmap.value?.let { "${it.width}x${it.height}" },
        artwork = _musicArtwork.value,
    )

    /** Positive Ready inputs by candidate id (equality-checked on every use). */
    private val drawableInputs = mutableStateMapOf<String, PrepareInputs>()

    /** Drawable inputs recorded alongside the frozen selection, if any. */
    private var frozenDrawableInputs: PrepareInputs? by mutableStateOf(null)

    internal val isFrozenCollageDrawable: Boolean
        get() = frozenCollageSelection != null && frozenDrawableInputs == prepareInputs()

    internal val isDisplayedCollageDrawable: Boolean
        get() = collageChoices.displayed?.let { drawableMatches(it.candidateId) } == true

    /** Current positive drawable evidence for [candidateId], if any. */
    private fun drawableMatches(candidateId: String): Boolean =
        drawableInputs[candidateId]?.let { it == prepareInputs() } == true

    /**
     * Lifecycle-safe report of the host's measured prepare for one
     * candidate. Stored keyed by id; validated against live state at every
     * use. A Rejected report whose inputs match the frozen proof clears it
     * fail-closed: the sheet flipped Ready→Rejected (e.g. late artwork
     * changing the measured band).
     */
    internal fun onCandidatePrepared(candidateId: String, ready: Boolean, inputs: PrepareInputs) {
        if (collageChoices.candidates.none { it.candidateId == candidateId }) return
        if (inputs != prepareInputs()) {
            // An inconsistent report cannot leave this candidate approved.
            // Keep authored edits, but require a fresh matching Ready result.
            drawableInputs.remove(candidateId)
            if (frozenCollageSelection?.candidateId == candidateId) frozenDrawableInputs = null
            return
        }
        if (ready) {
            drawableInputs[candidateId] = inputs
            // Keep the authored choice through host recreation, but permit it
            // only after the NEW host has actually prepared the same sheet.
            if (frozenCollageSelection?.candidateId == candidateId &&
                collageChoices.selected?.candidateId == candidateId) {
                frozenDrawableInputs = inputs
            }
        } else {
            drawableInputs.remove(candidateId)
            if (frozenCollageSelection?.candidateId == candidateId) {
                frozenCollageSelection = null
                frozenDrawableInputs = null
            }
        }
    }

    /** Drops every drawable proof (session/content reset boundary). */
    private fun clearDrawableProofs() {
        drawableInputs.clear()
        frozenDrawableInputs = null
    }

    /** Explicit sender collage selection; refused if no such candidate. */
    fun selectCollageCandidate(index: Int): Boolean {
        val candidate = collageChoices.candidates.getOrNull(index) ?: return false
        // Drawable gate: the candidate must carry a matching positive Ready
        // proof from the host's real prepare (never a stale or unknown state).
        if (!drawableMatches(candidate.candidateId)) {
            failGuard("candidate is not drawable")
            return false
        }
        val selected = collageChoices.select(index)
        // The display follows the newly displayed candidate's keyed entries.
        if (selected) refreshCropDisplay()
        return selected
    }

    /**
     * Drops a frozen collage choice without touching candidates, preview, or
     * retained analysis. The chooser host calls this on every Previous/Next
     * navigation so a frozen id can never name a sheet the canvas no longer
     * shows. Keyed crop entries SURVIVE navigation (browse persistence);
     * only the display refocuses on the newly displayed candidate.
     */
    fun clearFrozenCollageSelection() {
        collageChoices.clearSelection()
        frozenCollageSelection = null
        frozenDrawableInputs = null
        refreshCropDisplay()
    }

    // ---------------------------------------------------------------------
    // Manual crop state is keyed by candidate, slot and content. Done saves
    // the exact source window chosen by the sender. Composition analysis is
    // an automatic-crop aid, never a veto of a valid manual frame. Saved crops
    // survive browsing; Back discards the open draft. Content edits clear both.
    // ---------------------------------------------------------------------

    /** Source-window correction relative to the original solver crop. */
    data class CropCorrection(
        val contentId: String,
        val zoom: Float,
        val panX: Float,
        val panY: Float,
    )

    private val _cropTransforms =
        MutableStateFlow<Map<dev.hryshyn.remanence.ui.collage.CorrectionKey,
            dev.hryshyn.remanence.ui.collage.CropTransform>>(emptyMap())

    // Display map for the currently displayed candidate (contentId keyed).
    private val _cropCorrections = MutableStateFlow<Map<String, CropCorrection>>(emptyMap())
    val cropCorrections: StateFlow<Map<String, CropCorrection>> = _cropCorrections.asStateFlow()

    /** Open crop stage slot (preview-source contentId), or null when closed. */
    private val _cropSlot = MutableStateFlow<String?>(null)
    val cropSlot: StateFlow<String?> = _cropSlot.asStateFlow()

    private fun cropKeyFor(
        candidate: dev.hryshyn.remanence.ui.collage.CollageCandidate,
        contentId: String,
    ): dev.hryshyn.remanence.ui.collage.CorrectionKey? {
        val slot = candidate.expression.placements.indexOfFirst { it.contentId == contentId }
        if (slot < 0) return null
        return dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection
            .correctionKey(candidate.candidateId, slot, contentId)
    }

    private fun keyMatchesCandidate(
        key: dev.hryshyn.remanence.ui.collage.CorrectionKey,
        candidate: dev.hryshyn.remanence.ui.collage.CollageCandidate,
    ): Boolean {
        val placements = candidate.expression.placements
        return key.expressionIdentity == candidate.candidateId &&
            key.slot in placements.indices &&
            placements[key.slot].contentId == key.contentId
    }

    /** Candidate identity the open crop stage was opened against. */
    private var cropSlotCandidateId: String? = null

    // Keep the original solver window across successive edits. Reopening a
    // saved crop must still allow zooming out and Reset to the original frame.
    private var cropBases = emptyMap<dev.hryshyn.remanence.ui.collage.CorrectionKey, GeneratorExpression.CropWindow>()
    private var cropEntryTransform: dev.hryshyn.remanence.ui.collage.CropTransform? = null

    private fun restoreCropDraft() {
        val candidateId = cropSlotCandidateId ?: return
        val contentId = _cropSlot.value ?: return
        val candidate = collageChoices.candidates.firstOrNull { it.candidateId == candidateId } ?: return
        val key = cropKeyFor(candidate, contentId) ?: return
        val entry = cropEntryTransform
        _cropTransforms.value = if (entry == null) _cropTransforms.value - key
            else _cropTransforms.value + (key to entry)
    }

    /** Refreshes the display map from the keyed store for the displayed candidate. */
    private fun refreshCropDisplay() {
        val candidate = collageChoices.displayed
        if (_cropSlot.value != null && candidate?.candidateId != cropSlotCandidateId) {
            restoreCropDraft()
            _cropSlot.value = null
            cropSlotCandidateId = null
            cropEntryTransform = null
        }
        val display = LinkedHashMap<String, CropCorrection>()
        if (candidate != null) {
            for ((key, transform) in _cropTransforms.value) {
                if (!keyMatchesCandidate(key, candidate)) continue
                display[key.contentId] = CropCorrection(
                    contentId = key.contentId,
                    zoom = transform.z.toFloat(),
                    panX = transform.xPercent.toFloat(),
                    panY = transform.yPercent.toFloat(),
                )
            }
        }
        _cropCorrections.value = display
        // The stage is bound to one photo of one displayed candidate; when the
        // displayed candidate (or its slots) changes underneath it, close the
        // stage instead of showing a frame that no longer matches the crop.
        val slot = _cropSlot.value
        if (slot != null) {
            val sameCandidate = candidate != null &&
                candidate.candidateId == cropSlotCandidateId &&
                candidate.expression.placements.any { it.contentId == slot }
            if (!sameCandidate) {
                _cropSlot.value = null
                cropSlotCandidateId = null
            }
        }
    }

    /** Frame geometry of one slot of the displayed candidate (crop stage input). */
    data class CropSlotGeometry(
        /** Fixed window aspect (`slot.w / slot.h`, Astra `--crop-ratio`). */
        val slotAspect: Float,
        /** The approved sealed window for this slot, or `null` when absent. */
        val baseCrop: GeneratorExpression.CropWindow?,
    )

    /** Geometry belongs to the displayed candidate even before selection. */
    fun cropSlotGeometry(contentId: String): CropSlotGeometry? {
        val candidate = collageChoices.displayed ?: return null
        val placement = candidate.expression.placements
            .firstOrNull { it.contentId == contentId } ?: return null
        val aspect = if (placement.height > 0) {
            placement.width.toFloat() / placement.height.toFloat()
        } else {
            return null
        }
        return CropSlotGeometry(aspect, cropKeyFor(candidate, contentId)?.let(cropBases::get) ?: placement.crop)
    }

    /**
     * Prepared producer invocation (T151 stage-5 `loadPhotos` over the shared
     * authoritative analysis cache): maps the current preview sources into
     * [dev.hryshyn.remanence.ui.collage.AllFamilyPhoto]s for
     * `AllFamilyCandidateProducer`, failing closed (`null`) when any analysis
     * is unavailable. Sealed V4 candidates come from the sibling
     * [buildAllFamilySealedCandidates], which seals through
     * `AllFamilyV4Adapter.seal` on the committed T154 schema this tree carries.
     */
    internal suspend fun loadAllFamilyPhotos(): List<dev.hryshyn.remanence.ui.collage.AllFamilyPhoto>? {
        val (sources, inputPhotos) = when (val preview = _generatorPreview.value) {
            is GeneratorPreviewState.Ready -> preview.sources to preview.expression.input.photos
            is GeneratorPreviewState.NotePending -> preview.sources to preview.input.photos
            else -> return null
        }
        val cropSource = collageCropSource ?: return null
        // The authoritative analysis decodes the retained full-res upright
        // original bytes, so its dimensions are the GeneratorInput upright
        // dims, NOT the reduced preview dims (`previewWidthPx/HeightPx`). Match
        // by content id (both lists come from the same loaded sources) and fail
        // closed if a source has no upright photo; never feed preview dims to
        // the producer/matrix identity guard, and never resize to bypass it.
        val uprightByContentId = inputPhotos.associateBy { it.contentId }
        val descriptors = sources.map { source ->
            val upright = uprightByContentId[source.contentId] ?: return null
            dev.hryshyn.remanence.ui.collage.PhotoDescriptor(
                contentId = source.contentId,
                widthPx = upright.widthPx,
                heightPx = upright.heightPx,
                hasProminentFace = false,
            )
        }
        return dev.hryshyn.remanence.ui.collage.AllFamilyCandidateProducer.loadPhotos(
            descriptors,
            cropSource.analysisSource(),
        )?.map { photo ->
            // The frozen g22 gate reads the SAME prominent-face rule the
            // matrix uses (`w*h > .003`), derived from the cached analysis.
            photo.copy(
                hasProminentFace = photo.analysis?.faces
                    ?.any { it.box.width * it.box.height > 0.003 } == true,
            )
        }
    }

    /**
     * T150 integration: build the **all-family sealed V4** candidates — the
     * T151 stage-7 producer on the real Android crop matrix (shared analysis
     * cache, one detector run per photo) sealed by
     * [AllFamilyV4Adapter.seal] on the committed T154 schema.
     *
     * Returns `null` when the authoritative seam cannot produce a sealed
     * candidate (no analysis, no bundled style for a grammar, adapter
     * rejection), so the caller keeps the V3 provider path — a failed seal is
     * never replaced by a fake or partial candidate.
     */
    internal suspend fun buildAllFamilySealedCandidates(
        textMeasurer: dev.hryshyn.remanence.ui.collage.CollageTextMeasurer? = null,
    ): List<CollageCandidate>? {
        // Cheap non-suspending gates first: never touch the (possibly gated)
        // identity seam unless the authoritative analysis is available.
        val input = collageChooserInput() ?: return null
        val photos = loadAllFamilyPhotos() ?: return null
        val sender = identityProvider() ?: return null
        // T186: publish-side metadata requires the handle; handleless
        // accounts never reach here (Create entry gate), this is defense in
        // depth that fails closed without a placeholder.
        val senderHandle = sender.handle ?: return null
        val artwork = _musicArtwork.value
        val familyInput = dev.hryshyn.remanence.ui.collage.AllFamilyInput(
            id = input.ownerId, sender = senderHandle, note = input.note,
            music = input.music != null, artwork = artwork != null, photos = photos,
        )
        // Production supplies the host's real font measurer. Capture its metrics
        // on the UI thread before moving matching/solver work off-main.
        val measure = textMeasurer?.let { text ->
            AllFamilyCandidateProducer.prepareTextMeasurer(familyInput,
                dev.hryshyn.remanence.ui.collage.FrozenGrammarPlans.TextMeasurer { note, width, size, line, font ->
                    val layout = text.layout(dev.hryshyn.remanence.ui.collage.CollageRenderer.TextMeasureRequest(
                        text = note, family = font, size = size.toInt(), weight = 400, italic = false,
                        line = line.toInt(), maxWidth = kotlin.math.floor(width).toInt(), maxHeight = Int.MAX_VALUE,
                    ))
                    dev.hryshyn.remanence.ui.collage.FrozenGrammarPlans.TextMetric(layout.size.height.toDouble(), layout.lineCount)
                })
        } ?: dev.hryshyn.remanence.ui.collage.FrozenGrammarPlans.LINE_HEIGHT_STUB
        val outcome = withContext(ioDispatcher) {
            AllFamilyCandidateProducer(AndroidCollageCropMatrixSource(collageCropPolicy), measure, collageCropPolicy).produce(familyInput)
        }
        if (outcome !is dev.hryshyn.remanence.ui.collage.AllFamilyOutcome.Candidates) {
            return null
        }
        val sealed = ArrayList<CollageCandidate>(outcome.candidates.size)
        for (candidate in outcome.candidates) {
          for (style in AllFamilyV4Adapter.stylesForGrammar(candidate.grammarId)) {
            val sealedOutcome = AllFamilyV4Adapter.seal(candidate, input, senderHandle, style, artwork)
            if (sealedOutcome !is AllFamilyV4Adapter.Outcome.Sealed) continue
            sealed += CollageCandidate(
                candidateId = GeneratorBer1Provider.candidateIdFor(sealedOutcome.expression),
                styleId = style.id,
                branchId = candidate.branchId,
                expression = sealedOutcome.expression,
            )
          }
        }
        if (dev.hryshyn.remanence.BuildConfig.DEBUG) {
            android.util.Log.d("CollagePreparation", "allFamily=${outcome.candidates.size} sealedStyles=${sealed.size} omissions=" +
                outcome.omissions.groupingBy { it.kind }.eachCount())
        }
        return sealed.ifEmpty { null }
    }

    /** Opens the crop stage for one photo of the DISPLAYED candidate. */
    fun openCrop(contentId: String): Boolean {
        if (_step.value != Step.COMPOSE) return false
        val candidate = collageChoices.displayed
        if (candidate == null) {
            failGuard("crop needs a displayed candidate")
            return false
        }
        val key = cropKeyFor(candidate, contentId)
        val placement = key?.let { candidate.expression.placements.getOrNull(it.slot) }
        val initialCrop = placement?.crop
        if (key == null || initialCrop == null) {
            failGuard("crop needs a current preview photo")
            return false
        }
        if (_cropSlot.value == contentId && cropSlotCandidateId == candidate.candidateId) return true
        closeCrop()
        cropBases = cropBases + (key to (cropBases[key] ?: initialCrop))
        cropEntryTransform = _cropTransforms.value[key]
        _cropSlot.value = contentId
        cropSlotCandidateId = candidate.candidateId
        _flowError.value = null
        return true
    }

    /** Moves the draft frame within the original photo; never changes sealed geometry. */
    fun updateCrop(zoom: Float, panX: Float, panY: Float) {
        val slot = _cropSlot.value ?: return
        val candidate = collageChoices.displayed?.takeIf { it.candidateId == cropSlotCandidateId } ?: return
        val key = cropKeyFor(candidate, slot) ?: return
        val base = cropBases[key]?.let {
            dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.normalizedCrop(it)
        } ?: return
        val clamped = CropEditorGeometry.clamp(base,
            dev.hryshyn.remanence.ui.collage.CropTransform(zoom.toDouble(), panX.toDouble(), panY.toDouble())) ?: return
        _cropTransforms.value = _cropTransforms.value + (key to clamped)
        _flowError.value = null
        refreshCropDisplay()
    }

    /** Resets the draft to the original solver window. Done is still required. */
    fun resetCrop() {
        val slot = _cropSlot.value ?: return
        val candidate = collageChoices.displayed?.takeIf { it.candidateId == cropSlotCandidateId }
        val key = candidate?.let { cropKeyFor(it, slot) }
        if (key != null) {
            _cropTransforms.value = _cropTransforms.value +
                (key to dev.hryshyn.remanence.ui.collage.CropTransform(1.0, 0.0, 0.0))
        }
        _flowError.value = null
        refreshCropDisplay()
    }

    /** Cancel restores the last saved crop; only Done commits a draft. */
    fun closeCrop(): Boolean {
        if (_cropSlot.value == null) return false
        restoreCropDraft()
        _cropSlot.value = null
        cropSlotCandidateId = null
        cropEntryTransform = null
        _flowError.value = null
        refreshCropDisplay()
        return true
    }

    /** Saves this photo's frame without turning browsing into a selection. */
    suspend fun applyCropAndClose(): Boolean {
        val slot = _cropSlot.value ?: return false
        val candidate = collageChoices.displayed?.takeIf { it.candidateId == cropSlotCandidateId } ?: return false
        val key = cropKeyFor(candidate, slot) ?: return false
        val transform = _cropTransforms.value[key]
        if (transform == null) {
            closeCrop()
            return true
        }
        // Prove all live text/music inputs BEFORE replacing any candidate.
        val priorProof = drawableInputs[candidate.candidateId]
        if (priorProof == null || priorProof != prepareInputs()) {
            failGuard("candidate is not drawable")
            return false
        }
        val placement = candidate.expression.placements[key.slot]
        val base = cropBases[key]?.let {
            dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection.normalizedCrop(it)
        } ?: return false
        val requested = CropEditorGeometry.window(base, transform) ?: return false
        // The editor already preserves the slot aspect and clamps the frame
        // to the full original. Quantize this exact user choice, without
        // re-running the automatic face/contour/protection-field classifier.
        // Structural source bounds and the expression/publication guards
        // below still apply; subjective composition is the sender's choice.
        val quantized = requested.toCropWindow()
        val placements = candidate.expression.placements.toMutableList().also {
            it[key.slot] = placement.copy(crop = quantized)
        }
        val corrected = candidate.expression.copy(placements = placements)
        if (dev.hryshyn.remanence.core.model.GeneratorExpression.validateResolved(corrected)
            !is dev.hryshyn.remanence.core.model.GeneratorExpression.InputValidation.Valid
        ) {
            failGuard("corrected collage is invalid")
            return false
        }
        val index = collageChoices.candidates.indexOfFirst { it.candidateId == candidate.candidateId }
        if (index < 0) {
            failGuard("crop needs a displayed candidate")
            return false
        }
        val correctedId = dev.hryshyn.remanence.core.model.GeneratorBer1Provider
            .candidateIdFor(corrected)
        val replacement = candidate.copy(candidateId = correctedId, expression = corrected)
        val wasSelected = collageChoices.selected?.candidateId == candidate.candidateId
        val updatedFrozen = if (wasSelected) buildFrozenSelection(
            candidate = replacement, currentInput = collageChooserInput(), capsuleId = _capsuleId,
        ) ?: run {
            failGuard("corrected collage cannot freeze")
            return false
        } else null
        if (!collageChoices.replaceCandidate(index, replacement)) {
            failGuard("crop needs a displayed candidate")
            return false
        }
        // Only crops changed, so carry over the unchanged text layout proof.
        drawableInputs[correctedId] = priorProof
        if (wasSelected) {
            frozenCollageSelection = updatedFrozen
            frozenDrawableInputs = priorProof
        }
        fun migrated(key: dev.hryshyn.remanence.ui.collage.CorrectionKey) =
            if (keyMatchesCandidate(key, candidate)) key.copy(expressionIdentity = correctedId) else key
        cropBases = cropBases.mapKeys { migrated(it.key) }
        _cropTransforms.value = _cropTransforms.value.mapKeys { migrated(it.key) }
        _cropSlot.value = null
        cropSlotCandidateId = null
        cropEntryTransform = null
        _flowError.value = null
        refreshCropDisplay()
        // Successful user Done can update an already selected expression
        // without another freeze call. Export that committed final snapshot;
        // cancellation/rejection never reaches here, and no-op Done dedupes.
        if (wasSelected) runCatching { reportTelemetrySelection() }
        return true
    }

    private fun clearCropState() {
        _cropSlot.value = null
        cropSlotCandidateId = null
        cropEntryTransform = null
        cropBases = emptyMap()
        _cropTransforms.value = emptyMap()
        _cropCorrections.value = emptyMap()
    }

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
    private var frontDeliveryJob: Job? = null
    private var uploadRetryJob: Job? = null
    private val _uploadRetrying = MutableStateFlow(false)
    val uploadRetrying: StateFlow<Boolean> = _uploadRetrying.asStateFlow()

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

    /**
     * Sender collage chooser state. Candidates are built by the collage host;
     * only an explicitly frozen and publish-gate-accepted candidate can seal.
     */
    val collageChoices = CollageChoiceSession().also { session ->
        session.onTelemetryInvalidated = { telemetrySession?.reset() }
        session.onReadinessInvalidated = ::clearDrawableProofs
        session.beginDraft(_capsuleId)
        session.onCandidatesReplaced = {
            telemetrySession?.reset()
            frozenCollageSelection = null
            frozenDrawableInputs = null
            clearDrawableProofs()
            clearCropState()
        }
    }

    /**
     * Explicitly frozen collage choice (T001 pre-publish foundation). Set
     * only by [freezeCollageSelection] after full revalidation; cleared on
     * every photo/note/track edit, on ownership resets, and whenever a fresh
     * candidate set is built. Never consulted by publish (still fail-closed).
     */
    var frozenCollageSelection: CollageFrozenSelection? by mutableStateOf(null)
        private set

    /**
     * Freezes the currently selected collage candidate against the chooser
     * input (current preview input plus the mapped text-mode music ref).
     * Returns false (leaving any previously valid frozen selection untouched)
     * when nothing is selected, the preview holds no current input, or
     * revalidation fails. Publish revalidates this value against preread
     * originals and the pre-suspend track snapshot.
     */
    fun freezeCollageSelection(): Boolean {
        val frozen = buildFrozenSelection(
            candidate = collageChoices.selected,
            currentInput = collageChooserInput(),
            capsuleId = _capsuleId,
        ) ?: return false
        // Drawable gate: freeze only a positively-Ready candidate under the
        // current key; a stale or unknown proof leaves prior state untouched.
        if (!drawableMatches(frozen.candidateId)) {
            failGuard("candidate is not drawable")
            return false
        }
        frozenCollageSelection = frozen
        frozenDrawableInputs = prepareInputs()
        runCatching { reportTelemetrySelection() } // optional network/collection never gates freeze/Send
        return true
    }

    /** Real nonzero ready-canvas draw only, never the eager hidden prepare callback. */
    internal fun onTelemetryShown(index: Int) {
        val admission = telemetryCoordinator?.admission() ?: return
        val consent = admission.consent
        if (!consent.design && !consent.crops) return
        runCatching {
            val candidates = collageChoices.candidates
            val displayed = candidates.getOrNull(index) ?: return
            val choices = candidates.map {
                dev.hryshyn.remanence.telemetry.TelemetryChoice.normalized(it.expression.grammarId, it.styleId) ?: return
            }
            val originals = if (consent.crops) candidates.map { candidate -> candidate.expression.placements.map { placement ->
                val base = cropKeyFor(candidate, placement.contentId)?.let(cropBases::get)
                // Already committed before this consent/cohort began: no retrospective export.
                if (base != null && base != placement.crop) null
                else placement.crop?.let(dev.hryshyn.remanence.telemetry.TelemetryBox::from)
            } } else emptyList()
            val configuration = if (consent.design)
                dev.hryshyn.remanence.telemetry.TelemetryConfig.from(displayed.expression.input)
                else dev.hryshyn.remanence.telemetry.TelemetryConfig(0, "none", false, false)
            telemetrySession?.shown(admission, collageChoices.buildGeneration, index, choices, configuration, originals)
        }
    }

    private fun reportTelemetrySelection() {
        val admission = telemetryCoordinator?.admission() ?: return
        val consent = admission.consent
        if (!consent.design && !consent.crops) return
        val index = collageChoices.selectedIndex ?: return
        val candidate = collageChoices.selected ?: return
        val photos = candidate.expression.input.photos.associateBy { it.contentId }
        val crops = if (consent.crops) candidate.expression.placements.mapIndexedNotNull { slot, placement ->
            val finalCrop = placement.crop ?: return@mapIndexedNotNull null
            val photo = photos[placement.contentId] ?: return@mapIndexedNotNull null
            val analysis = collageCropSource?.cachedAnalysisFor(photo.contentId, photo.widthPx, photo.heightPx)
            val compact = dev.hryshyn.remanence.telemetry.CompactTelemetryAnalysis.from(analysis, photo.widthPx, photo.heightPx)
                ?: return@mapIndexedNotNull null
            dev.hryshyn.remanence.telemetry.TelemetryCropInput(slot,
                dev.hryshyn.remanence.telemetry.TelemetryBox.from(finalCrop), compact.aspect, compact.faces, compact.focus)
        } else emptyList()
        telemetrySession?.selected(admission, collageChoices.buildGeneration, index, crops)
    }

    private fun currentPreviewInput(): GeneratorExpression.GeneratorInput? =
        when (val preview = _generatorPreview.value) {
            is GeneratorPreviewState.Ready -> preview.expression.input
            is GeneratorPreviewState.NotePending -> preview.input
            else -> null
        }

    /**
     * Chooser music: the current mapped text-mode snapshot, or null without
     * a valid selection. An invalid hit maps to null here (photo-only
     * variants); publish re-maps strictly and fails closed on it, so nothing
     * can seal a bad track silently.
     */
    internal val collageChooserTrack: CapsuleTrackSnapshotV1?
        get() = collageChooserTrackFor(_musicSelection.value)

    /** [collageChooserTrack] for an explicitly observed [musicSelection] value. */
    internal fun collageChooserTrackFor(
        hit: dev.hryshyn.remanence.core.data.network.MusicTrackHit?,
    ): CapsuleTrackSnapshotV1? = hit?.let {
        runCatching { dev.hryshyn.remanence.create.mapMusicSelectionToSnapshot(it) }.getOrNull()
    }

    /**
     * Chooser input: the current preview input plus the mapped text-mode
     * music ref (`artworkId = null`). Null when no current preview input.
     * The content step builds displayed candidates from exactly this object
     * and [freezeCollageSelection] validates against it, so a selected track
     * always describes the same capsule as the frozen sheet.
     */
    internal fun collageChooserInput(): GeneratorExpression.GeneratorInput? =
        collageChooserInputFor(_musicSelection.value)

    /** [collageChooserInput] for an explicitly observed [musicSelection] value. */
    internal fun collageChooserInputFor(
        hit: dev.hryshyn.remanence.core.data.network.MusicTrackHit?,
    ): GeneratorExpression.GeneratorInput? {
        val base = currentPreviewInput() ?: return null
        val track = collageChooserTrackFor(hit) ?: return base
        return base.copy(music = GeneratorExpression.MusicRef(track.trackId.toString(), null))
    }

    /** Stage1-C CONTENT preview: pure planner over loaded, bounded sources. */
    private val generatorPreviewCoordinator = GeneratorPreviewCoordinator(generatorPreviewLoader)
    private val _generatorPreview = MutableStateFlow<GeneratorPreviewState>(GeneratorPreviewState.Idle)
    val generatorPreview: StateFlow<GeneratorPreviewState> = _generatorPreview.asStateFlow()
    private var previewJob: Job? = null
    private var previewGeneration: Long = 0L

    /**
     * Create-session retained ORIGINAL bytes for the collage path (A4B4
     * holder). Empty unless the retain sink is installed; cleared (zeroized)
     * on every ownership reset. Single-confined to the preview coroutine
     * context documented on the holder.
     */
    private val retainedOriginals = RetainedOriginalHolder()

    /**
     * Production crop source, built on preview Ready when the factory is
     * present; null otherwise. Closed and nulled on every ownership reset
     * and on teardown.
     */
    var collageCropSource: AndroidCollageCropSource? = null
        private set

    /**
     * Photo set the crop source was built for. Cache-hit refreshes recompute
     * the same Ready state without reloading; rebuilding the source for them
     * would leak the previous instance, so exactly one source exists per
     * photo set. Cleared with the rest of the collage state.
     */
    private var cropSourceBuiltIds: List<String>? = null

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

    /** Exact explicitly chosen V5 selection accepted for the active bridge session. */
    private var generatorFrozenSelection: CollageFrozenSelection? = null

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
        /**
         * T186 M12e-boundary: null for a frozen PENDING_V2 route (no
         * handle to confirm — the boundary branch consumes the route
         * instead). The v1 sealed path requires non-null (fail-closed
         * below, plus the startPublishing guard).
         */
        val recipient: ResolvedHandleSnapshot?,
        val noteText: String?,
        val frontFingerprintId: String,
        val photoIds: List<String>,
        val trackSnapshot: CapsuleTrackSnapshotV1?,
        /**
         * T186 M12e-gap3: immutable route + ORIGINAL lease captured at
         * publication entry (startPublishing, before ANY identity or
         * staging await). The boundary branch consumes these — never a
         * live store re-read after awaits — and the runner pins the
         * lease instead of re-pinning a fresh one, so a same-owner
         * replacement cannot slip a new incarnation in.
         */
        val pendingRoute: dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity.Pending? = null,
        val pendingLease: dev.hryshyn.remanence.core.data.network.SessionRequestLease? = null,
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
        cancelDeliveryAndRetry()
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
        collageChoices.beginDraft(_capsuleId)
        sessionOwner = nextOwner
        // C2: session-sync for the bridge — the epoch is retained for the
        // context and the bridge is resolved per session owner (a null
        // owner or null provider fails publishing closed at publish).
        generatorSessionEpoch = epoch
        generatorBridge = nextOwner?.let { owner -> generatorBridgeProvider?.invoke(owner) }
        resetGeneratorPreview()
        _step.value = Step.RECIPIENT_LOOKUP
        // FIX-M1-ONDEVICE-01: pending and confirmed recipient material both die.
        recipientFlow.clearTransientMaterial()
        pendingPickerResolution = null
        pickerVm.reset()
        photoSelection.clear()
        _pickedPhotoIds.value = emptyList()
        clearPickedThumbnails()
        noteEditor.reset()
        // S2b-sender: a picker selection belongs to exactly one
        // session; a new epoch/owner must never inherit it (placed after
        // the same-epoch early return above, so rotation keeps it).
        // Photo/note edits intentionally do NOT clear it: music is
        // independent of photos/note, and clearing on keystrokes would
        // destroy the selection with zero safety benefit.
        _musicSelection.value = null
        startMusicArtworkResolution(null)
        clearCropState()
        frontAttempt.reset()
        frontFingerprintId = null
        _flowError.value = null
        _publishError.value = null
        _uploadStatus.value = CreateUploadStatus.NotStarted
        _revokeStatus.value = CapsuleRevokeStatus.Idle
        observeCurrentOutbox(sessionOwner, _capsuleId, createSessionGeneration)
        refreshSelfRecipientAccount()
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
        // A pending-v2 attempt has no v1 outbox row. A late/null Room
        // projection must not erase its finalizer outcome or confirmed code.
        if (pendingFinalizeAttempt == (owner to capsuleId)) return
        val localQueueFailure = _uploadStatus.value == CreateUploadStatus.RetryableFailure("QUEUE_FAILED")
        // ENCRYPTED proves durable recovery material, not scheduler admission.
        // Room may deliver this older staging projection after enqueue refused.
        // Keep the local failure until explicit retry or actual worker progress.
        if (localQueueFailure && (status == null || status.state == OutboxCapsuleState.PREPARING ||
                status.state == OutboxCapsuleState.ENCRYPTED)) return
        // Clearing our own parking marker is not scheduler/worker admission.
        if (localQueueFailure && finalizingQueueRefused &&
            status?.state == OutboxCapsuleState.FINALIZING && status.lastErrorCode == null) return
        val mapped = when (status?.state) {
            null -> CreateUploadStatus.NotStarted
            OutboxCapsuleState.PREPARING,
            OutboxCapsuleState.ENCRYPTED,
            OutboxCapsuleState.UPLOADING,
            -> CreateUploadStatus.Pending(status.state)
            OutboxCapsuleState.FINALIZING -> when (status.lastErrorCode) {
                "SEND_LIMIT_REACHED" -> CreateUploadStatus.PublicationIssue(PublicationIssueKind.LIMIT, true)
                "SEND_ACCESS_UNAVAILABLE" -> CreateUploadStatus.PublicationIssue(PublicationIssueKind.ACCESS_UNAVAILABLE, true)
                "FINALIZE_UNCONFIRMED" -> CreateUploadStatus.PublicationIssue(PublicationIssueKind.UNKNOWN, true)
                else -> CreateUploadStatus.Pending(status.state)
            }
            OutboxCapsuleState.RETRYABLE_FAILURE ->
                CreateUploadStatus.RetryableFailure(status.lastErrorCode)
            OutboxCapsuleState.TERMINAL_FAILURE ->
                CreateUploadStatus.TerminalFailure(status.lastErrorCode)
            OutboxCapsuleState.PUBLISHED -> CreateUploadStatus.Published
        }
        if (!outboxObservationStillCurrent(generation, owner, capsuleId)) return
        if (localQueueFailure) _publishError.value = null
        if (localQueueFailure) finalizingQueueRefused = false
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

    private fun CreateUploadStatus.hasOutboxProgress(): Boolean = when (this) {
        CreateUploadStatus.NotStarted -> false
        is CreateUploadStatus.Pending -> state != OutboxCapsuleState.PREPARING && state != OutboxCapsuleState.ENCRYPTED
        is CreateUploadStatus.RetryableFailure -> errorCode != "QUEUE_FAILED"
        is CreateUploadStatus.TerminalFailure, CreateUploadStatus.Published,
        is CreateUploadStatus.PublicationIssue -> true
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

    /** Shared Activity/Compose recent selection entrypoint; still a fresh lookup. */
    fun lookupRecentRecipient(
        suggestion: dev.hryshyn.remanence.core.data.network.RecentRecipient,
        ownerUserId: String?,
        epoch: Long,
    ) {
        // A callback captured by an obsolete Activity/session cannot replace
        // the current lookup's expectation, even for the same handle.
        if (_step.value != Step.RECIPIENT_LOOKUP || ownerUserId == null ||
            sessionOwner?.toRestString() != ownerUserId || begunEpoch != epoch) return
        onHandleChange(suggestion.handle)
        pickerVm.lookupRecent(suggestion.userId)
    }

    fun onHandleChange(value: String) {
        selfLookupJob?.cancel()
        selfLookupJob = null
        selfRecipientTargetUserId = null
        _selfRecipientLookupRejected.value = false
        pickerVm.onHandleChange(value)
    }

    fun lookupRecipient() {
        selfLookupJob?.cancel()
        selfLookupJob = null
        selfRecipientTargetUserId = null
        _selfRecipientLookupRejected.value = false
        pickerVm.lookup()
    }

    private suspend fun readSelfRecipientAccount(): SelfRecipientAccount? {
        val account = withContext(ioDispatcher) { selfRecipientAccountProvider() } ?: return null
        return runCatching {
            SelfRecipientAccount(UserId.parseRest(account.userId).toRestString(),
                dev.hryshyn.remanence.core.model.NormalizedHandle.parse(account.handle).value)
        }.getOrNull()
    }

    private suspend fun readSelfLookupOwner(): String? = try { selfLookupOwnerProvider() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun refreshSelfRecipientAccount() {
        clearSelfRecipientState()
        val expectedOwner = sessionOwner?.toRestString() ?: return
        val generation = createSessionGeneration
        selfAccountJob = viewModelScope.launch {
            val account = try { readSelfRecipientAccount() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val liveOwner = readSelfLookupOwner()
            if (generation == createSessionGeneration && sessionOwner?.toRestString() == expectedOwner &&
                account?.userId == expectedOwner && liveOwner == expectedOwner) {
                _selfRecipientAccount.value = account
            }
        }
    }

    /** Same authenticated directory flow; a successful lookup still needs explicit confirmation. */
    fun lookupPostcardForMyself() {
        if (_step.value != Step.RECIPIENT_LOOKUP || _selfRecipientAccount.value == null ||
            pickerVm.state.value is RecipientLookupUiState.LookingUp || selfLookupJob?.isActive == true) return
        val expectedOwner = sessionOwner?.toRestString() ?: return
        val generation = createSessionGeneration
        val typedAtStart = pickerVm.handle.value
        _selfRecipientLookupRejected.value = false
        selfLookupJob = viewModelScope.launch {
            val account = try { readSelfRecipientAccount() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val liveOwner = readSelfLookupOwner()
            if (generation != createSessionGeneration || sessionOwner?.toRestString() != expectedOwner ||
                _step.value != Step.RECIPIENT_LOOKUP || pickerVm.handle.value != typedAtStart) return@launch
            if (account?.userId != expectedOwner || liveOwner != expectedOwner) {
                _selfRecipientAccount.value = null
                _selfRecipientLookupRejected.value = true
                return@launch
            }
            _selfRecipientAccount.value = account
            selfRecipientTargetUserId = expectedOwner
            // Not local key construction or automatic recipient binding.
            pickerVm.onHandleChange(account.handle)
            pickerVm.lookup()
        }
    }

    private fun clearSelfRecipientState() {
        selfAccountJob?.cancel(); selfAccountJob = null
        selfLookupJob?.cancel(); selfLookupJob = null
        selfRecipientTargetUserId = null
        _selfRecipientAccount.value = null
        _selfRecipientLookupRejected.value = false
    }

    /** Only the currently admitted picker result can advance its Compose effect. */
    fun onPickerResolved(resolution: RecipientLookupUiState.Resolved) {
        if (pickerVm.state.value !== resolution) return
        onResolved(resolution.snapshot)
        if (pendingRecipient.value === resolution.snapshot) pendingPickerResolution = resolution
    }

    fun onResolved(snapshot: ResolvedHandleSnapshot) {
        if (!requireStep(Step.RECIPIENT_LOOKUP, "recipient resolution")) return
        pendingPickerResolution = null
        selfRecipientTargetUserId?.let { expectedOwner ->
            val live = pickerVm.state.value as? RecipientLookupUiState.Resolved
            if (snapshot.userId.toRestString() != expectedOwner ||
                sessionOwner?.toRestString() != expectedOwner || live?.snapshot !== snapshot) {
                selfRecipientTargetUserId = null
                pickerVm.reset()
                _selfRecipientLookupRejected.value = true
                return
            }
        }
        recipientFlow.onResolved(snapshot)
        _step.value = Step.RECIPIENT_CONFIRM
    }

    fun confirmRecipient() {
        // A second tap queued before confirmation unmounts is not a new
        // recipient operation. Keep the immutable confirmed snapshot.
        if (_step.value == Step.FRONT && confirmedRecipient.value != null) return
        if (!requireStep(Step.RECIPIENT_CONFIRM, "recipient confirmation")) return
        pendingPickerResolution?.let { resolution ->
            if (pickerVm.state.value !== resolution) {
                // A synchronous logout/input boundary may precede the next
                // Activity frame. Do not bind the now-retired pending result.
                restartLookup()
                return
            }
        }
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
        pendingPickerResolution = null
        selfRecipientTargetUserId = null
        _selfRecipientLookupRejected.value = false
        // The lookup screen observes Resolved and advances to confirmation.
        // Invalidate that result (and any late lookup) BEFORE mounting it again,
        // but retain the handle so changing recipient is not a fresh form.
        pickerVm.onHandleChange(pickerVm.handle.value)
        clearGuardError()
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
        if (frontAttempt.phase != dev.hryshyn.remanence.capture.CaptureAttemptPhase.Capturing) {
            jpegBytes.fill(0)
            return
        }
        if (!requireStep(Step.FRONT, "front delivery")) {
            jpegBytes.fill(0)
            return
        }
        val generation = deliveryGeneration
        frontDeliveryJob?.cancel()
        frontDeliveryJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
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
        // PickMultipleVisualMedia reports dismissal/cancel as an empty list,
        // not a deliberate "remove all" edit. Tile toggles own that action.
        if (ids.isEmpty()) return
        // A deliberate new picker result may retry previously failed reads.
        _pickedThumbnails.value = _pickedThumbnails.value.filterValues { it != null }
        // C2: a fresh picker result is a photo edit — it revokes any bound
        // bridge session on its exact context before the selection changes.
        dropGeneratorBound { bridge, context, sessionId ->
            bridge.onPhotoEdit(context, sessionId)
        }
        // Fresh selection supersedes all retained originals: clear BEFORE the
        // toggles below, whose inline refreshes may already retain anew.
        clearCollageDiagnostics()
        clearCropState()
        photoSelection.clear()
        _pickedPhotoIds.value = ids.distinct().take(PhotoSelectionState.MAX_PHOTOS)
        _pickedPhotoIds.value.forEach { id -> photoSelection.toggle(id) }
        refreshPickedThumbnails()
        refreshGeneratorPreview()
    }

    fun togglePickedPhoto(id: String) {
        if (!requireStep(Step.CONTENT, "photo selection")) return
        if (id !in _pickedPhotoIds.value) return
        // PhotoSelection's edit callback invalidates the frozen choice and
        // bridge proof. Unchecked photos remain thumbnails, never sealed input.
        photoSelection.toggle(id)
    }

    /**
     * CONTENT selected-tiles thumbnails, keyed by picked URI string in
     * selection order. Loaded off-main on [ioDispatcher] through
     * [pickedThumbnailLoader]; only missing keys load, a newer pick cancels
     * the in-flight job (epoch guard), and load failures publish a null
     * sentinel — the tile keeps its selection check, the valid choice is
     * retained. Empty without a loader: tiles render check-only.
     */
    private val _pickedThumbnails =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap?>>(emptyMap())
    val pickedThumbnails: kotlinx.coroutines.flow.StateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap?>> =
        _pickedThumbnails.asStateFlow()

    private var pickedThumbnailEpoch = 0
    private var pickedThumbnailJob: kotlinx.coroutines.Job? = null

    private fun clearPickedThumbnails() {
        pickedThumbnailEpoch++
        pickedThumbnailJob?.cancel()
        pickedThumbnailJob = null
        _pickedThumbnails.value = emptyMap()
    }

    fun refreshPickedThumbnails() {
        val epoch = ++pickedThumbnailEpoch
        pickedThumbnailJob?.cancel()
        val loader = pickedThumbnailLoader ?: run {
            _pickedThumbnails.value = emptyMap()
            return
        }
        val wanted = (_pickedPhotoIds.value + photoSelection.selectedIds).distinct()
        pickedThumbnailJob = viewModelScope.launch(ioDispatcher) {
            val current = _pickedThumbnails.value.toMutableMap()
            current.keys.retainAll(wanted.toSet())
            if (epoch != pickedThumbnailEpoch) return@launch
            _pickedThumbnails.value = current.toMap()
            for (uri in wanted) {
                if (epoch != pickedThumbnailEpoch) return@launch
                if (current.containsKey(uri)) continue
                val bitmap = try {
                    loader(uri)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (epoch != pickedThumbnailEpoch) return@launch
                // Presence marks completion; null is a failed decode, not an
                // endless in-progress tile. A new picker inventory can retry.
                current[uri] = bitmap
                _pickedThumbnails.value = current.toMap()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Sealing: the ONE production path - publisher → ciphertext outbox.
    // ---------------------------------------------------------------------

    /**
     * T150 explicit Next: CONTENT form → COMPOSE browsing surface. Same
     * readiness guards as publishing (front, 3..5 photos, note, confirmed
     * recipient, owner); all inputs are VM state and survive the transition.
     * Off-step calls are refused with a visible error.
     */
    fun goToCompose(): Boolean {
        if (!requireStep(Step.CONTENT, "composition")) return false
        if (frontFingerprintId == null) {
            failGuard("front must be captured before composition")
            return false
        }
        if (!photoSelection.canProceed) {
            failGuard("3..5 photos required")
            return false
        }
        if (!noteEditor.canIncludeInCapsule) {
            failGuard("the note exceeds its byte limit")
            return false
        }
        if (confirmedRecipient.value == null) {
            // T186 M12e-gap1: a frozen PENDING_V2 route is an alternate
            // VALID recipient binding for composition (no handle, no
            // account, no recipient keys fabricated — the route itself
            // is the binding). v1 guards below are unchanged.
            val frozenComposed =
                if (dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()) {
                    sessionStore.pendingRoute.value
                } else {
                    null
                }
            if (frozenComposed !is dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity.Pending) {
                failGuard("a recipient must be confirmed before composition")
                return false
            }
        }
        if (sessionOwner == null) {
            failGuard("authenticated owner is unavailable; composition disabled")
            return false
        }
        clearGuardError()
        _step.value = Step.COMPOSE
        return true
    }

    /**
     * T150 explicit Back: COMPOSE → CONTENT with every input retained
     * (photos, note, music, frozen choice untouched). Silent no-op elsewhere.
     */
    fun backToContent(): Boolean {
        if (_step.value != Step.COMPOSE) return false
        _step.value = Step.CONTENT
        return true
    }

    /**
     * T186 M14a: FRONT → RECIPIENT_LOOKUP recipient return (explicit
     * user action only). Lets a cleared pending route be re-frozen
     * (or a handle chosen) without stranding the flow at FRONT; v1
     * confirmed handles are untouched (recipient survives, only the
     * step moves). Silent no-op elsewhere.
     */
    fun backToRecipient(): Boolean {
        if (_step.value != Step.FRONT) return false
        _step.value = Step.RECIPIENT_LOOKUP
        return true
    }

    fun backToFront(): Boolean {
        if (_step.value != Step.CONTENT) return false
        createSessionGeneration += 1
        deliveryGeneration += 1
        cancelDeliveryAndRetry()
        // FRONT baselines are immutable per capsule. A different postcard
        // starts a fresh draft identity, keeping its content and recipient;
        // never overwrite/delete the old baseline (an earlier publication
        // may still own it even after its UI job detached).
        cancelPublishingLocked()
        dropGeneratorBound { bridge, context, sessionId ->
            bridge.cancel(context, sessionId)
        }
        clearFrozenCollageSelection()
        outboxObservationJob?.cancel()
        outboxObservationJob = null
        _capsuleId = UUID.randomUUID().toString()
        // T186 M12e-cap: the fresh draft identity invalidates the frozen
        // pending route (R1 bound route.capsule_id to the old id) — a
        // rescan/back can never publish the old-UUID route. The sender
        // must freeze a new R1 route for the new capsule; no fallback,
        // no fake identity. The confirmed handle recipient survives.
        sessionStore.clearPendingRoute()
        collageChoices.beginDraft(_capsuleId)
        _publishError.value = null
        _uploadStatus.value = CreateUploadStatus.NotStarted
        observeCurrentOutbox(sessionOwner, _capsuleId, createSessionGeneration)
        frontFingerprintId = null
        frontAttempt.restartCapture()
        clearGuardError()
        _step.value = Step.FRONT
        return true
    }

    fun startPublishing() {
        if (pendingFinalizeAttempt == (sessionOwner to _capsuleId)) {
            if (_step.value != Step.PUBLISHED) {
                if (_uploadStatus.value !is CreateUploadStatus.PublicationIssue)
                    showPendingPublicationIssue(PublicationIssueKind.UNKNOWN)
                else _step.value = Step.UPLOAD_PENDING
            }
            return
        }
        if (!requireStep(Step.COMPOSE, "publishing")) return
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
        // T186 M12e-boundary: a frozen PENDING_V2 route is a second
        // legitimate binding (no handle to confirm). A frozen V1 route
        // without a confirmed handle still fails here — the resolved
        // handle must be confirmed first (existing path below).
        val frozenRouteForPublish =
            if (dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()) {
                sessionStore.pendingRoute.value
            } else {
                null
            }
        if (boundRecipient == null &&
            frozenRouteForPublish !is dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity.Pending
        ) {
            failGuard("a recipient must be confirmed before publishing")
            return
        }
        val owner = sessionOwner
        if (owner == null) {
            failGuard("authenticated owner is unavailable; publishing is disabled")
            return
        }
        clearGuardError()
        // Drawable gate: publish only a frozen candidate with matching
        // positive Ready evidence under the current key. Stale, unknown,
        // or flipped-to-Rejected proofs refuse here; the S5 identity gates
        // downstream are untouched.
        val frozenForPublish = frozenCollageSelection
        if (frozenForPublish == null ||
            frozenDrawableInputs == null ||
            frozenDrawableInputs != prepareInputs()
        ) {
            failGuard("frozen candidate is not drawable")
            return
        }
        // FIX-STATE-11: immutable inputs of THIS session, captured before any
        // suspend boundary.
        val trackSnapshot = _musicSelection.value?.let { hit ->
            try {
                dev.hryshyn.remanence.create.mapMusicSelectionToSnapshot(hit)
            } catch (_: IllegalArgumentException) {
                failGuard("selected track is invalid; publishing cancelled")
                return
            }
        }
        val pendingRouteForInputs = frozenRouteForPublish as?
            dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity.Pending
        // T186 M12e-gap3b: the ORIGINAL lease is entry state. When a
        // pending route is bound but its lease is unavailable here,
        // fail closed synchronously — never pass a null lease through
        // and recapture fresh after staging.
        val pendingLeaseForInputs =
            if (pendingBoundary == null || pendingRouteForInputs == null ||
                !dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()
            ) {
                null
            } else {
                try {
                    pendingBoundary.gate.capture()
                } catch (_: Exception) {
                    null
                }
            }
        if (pendingRouteForInputs != null && pendingBoundary != null &&
            dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()
        ) {
            if (pendingLeaseForInputs == null) {
                failGuard("authenticated owner changed; publishing cancelled")
                return
            }
            // Lease owner must be the session owner at entry (never a
            // foreign lease carried into this publication).
            if (pendingLeaseForInputs.ownerUserId != owner) {
                failGuard("authenticated owner changed; publishing cancelled")
                return
            }
        }
        val inputs = PublishInputs(
            capsuleId = capsuleId,
            owner = owner,
            recipient = boundRecipient,
            noteText = if (noteEditor.isEmpty) null else noteEditor.text,
            frontFingerprintId = requireNotNull(frontFingerprintId),
            photoIds = photoSelection.selectedIds.toList(),
            trackSnapshot = trackSnapshot,
            // T186 M12e-gap3: capture the immutable route + ORIGINAL
            // lease synchronously here (no awaits above since the
            // guard) so the boundary consumes entry-time state even
            // after long staging awaits.
            pendingRoute = pendingRouteForInputs,
            pendingLease = pendingLeaseForInputs,
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
        // Every invalidation path (photo/note edit, owner/epoch change,
        // retry, cancel, teardown) drops the frozen selection too.
        generatorFrozenSelection = null
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
        clearCollageDiagnostics()
        refreshGeneratorPreview()
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
        refreshGeneratorPreview()
    }

    /**
     * Stage1-C CONTENT preview refresh: cancels any in-flight load, then
     * recomputes the typed preview from the current selection + note. Empty
     * selection returns to [GeneratorPreviewState.Idle]; a stale async result
     * can never overwrite a newer one (monotonic generation).
     */
    private fun refreshGeneratorPreview() {
        // A source/note edit invalidates the built collage candidates too.
        collageChoices.clear()
        frozenCollageSelection = null
        frozenDrawableInputs = null
        clearDrawableProofs()
        // T157 §6: photo/note edits change the expression identity, so
        // per-photo corrections are dropped fail-closed (no cross-slot reuse).
        clearCropState()
        val ids = photoSelection.selectedIds
        previewJob?.cancel()
        previewJob = null
        val generation = ++previewGeneration
        // Retained-original sink for THIS preview generation only. A stale
        // async completion (older generation) cannot retain; the loader
        // additionally rechecks cancellation and never lets a throwing sink
        // break preview. The result is ignored: a retain rejection must not
        // break ordinary preview and yields no candidate.
        (generatorPreviewLoader as? OriginalRetainSinkHost)?.retainOriginal = { bytes, hash ->
            if (generation == previewGeneration) {
                retainedOriginals.retain(CapsulePhotoIdentity.contentIdFor(hash), bytes, hash)
            }
        }
        if (ids.isEmpty()) {
            _generatorPreview.value = GeneratorPreviewState.Idle
            generatorPreviewCoordinator.zeroize()
            return
        }
        if (ids.size !in 3..5) {
            _generatorPreview.value = GeneratorPreviewState.Rejected(listOf("select 3..5 photos"))
            return
        }
        _generatorPreview.value = GeneratorPreviewState.Loading
        val ownerId = sessionOwner?.toRestString()
        val epoch = generatorSessionEpoch ?: 0L
        val note = if (noteEditor.isEmpty) null else noteEditor.text
        previewJob = viewModelScope.launch {
            try {
            val result = generatorPreviewCoordinator.compute(ownerId, epoch, ids, note)
            if (generation == previewGeneration) {
                // A non-empty note previews as NotePending before any Ready;
                // the same retained originals and photo-id cache serve both.
                // Install the non-observable crop source before publishing the
                // observable preview state, so composition can never mount the
                // chooser once with the fail-closed source and miss the real one.
                if (result is GeneratorPreviewState.Ready || result is GeneratorPreviewState.NotePending) {
                    maybeBuildCropSource(ids)
                }
                _generatorPreview.value = result
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preparation is not publication. Keep the current form and
                // durable FRONT/capsule ID, with no automatic retry or raw
                // exception text. An obsolete failure cannot retire a newer
                // preview, source or retained-original owner.
                if (generation == previewGeneration) {
                    _generatorPreview.value = GeneratorPreviewState.Unavailable("preview preparation failed")
                }
            }
        }
    }

    /**
     * Builds the production crop source on a current Ready preview when the
     * factory is present. Never runs the provider and never populates
     * candidates; the content step mounts the exposed source into the
     * chooser host. Absent factory (or a null factory result) leaves it
     * absent and the chooser shows the typed notice.
     */
    private fun maybeBuildCropSource(photoIds: List<String>) {
        val factory = collageSourceFactory ?: return
        if (collageCropSource != null) return
        if (cropSourceBuiltIds == photoIds) return
        collageCropSource = factory.create(retainedOriginals)
        // Commit after construction: an ordinary factory failure must not
        // poison an explicit note/edit retry for the same selected photos.
        cropSourceBuiltIds = photoIds.toList()
    }

    /** Drops collage holder/source ownership (zeroized/closed). */
    private fun clearCollageDiagnostics() {
        (generatorPreviewLoader as? OriginalRetainSinkHost)?.retainOriginal = null
        collageCropSource?.close()
        collageCropSource = null
        cropSourceBuiltIds = null
        retainedOriginals.clear()
        // The preview cache is backed by these originals, not just picker
        // IDs. Reusing it after a photo revision would skip the retain sink.
        // Drop references only: the outgoing host may still draw its JPEGs.
        generatorPreviewCoordinator.reset()
    }

    /** Drops all preview ownership (sources + in-flight load) on any change. */
    private fun resetGeneratorPreview() {
        previewJob?.cancel()
        previewJob = null
        previewGeneration += 1
        _generatorPreview.value = GeneratorPreviewState.Idle
        generatorPreviewCoordinator.zeroize()
        collageChoices.clear()
        frozenCollageSelection = null
        frozenDrawableInputs = null
        clearDrawableProofs()
        clearCropState()
        clearCollageDiagnostics()
    }

    /** Legacy preview callback; V5 publish trusts only an explicit frozen collage. */
    fun onPreviewMeasured(@Suppress("UNUSED_PARAMETER") expression: GeneratorExpression.ResolvedExpression) = Unit

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
        // A prior accepted selection can never satisfy a new attempt.
        generatorFrozenSelection = null
        val sender = capturedSender
        // T186: generator attribution requires the handle (entry-gated;
        // fail-closed defense in depth, never a placeholder).
        val senderHandle = sender.handle
        if (senderHandle == null) {
            failPublishing("local handle is unavailable; handle setup required", generation)
            return false
        }
        val prereadOwner = GeneratorPrereadOwner()
        try {
            val prereadAvailable = withContext(ioDispatcher) {
                prereadGeneratorOriginals(inputs.photoIds, openPhotoSource, prereadOwner)
            }
            if (!prereadAvailable) {
                failPublishing("generator photo sources are unreadable; publishing cancelled", generation)
                return false
            }
            val preread = prereadOwner.originals()
            // Retry/re-begin must never orphan a previous binding: revoke any
            // leftover from this or an older generation on its exact context
            // before opening a new G3 session. A newer generation's binding (a
            // stale job racing a live session) is never touched.
            if (generatorBoundGeneration?.let { it <= generation } == true) {
                dropGeneratorBound { bridge, context, sessionId ->
                    bridge.cancel(context, sessionId)
                }
            }
            val input = GeneratorExpression.GeneratorInput(
                ownerId = inputs.owner.toRestString(),
                epoch = epoch,
                photos = preread.mapIndexed { index, original ->
                    val contentHash = sha256Hex(original.bytes)
                    GeneratorExpression.PhotoRef(
                        contentId = CapsulePhotoIdentity.contentIdFor(contentHash),
                        ordinal = index,
                        widthPx = original.widthPx,
                        heightPx = original.heightPx,
                        contentHash = contentHash,
                    )
                },
                note = inputs.noteText,
                music = frozenCollageSelection?.expression?.input?.music,
            )
            val selection = when (val accepted = acceptFrozenForPublish(
                frozen = frozenCollageSelection,
                prereadInput = input,
                trackSnapshot = inputs.trackSnapshot,
                capsuleId = inputs.capsuleId,
            )) {
                is FrozenPublishAcceptance.Accepted -> accepted.selection
                is FrozenPublishAcceptance.Refused -> {
                    failPublishing("the frozen collage was refused: ${accepted.reason}", generation)
                    return false
                }
            }
            val begun = bridge.begin(
                owner = inputs.owner,
                sessionEpoch = epoch,
                generationId = inputs.capsuleId,
                input = selection.expression.input,
                sender = GeneratorStaging.SenderSnapshot(sender.userId, senderHandle),
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
            generatorFrozenSelection = selection
            return true
        } finally {
            prereadOwner.wipe()
        }
    }

    /** One bound authored photo for the publish request (derived bytes/dims). */
    private data class BoundPhotoForPublish(val bytes: ByteArray, val widthPx: Int, val heightPx: Int)

    /** The frozen bound photos plus the verified session-frozen G2 selection. */
    private data class BoundPhotosForPublish(
        val photos: List<BoundPhotoForPublish>,
        val selection: CollageFrozenSelection,
    )

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
    /**
     * Reason token for a refused bind slot (diagnostics only — the user
     * copy stays unchanged). `null` means the call itself threw and was
     * swallowed above (never a typed refusal); otherwise the variant.
     * Every reason string in the bridge/binder/staging seam is a fixed
     * literal (audited: no ids, hashes, bytes, tokens or interpolated
     * values), and this token carries only the class — no private
     * content can pass.
     */
    private fun reportBindRefusal(
        ordinal: Int,
        result: GeneratorCreateBridge.SlotResult?,
        thrown: Exception?,
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "GeneratorBind",
                "bind slot refused: ordinal=$ordinal class=${bindSlotRefusalToken(result, thrown)}",
            )
        }
    }

    private suspend fun bindGeneratorPhotos(
        generation: Long,
        inputs: PublishInputs,
        bound: GeneratorBoundSession,
    ): BoundPhotosForPublish? {
        val bridge = bound.bridge
        val photos = ArrayList<BoundPhotoForPublish>(inputs.photoIds.size)
        for ((ordinal, pickerId) in inputs.photoIds.withIndex()) {
            var thrown: Exception? = null
            val result = try {
                bridge.bindSlot(bound.context, bound.sessionId, ordinal, openPhotoSource(pickerId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                thrown = e
                null
            }
            val slot = result as? GeneratorCreateBridge.SlotResult.Bound
            if (slot == null) {
                reportBindRefusal(ordinal, result, thrown)
                dropGeneratorBoundForGeneration(generation) { b, c, s -> b.cancel(c, s) }
                failPublishing("generator bind rejected slot $ordinal; publishing cancelled", generation)
                return null
            }
            photos += BoundPhotoForPublish(slot.normalized.bytes, slot.normalized.widthPx, slot.normalized.heightPx)
        }
        val frozen = try {
            bridge.freeze(bound.context, bound.sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val handoff = (frozen as? GeneratorCreateBridge.FreezeResult.Frozen)?.handoff
        if (handoff == null ||
            handoff.context != bound.context ||
            handoff.context.generationId != inputs.capsuleId ||
            handoff.inputHash != GeneratorExpression.canonicalHash(handoff.input) ||
            handoff.inputHash != generatorFrozenSelection?.inputHash
        ) {
            dropGeneratorBoundForGeneration(generation) { b, c, s -> b.cancel(c, s) }
            failPublishing("generator freeze rejected; publishing cancelled", generation)
            return null
        }
        // ADR-018 exact-descriptor publish gate: the session-frozen selection
        // was computed from the pre-read ORIGINALS (and, for a note, from the
        // host's measured fit); require the frozen handoff descriptors to be
        // byte-identical, then recompute candidateId + BEXPR01 projection from
        // the frozen expression and require exact equality. A mismatch (picker
        // content changed between pre-read and bind, dims diverged, stale
        // measurement, etc.) is a typed rejection — never a different layout.
        val selection = generatorFrozenSelection
        val exactInputs = selection != null && handoff.input == selection.expression.input
        val recomputed = if (selection == null) {
            null
        } else {
            val expression = selection.expression
            val candidateId = GeneratorBer1Provider.candidateIdFor(expression)
            val projectedHash = CapsulePhotoIdentity.projectedHash(
                expression = expression,
                candidateId = candidateId,
                capsuleId = CapsuleId(UUID.fromString(inputs.capsuleId)),
                contentIds = expression.input.photos.map { it.contentId },
            )
            candidateId to projectedHash
        }
        if (selection == null ||
            !exactInputs ||
            recomputed == null ||
            recomputed.first != selection.candidateId ||
            recomputed.second != selection.projectedHash
        ) {
            dropGeneratorBoundForGeneration(generation) { b, c, s -> b.cancel(c, s) }
            failPublishing(
                "generator projection changed since the session began; publishing cancelled",
                generation,
            )
            return null
        }
        return BoundPhotosForPublish(photos, selection)
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
        _pendingPublication.value = null
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
            // Provider/Room/WorkManager exception text is never presentation
            // copy; retain only a fixed diagnostic category in VM state.
            _publishError.value = "publishing failed"
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
        // [PublishInputs] before any suspend boundary (null for a frozen
        // pending route — the boundary branch below consumes the route
        // instead; fail-closed there). The sender identity
        // (user id, signing key, handle, owner) is the single snapshot
        // captured per publication ([capturedSender]). A self-send is a
        // valid publication because the user may confirm their own handle -
        // the request builder then receives EQUAL VALUES for sender and
        // recipient, not a default.
        // pending route is frozen, the v1 request below is unreachable
        // (its recipient would be null); the startPublishing guard plus
        // the branch above keep exactly one path live.
        val sender = capturedSender
        // T186: publishing requires the handle (entry-gated; fail-closed
        // defense in depth, never a placeholder).
        val senderHandle = sender.handle
        if (senderHandle == null) {
            failPublishing("local handle is unavailable; handle setup required", generation)
            return
        }
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
            val frozenSelection = boundPhotos.selection
            val photoJpegs: List<ByteArray> = boundPhotos.photos.map { it.bytes }
            val photoWidthsPx: List<Int> = boundPhotos.photos.map { it.widthPx }
            val photoHeightsPx: List<Int> = boundPhotos.photos.map { it.heightPx }
            ensureCurrent()
            // S2b-sender: use only the snapshot captured in PublishInputs
            // before the first suspension; never reread live picker state.
            val trackSnapshot = inputs.trackSnapshot
            // T186 M12e-boundary: frozen pending route → v2 sealed
            // publication at the normal boundary (authored inputs above:
            // bound photos/dims, frozen expression, note, fingerprint).
            // The existing v1 path below runs unchanged when no pending
            // route is bound. Early return still runs the staging/front
            // cleanups in the surrounding finally blocks.
            //
            // T186 M12e-gap3: the route AND the entry-time lease come
            // from [inputs] (captured at publication entry, before ANY
            // staging await) — never a live store re-read here.
            val pendingFrozen = inputs.pendingRoute
            if (pendingFrozen != null) {
                publishPendingAtBoundary(
                    generation, inputs, sender, senderHandle,
                    photoJpegs, photoWidthsPx, photoHeightsPx,
                    frozenSelection, trackSnapshot, frontForPublish, pendingFrozen,
                )
                return
            }
            // v1-only from here: the recipient is non-null by the
            // startPublishing guard (pending routes returned above).
            val snapshot = inputs.recipient ?: run {
                failPublishing("a recipient must be confirmed before publishing", generation)
                return
            }
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
                        senderHandleSnapshot = senderHandle,
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
                        // ADR-018: only the session-frozen, projection-verified
                        // selection is sealed; contentIds are authored order.
                        // S2b-sender: plus the validated track snapshot, if
                        // the picker selected one (null otherwise).
                        expression = CapsuleExpressionArtifact(
                            candidateId = frozenSelection.candidateId,
                            contentIds = frozenSelection.expression.input.photos.map { it.contentId },
                            expression = frozenSelection.expression,
                        ),
                        trackSnapshot = trackSnapshot,
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
                    // Ciphertext is already durably staged. Never send the
                    // user through sealing again (stage rejects replay).
                    if (!isPublishCurrent(generation)) return
                    // An already-running worker can make progress before a
                    // refused/lost scheduler ACK. Its state wins in either order.
                    if (_uploadStatus.value.hasOutboxProgress()) {
                        _step.value = if (_uploadStatus.value is CreateUploadStatus.Published)
                            Step.PUBLISHED else Step.UPLOAD_PENDING
                        return
                    }
                    _uploadStatus.value = CreateUploadStatus.RetryableFailure("QUEUE_FAILED")
                    failPublishing("upload could not be queued; retry available", generation, Step.UPLOAD_PENDING)
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

    /**
     * T186 M12e-boundary: v2 sealed publication at the normal publish
     * boundary from the already-authored inputs (bound photos/dims,
     * frozen expression, note, fingerprint — the same locals the v1
     * request below is built from). Full
     * recognition+content+photos payload with unchanged inner bytes and
     * the frozen delivery target; the handwritten code lands in
     * [pendingPublication] on a genuine R5 result. Early returns run
     * the caller's staging/front cleanups via the surrounding
     * finally blocks.
     */
    @Suppress("LongParameterList")
    private suspend fun publishPendingAtBoundary(
        generation: Long,
        inputs: PublishInputs,
        sender: SenderIdentitySnapshot,
        senderHandle: String,
        photoJpegs: List<ByteArray>,
        photoWidthsPx: List<Int>,
        photoHeightsPx: List<Int>,
        frozenSelection: CollageFrozenSelection,
        trackSnapshot: CapsuleTrackSnapshotV1?,
        frontForPublish: ByteArray,
        frozen: dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity.Pending,
    ) {
        val ports = pendingBoundary
        if (ports == null) {
            failPublishing("code sending isn't configured in this build", generation)
            return
        }
        // Owner fence mirrors the v1 path (senderOwner != inputs.owner).
        val pendingOwner = runCatching { UserId.parseRest(sender.userId) }.getOrNull()
        if (pendingOwner == null || pendingOwner != inputs.owner) {
            failPublishing("authenticated owner changed; publishing cancelled", generation)
            return
        }
        val pin = try {
            ports.pin()
        } catch (_: Exception) {
            null
        }
        if (pin == null) {
            failPublishing("code sending isn't configured in this build", generation)
            return
        }
        val result = try {
            dev.hryshyn.remanence.pending.PendingBoundaryPublish.run(
                frozen = frozen,
                authored = dev.hryshyn.remanence.pending.PendingBoundaryPublish.Authored(
                    capsuleId = inputs.capsuleId,
                    photos = photoJpegs.mapIndexed { index, jpeg ->
                        dev.hryshyn.remanence.pending.PendingBoundaryPublish.PhotoBytes(
                            jpeg, photoWidthsPx[index], photoHeightsPx[index],
                        )
                    },
                    manifests = dev.hryshyn.remanence.pending.PendingBoundaryPublish.ManifestInputs(
                        senderHandleSnapshot = senderHandle,
                        frontFingerprintBytes = frontForPublish,
                        noteUtf8 = inputs.noteText,
                        expression = dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher.ExpressionInput(
                            candidateId = frozenSelection.candidateId,
                            expression = frozenSelection.expression,
                            contentIds = frozenSelection.expression.input.photos.map { it.contentId },
                            trackSnapshot = trackSnapshot,
                        ),
                    ),
                    claimExpiresAt = frozen.claimDeadline,
                    createdAtEpochSeconds = clockMillis() / 1000L,
                ),
                identity = dev.hryshyn.remanence.pending.PendingSenderIdentity(
                    sender.userId, sender.activeKeyBundleId, sender.signingPrivateHandle,
                ),
                pin = pin,
                sessionGate = ports.gate,
                accountProvider = ports.account,
                draftPort = ports.draft,
                blobPort = ports.blob,
                finalizePort = ports.finalize,
                // T186 M12e-gap3: the ORIGINAL lease captured at
                // publication entry (PublishInputs) — the runner pins it
                // instead of re-pinning a fresh lease, so a same-owner
                // replacement across the staging awaits fails closed.
                expectedLease = inputs.pendingLease,
                onFinalizeStarted = {
                    // Called BEFORE the final POST, including its cancellation/lost reply.
                    if (isPublishCurrent(generation) && sessionOwner == inputs.owner &&
                        _capsuleId == inputs.capsuleId) {
                        pendingFinalizeAttempt = inputs.owner to inputs.capsuleId
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failPublishing("publishing failed", generation)
            return
        }
        when (result) {
            is dev.hryshyn.remanence.pending.PendingBoundaryPublish.Result.Published -> {
                if (!isPublishCurrent(generation)) return
                _pendingPublication.value = PendingPublishedCode(
                    result.deliveryId, result.locatorCode, result.hint,
                )
                _step.value = Step.PUBLISHED
            }
            is dev.hryshyn.remanence.pending.PendingBoundaryPublish.Result.Failed -> {
                if (!isPublishCurrent(generation)) return
                if (pendingFinalizeAttempt == (inputs.owner to inputs.capsuleId)) {
                    showPendingPublicationIssue(when (result.reason) {
                        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.SEND_LIMIT_REACHED -> PublicationIssueKind.LIMIT
                        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.SEND_ACCESS_UNAVAILABLE -> PublicationIssueKind.ACCESS_UNAVAILABLE
                        else -> PublicationIssueKind.UNKNOWN
                    })
                } else failPublishing(pendingBoundaryMessage(result.reason), generation)
            }
        }
    }

    private fun showPendingPublicationIssue(kind: PublicationIssueKind) {
        _publishError.value = null
        _uploadStatus.value = CreateUploadStatus.PublicationIssue(kind, durable = false)
        _step.value = Step.UPLOAD_PENDING
    }

    private fun pendingBoundaryMessage(
        reason: dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason,
    ): String = when (reason) {
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.NETWORK ->
            "code sending failed; network unreachable"
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.SESSION_CHANGED ->
            "authenticated owner changed; publishing cancelled"
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.CONFLICT ->
            "that send is already on the server"
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.BLOCKED,
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.SERVICE_UNAVAILABLE,
        -> "code sending isn't configured in this build"
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.RECOVERY_REQUIRED ->
            "local identity is unavailable; recovery required"
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.VALIDATION ->
            "that address can't be used for code sending"
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.GENERIC ->
            "publishing failed"
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.SEND_LIMIT_REACHED,
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.SEND_ACCESS_UNAVAILABLE,
        dev.hryshyn.remanence.pending.PendingBoundaryPublish.Reason.FINALIZE_UNKNOWN ->
            "publication is not confirmed"
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

    /** Retry transport only; the account/capsule row remains the sole source. */
    fun retryUpload() {
        if (_step.value != Step.UPLOAD_PENDING || _uploadRetrying.value) return
        val owner = sessionOwner ?: return
        val capsule = _capsuleId
        val generation = createSessionGeneration
        val dao = outboxCapsuleDao ?: return
        _uploadRetrying.value = true
        uploadRetryJob = viewModelScope.launch {
            var statusBeforeEnqueue: CreateUploadStatus? = null
            var retryingFinalization = false
            try {
                val row = dao.getByCapsuleIdAndOwner(capsule, owner.toRestString()) ?: return@launch
                if (!outboxObservationStillCurrent(generation, owner, capsule)) return@launch
                // In-flight chains keep their scheduler/backoff. Terminal,
                // published and preparation-only rows cannot be retried here.
                val parkedLimit = row.state == OutboxCapsuleState.FINALIZING &&
                    row.lastErrorCode in setOf("SEND_LIMIT_REACHED", "FINALIZE_UNCONFIRMED")
                val refusedFinalization = finalizingQueueRefused && row.state == OutboxCapsuleState.FINALIZING &&
                    row.lastErrorCode == null
                if (!parkedLimit && !refusedFinalization && row.state != OutboxCapsuleState.ENCRYPTED &&
                    row.state != OutboxCapsuleState.RETRYABLE_FAILURE) return@launch
                val liveOwner = identityProvider()?.userId
                if (liveOwner != owner.toRestString() ||
                    !outboxObservationStillCurrent(generation, owner, capsule)) return@launch
                val expectedStatus = _uploadStatus.value
                statusBeforeEnqueue = expectedStatus
                retryingFinalization = parkedLimit || refusedFinalization
                if (parkedLimit) {
                    if (dao.releaseParkedFinalizeForOwner(capsule, owner.toRestString()) != 1) return@launch
                    if (!outboxObservationStillCurrent(generation, owner, capsule) ||
                        identityProvider()?.userId != owner.toRestString()) return@launch
                }
                enqueueUpload(owner, CapsuleId.parseRest(capsule))
                if (outboxObservationStillCurrent(generation, owner, capsule)) {
                    finalizingQueueRefused = false
                    _publishError.value = null
                    // A fast worker may already have emitted a newer state.
                    // Never repaint Published/a fresh failure with our old row.
                    if (_step.value == Step.UPLOAD_PENDING && _uploadStatus.value == expectedStatus) {
                        _uploadStatus.value = CreateUploadStatus.Pending(row.state)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (outboxObservationStillCurrent(generation, owner, capsule) &&
                    _step.value == Step.UPLOAD_PENDING && _uploadStatus.value !is CreateUploadStatus.Published) {
                    // Do not overwrite a worker observation delivered during
                    // this attempt; an unchanged prior failure is still retryable.
                    if (_uploadStatus.value == statusBeforeEnqueue || !_uploadStatus.value.hasOutboxProgress() ||
                        (retryingFinalization && _uploadStatus.value == CreateUploadStatus.Pending(OutboxCapsuleState.FINALIZING))) {
                        finalizingQueueRefused = retryingFinalization
                        _uploadStatus.value = CreateUploadStatus.RetryableFailure("QUEUE_FAILED")
                        _publishError.value = "upload could not be queued; retry available"
                    }
                }
            } finally {
                if (generation == createSessionGeneration) _uploadRetrying.value = false
            }
        }
    }

    private fun cancelDeliveryAndRetry() {
        finalizingQueueRefused = false
        frontDeliveryJob?.cancel(); frontDeliveryJob = null
        uploadRetryJob?.cancel(); uploadRetryJob = null
        _uploadRetrying.value = false
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
        runCatching { telemetryCoordinator?.clearSessionData() }
        // FIX-M1-ONDEVICE-01 / FIX-STATE-11: pending resolved material AND an
        // in-flight publication never outlive the surface; the cancelled job's
        // own NonCancellable cleanup guarantees staged plaintext removal.
        createSessionGeneration += 1
        deliveryGeneration += 1
        cancelDeliveryAndRetry()
        clearSelfRecipientState()
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
        pendingPickerResolution = null
        pickerVm.reset()
        photoSelection.clear()
        _pickedPhotoIds.value = emptyList()
        clearPickedThumbnails()
        noteEditor.reset()
        // S2b-sender: leaving the surface drops the picker selection
        // with every other content state (see beginSession for the rationale).
        _musicSelection.value = null
        startMusicArtworkResolution(null)
        frontAttempt.reset()
        frontFingerprintId = null
        clearCropState()
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
        resetGeneratorPreview()
        _uploadStatus.value = CreateUploadStatus.NotStarted
        _revokeStatus.value = CapsuleRevokeStatus.Idle
    }

    override fun onCleared() {
        // Cancellation cleanup: transient session state never outlives the VM.
        pickerVm.close()
        endSession()
        telemetrySession?.close()
        super.onCleared()
    }
}

/**
 * Reason token for a refused bind slot (diagnostics only — the user copy
 * stays unchanged). `thrown` (non-null) wins: only fixed allowlisted
 * exception categories, never names/messages/stacks. Otherwise the result
 * variant plus, for known literals, the exact static reason; anything
 * unrecognized maps to a fixed `-other` token and raw strings are never
 * emitted. Every reason string in the bridge/binder/staging seam is a
 * fixed literal (audited: no ids, hashes, bytes, tokens or interpolated
 * values), and the mapping only ever outputs allowlisted constants.
 * Top-level (not a member) so the mapping is unit-testable without a
 * ViewModel instance.
 */
internal fun bindSlotRefusalToken(
    result: GeneratorCreateBridge.SlotResult?,
    thrown: Exception? = null,
): String {
    if (thrown != null) {
        return when (thrown) {
            is SecurityException -> "thrown-security"
            is java.io.IOException -> "thrown-io"
            else -> "thrown-other"
        }
    }
    return when (result) {
        null -> "thrown"
        is GeneratorCreateBridge.SlotResult.Stale ->
            when (result.reason) {
                "unknown session" -> "stale-unknown-session"
                "stale context" -> "stale-context"
                "already consumed" -> "stale-consumed"
                else -> "stale-other"
            }
        is GeneratorCreateBridge.SlotResult.Duplicate -> "duplicate"
        is GeneratorCreateBridge.SlotResult.Failed ->
            when (result.reason) {
                "bad content id" -> "failed-bad-content-id"
                "bad ordinal" -> "failed-bad-ordinal"
                "bad content hash" -> "failed-bad-content-hash"
                "bad upright dims" -> "failed-bad-upright-dims"
                "bind failed" -> "failed-bind-failed"
                "bytes over bound" -> "failed-bytes-over-bound"
                "decode failed" -> "failed-decode-failed"
                "derived over budget" -> "failed-derived-over-budget"
                "dimension mismatch" -> "failed-dimension-mismatch"
                "empty bytes" -> "failed-empty-bytes"
                "hash mismatch" -> "failed-hash-mismatch"
                "hash mismatch at stage" -> "failed-hash-mismatch-at-stage"
                "invalid input" -> "failed-invalid-input"
                "non-positive dims" -> "failed-non-positive-dims"
                "normalization failed" -> "failed-normalization-failed"
                "out-of-order stage" -> "failed-out-of-order-stage"
                "unknown slot" -> "failed-unknown-slot"
                "unknown session" -> "failed-session-missing"
                "session bytes over bound" -> "failed-session-bytes-over-bound"
                "source failed" -> "failed-source-failed"
                "source over 32MiB bound" -> "failed-source-over-32mib-bound"
                "source over bound" -> "failed-source-over-bound"
                "source unreadable" -> "failed-source-unreadable"
                "store unavailable" -> "failed-store-unavailable"
                "session not active" -> "failed-session-not-active"
                "non-canonical owner" -> "failed-non-canonical-owner"
                "owner mismatch" -> "failed-owner-mismatch"
                "stale epoch" -> "failed-stale-epoch"
                "stale revision" -> "failed-stale-revision"
                "expired" -> "failed-expired"
                "too many leases" -> "failed-too-many-leases"
                else -> "failed-other"
            }
        is GeneratorCreateBridge.SlotResult.Bound -> "bound"
    }
}
