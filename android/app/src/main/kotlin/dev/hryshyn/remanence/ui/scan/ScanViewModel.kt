package dev.hryshyn.remanence.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.create.RealStillFingerprintProcessor
import dev.hryshyn.remanence.scan.ScanCaptureSession
import dev.hryshyn.remanence.scan.ScannedSide
import dev.hryshyn.remanence.scan.ScanSideExtractor
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceGate
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceInput
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceResult
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetParser
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.ReceivedFrontCapture
import dev.hryshyn.remanence.core.data.fingerprints.RecipientBaselineCreator
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.CandidateOrigin
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.LocalMatchEngine
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanFlowResult
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparation
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparationRejection
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparationResult
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.session.SessionBoundary
import kotlinx.coroutines.Job

/**
 * One chooser row carrying ONLY locally decrypted minimal hints plus score
 * (docs/product.md section 11): no thumbnails, notes, or counts.
 */
data class ChooserRow(
    val candidateId: String,
    val compositeScore: Double,
    val senderHandleSnapshot: String? = null,
    val createdAtEpochSeconds: Long? = null,
    val placeLabel: String? = null,
)

/** Terminal scan-flow state handed to navigation and presentation. */
sealed interface ScanTerminalState {
    data object Idle : ScanTerminalState

    /** Grant issued after REAL crypto verification; carries both IDs. */
    data class Granted(
        val grantId: String,
        val capsuleId: String,
        val viaSenderFallback: Boolean,
    ) : ScanTerminalState
}

/**
 * FIX-M1-007-12 / FIX-REVIEW-01 / M2-F0-07: the production Scan flow.
 * Entry is an honest FRONT-only capture state - one FRONT still through the
 * real ORB pipeline, then matching runs immediately against the encrypted
 * local index (docs/recognition.md section 3). The candidate index is
 * built from locally sealed fingerprint rows only; stills run through the ORB
 * processor; [LocalMatchEngine] classifies the hierarchy; and every path to
 * a grant - automatic or manually chosen - passes the existing verification
 * boundary first. Incoming sender-index candidates are joined in memory with
 * the sealed Room recipient baselines; neither decrypted index is persisted.
 */
