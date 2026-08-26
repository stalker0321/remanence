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
import dev.hryshyn.remanence.core.data.db.FingerprintSide as DbFingerprintSide
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.ReceivedSideCapture
import dev.hryshyn.remanence.core.data.fingerprints.RecipientBaselineCreator
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.CandidateOrigin
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.LocalMatchEngine
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanFlowResult
import dev.hryshyn.remanence.core.recognition.ScanGrantManager

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

/** A locally decrypted minimal chooser hint for one plausible candidate. */
data class DecryptedHint(
    val candidateId: String,
    val senderHandleSnapshot: String,
    val createdAtEpochSeconds: Long,
    val placeLabel: String?,
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
 * FIX-M1-007-12 / FIX-REVIEW-01: the production Scan flow. Entry is an honest
 * capture state - FRONT first, then BACK, and only a complete capture pair
 * reaches matching (docs/recognition.md section 3). The candidate index is
 * built from locally sealed fingerprint rows only; stills run through the ORB
 * processors; [LocalMatchEngine] classifies the hierarchy; and EVERY path to
 * a grant - automatic or manually chosen - passes the real envelope/signature/
 * ID/hash/AEAD verification ([CapsuleAcceptanceGate]) first. A verified
 * receipt persists the delivered pair as the preferred RECIPIENT baseline.
 */
class ScanViewModel(
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
    grantsClockMillis: () -> Long,
    /**
     * FIX-REVIEW-03: THE one authoritative memory-only grant lifecycle, shared
     * with the root navigation. Grants exist only after the full crypto gate
     * passes; resolve/consume/expiry all go through this same instance.
     */
    private val grants: ScanGrantManager = ScanGrantManager(clockMillis = grantsClockMillis),
    frontProcessor: dev.hryshyn.remanence.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.FRONT),
    backProcessor: dev.hryshyn.remanence.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.BACK),
    candidateIndexProvider: (suspend () -> List<IndexedCandidate>)? = null,
    private val cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {

    val captureSession = ScanCaptureSession(ScanSideExtractor { side ->
        requireNotNull(queuedStill.getAndSet(null)) { "no processed still queued for $side" }
    })

    /**
     * FIX-STATE-01: THE authoritative per-side capture attempts. Rejections,
     * failures, processing, and retakes all render from these controllers.
     */
    val frontAttempt = CaptureAttemptController()
    val backAttempt = CaptureAttemptController()

    private val _matchState = MutableStateFlow<ScanMatchUiState>(ScanMatchUiState.AwaitingCapture)
    val matchState: StateFlow<ScanMatchUiState> = _matchState.asStateFlow()

    private val _terminal = MutableStateFlow<ScanTerminalState>(ScanTerminalState.Idle)
    val terminal: StateFlow<ScanTerminalState> = _terminal.asStateFlow()

    private val queuedStill = AtomicReference<ScannedSide?>()
    private val frontProcessor = frontProcessor
    private val backProcessor = backProcessor
    private val candidateIndexProvider = candidateIndexProvider

    /**
     * FIX-REVIEW-01: generation guard so a stale asynchronous match result can
     * never overwrite a newer capture flow started by reset/re-entry.
     */
    private var matchGeneration: Int = 0

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

    fun beginSession(epoch: Long) {
        if (begunEpoch == epoch) return
        begunEpoch = epoch
        matchGeneration++
        deliveryGeneration++
        captureSession.reset()
        grants.clearAll()
        frontAttempt.reset()
        backAttempt.reset()
        queuedStill.set(null)
        _matchState.value = ScanMatchUiState.AwaitingCapture
        _terminal.value = ScanTerminalState.Idle
    }

    // ------------------------------------------------------------------
    // Capture.
    // ------------------------------------------------------------------

    /** Shutter press for the FRONT; legal only while the session awaits FRONT. */
    fun beginFrontCapture(): Boolean = beginCapture(FingerprintSide.FRONT)

    /** Shutter press for the BACK; legal only while the session awaits BACK. */
    fun beginBackCapture(): Boolean = beginCapture(FingerprintSide.BACK)

    private fun beginCapture(side: FingerprintSide): Boolean {
        val expected = if (side == FingerprintSide.FRONT) {
            dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT
        } else {
            dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_BACK
        }
        if (captureSession.state != expected) return false
        return try {
            attemptFor(side).beginAttempt()
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun attemptFor(side: FingerprintSide): CaptureAttemptController =
        if (side == FingerprintSide.FRONT) frontAttempt else backAttempt

    /** Camera bytes for the FRONT; stale deliveries are structurally inert. */
    fun deliverFrontJpeg(jpegBytes: ByteArray) {
        if (captureSession.state != dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT) return
        val generation = deliveryGeneration
        viewModelScope.launch {
            acceptProcessed(jpegBytes, frontProcessor, FingerprintSide.FRONT, generation)
        }
    }

    /** Camera bytes for the BACK; stale deliveries are structurally inert. */
    fun deliverBackJpeg(jpegBytes: ByteArray) {
        if (captureSession.state != dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_BACK) return
        val generation = deliveryGeneration
        viewModelScope.launch {
            val accepted = acceptProcessed(jpegBytes, backProcessor, FingerprintSide.BACK, generation)
            if (accepted && captureSession.readyForMatching) evaluateMatch()
        }
    }

    /**
     * FIX-STATE-10: the ONLY authority for whether a resumed pipeline result
     * may mutate anything. Checked BEFORE every mutation - a stale outcome is
     * dropped on the floor without touching queuedStill, the session, the
     * attempt controllers, or match state.
     */
    private fun deliveryIsCurrent(
        generation: Long,
        side: FingerprintSide,
    ): Boolean {
        if (generation != deliveryGeneration) return false
        val expected = if (side == FingerprintSide.FRONT) {
            dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT
        } else {
            dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_BACK
        }
        if (captureSession.state != expected) return false
        return attemptFor(side).hasActiveAttempt
    }

    /**
     * FIX-STATE-01: a delivered still ALWAYS terminates its attempt -
     * Accepted, Rejected, or Failed - even when the ORB processor throws;
     * cancellation completes the lifecycle without publishing any result.
     */
    private suspend fun acceptProcessed(
        jpegBytes: ByteArray,
        processor: dev.hryshyn.remanence.capture.StillProcessor,
        side: FingerprintSide,
        generation: Long,
    ): Boolean {
        val attempt = attemptFor(side)
        if (!attempt.markProcessing()) return false
        return try {
            val processed =
                withContext(cpuDispatcher) { processor.process(jpegBytes) }

            // FIX-STATE-10: BEFORE ANY MUTATION - a reset/new session during
            // processing makes this whole outcome disappear without a trace.
            if (!deliveryIsCurrent(generation, side)) return false

            when (processed) {
                is ProcessedStill.Rejected -> {
                    attempt.reject(processed.reasons)
                    false
                }
                is ProcessedStill.Accepted -> {
                    queuedStill.set(
                        ScannedSide(processed.profileId, side, processed.serializedBytes),
                    )
                    if (side == FingerprintSide.FRONT) captureSession.captureFront()
                    else captureSession.captureBack()
                    attempt.accept()
                    true
                }
            }
        } catch (cancelled: CancellationException) {
            // Teardown touches state only while this delivery still owns it.
            if (deliveryIsCurrent(generation, side)) {
                queuedStill.set(null)
                attempt.cancelActiveAttempt()
            }
            throw cancelled
        } catch (failure: Exception) {
            if (deliveryIsCurrent(generation, side)) {
                queuedStill.set(null)
                attempt.fail(failure.message ?: "capture failed")
            }
            false
        }
    }

    /** Module-internal view of THE delivery generation (tests only). */
    internal fun deliveryGenerationForDiagnostics(): Long = deliveryGeneration

    /** Explicit Retake after Rejected/Failed on either side. */
    fun retakeFront() {
        runCatchingRetake(frontAttempt)
    }

    fun retakeBack() {
        runCatchingRetake(backAttempt)
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
        matchGeneration++
        deliveryGeneration++
        // FIX-STATE-05: an in-flight delivery/match can never write into the
        // fresh flow - active attempts are cancelled cleanly and stale
        // terminal callbacks are structurally inert afterwards.
        frontAttempt.restartCapture()
        backAttempt.restartCapture()
        queuedStill.set(null)
        captureSession.reset()
        _matchState.value = ScanMatchUiState.AwaitingCapture
    }

    // ------------------------------------------------------------------
    // Matching over the encrypted local index.
    // ------------------------------------------------------------------

    private suspend fun buildRoomCandidateIndex(): List<IndexedCandidate> {
        val rows = database.recognitionFingerprintDao().getAll()
        return rows.groupBy { it.capsuleId }.mapNotNull { (capsuleId, sides) ->
            val preferredOrigin = sides.any {
                it.origin == FingerprintOrigin.RECIPIENT && it.preferred
            }
            fun pick(side: DbFingerprintSide) =
                sides.firstOrNull { it.side == side && it.origin == FingerprintOrigin.RECIPIENT }
                    ?: sides.firstOrNull { it.side == side }
            val frontRow = pick(DbFingerprintSide.FRONT) ?: return@mapNotNull null
            val backRow = pick(DbFingerprintSide.BACK) ?: return@mapNotNull null
            val front = try {
                FingerprintCodec.parse(persistence.decrypt(frontRow.fingerprintId))
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val back = try {
                FingerprintCodec.parse(persistence.decrypt(backRow.fingerprintId))
            } catch (_: Exception) {
                return@mapNotNull null
            }
            IndexedCandidate(
                capsuleId = UUID.fromString(capsuleId),
                front = front,
                back = back,
                recipientPreferred = preferredOrigin,
            )
        }.distinctBy { it.capsuleId }
    }

    private fun evaluateMatch() {
        val sessionFront = captureSession.front ?: return
        val sessionBack = captureSession.back ?: return
        _matchState.value = ScanMatchUiState.Matching
        val generation = ++matchGeneration
        viewModelScope.launch {
            try {
                val engine = LocalMatchEngine(
                    profile = profile,
                    verifier = { capsuleId -> verifyCapsuleCrypto(capsuleId) },
                    grantIssuer = { capsuleId -> issueGrant(capsuleId) },
                )
                val result = engine.run(
                    queryFront = FingerprintCodec.parse(sessionFront.serializedBytes),
                    queryBack = FingerprintCodec.parse(sessionBack.serializedBytes),
                    candidates = candidateIndexProvider?.invoke() ?: buildRoomCandidateIndex(),
                )
                if (generation == matchGeneration) applyResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == matchGeneration) {
                    _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 1)
                }
            }
        }
    }

    private suspend fun applyResult(result: ScanFlowResult): Unit = when (result) {
        is ScanFlowResult.Granted -> onVerifiedGrant(result)

        is ScanFlowResult.Ambiguous -> {
            val hints: Map<String, DecryptedHint> = result.rows.mapNotNull { (id, _) ->
                try {
                    decryptHint(id.toString())
                } catch (_: Exception) {
                    null
                }
            }.associateBy(DecryptedHint::candidateId)
            _matchState.value = ScanMatchUiState.Chooser(
                result.rows.map { (id, score) ->
                    val hint = hints[id.toString()]
                    ChooserRow(
                        candidateId = id.toString(),
                        compositeScore = score,
                        senderHandleSnapshot = hint?.senderHandleSnapshot,
                        createdAtEpochSeconds = hint?.createdAtEpochSeconds,
                        placeLabel = hint?.placeLabel,
                    )
                },
            )
        }

        ScanFlowResult.RecaptureRequired ->
            _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 1)
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
        if (chooser.rows.none { it.candidateId == candidateId }) return
        val generation = ++matchGeneration
        viewModelScope.launch {
            val id = UUID.fromString(candidateId)
            if (!verifyCapsuleCrypto(id)) {
                if (generation == matchGeneration) {
                    _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 2)
                }
                return@launch
            }
            val grantId = issueGrant(id)
            if (grantId == null) {
                if (generation == matchGeneration) {
                    _matchState.value = ScanMatchUiState.RecaptureGuidance(failedAttempts = 2)
                }
                return@launch
            }
            if (generation != matchGeneration) return@launch
            applyResult(
                ScanFlowResult.Granted(
                    capsuleId = id,
                    origin = CandidateOrigin.RECIPIENT_PREFERRED,
                    grantId = grantId,
                    compositeScore = Double.NaN,
                ),
            )
        }
    }

    private suspend fun onVerifiedGrant(result: ScanFlowResult.Granted) {
        _terminal.value = ScanTerminalState.Granted(
            grantId = result.grantId,
            capsuleId = result.capsuleId.toString(),
            viaSenderFallback = result.origin == CandidateOrigin.SENDER_FALLBACK,
        )
        _matchState.value = ScanMatchUiState.Accepted(
            candidateId = result.capsuleId.toString(),
            viaSenderFallback = result.origin == CandidateOrigin.SENDER_FALLBACK,
        )
        persistVerifiedRecipientBaseline(result.capsuleId.toString())
    }

    private fun issueGrant(capsuleId: UUID): String? =
        grants.issue(capsuleId).grantId.toString()

    /** Module-internal view of the live grant: capsule ID only while valid. */
    internal fun liveGrantCapsuleId(grantId: String): String? {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return null
        return grants.resolveCapsuleId(uuid)?.toString()
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
                    is dev.hryshyn.remanence.identity.SenderKeyResolution.Untrusted -> return false
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
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun persistVerifiedRecipientBaseline(capsuleId: String) {
        val front = captureSession.front ?: return
        val back = captureSession.back ?: return
        try {
            RecipientBaselineCreator(persistence).createAfterVerifiedReceipt(
                capsuleId = capsuleId,
                front = ReceivedSideCapture(front.profileId, DbFingerprintSide.FRONT, front.serializedBytes),
                back = ReceivedSideCapture(back.profileId, DbFingerprintSide.BACK, back.serializedBytes),
            )
        } catch (_: dev.hryshyn.remanence.core.data.fingerprints.ImmutableBaselineException) {
            // The initial recipient baseline is immutable; later scans keep it.
        }
    }

    // ------------------------------------------------------------------
    // Minimal decrypted hints for the ambiguity chooser.
    // ------------------------------------------------------------------

    private suspend fun decryptHint(candidateId: String): DecryptedHint {
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
        return DecryptedHint(
            candidateId = candidateId,
            senderHandleSnapshot = content.senderHandleSnapshot,
            createdAtEpochSeconds = content.createdAtEpochSeconds,
            placeLabel = content.placeLabel,
        )
    }

    /** Mirrors SameAccountCapsulePublisher's deterministic recognition blob id. */
    private fun deriveRecognitionBlobId(capsuleId: UUID): BlobId {
        val base = CapsuleId(capsuleId).toProtoBytes().toByteArray()
        base[0] = RECOGNITION_BLOB_BYTE.toByte()
        return BlobId.fromProtoBytes(com.google.protobuf.ByteString.copyFrom(base))
    }

    override fun onCleared() {
        captureSession.consume()
        super.onCleared()
    }

    companion object {
        const val RECOGNITION_BLOB_BYTE = 0x01
    }
}
