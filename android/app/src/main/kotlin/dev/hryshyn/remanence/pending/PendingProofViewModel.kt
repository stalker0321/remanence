package dev.hryshyn.remanence.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.PendingClaimChallenge
import dev.hryshyn.remanence.core.crypto.PendingClaimIntent
import dev.hryshyn.remanence.core.crypto.PendingPublishVerifier
import dev.hryshyn.remanence.core.data.network.ClaimOptionsResult
import dev.hryshyn.remanence.core.data.network.ContinueResult
import dev.hryshyn.remanence.core.data.network.GiftLookupFailure
import dev.hryshyn.remanence.core.data.network.VerificationResult
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.GeneralSecurityException
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Narrow seams with real callers (container adapters wrap the frozen clients). */
fun interface ProofContinuePort {
    suspend fun continueFrom(reference: String, token: String): ContinueResult
}

/** Two-method seam: request an attempt, then check the code. Plain interface. */
interface ProofVerifyPort {
    suspend fun request(
        deliveryId: String,
        destination: String,
        smsConsent: Boolean,
        nowSeconds: Long,
        idempotencyKey: String,
        token: String,
    ): VerificationResult

    suspend fun check(deliveryId: String, attemptId: String, code: String, token: String): VerificationResult
}

fun interface ProofOptionsPort {
    suspend fun options(deliveryId: String, token: String): ClaimOptionsResult
}

fun interface ProofClaimPort {
    suspend fun submit(
        deliveryId: String,
        intentB64: String,
        intentSignature69B64: String,
        idempotencyKey: String,
        token: String,
    ): dev.hryshyn.remanence.core.data.network.ClaimSubmitResult
}

/** Authenticated account material for the proof/claim flow. */
data class ProofAccount(
    val userId: String,
    val bundleId: String,
)

/**
 * T186 M7c: receiving proof + claim flow (frozen R7/R8/R9/R10/R11, exact §8).
 *
 * lookup → R7 continue → S signature verified with the directory-backed
 * sender key → R8 attempt → R9 code → VERIFIED → R10 options → open
 * challenge nonce → build + sign intent → R11 submit → trusted envelope
 * acceptance (partial until the service key is distributed — typed,
 * never promoted, never admission). Nothing is faked, nothing is
 * auto-opened; the gift opens later through ordinary sync/scan.
 */