class ScanViewModel internal constructor(
    private val persistence: SealedFingerprintPersistence,
    private val database: RemanenceLocalDatabase,
    private val profile: RecognitionProfile,
    private val identityProvider: suspend () -> SenderIdentitySnapshot?,
    /**
     * FIX-REVIEW2-04: THE trusted sender-key boundary. The acceptance gate's
     * verifier comes ONLY from here - directory-proven material for other
     * senders, the provably own export only for an exact self-account match.
     * A keyset stored next to the capsule row never decides trust, so M2 can
     * not accidentally grow on storage adjacency.
     */
    private val trustedSenderKeys: dev.hryshyn.remanence.identity.TrustedSenderKeyStore,
    /**
     * FIX-REVIEW-03: THE one authoritative memory-only grant lifecycle, shared
     * with the root navigation. Grants exist only after the full crypto gate
     * passes; resolve/consume/expiry all go through this same instance.
     */
    private val presentationGrants: PresentationGrantAuthority,
    frontProcessor: dev.hryshyn.remanence.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.FRONT),
    private val candidateIndexProvider: suspend (UserId) -> ScanCandidateIndex,
    /** The production factory supplies the real local incoming preparation gate. */
    private val incomingPresentationPreparation: IncomingPresentationPreparation?,
    private val incomingPrepareOverride:
        (suspend (UserId, CapsuleId) -> IncomingPresentationPreparationResult)? = null,
    /**
     * Existing owner-scoped incoming KEEP chain (sync + prefetch + ack).
     * Scan never enqueues a second worker type.
     */
    private val scheduleIncomingSync: suspend (UserId) -> Unit = {},
    /** Live connectivity for pending-material copy; WorkManager still owns backoff. */
    private val networkConnected: () -> Boolean = { true },
    /** Immediate account-boundary fence shared with RootViewModel. */
    private val sessionBoundary: SessionBoundary? = null,
    private val cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {

    val captureSession = ScanCaptureSession(ScanSideExtractor {
        requireNotNull(queuedStill.getAndSet(null)) { "no processed still queued for FRONT" }
    })

    /**
     * FIX-STATE-01: THE authoritative capture attempt. Rejections,
     * failures, processing, and retakes all render from this controller.
     */
    val frontAttempt = CaptureAttemptController()

    private val _matchState = MutableStateFlow<ScanMatchUiState>(ScanMatchUiState.AwaitingCapture)
    val matchState: StateFlow<ScanMatchUiState> = _matchState.asStateFlow()

    private val _terminal = MutableStateFlow<ScanTerminalState>(ScanTerminalState.Idle)
    val terminal: StateFlow<ScanTerminalState> = _terminal.asStateFlow()

    /**
     * Published only after [beginSession] has reset the retained controllers.
     * The root surface gates camera composition on this value so a new epoch
     * cannot bind against the previous epoch's permission/binding state.
     */
    private val _initializedEpoch = MutableStateFlow<Long?>(null)
    val initializedEpoch: StateFlow<Long?> = _initializedEpoch.asStateFlow()

    private val queuedStill = AtomicReference<ScannedSide?>()
    private val frontProcessor = frontProcessor
    /**
     * FIX-REVIEW-01: generation guard so a stale asynchronous match result can
     * never overwrite a newer capture flow started by reset/re-entry.
     */
    private var matchGeneration: Int = 0

    /** Hints are retained only while the matching generation owns a chooser. */
    private var chooserContext: ChooserContext? = null

    private data class ChooserContext(
        val generation: Int,
        val origin: CandidateOrigin,
        val hints: Map<String, ScanChooserHint>,
        val presentationSources: Map<UUID, CapsulePresentationSource>,
    )

    /**
     * FIX-STATE-10: monotonic guard for DELIVERED-STILL continuations.
     * resetSession()/beginSession() bump it; a processing result whose
     * generation no longer matches is fully inert - it may neither touch
     * queuedStill, nor captureSession, nor match state, nor the controllers.
     */
    private var deliveryGeneration: Long = 0L

    /**
     * FIX-REVIEW-02: epoch of the scan session this ViewModel currently holds.
     * beginSession(epoch) fully resets when [epoch] differs - captures, match
     * state, terminal grant, and any live grant are consumed so re-entry is
     * always a fresh FRONT-first flow and an old Granted can never be
     * reopened without a new scan. Same epoch is a no-op (rotation safety).
     */
    private var begunEpoch: Long? = null

    /**
     * Recognized incoming capsule waiting on MATERIAL_CACHED. Cleared by
     * beginSession/reset and by a successful grant. Generation-fenced.
     */
    private var pendingIncoming: PendingIncoming? = null

    private var pendingWatcherJob: Job? = null
    private var watchedPending: PendingIncoming? = null
    private var incomingSyncScheduleJob: Job? = null
    private var sessionBoundaryEpoch: Long = sessionBoundary?.currentEpoch() ?: 0L
    private val unregisterSessionBoundary: (() -> Unit)? = sessionBoundary?.register {
        invalidateForAccountBoundary()
    }

    private data class PendingIncoming(
        val owner: UserId,
        val capsuleId: UUID,
        val origin: CandidateOrigin,
        val generation: Int,
        val sessionBoundaryEpoch: Long,
    )

    /**
     * Authenticated Scan entry starts a fresh FRONT-only session and
     * schedules the existing owner-scoped KEEP incoming chain. Same-epoch
     * rotation is a no-op and does not re-enqueue.
     */
    fun beginSession(epoch: Long) {
        if (begunEpoch == epoch) return
        begunEpoch = epoch
        cancelPendingWatcher()
        cancelIncomingSyncSchedule()
        sessionBoundaryEpoch = sessionBoundary?.currentEpoch() ?: sessionBoundaryEpoch
        matchGeneration++
        chooserContext = null
        pendingIncoming = null
        deliveryGeneration++
        captureSession.reset()
        presentationGrants.clearAll()
        frontAttempt.reset()
        queuedStill.set(null)
        _matchState.value = ScanMatchUiState.AwaitingCapture
        _terminal.value = ScanTerminalState.Idle
        _initializedEpoch.value = epoch
        scheduleOwnerIncomingSync(matchGeneration)
    }

    // ------------------------------------------------------------------
    // Capture.
    // ------------------------------------------------------------------

    /** Shutter press for the FRONT; legal only while the session awaits FRONT. */
    fun beginFrontCapture(): Boolean {
        if (captureSession.state != dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT) return false
        return try {
            frontAttempt.beginAttempt()
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    /** Camera bytes for the FRONT; stale deliveries are structurally inert. */
    fun deliverFrontJpeg(jpegBytes: ByteArray) {
        if (captureSession.state != dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT) return
        val generation = deliveryGeneration
        viewModelScope.launch {
            val accepted = acceptProcessed(jpegBytes, frontProcessor, generation)
            if (accepted && captureSession.readyForMatching) evaluateMatch()
        }
    }

    /**
     * FIX-STATE-10: the ONLY authority for whether a resumed pipeline result
     * may mutate anything. Checked BEFORE every mutation - a stale outcome is
     * dropped on the floor without touching queuedStill, the session, the
     * attempt controller, or match state.
     */
    private fun deliveryIsCurrent(
        generation: Long,
    ): Boolean {
        if (generation != deliveryGeneration) return false
        if (captureSession.state != dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT) return false
        return frontAttempt.hasActiveAttempt
    }

    /**
     * FIX-STATE-01: a delivered still ALWAYS terminates its attempt -
     * Accepted, Rejected, or Failed - even when the ORB processor throws;
     * cancellation completes the lifecycle without publishing any result.
     */
    private suspend fun acceptProcessed(
        jpegBytes: ByteArray,
        processor: dev.hryshyn.remanence.capture.StillProcessor,
        generation: Long,
    ): Boolean {
        val attempt = frontAttempt
        if (!attempt.markProcessing()) return false
        return try {
            val processed =
                withContext(cpuDispatcher) { processor.process(jpegBytes) }

            // FIX-STATE-10: BEFORE ANY MUTATION - a reset/new session during
            // processing makes this whole outcome disappear without a trace.
            if (!deliveryIsCurrent(generation)) return false

            when (processed) {
                is ProcessedStill.Rejected -> {
                    attempt.reject(processed.reasons, processed.diagnostic)
                    false
                }
                is ProcessedStill.Accepted -> {
                    queuedStill.set(
                        ScannedSide(processed.profileId, processed.serializedBytes),
                    )
                    captureSession.captureFront()
                    attempt.accept()
                    true
                }
            }
        } catch (cancelled: CancellationException) {
            // Teardown touches state only while this delivery still owns it.
            if (deliveryIsCurrent(generation)) {
                queuedStill.set(null)
                attempt.cancelActiveAttempt()
            }
            throw cancelled
        } catch (failure: Exception) {
            if (deliveryIsCurrent(generation)) {
                queuedStill.set(null)
                attempt.fail(failure.message ?: "capture failed")
            }
            false
        }
    }

    /** Module-internal view of THE delivery generation (tests only). */
    internal fun deliveryGenerationForDiagnostics(): Long = deliveryGeneration

    /** Explicit Retake after Rejected/Failed on the FRONT. */
    fun retakeFront() {
        runCatchingRetake(frontAttempt)
    }

    private fun runCatchingRetake(attempt: CaptureAttemptController) {
        runCatching { attempt.startRetake() }
    }

    /**
     * FIX-REVIEW-01: the explicit user restart returns the WHOLE flow to the
     * FRONT capture state; any in-flight evaluation is discarded so a stale
     * result can never overwrite the fresh capture sequence.
     */
    fun resetSession() {
        cancelPendingWatcher()
        cancelIncomingSyncSchedule()
        matchGeneration++
        chooserContext = null
        pendingIncoming = null
        deliveryGeneration++
        presentationGrants.clearAll()
        // FIX-STATE-05: an in-flight delivery/match can never write into the
        // fresh flow - active attempts are cancelled cleanly and stale
        // terminal callbacks are structurally inert afterwards.
        frontAttempt.restartCapture()
        queuedStill.set(null)
        captureSession.reset()
        _matchState.value = ScanMatchUiState.AwaitingCapture
        _terminal.value = ScanTerminalState.Idle
    }

    // ------------------------------------------------------------------
    // Matching over the encrypted local index.
    // ------------------------------------------------------------------

    /**
     * M2-P03: the Room half of the scan index is the owning account's sealed
     * fingerprint rows only. Incoming sender-index candidates are joined by
     * [buildCandidateIndex] for this scan invocation.
     */
    private suspend fun buildRoomCandidateIndex(
        identity: SenderIdentitySnapshot,
    ): ScanCandidateIndex {
        val rows = database.recognitionFingerprintDao().getAllForOwner(identity.userId)
        val candidates = rows
            .groupBy { it.capsuleId }
            .toSortedMap()
            .flatMap { (capsuleId, capsuleRows) ->
                listOf(FingerprintOrigin.RECIPIENT, FingerprintOrigin.SENDER).mapNotNull { origin ->
                    val originRows = capsuleRows.filter { it.origin == origin }
                    val frontRow = originRows.singleOrNull() ?: return@mapNotNull null
                    val frontBytes = try {
                        persistence.decrypt(frontRow.fingerprintId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@mapNotNull null
                    }
                    try {
                        IndexedCandidate(
                            capsuleId = UUID.fromString(capsuleId),
                            front = FingerprintCodec.parse(frontBytes),
                            // Keep each complete origin available to the
                            // engine; it searches recipient and sender
                            // FRONTs as separate universes.
                            recipientPreferred = origin == FingerprintOrigin.RECIPIENT &&
                                originRows.any { it.preferred },
                        )
                    } catch (_: Exception) {
                        null
                    } finally {
                        frontBytes.fill(0)
                    }
                }
            }
        return ScanCandidateIndex(candidates)
    }

    private suspend fun buildCandidateIndex(): ScanCandidateIndex {
        val identity = identityProvider() ?: return ScanCandidateIndex.EMPTY
        val owner = try {
            UserId.parseRest(identity.userId)
        } catch (_: Exception) {
            return ScanCandidateIndex.EMPTY
        }
        val room = buildRoomCandidateIndex(identity)
        val incoming = candidateIndexProvider(owner)
        val merged = ArrayList<IndexedCandidate>(incoming.candidates.size + room.candidates.size)
        val mergedReferences = HashSet<Pair<UUID, Boolean>>()
        fun retain(candidate: IndexedCandidate) {
            // Incoming and Room sender rows are the same sender reference for
            // one capsule. Keep the incoming copy when available, while never
            // collapsing a recipient candidate into that sender reference.
            if (mergedReferences.add(candidate.capsuleId to candidate.recipientPreferred)) {
                merged += candidate
            }
        }
        incoming.candidates.forEach(::retain)
        room.candidates.forEach(::retain)
        // Storage membership is independent of recognition validity. Probe the
        // owner-scoped OUTBOX plane for every merged candidate, including an
        // incoming candidate whose Room recognition rows are absent/corrupt;
        // otherwise that candidate could be rebound to INCOMING silently.
        val outboxSources = LinkedHashMap<UUID, CapsulePresentationSource>()
        for (candidateId in merged.map { it.capsuleId }.distinct()) {
            if (database.outboxCapsuleDao().getByCapsuleIdAndOwner(
                    candidateId.toString(),
                    owner.toRestString(),
                ) != null
            ) {
                outboxSources[candidateId] = CapsulePresentationSource.OUTBOX
            }
        }
        // The OUTBOX probe is also owner-scoped work. Re-read the authenticated
        // owner after all Room/provider loads, including that probe, before any
        // source or candidate data can be returned to the scan engine.
        val finalIdentity = identityProvider() ?: return ScanCandidateIndex.EMPTY
        val finalOwner = try {
            UserId.parseRest(finalIdentity.userId)
        } catch (_: Exception) {
            return ScanCandidateIndex.EMPTY
        }
        if (finalOwner != owner) return ScanCandidateIndex.EMPTY
        val hints = incoming.chooserHints.filterKeys { id ->
            val candidateId = runCatching { UUID.fromString(id) }.getOrNull()
            merged.none { it.capsuleId == candidateId && it.recipientPreferred }
        }
        // Source is a storage-plane fact, not recognition provenance. An
        // incoming row wins this binding even when a recipient-preferred Room
        // baseline wins the recognition duplicate.
        val presentationSources = resolvePresentationSources(
            candidateIds = merged.map { it.capsuleId }.distinct(),
            incomingSources = incoming.presentationSources,
            roomSources = outboxSources,
        )
        return ScanCandidateIndex(merged, hints, presentationSources)
    }

    /** Test-only view of the same merged, owner-bound scan index. */
    internal suspend fun buildCandidateIndexForTests(): ScanCandidateIndex = buildCandidateIndex()

    private fun evaluateMatch() {
        val sessionFront = captureSession.front ?: return
        // M2-F0-07 FRONT-only: one FRONT fingerprint drives candidate
        // matching immediately; missing/multiple matches stay fail-closed.
        _matchState.value = ScanMatchUiState.Matching
        val generation = ++matchGeneration
        viewModelScope.launch {
            try {
                val candidateIndex = buildCandidateIndex()
                val engine = LocalMatchEngine(
                    profile = profile,
                    verifier = { capsuleId ->
                        when (candidateIndex.presentationSources[capsuleId]) {
                            CapsulePresentationSource.INCOMING -> true
                            CapsulePresentationSource.OUTBOX -> verifyCapsuleCrypto(capsuleId)
                            null -> false
                        }
                    },
                    grantIssuer = { capsuleId ->
                        if (generation == matchGeneration) {
                            candidateIndex.presentationSources[capsuleId]?.let { source ->
                                issueVerifiedGrant(
                                    capsuleId = capsuleId,
                                    source = source,
                                    generation = generation,
                                    originHint = null,
                                )
                            }
                        } else {
                            null
                        }
                    },
                )
                val result = engine.run(
                    queryFront = FingerprintCodec.parse(sessionFront.serializedBytes),
                    candidates = candidateIndex.candidates,
                )
                if (generation == matchGeneration) {
                    applyResult(
                        result = result,
                        candidateHints = candidateIndex.chooserHints,
                        presentationSources = candidateIndex.presentationSources,
                        generation = generation,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == matchGeneration) {
                    _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 1)
                }
            }
        }
    }

    private suspend fun applyResult(
        result: ScanFlowResult,
        candidateHints: Map<String, ScanChooserHint>,
        presentationSources: Map<UUID, CapsulePresentationSource>,
        generation: Int,
    ) {
        if (generation != matchGeneration) return
        when (result) {
            is ScanFlowResult.Granted -> {
                chooserContext = null
                    onVerifiedGrant(result, generation)
            }

            is ScanFlowResult.Ambiguous -> {
                val hints: Map<String, ScanChooserHint> = result.rows.mapNotNull { (id, _) ->
                    val candidateId = id.toString()
                    val hint = candidateHints[candidateId] ?: loadLegacyHint(candidateId)
                    hint?.let { candidateId to it }
                }.toMap()
                if (generation != matchGeneration) return
                chooserContext = ChooserContext(
                    generation = generation,
                    origin = result.origin,
                    hints = hints,
                    presentationSources = presentationSources,
                )
                _matchState.value = ScanMatchUiState.Chooser(
                    rows = result.rows.map { (id, score) ->
                        val hint = hints[id.toString()]
                        ChooserRow(
                            candidateId = id.toString(),
                            compositeScore = score,
                            senderHandleSnapshot = hint?.senderHandleSnapshot,
                            createdAtEpochSeconds = hint?.createdAtEpochSeconds,
                            placeLabel = hint?.placeLabel,
                        )
                    },
                    origin = result.origin,
                    generation = generation,
                )
            }

            ScanFlowResult.RecaptureRequired -> {
                chooserContext = null
                if (!publishPendingIfRecognized(generation, origin = null)) {
                    _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 1)
                }
            }
        }
    }

    /**
     * Manual choice from the ambiguity chooser STILL verifies the full crypto
     * chain before any grant exists - selection is not stronger evidence.
     * FIX-REVIEW-01: selection is accepted ONLY while the matching chooser
     * with exactly this row is live, so an out-of-flow call can never mint
     * state or grants.
     */
    fun onChooserSelected(candidateId: String) {
        val chooser = _matchState.value as? ScanMatchUiState.Chooser ?: return
        val context = chooserContext ?: return
        if (chooser.generation != matchGeneration ||
            context.generation != chooser.generation ||
            context.origin != chooser.origin
        ) return
        if (chooser.rows.none { it.candidateId == candidateId }) return
        val generation = ++matchGeneration
        chooserContext = null
        _matchState.value = ScanMatchUiState.Matching
        viewModelScope.launch {
            val id = UUID.fromString(candidateId)
            val source = context.presentationSources[id] ?: run {
                if (generation == matchGeneration) {
                    _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 2)
                }
                return@launch
            }
            val grantId = issueVerifiedGrant(
                capsuleId = id,
                source = source,
                generation = generation,
                originHint = chooser.origin,
            )
            if (grantId == null) {
                if (generation == matchGeneration) {
                    if (!publishPendingIfRecognized(generation, chooser.origin)) {
                        _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 2)
                    }
                }
                return@launch
            }
            if (generation != matchGeneration) return@launch
            applyResult(
                ScanFlowResult.Granted(
                    capsuleId = id,
                    origin = chooser.origin,
                    grantId = grantId,
                    compositeScore = Double.NaN,
                ),
                candidateHints = emptyMap(),
                presentationSources = context.presentationSources,
                generation = generation,
            )
        }
    }

    private suspend fun onVerifiedGrant(result: ScanFlowResult.Granted, generation: Int) {
        if (generation != matchGeneration) {
            revokeIssuedGrant(result.grantId)
            return
        }
        if (!grantStillLive(result.grantId)) {
            revokeIssuedGrant(result.grantId)
            return
        }
        try {
            persistVerifiedRecipientBaseline(result.capsuleId.toString())
        } catch (cancelled: CancellationException) {
            revokeIssuedGrant(result.grantId)
            throw cancelled
        } catch (failure: Exception) {
            revokeIssuedGrant(result.grantId)
            throw failure
        }
        if (generation != matchGeneration || !grantStillLive(result.grantId)) {
            revokeIssuedGrant(result.grantId)
            return
        }
        pendingIncoming = null
        cancelPendingWatcher()
        _terminal.value = ScanTerminalState.Granted(
            grantId = result.grantId,
            capsuleId = result.capsuleId.toString(),
            viaSenderFallback = result.origin == CandidateOrigin.SENDER_FALLBACK,
        )
        _matchState.value = ScanMatchUiState.Accepted(
            candidateId = result.capsuleId.toString(),
            viaSenderFallback = result.origin == CandidateOrigin.SENDER_FALLBACK,
        )
    }

    private fun revokeIssuedGrant(grantId: String) {
        runCatching { UUID.fromString(grantId) }
            .getOrNull()
            ?.let(presentationGrants::revoke)
    }

    private suspend fun issueVerifiedGrant(
        capsuleId: UUID,
        source: CapsulePresentationSource,
        generation: Int,
        originHint: CandidateOrigin?,
    ): String? {
        val boundary = sessionBoundaryEpoch
        if (generation != matchGeneration || !sessionBoundaryIsCurrent(boundary)) return null
        val presentationEpoch = presentationGrants.currentEpoch()
        val identity = try {
            identityProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        val owner = runCatching { UserId.parseRest(identity.userId) }.getOrNull() ?: return null

        var prepared: dev.hryshyn.remanence.ui.capsule.PreparedIncomingPresentation? = null
        try {
            if (source == CapsulePresentationSource.INCOMING) {
                val result = incomingPrepareOverride?.invoke(owner, CapsuleId(capsuleId))
                    ?: incomingPresentationPreparation?.prepare(owner, CapsuleId(capsuleId))
                    ?: return null
                when (result) {
                    is IncomingPresentationPreparationResult.Prepared ->
                        prepared = result.presentation
                    is IncomingPresentationPreparationResult.Rejected -> {
                        if (shouldHoldForIncomingMaterial(owner, capsuleId, result.reason)) {
                            rememberPendingIncoming(owner, capsuleId, generation, originHint)
                        }
                        return null
                    }
                    is IncomingPresentationPreparationResult.Unavailable -> return null
                    else -> return null
                }
            } else if (!verifyCapsuleCrypto(capsuleId)) {
                return null
            }

            if (generation != matchGeneration || !sessionBoundaryIsCurrent(boundary)) return null
            val currentIdentity = try {
                identityProvider()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            } ?: return null
            val currentOwner = runCatching { UserId.parseRest(currentIdentity.userId) }.getOrNull()
            if (currentOwner != owner || !sessionBoundaryIsCurrent(boundary)) return null

            val grant = presentationGrants.issue(
                ownerUserId = owner,
                capsuleId = capsuleId,
                source = source,
                scanGeneration = generation,
                expectedEpoch = presentationEpoch,
                incomingPresentation = prepared,
            )
            // The authority now owns the exact prepared handle.
            prepared = null
            return grant.grantId.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            // This covers every failure after preparation, including a
            // provider exception while the final owner is reread. Once the
            // authority accepts the binding, ownership is cleared above.
            runCatching { prepared?.close() }
        }
    }

    private suspend fun grantStillLive(grantId: String): Boolean {
        val identity = try {
            identityProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } ?: return false
        val owner = runCatching { UserId.parseRest(identity.userId) }.getOrNull() ?: return false
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return false
        return presentationGrants.resolve(uuid, owner) != null
    }

    /** Module-internal test view through the complete owner-bound authority. */
    internal fun liveGrantCapsuleId(grantId: String, ownerUserId: UserId): String? {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return null
        return presentationGrants.resolve(uuid, ownerUserId)?.capsuleId?.toString()
    }

    // ------------------------------------------------------------------
    // THE verification gate: no grant without it, ever.
    // ------------------------------------------------------------------

    private suspend fun verifyCapsuleCrypto(capsuleId: UUID): Boolean {
        val identity = identityProvider() ?: return false
        // M2-P03: outbox lookups are account-scoped; a capsule owned by
        // another local account can never be verified, decrypted, or granted.
        val row =
            database.outboxCapsuleDao()
                .getByCapsuleIdAndOwner(capsuleId.toString(), identity.userId)
                ?: return false
        val blobs = database.outboxBlobDao()
            .getAllByCapsuleIdAndOwner(capsuleId.toString(), identity.userId)
        val statementPath = row.publishStatementPath ?: return false
        val signaturePath = row.publishStatementSignaturePath ?: return false
        val envelopePath = row.envelopePath ?: return false

        return try {
            val statementBytes = File(statementPath).readBytes()
            val signatureBytes = File(signaturePath).readBytes()
            val envelopeBytes = File(envelopePath).readBytes()
            val ownUser = UserId(UUID.fromString(identity.userId))

            // FIX-REVIEW2-01: THE one strict routing parser decides before any
            // crypto runs. Malformed non-null identity material fails closed;
            // only genuinely NULL v3 columns resolve through the documented
            // legacy self-send fallback.
            val routing = when (val resolution = dev.hryshyn.remanence.identity.CapsuleRoutingPolicy.resolve(row)) {
                is dev.hryshyn.remanence.identity.CapsuleRoutingResolution.Resolved -> resolution
                is dev.hryshyn.remanence.identity.CapsuleRoutingResolution.Corrupt -> return false
            }

            val openedEnvelope = RecipientEnvelopeCryptor().open(
                recipientEncryptionPrivateKeyset = identity.encryptionPrivateHandle,
                context = dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                    CapsuleId(capsuleId),
                    routing.senderUserId,
                    routing.recipientUserId,
                    routing.recipientKeyBundleId,
                ),
                ciphertext = envelopeBytes,
            )

            // FIX-REVIEW2-04: the verifier comes ONLY from the trusted
            // sender-key boundary - directory-proven for other senders, the
            // provably own export only for an exact self-account match. The
            // row-carried keyset is a transport/cache candidate and never a
            // trust decision; any lookup refusal fails closed.
            val senderVerifyingKeyset =
                when (
                    val lookup = trustedSenderKeys.senderVerifyingKeyset(
                        routing.senderUserId,
                        routing.senderKeyBundleId,
                    )
                ) {
                    is dev.hryshyn.remanence.identity.SenderKeyResolution.Trusted -> lookup.verifyingKeyset
                    is dev.hryshyn.remanence.identity.SenderKeyResolution.Untrusted,
                    is dev.hryshyn.remanence.identity.SenderKeyResolution.Unavailable,
                    -> return false
                }

            val gateInput = CapsuleAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleId),
                authenticatedUserId = ownUser,
                senderVerifyingKeyset = senderVerifyingKeyset,
                expectedSenderKeyBundleId = routing.senderKeyBundleId,
                envelopePlaintextBytes = openedEnvelope,
                statementBytes = statementBytes,
                signature = signatureBytes,
                deliveredBlobs = blobs.map { blob ->
                    val file = File(blob.localCiphertextPath)
                    dev.hryshyn.remanence.core.crypto.DeliveredBlob(
                        blobId = BlobId(UUID.fromString(blob.blobId)),
                        ciphertextSize = file.length(),
                        ciphertextSha256 =
                            MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
                    )
                },
            )
            CapsuleAcceptanceGate().verify(gateInput) is CapsuleAcceptanceResult.Accepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun persistVerifiedRecipientBaseline(capsuleId: String) {
        val front = captureSession.front ?: return
        try {
            RecipientBaselineCreator(persistence).createAfterVerifiedReceipt(
                capsuleId = capsuleId,
                front = ReceivedFrontCapture(front.profileId, front.serializedBytes),
            )
        } catch (_: dev.hryshyn.remanence.core.data.fingerprints.ImmutableBaselineException) {
            // The initial recipient baseline is immutable; later scans keep it.
        }
    }

    // ------------------------------------------------------------------
    // Minimal decrypted hints for the ambiguity chooser.
    // ------------------------------------------------------------------

    private suspend fun loadLegacyHint(candidateId: String): ScanChooserHint? = try {
        decryptHint(candidateId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun decryptHint(candidateId: String): ScanChooserHint {
        val identity = identityProvider() ?: throw IllegalStateException("no identity")
        val capsuleUuid = UUID.fromString(candidateId)
        // M2-P03: account-scoped outbox reads; chooser hints never resolve
        // another local account's capsule.
        val row = database.outboxCapsuleDao()
            .getByCapsuleIdAndOwner(candidateId, identity.userId)
            ?: throw IllegalStateException("unknown capsule")
        val blobs = database.outboxBlobDao()
            .getAllByCapsuleIdAndOwner(candidateId, identity.userId)
        val recognitionBlob = blobs.first { it.kind == OutboxArtifactKind.RECOGNITION_MANIFEST.name }
        val manifestCiphertext = File(recognitionBlob.localCiphertextPath).readBytes()
        val envelopeCiphertext = File(requireNotNull(row.envelopePath)).readBytes()

        // FIX-REVIEW2-01: strict routing parse; corrupt material refuses to
        // decrypt a hint (the chooser row simply loses its decrypted hints and
        // selection still fails the full gate).
        val routing = when (val resolution = dev.hryshyn.remanence.identity.CapsuleRoutingPolicy.resolve(row)) {
            is dev.hryshyn.remanence.identity.CapsuleRoutingResolution.Resolved -> resolution
            is dev.hryshyn.remanence.identity.CapsuleRoutingResolution.Corrupt ->
                throw IllegalStateException("corrupt capsule routing: ${resolution.field}")
        }
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid),
                routing.senderUserId,
                routing.recipientUserId,
                routing.recipientKeyBundleId,
            ),
            envelopeCiphertext,
        )
        val capsuleKeyset = CapsuleKeysetParser().parseExactAes256GcmTink(
            dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext.parseFrom(opened)
                .capsuleAeadKeyset.toByteArray(),
        )

        val content = dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec().decryptAndParse(
            capsuleKeyset,
            dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec.RoutingContext(
                CapsuleId(capsuleUuid),
                deriveRecognitionBlobId(capsuleUuid),
                routing.senderUserId,
                routing.recipientUserId,
            ),
            manifestCiphertext,
        )
        return ScanChooserHint(
            candidateId = candidateId,
            senderHandleSnapshot = content.senderHandleSnapshot,
            createdAtEpochSeconds = content.createdAtEpochSeconds,
            placeLabel = content.placeLabel,
        )
    }

    /** Mirrors CapsulePublisher's deterministic recognition blob id. */
    private fun deriveRecognitionBlobId(capsuleId: UUID): BlobId {
        val base = CapsuleId(capsuleId).toProtoBytes().toByteArray()
        base[0] = RECOGNITION_BLOB_BYTE.toByte()
        return BlobId.fromProtoBytes(com.google.protobuf.ByteString.copyFrom(base))
    }

    override fun onCleared() {
        cancelPendingWatcher()
        cancelIncomingSyncSchedule()
        unregisterSessionBoundary?.invoke()
        captureSession.consume()
        super.onCleared()
    }

    private fun scheduleOwnerIncomingSync(generation: Int) {
        cancelIncomingSyncSchedule()
        val boundary = sessionBoundaryEpoch
        incomingSyncScheduleJob = viewModelScope.launch {
            val identity = try {
                identityProvider()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            } ?: return@launch
            if (generation != matchGeneration || !sessionBoundaryIsCurrent(boundary)) return@launch
            val owner = runCatching { UserId.parseRest(identity.userId) }.getOrNull() ?: return@launch
            if (!sessionBoundaryIsCurrent(boundary)) return@launch
            try {
                scheduleIncomingSync(owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Existing KEEP enqueue remains best-effort; pending UI still stands.
            }
        }
    }

    private suspend fun shouldHoldForIncomingMaterial(
        owner: UserId,
        capsuleId: UUID,
        reason: IncomingPresentationPreparationRejection,
    ): Boolean {
        if (reason != IncomingPresentationPreparationRejection.CAPSULE_STATE_INVALID &&
            reason != IncomingPresentationPreparationRejection.MATERIAL_MISSING
        ) {
            return false
        }
        val row = try {
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(
                capsuleId.toString(),
                owner.toRestString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } ?: return false
        return dev.hryshyn.remanence.ui.capsule.isMaterialPendingEligible(
            capsule = row,
            ownerUserId = owner,
            capsuleId = CapsuleId(capsuleId),
        )
    }

    private fun rememberPendingIncoming(
        owner: UserId,
        capsuleId: UUID,
        generation: Int,
        originHint: CandidateOrigin?,
    ) {
        if (generation != matchGeneration) return
        pendingIncoming = PendingIncoming(
            owner = owner,
            capsuleId = capsuleId,
            origin = originHint ?: CandidateOrigin.SENDER_FALLBACK,
            generation = generation,
            sessionBoundaryEpoch = sessionBoundaryEpoch,
        )
    }

    private fun publishPendingIfRecognized(
        generation: Int,
        origin: CandidateOrigin?,
    ): Boolean {
        val pending = pendingIncoming ?: return false
        if (pending.generation != generation ||
            pending.sessionBoundaryEpoch != sessionBoundaryEpoch ||
            !sessionBoundaryIsCurrent()
        ) return false
        if (origin != null && pending.origin != origin) {
            pendingIncoming = pending.copy(origin = origin)
        }
        val published = pendingIncoming ?: return false
        _matchState.value = ScanMatchUiState.MaterialPending(
            capsuleId = published.capsuleId.toString(),
            connected = networkConnected(),
        )
        scheduleOwnerIncomingSync(generation)
        watchPendingIncoming(published)
        return true
    }

    private fun watchPendingIncoming(pending: PendingIncoming) {
        if (pendingWatcherJob?.isActive == true && watchedPending == pending) return
        cancelPendingWatcher()
        watchedPending = pending
        val boundary = pending.sessionBoundaryEpoch
        pendingWatcherJob = viewModelScope.launch {
            database.incomingCapsuleDao().observeMaterialStateByCapsuleIdAndOwner(
                pending.capsuleId.toString(),
                pending.owner.toRestString(),
            ).collect { state ->
                if (pending.generation != matchGeneration ||
                    boundary != sessionBoundaryEpoch ||
                    !sessionBoundaryIsCurrent(boundary)
                ) return@collect
                if (_matchState.value !is ScanMatchUiState.MaterialPending) return@collect
                if (state != LocalMaterialState.MATERIAL_CACHED &&
                    state != LocalMaterialState.FINGERPRINT_ACCEPTED
                ) {
                    return@collect
                }
                val identity = try {
                    identityProvider()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@collect
                } ?: return@collect
                val currentOwner = runCatching { UserId.parseRest(identity.userId) }.getOrNull()
                if (currentOwner != pending.owner || !sessionBoundaryIsCurrent(boundary)) return@collect
                val grantId = issueVerifiedGrant(
                    capsuleId = pending.capsuleId,
                    source = CapsulePresentationSource.INCOMING,
                    generation = pending.generation,
                    originHint = pending.origin,
                ) ?: return@collect
                applyResult(
                    result = ScanFlowResult.Granted(
                        capsuleId = pending.capsuleId,
                        origin = pending.origin,
                        grantId = grantId,
                        compositeScore = Double.NaN,
                    ),
                    candidateHints = emptyMap(),
                    presentationSources = mapOf(
                        pending.capsuleId to CapsulePresentationSource.INCOMING,
                    ),
                    generation = pending.generation,
                )
            }
        }
    }

    private fun cancelPendingWatcher() {
        pendingWatcherJob?.cancel()
        pendingWatcherJob = null
        watchedPending = null
    }

    private fun cancelIncomingSyncSchedule() {
        incomingSyncScheduleJob?.cancel()
        incomingSyncScheduleJob = null
    }

    private fun sessionBoundaryIsCurrent(
        expected: Long = sessionBoundaryEpoch,
    ): Boolean = sessionBoundary?.currentEpoch()?.let { it == expected } ?: true

    private fun invalidateForAccountBoundary() {
        sessionBoundaryEpoch = sessionBoundary?.currentEpoch() ?: (sessionBoundaryEpoch + 1L)
        resetSession()
        begunEpoch = null
    }

    companion object {
        const val RECOGNITION_BLOB_BYTE = 0x01
    }
}
