package app.postmark.memory.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.postmark.memory.capture.ProcessedStill
import app.postmark.memory.capture.SingleStillCaptureShell
import app.postmark.memory.create.RealStillFingerprintProcessor
import app.postmark.memory.scan.ScanCaptureSession
import app.postmark.memory.scan.ScannedSide
import app.postmark.memory.scan.ScanSideExtractor
import app.postmark.memory.ui.create.SenderIdentitySnapshot
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import postmark.core.crypto.CapsuleAcceptanceGate
import postmark.core.crypto.CapsuleAcceptanceInput
import postmark.core.crypto.CapsuleAcceptanceResult
import postmark.core.crypto.CapsuleKeysetParser
import postmark.core.crypto.RecipientEnvelopeCryptor
import postmark.core.data.db.FingerprintSide as DbFingerprintSide
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.ReceivedSideCapture
import postmark.core.data.fingerprints.RecipientBaselineCreator
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.data.outbox.OutboxArtifactKind
import postmark.core.model.BlobId
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.UserId
import postmark.core.recognition.CandidateOrigin
import postmark.core.recognition.FingerprintCodec
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.IndexedCandidate
import postmark.core.recognition.LocalMatchEngine
import postmark.core.recognition.QualityReason
import postmark.core.recognition.RecognitionProfile
import postmark.core.recognition.ScanFlowResult
import postmark.core.recognition.ScanGrantManager

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
    private val database: PostmarkLocalDatabase,
    private val profile: RecognitionProfile,
    private val identityProvider: suspend () -> SenderIdentitySnapshot?,
    private val signingPublicExports: suspend () -> ByteArray?,
    grantsClockMillis: () -> Long,
    frontProcessor: app.postmark.memory.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.FRONT),
    backProcessor: app.postmark.memory.capture.StillProcessor =
        RealStillFingerprintProcessor(profile, FingerprintSide.BACK),
    candidateIndexProvider: (suspend () -> List<IndexedCandidate>)? = null,
) : ViewModel() {

    val captureSession = ScanCaptureSession(ScanSideExtractor { side ->
        requireNotNull(queuedStill.getAndSet(null)) { "no processed still queued for $side" }
    })

    private val _qualityRejection = MutableStateFlow<Set<QualityReason>>(emptySet())
    val qualityRejection: StateFlow<Set<QualityReason>> = _qualityRejection.asStateFlow()

    private val _matchState = MutableStateFlow<ScanMatchUiState>(ScanMatchUiState.AwaitingCapture)
    val matchState: StateFlow<ScanMatchUiState> = _matchState.asStateFlow()

    private val _terminal = MutableStateFlow<ScanTerminalState>(ScanTerminalState.Idle)
    val terminal: StateFlow<ScanTerminalState> = _terminal.asStateFlow()

    private val grants = ScanGrantManager(clockMillis = grantsClockMillis)

    private val queuedStill = AtomicReference<ScannedSide?>()
    private val frontProcessor = frontProcessor
    private val backProcessor = backProcessor
    private val candidateIndexProvider = candidateIndexProvider

    /**
     * FIX-REVIEW-01: generation guard so a stale asynchronous match result can
     * never overwrite a newer capture flow started by reset/re-entry.
     */
    private var matchGeneration: Int = 0

    // ------------------------------------------------------------------
    // Capture.
    // ------------------------------------------------------------------

    fun onFrontJpeg(jpegBytes: ByteArray, shell: SingleStillCaptureShell) {
        viewModelScope.launch {
            acceptProcessed(frontProcessor.process(jpegBytes), FingerprintSide.FRONT, shell)
        }
    }

    fun onBackJpeg(jpegBytes: ByteArray, shell: SingleStillCaptureShell) {
        viewModelScope.launch {
            acceptProcessed(backProcessor.process(jpegBytes), FingerprintSide.BACK, shell)
            if (captureSession.readyForMatching) evaluateMatch()
        }
    }

    private suspend fun acceptProcessed(
        outcome: ProcessedStill,
        side: FingerprintSide,
        shell: SingleStillCaptureShell,
    ) {
        when (outcome) {
            is ProcessedStill.Accepted -> {
                shell.onStillDelivered()
                _qualityRejection.value = emptySet()
                queuedStill.set(
                    ScannedSide(outcome.profileId, side, outcome.serializedBytes),
                )
                if (side == FingerprintSide.FRONT) captureSession.captureFront()
                else captureSession.captureBack()
            }
            is ProcessedStill.Rejected -> {
                shell.onStillDelivered()
                _qualityRejection.value = outcome.reasons
            }
        }
    }

    /**
     * FIX-REVIEW-01: the explicit user restart returns the WHOLE flow to the
     * FRONT capture state; any in-flight evaluation is discarded so a stale
     * result can never overwrite the fresh capture sequence.
     */
    fun resetSession() {
        matchGeneration++
        captureSession.reset()
        _qualityRejection.value = emptySet()
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
        grants.issue(capsuleId)?.toString()

    // ------------------------------------------------------------------
    // THE verification gate: no grant without it, ever.
    // ------------------------------------------------------------------

    private suspend fun verifyCapsuleCrypto(capsuleId: UUID): Boolean {
        val identity = identityProvider() ?: return false
        val row = database.outboxCapsuleDao().getByCapsuleId(capsuleId.toString()) ?: return false
        val blobs = database.outboxBlobDao().getAllByCapsuleId(capsuleId.toString())
        val statementPath = row.publishStatementPath ?: return false
        val signaturePath = row.publishStatementSignaturePath ?: return false
        val envelopePath = row.envelopePath ?: return false

        return try {
            val statementBytes = File(statementPath).readBytes()
            val signatureBytes = File(signaturePath).readBytes()
            val envelopeBytes = File(envelopePath).readBytes()
            val publicSigningKeyset = signingPublicExports() ?: return false
            val ownUser = UserId(UUID.fromString(identity.userId))

            val openedEnvelope = RecipientEnvelopeCryptor().open(
                recipientEncryptionPrivateKeyset = identity.encryptionPrivateHandle,
                context = postmark.core.model.RecipientEnvelopeContextInput(
                    CapsuleId(capsuleId),
                    ownUser,
                    ownUser,
                    KeyBundleId(UUID.fromString(row.recipientKeyBundleId)),
                ),
                ciphertext = envelopeBytes,
            )

            val gateInput = CapsuleAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleId),
                authenticatedUserId = ownUser,
                senderVerifyingKeyset =
                    TinkProtoKeysetFormat.parseKeysetWithoutSecret(publicSigningKeyset),
                expectedSenderKeyBundleId =
                    KeyBundleId(UUID.fromString(row.recipientKeyBundleId)),
                envelopePlaintextBytes = openedEnvelope,
                statementBytes = statementBytes,
                signature = signatureBytes,
                deliveredBlobs = blobs.map { blob ->
                    val file = File(blob.localCiphertextPath)
                    postmark.core.crypto.DeliveredBlob(
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
        } catch (_: postmark.core.data.fingerprints.ImmutableBaselineException) {
            // The initial recipient baseline is immutable; later scans keep it.
        }
    }

    // ------------------------------------------------------------------
    // Minimal decrypted hints for the ambiguity chooser.
    // ------------------------------------------------------------------

    private suspend fun decryptHint(candidateId: String): DecryptedHint {
        val identity = identityProvider() ?: throw IllegalStateException("no identity")
        val capsuleUuid = UUID.fromString(candidateId)
        val row = database.outboxCapsuleDao().getByCapsuleId(candidateId)
            ?: throw IllegalStateException("unknown capsule")
        val blobs = database.outboxBlobDao().getAllByCapsuleId(candidateId)
        val recognitionBlob = blobs.first { it.kind == OutboxArtifactKind.RECOGNITION_MANIFEST.name }
        val manifestCiphertext = File(recognitionBlob.localCiphertextPath).readBytes()
        val envelopeCiphertext = File(requireNotNull(row.envelopePath)).readBytes()

        val ownUser = UserId(UUID.fromString(identity.userId))
        val bundleId = KeyBundleId(UUID.fromString(row.recipientKeyBundleId))
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            postmark.core.model.RecipientEnvelopeContextInput(CapsuleId(capsuleUuid), ownUser, ownUser, bundleId),
            envelopeCiphertext,
        )
        val capsuleKeyset = CapsuleKeysetParser().parseExactAes256GcmTink(
            app.postmark.protocol.v1.RecipientEnvelopePlaintext.parseFrom(opened)
                .capsuleAeadKeyset.toByteArray(),
        )

        val content = postmark.core.crypto.RecognitionManifestCodec().decryptAndParse(
            capsuleKeyset,
            postmark.core.crypto.RecognitionManifestCodec.RoutingContext(
                CapsuleId(capsuleUuid),
                deriveRecognitionBlobId(capsuleUuid),
                ownUser,
                ownUser,
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