class PendingProofViewModel(
    private val continuePort: ProofContinuePort,
    private val verifyPort: ProofVerifyPort,
    private val optionsPort: ProofOptionsPort,
    private val claimPort: ProofClaimPort? = null,
    /**
     * T186 M10: held lease gate — EVERY network call captures a fresh
     * lease, sends with its token, and rechecks owner/incarnation
     * liveness after the await. No bare token strings cross calls.
     */
    private val sessionGate: PendingRetirementSessionGate,
    private val accountProvider: suspend () -> ProofAccount?,
    private val signingKeyProvider: suspend () -> KeysetHandle?,
    private val hpkeKeyProvider: suspend () -> KeysetHandle?,
    /**
     * Sender signing public keyset bytes by the immutable pair
     * (sender_user_id, bundle_id), directory-backed — the server holds
     * no monopoly. The provider must refuse when the directory bundle
     * owner differs from the statement sender (OWNER_MISMATCH, fail
     * closed). Null/unfetchable fails S verification closed; the flow
     * never proceeds on structure alone.
     */
    private val senderKeyProvider: suspend (senderUserId: String, bundleId: String) -> ByteArray? = { _, _ -> null },
    /**
     * Own bundle public keyset bytes (directory-fetched — the exact stored
     * bytes the server hashed for the recipient digest check). Null fails
     * envelope acceptance closed.
     */
    private val ownBundleKeyProvider: suspend () -> ByteArray? = { null },
    /**
     * Auth session id for the intent (fenced holder in production, null
     * until a live owner-bound session exists). Null keeps the flow at a
     * verified, signed, ready intent (BLOCKED state) instead of inventing
     * an id.
     */
    private val sessionIdProvider: suspend () -> String? = { null },
    /**
     * T186 M12b: root-selected service pin (SAME out-of-band pair the
     * sender uses for S — never an R-carried key). When present AND its
     * keyset parses, the service signature is verified AND the pin is
     * required for `Accepted`; otherwise the typed `PartiallyAccepted`
     * path stands (production today: null ⇒ always partial, never
     * admission). A malformed pin fails closed to partial, never to
     * `Accepted`.
     */
    private val servicePinProvider: () -> PendingServicePin? = { null },
    /**
     * T186 M14d-claim: admitted-row sink, written on `Accepted` only.
     * Null keeps the pre-row behavior (unit surfaces); production
     * passes the durable admitted store. A failed persist does NOT
     * fail the claim (the server holds the durable truth and the
     * feed sync re-admits the same row) — Claimed stays truthful.
     */
    private val admittedStore: PendingAdmittedGiftStore? = null,
    /**
     * T186 M14d-scan: kind-1 fetch for reception-time FRONT staging
     * (best-effort — null skips staging and the row still admits).
     * Production passes the owned-material blob fetch.
     */
    private val recognitionBlobPort: PendingRecognitionStage.BlobFetchPort? = null,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    scope: CoroutineScope? = null,
) : ViewModel() {

    sealed interface ProofState {
        data object Idle : ProofState
        data object Continuing : ProofState
        data class SyncReady(val capsuleId: String) : ProofState
        data class ProofRequired(
            val deliveryId: String,
            val channel: String,
            val claimExpiresAt: Long,
            val statementB64: String,
        ) : ProofState

        data object RequestingAttempt : ProofState

        data class CodeRequired(
            val deliveryId: String,
            val attemptId: String,
            val cooldownSeconds: Long?,
        ) : ProofState

        data object CheckingCode : ProofState
        data object LoadingOptions : ProofState

        data class IntentReady(
            val deliveryId: String,
            val nonceB64: String,
        ) : ProofState

        data object Claiming : ProofState

        data class Claimed(
            val deliveryId: String,
            /** False until the service key is distributed (partial path). */
            val serviceVerified: Boolean,
        ) : ProofState

        data class Failed(val message: PendingProofMessage) : ProofState
    }

    enum class PendingProofMessage {
        NETWORK,
        NOT_FOUND,
        SESSION_CHANGED,
        EXPIRED,
        CONFLICT,
        BLOCKED,
        GENERIC,
    }

    private val launchScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow<ProofState>(ProofState.Idle)
    val state: StateFlow<ProofState> = _state.asStateFlow()

    private val _destination = MutableStateFlow("")
    val destination: StateFlow<String> = _destination.asStateFlow()

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    /**
     * T186 M10: explicit affirmative SMS consent (phone channels only).
     * Never derived from the channel — the user toggles it, timestamped
     * at request time. Email flows always send no consent.
     */
    private val _smsConsent = MutableStateFlow(false)
    val smsConsent: StateFlow<Boolean> = _smsConsent.asStateFlow()

    fun onSmsConsentChange(affirmed: Boolean) {
        _smsConsent.value = affirmed
    }

    /**
     * T186 M10: retry after a wrong code / network failure WITHOUT losing
     * the gift or the attempt — restores the previous step in place.
     */
    fun retry() {
        if (_state.value !is ProofState.Failed) return
        val currentProof = proof ?: return
        val currentAttempt = attempt
        _state.value = if (currentAttempt != null) {
            ProofState.CodeRequired(currentProof.deliveryId, currentAttempt, null)
        } else {
            ProofState.ProofRequired(
                currentProof.deliveryId, currentProof.channel,
                currentProof.claimExpiresAt, currentProof.publishStatementB64,
            )
        }
    }

    fun onDestinationChange(value: String) {
        _destination.value = value
    }

    fun onCodeChange(value: String) {
        _code.value = value
    }

    private var proof: ContinueResult.Proof? = null
    private var attempt: String? = null
    private var statementBytes: ByteArray? = null
    private var senderKeyBytesStored: ByteArray? = null
    private var senderSigStored: ByteArray? = null
    private var builtIntent: ByteArray? = null
    private var builtIntentSig: ByteArray? = null
    private var builtOpId: String? = null
    private var builtNonce: ByteArray? = null
    private var builtChallengeId: String? = null

    private fun uuidRest(bytes: com.google.protobuf.ByteString): String {
        val raw = bytes.toByteArray()
        require(raw.size == 16) { "uuid must be 16 bytes" }
        val buffer = java.nio.ByteBuffer.wrap(raw)
        return java.util.UUID(buffer.long, buffer.long).toString()
    }

    fun start(reference: String) {
        if (_state.value !is ProofState.Idle) return
        _state.value = ProofState.Continuing
        launchScope.launch {
            _state.value = try {
                continueFlow(reference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProofState.Failed(PendingProofMessage.GENERIC)
            }
        }
    }

    /** Captures a fresh lease + token + owner for exactly one call. */
    private fun creds(): ProofCreds? {
        val lease = try {
            sessionGate.capture()
        } catch (_: Exception) {
            return null
        } ?: return null
        val token = try {
            sessionGate.accessTokenFor(lease)
        } catch (_: Exception) {
            return null
        }
        if (token.isNullOrBlank()) return null
        return ProofCreds(lease, token, lease.ownerUserId.toRestString())
    }

    /** Post-await fence: same owner AND live incarnation, or the call dies. */
    private fun credsLive(creds: ProofCreds): Boolean = try {
        sessionGate.isLive(creds.lease) &&
            creds.lease.ownerUserId.toRestString() == creds.ownerId
    } catch (_: Exception) {
        false
    }

    private data class ProofCreds(
        val lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        val token: String,
        val ownerId: String,
    )

    private suspend fun continueFlow(reference: String): ProofState {
        val creds = creds() ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        val result = try {
            continuePort.continueFrom(reference, creds.token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProofState.Failed(PendingProofMessage.NETWORK)
        }
        if (!credsLive(creds)) return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        return when (result) {
            is ContinueResult.Sync -> ProofState.SyncReady(result.capsuleId)
            is ContinueResult.Proof -> {
                // Full S verification with the REAL sender key from the
                // directory (no server monopoly): structure + canonical +
                // Ed25519 over the frozen domain input. Anything missing
                // fails closed — never structure-only acceptance.
                val sBytes = try {
                    Base64.getDecoder().decode(result.publishStatementB64)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return ProofState.Failed(PendingProofMessage.GENERIC)
                val statement = try {
                    PendingPublishVerifier.verifyStructure(sBytes)
                } catch (_: GeneralSecurityException) {
                    return ProofState.Failed(PendingProofMessage.GENERIC)
                } catch (_: Exception) {
                    return ProofState.Failed(PendingProofMessage.GENERIC)
                }
                val senderKeyBytes = try {
                    senderKeyProvider(
                        uuidRest(statement.senderUserId),
                        uuidRest(statement.senderKeyBundleId),
                    )
                } catch (_: Exception) {
                    null
                } ?: return ProofState.Failed(PendingProofMessage.GENERIC)
                val senderSig = try {
                    Base64.getDecoder().decode(result.publishSignature69B64)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return ProofState.Failed(PendingProofMessage.GENERIC)
                try {
                    PendingPublishVerifier.verifyPublish(senderKeyBytes, sBytes, senderSig)
                } catch (_: GeneralSecurityException) {
                    return ProofState.Failed(PendingProofMessage.GENERIC)
                } catch (_: Exception) {
                    return ProofState.Failed(PendingProofMessage.GENERIC)
                }
                proof = result
                statementBytes = sBytes
                senderKeyBytesStored = senderKeyBytes
                senderSigStored = senderSig
                ProofState.ProofRequired(result.deliveryId, result.channel, result.claimExpiresAt, result.publishStatementB64)
            }
            is ContinueResult.Failure -> ProofState.Failed(mapLookupFailure(result.reason))
        }
    }

    fun requestAttempt() {
        val current = proof ?: return
        if (_state.value !is ProofState.ProofRequired) return
        if (_destination.value.isBlank()) return
        _state.value = ProofState.RequestingAttempt
        launchScope.launch {
            _state.value = try {
                val creds = creds()
                if (creds == null) {
                    ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                } else {
                    // Entry owner fence (same as R10/R11): the local
                    // account must be the lease owner before any R8 work.
                    val account = try {
                        accountProvider()
                    } catch (_: Exception) {
                        null
                    }
                    if (account == null || account.userId.isBlank() ||
                        account.userId != creds.ownerId
                    ) {
                        ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                    } else {
                        val result = verifyPort.request(
                            current.deliveryId, _destination.value.trim(), _smsConsent.value,
                            clockSeconds(), java.util.UUID.randomUUID().toString(), creds.token,
                        )
                        if (!credsLive(creds)) {
                            ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                        } else when (result) {
                            is VerificationResult.Attempt -> {
                                attempt = result.attemptId
                                ProofState.CodeRequired(current.deliveryId, result.attemptId, result.resendCooldownSeconds)
                            }
                            is VerificationResult.Verified -> verifiedState(current.deliveryId, result.attemptId)
                            is VerificationResult.Failure -> ProofState.Failed(mapLookupFailure(result.reason))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProofState.Failed(PendingProofMessage.GENERIC)
            }
        }
    }

    fun checkCode() {
        val current = proof ?: return
        val attemptId = attempt ?: return
        if (_code.value.isBlank()) return
        _state.value = ProofState.CheckingCode
        launchScope.launch {
            _state.value = try {
                val creds = creds()
                if (creds == null) {
                    ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                } else {
                    // Entry owner fence (same as R10/R11): the local
                    // account must be the lease owner before any R9 work.
                    val account = try {
                        accountProvider()
                    } catch (_: Exception) {
                        null
                    }
                    if (account == null || account.userId.isBlank() ||
                        account.userId != creds.ownerId
                    ) {
                        ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                    } else {
                        val result = verifyPort.check(current.deliveryId, attemptId, _code.value.trim(), creds.token)
                        if (!credsLive(creds)) {
                            ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                        } else when (result) {
                            is VerificationResult.Verified -> verifiedState(current.deliveryId, result.attemptId)
                            is VerificationResult.Attempt -> {
                                attempt = result.attemptId
                                ProofState.CodeRequired(current.deliveryId, result.attemptId, result.resendCooldownSeconds)
                            }
                            is VerificationResult.Failure -> ProofState.Failed(mapLookupFailure(result.reason))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProofState.Failed(PendingProofMessage.GENERIC)
            }
        }
    }

    /**
     * Submits the built claim intent (R11) and runs trusted envelope
     * acceptance. The service signature is still undistributed, so success
     * lands explicitly PARTIAL (never production `Accepted`, never
     * admission, never auto-open — the gift opens later via sync/scan).
     */
    fun submitClaim() {
        val intent = builtIntent
        val intentSig = builtIntentSig
        val opId = builtOpId
        val current = proof
        if (_state.value !is ProofState.IntentReady || intent == null ||
            intentSig == null || opId == null || current == null
        ) {
            return
        }
        _state.value = ProofState.Claiming
        launchScope.launch {
            _state.value = try {
                submitClaimFlow(current, intent, intentSig, opId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProofState.Failed(PendingProofMessage.GENERIC)
            }
        }
    }

    private suspend fun submitClaimFlow(
        current: ContinueResult.Proof,
        intent: ByteArray,
        intentSig: ByteArray,
        opId: String,
    ): ProofState {
        val port = claimPort
            ?: return ProofState.Failed(PendingProofMessage.BLOCKED)
        val creds = creds() ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        if (account.userId.isBlank() || account.bundleId.isBlank() ||
            account.userId != creds.ownerId
        ) {
            // Owner fence at entry: the account must be the lease owner.
            return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        }
        val submitted = try {
            port.submit(
                current.deliveryId,
                Base64.getEncoder().encodeToString(intent),
                Base64.getEncoder().encodeToString(intentSig),
                opId, creds.token,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProofState.Failed(PendingProofMessage.NETWORK)
        }
        if (!credsLive(creds)) return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        if (submitted !is dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed) {
            val failure = submitted as dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Failure
            return ProofState.Failed(mapClaimFailure(failure.reason))
        }
        // Trusted acceptance of the server response. The SAME
        // out-of-band selected pin the sender uses for S is the only
        // trust root here (never an R-carried key): both the handle and
        // the authorized UUID come from that one configured pair, so a
        // handle that fails to parse can never yield `Accepted`.
        // Null/malformed pin ⇒ typed `PartiallyAccepted` (never
        // production `Accepted`, never admission).
        val sBytes = statementBytes
            ?: return ProofState.Failed(PendingProofMessage.GENERIC)
        val senderKeyBytes = senderKeyBytesStored
            ?: return ProofState.Failed(PendingProofMessage.GENERIC)
        val hpke = try {
            hpkeKeyProvider()
        } catch (_: Exception) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        val ownPublic = try {
            ownBundleKeyProvider()
        } catch (_: Exception) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.GENERIC)
        val selectedPin = try {
            servicePinProvider()
        } catch (_: Exception) {
            null
        }
        val serviceVerify = selectedPin?.let { pin ->
            try {
                val keysetBytes = Base64.getDecoder().decode(pin.publicKeysetB64)
                com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(keysetBytes)
            } catch (_: Exception) {
                null
            }
        }
        // Held-lease fence AFTER the key-provider awaits: an
        // owner/incarnation death across hpke/own-bundle fetch must not
        // admit, persist, or promote below (same SESSION_CHANGED wall as
        // every R-call await).
        if (!credsLive(creds)) return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        return try {
            val binding = Base64.getDecoder().decode(submitted.claimBindingB64)
            val envelope = Base64.getDecoder().decode(submitted.envelopeB64)
            val envelopeSha = unhexStrict(submitted.envelopeSha256Hex)
            val claimSig = Base64.getDecoder().decode(submitted.claimSignature69B64)
            val senderVerify = com.google.crypto.tink.TinkProtoKeysetFormat
                .parseKeysetWithoutSecret(senderKeyBytes)
            val senderSig = senderSigStored
                ?: return ProofState.Failed(PendingProofMessage.GENERIC)
            // Caller-owned secret copies from accept() are dropped here
            // (no grant/persist on this path — Partial never yields a
            // grant; opening depends on future sync/scan admission).
            // Zero them in finally; Java/protobuf immutable internals
            // are out of scope for this caller-copy contract.
            val acceptance = dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.accept(
                sealedEnvelope = envelope,
                expectedEnvelopeSha256 = envelopeSha,
                statementBytes = sBytes,
                claimBytes = binding,
                senderSignature = senderSig,
                claimSignature = claimSig,
                recipientPrivateHandle = hpke,
                senderVerifyHandle = senderVerify,
                serviceVerifyHandle = serviceVerify,
                expected = dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.LocalExpectation(
                    recipientUserId = account.userId,
                    recipientKeyBundleId = account.bundleId,
                    recipientPublicKeyset = ownPublic,
                    nowEpochSeconds = clockSeconds(),
                    // Bound to the SAME selected pin the handle parsed
                    // from; null (or an unparsable pin) stays partial.
                    authorizedServiceKeyId = serviceVerify?.let { selectedPin?.deploymentUuid },
                ),
            )
            try {
                when (acceptance) {
                    is dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.EnvelopeAcceptance.Accepted -> {
                        // T186 M14d-claim: trust is established — persist
                        // the admitted row NOW (metadata + sealed bytes
                        // only) so the gift survives without depending on
                        // a later feed sync. Partial never writes.
                        //
                        // Row ids come from the VERIFIED S/R bytes (never
                        // the unsigned Continue/submit echoes): S.capsule/
                        // target and R.claim must name this exact gift.
                        // accept() only proves S/R internally consistent,
                        // so an otherwise-valid response for a sibling gift
                        // (consistent with its own signed pair) would
                        // otherwise poison this row. Same substitution
                        // negative as the ingest gate.
                        val verified = try {
                            verifiedRowIds(sBytes, binding)
                        } catch (_: Exception) {
                            return ProofState.Failed(PendingProofMessage.GENERIC)
                        }
                        if (verified.capsuleId != current.capsuleId ||
                            verified.deliveryTargetId != current.deliveryTargetId ||
                            verified.claimId != submitted.claimId
                        ) {
                            return ProofState.Failed(PendingProofMessage.GENERIC)
                        }
                        // Reception-time FRONT staging (best-effort): the
                        // kind-1 ciphertext rides the row so scan
                        // enumeration stays offline. "" on any failure —
                        // the claim stays truthful either way.
                        val recognitionB64 = stageRecognition(
                            creds = creds,
                            deliveryId = current.deliveryId,
                            statementBytes = sBytes,
                        )
                        persistAdmittedRow(
                            creds = creds,
                            accountUserId = account.userId,
                            recipientKeyBundleId = account.bundleId,
                            capsuleId = verified.capsuleId,
                            deliveryId = current.deliveryId,
                            deliveryTargetId = verified.deliveryTargetId,
                            claimId = verified.claimId,
                            recognitionCiphertextB64 = recognitionB64,
                            submitted = submitted,
                            statementBytes = sBytes,
                            senderSignature = senderSig,
                        )
                        // Fence before promotion: a session death across
                        // the suspend upsert must not yield Claimed.
                        if (!credsLive(creds)) {
                            return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                        }
                        ProofState.Claimed(current.deliveryId, serviceVerified = true)
                    }
                    is dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.EnvelopeAcceptance.PartiallyAccepted ->
                        ProofState.Claimed(current.deliveryId, serviceVerified = false)
                }
            } finally {
                when (acceptance) {
                    is dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.EnvelopeAcceptance.Accepted ->
                        acceptance.capsuleAeadKeyset.fill(0)
                    is dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.EnvelopeAcceptance.PartiallyAccepted ->
                        acceptance.capsuleAeadKeyset.fill(0)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GeneralSecurityException) {
            ProofState.Failed(PendingProofMessage.GENERIC)
        } catch (_: Exception) {
            ProofState.Failed(PendingProofMessage.GENERIC)
        }
    }

    /**
     * Durable row ids parsed from the VERIFIED S/R bytes (canonical
     * 16-byte network order, same layout the verifiers check). The
     * caller compares them against the unsigned Continue/submit echoes
     * and builds the row from these — never from echo strings.
     */
    private data class VerifiedRowIds(
        val capsuleId: String,
        val deliveryTargetId: String,
        val claimId: String,
    )

    private fun verifiedRowIds(statementBytes: ByteArray, claimBytes: ByteArray): VerifiedRowIds {
        val statement = try {
            PendingPublishStatementV2.parseFrom(statementBytes)
        } catch (_: Exception) {
            throw IllegalStateException("pending publish statement malformed")
        }
        val claim = try {
            ClaimBindingV1.parseFrom(claimBytes)
        } catch (_: Exception) {
            throw IllegalStateException("pending claim binding malformed")
        }
        return VerifiedRowIds(
            uuidRest(statement.capsuleId),
            uuidRest(statement.deliveryTargetId),
            uuidRest(claim.claimId),
        )
    }

    /**
     * T186 M14d-scan: best-effort kind-1 staging for the admitted row.
     * Runs on the held R11 lease (fenced before the network and after —
     * a mid-await death stages nothing). "" when the publication carries
     * no FRONT, the port is absent, or the fetch fails verification.
     * Cancellation propagates (the caller's catch rethrows).
     */
    private suspend fun stageRecognition(
        creds: ProofCreds,
        deliveryId: String,
        statementBytes: ByteArray,
    ): String {
        val port = recognitionBlobPort ?: return ""
        val binding = PendingRecognitionStage.selectKind1(statementBytes) ?: return ""
        if (!credsLive(creds)) return ""
        val bytes = try {
            PendingRecognitionStage.fetchVerified(deliveryId, creds.token, binding, port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return ""
        if (!credsLive(creds)) return ""
        return try {
            Base64.getEncoder().encodeToString(bytes)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * T186 M14d-claim: builds the durable admitted row from
     * already-verified claim material (metadata + sealed opaque
     * bytes only — no keys, no plaintext). Best-effort: false means
     * the feed sync will admit the same row later; the claim itself
     * stays truthful either way.
     */
    private suspend fun persistAdmittedRow(
        creds: ProofCreds,
        accountUserId: String,
        recipientKeyBundleId: String,
        capsuleId: String,
        deliveryId: String,
        deliveryTargetId: String,
        claimId: String,
        recognitionCiphertextB64: String,
        submitted: dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed,
        statementBytes: ByteArray,
        senderSignature: ByteArray,
    ): Boolean {
        val store = admittedStore ?: return false
        if (accountUserId.isBlank() || capsuleId.isBlank() || deliveryId.isBlank() ||
            deliveryTargetId.isBlank() || claimId.isBlank()
        ) {
            return false
        }
        val statementShaHex = try {
            java.security.MessageDigest.getInstance("SHA-256").digest(statementBytes)
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            return false
        }
        // No-blank-overwrite rule: a failed claim-time fetch ("") must
        // not clobber a good durable cipher (e.g. staged earlier by
        // sync) — keep it only while it still verifies against this S.
        val prior = try {
            store.get(accountUserId, capsuleId)?.recognitionCiphertextB64 ?: ""
        } catch (_: Exception) {
            ""
        }
        val staged = if (recognitionCiphertextB64.isNotBlank()) {
            recognitionCiphertextB64
        } else {
            PendingRecognitionStage.keepVerifiedPrior(prior, statementBytes)
        }
        return try {
            // Held-lease fences around the suspend upsert: the lease that
            // submitted R11 must still own this incarnation immediately
            // before AND after the write — a mid-await death writes no row
            // (and the caller additionally refuses Claimed below).
            if (!credsLive(creds)) return false
            val durable = store.upsert(
                PendingAdmittedGiftStore.AdmittedGift(
                    ownerUserId = accountUserId,
                    capsuleId = capsuleId,
                    deliveryId = deliveryId,
                    deliveryTargetId = deliveryTargetId,
                    claimId = claimId,
                    availableAt = clockSeconds(),
                    envelopeB64 = submitted.envelopeB64,
                    envelopeSha256Hex = submitted.envelopeSha256Hex,
                    statementB64 = Base64.getEncoder().encodeToString(statementBytes),
                    statementSha256Hex = statementShaHex,
                    publishSignature69B64 = Base64.getEncoder()
                        .encodeToString(senderSignature),
                    claimBindingB64 = submitted.claimBindingB64,
                    claimSignature69B64 = submitted.claimSignature69B64,
                    recipientKeyBundleId = recipientKeyBundleId,
                    recognitionCiphertextB64 = staged,
                    envelopeRef = "/v2/pending-deliveries/$deliveryId/claim",
                    manifestRef = "/v2/pending-deliveries/$deliveryId/manifest",
                    blobRefs = emptyList(),
                    completedAt = null,
                    admittedAtMillis = System.currentTimeMillis(),
                ),
            )
            if (!credsLive(creds)) return false
            durable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun unhexStrict(hex: String): ByteArray {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "digest must be 64 hex"
        }
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private suspend fun verifiedState(deliveryId: String, attemptId: String): ProofState {
        attempt = attemptId
        _state.value = ProofState.LoadingOptions
        val creds = creds() ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        if (account.userId.isBlank() || account.bundleId.isBlank() ||
            account.userId != creds.ownerId
        ) {
            return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        }
        val options = try {
            optionsPort.options(deliveryId, creds.token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProofState.Failed(PendingProofMessage.NETWORK)
        }
        if (!credsLive(creds)) return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        if (options !is ClaimOptionsResult.Options) {
            val failure = options as ClaimOptionsResult.Failure
            return ProofState.Failed(mapClaimFailure(failure.reason))
        }
        val hpke = try {
            hpkeKeyProvider()
        } catch (_: Exception) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        val challengeRaw = try {
            Base64.getDecoder().decode(options.claimChallengeB64)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.GENERIC)
        val sealed = try {
            Base64.getDecoder().decode(options.hpkeChallengeB64)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.GENERIC)
        // Session id is NOT persisted client-side (explicit block):
        // without it the intent cannot submit, so stop at a verified,
        // signed, ready intent instead of inventing an id.
        val sessionId = try {
            sessionIdProvider()
        } catch (_: Exception) {
            null
        }
        if (sessionId.isNullOrBlank()) {
            return ProofState.Failed(PendingProofMessage.BLOCKED)
        }
        return try {
            val nonce = PendingClaimChallenge.openNonce(
                challengeRaw, sealed,
                PendingClaimChallenge.Expectation(
                    capsuleId = proof?.capsuleId ?: "",
                    deliveryTargetId = proof?.deliveryTargetId ?: "",
                    recipientUserId = account.userId,
                    recipientKeyBundleId = account.bundleId,
                    sessionId = sessionId,
                    nowEpochSeconds = clockSeconds(),
                ),
                hpke,
            )
            // Build + sign the intent now (real crypto); the SUBMIT waits.
            val sBytes = statementBytes ?: return ProofState.Failed(PendingProofMessage.GENERIC)
            val h = java.security.MessageDigest.getInstance("SHA-256").digest(sBytes)
            val signing = try {
                signingKeyProvider()
            } catch (_: Exception) {
                null
            } ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
            val opId = java.util.UUID.randomUUID().toString()
            val intent = PendingClaimIntent.buildCanonical(
                PendingClaimIntent.Fields(
                    operationId = opId,
                    capsuleId = proof?.capsuleId ?: "",
                    deliveryTargetId = proof?.deliveryTargetId ?: "",
                    publishStatementSha256 = h,
                    proofId = attemptId,
                    challengeId = options.challengeId,
                    recipientUserId = account.userId,
                    recipientKeyBundleId = account.bundleId,
                    sessionId = sessionId,
                    nonce = nonce,
                    bindAlias = false,
                ),
            )
            val intentSig = PendingClaimIntent.signIntent(signing, intent)
            builtIntent = intent
            builtIntentSig = intentSig
            builtOpId = opId
            builtNonce = nonce
            builtChallengeId = options.challengeId
            ProofState.IntentReady(deliveryId, Base64.getEncoder().encodeToString(nonce))
        } catch (_: GeneralSecurityException) {
            ProofState.Failed(PendingProofMessage.GENERIC)
        } catch (_: Exception) {
            ProofState.Failed(PendingProofMessage.GENERIC)
        }
    }

    private fun mapLookupFailure(reason: GiftLookupFailure): PendingProofMessage = when (reason) {
        GiftLookupFailure.NETWORK,
        GiftLookupFailure.RATE_LIMITED,
        -> PendingProofMessage.NETWORK
        GiftLookupFailure.GIFT_UNAVAILABLE -> PendingProofMessage.NOT_FOUND
        GiftLookupFailure.ADDRESS_PROOF_REQUIRED,
        GiftLookupFailure.SESSION_CHANGED,
        GiftLookupFailure.AUTH_INVALID,
        GiftLookupFailure.RECOVERY_REQUIRED,
        -> PendingProofMessage.SESSION_CHANGED
        GiftLookupFailure.PROOF_EXPIRED -> PendingProofMessage.EXPIRED
        else -> PendingProofMessage.GENERIC
    }

    private fun mapClaimFailure(reason: dev.hryshyn.remanence.core.data.network.ClaimFailure): PendingProofMessage =
        when (reason) {
            dev.hryshyn.remanence.core.data.network.ClaimFailure.NETWORK,
            dev.hryshyn.remanence.core.data.network.ClaimFailure.RATE_LIMITED,
            -> PendingProofMessage.NETWORK
            dev.hryshyn.remanence.core.data.network.ClaimFailure.ADDRESS_PROOF_REQUIRED,
            dev.hryshyn.remanence.core.data.network.ClaimFailure.AUTH_INVALID,
            -> PendingProofMessage.SESSION_CHANGED
            dev.hryshyn.remanence.core.data.network.ClaimFailure.CLAIM_CONFLICT,
            dev.hryshyn.remanence.core.data.network.ClaimFailure.IDEMPOTENCY_CONFLICT,
            -> PendingProofMessage.CONFLICT
            else -> PendingProofMessage.GENERIC
        }
}
