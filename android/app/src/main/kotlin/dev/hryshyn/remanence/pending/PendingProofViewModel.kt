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
    private val tokenProvider: suspend () -> String?,
    private val accountProvider: suspend () -> ProofAccount?,
    private val signingKeyProvider: suspend () -> KeysetHandle?,
    private val hpkeKeyProvider: suspend () -> KeysetHandle?,
    /**
     * Sender signing public keyset bytes by bundle id (directory-backed —
     * the server holds no monopoly). Null/unfetchable fails S verification
     * closed; the flow never proceeds on structure alone.
     */
    private val senderKeyProvider: suspend (bundleId: String) -> ByteArray? = { null },
    /**
     * Own bundle public keyset bytes (directory-fetched — the exact stored
     * bytes the server hashed for the recipient digest check). Null fails
     * envelope acceptance closed.
     */
    private val ownBundleKeyProvider: suspend () -> ByteArray? = { null },
    private val claimPort: ProofClaimPort? = null,
    /**
     * Auth session id for the intent (fenced holder in production, null
     * until a live owner-bound session exists). Null keeps the flow at a
     * verified, signed, ready intent (BLOCKED state) instead of inventing
     * an id.
     */
    private val sessionIdProvider: suspend () -> String? = { null },
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

    private suspend fun bearer(): String? = try {
        tokenProvider()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private suspend fun continueFlow(reference: String): ProofState {
        val token = bearer() ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        return when (val result = continuePort.continueFrom(reference, token)) {
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
                    senderKeyProvider(uuidRest(statement.senderKeyBundleId))
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
                val token = bearer()
                if (token == null) {
                    ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                } else {
                    val sms = !current.channel.equals("email", ignoreCase = true)
                    when (val result = verifyPort.request(
                        current.deliveryId, _destination.value.trim(), sms,
                        clockSeconds(), java.util.UUID.randomUUID().toString(), token,
                    )) {
                        is VerificationResult.Attempt -> {
                            attempt = result.attemptId
                            ProofState.CodeRequired(current.deliveryId, result.attemptId, result.resendCooldownSeconds)
                        }
                        is VerificationResult.Verified -> verifiedState(current.deliveryId, result.attemptId)
                        is VerificationResult.Failure -> ProofState.Failed(mapLookupFailure(result.reason))
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
                val token = bearer()
                if (token == null) {
                    ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
                } else {
                    when (val result = verifyPort.check(current.deliveryId, attemptId, _code.value.trim(), token)) {
                        is VerificationResult.Verified -> verifiedState(current.deliveryId, result.attemptId)
                        is VerificationResult.Attempt -> {
                            attempt = result.attemptId
                            ProofState.CodeRequired(current.deliveryId, result.attemptId, result.resendCooldownSeconds)
                        }
                        is VerificationResult.Failure -> ProofState.Failed(mapLookupFailure(result.reason))
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
        val token = bearer() ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        if (account.userId.isBlank() || account.bundleId.isBlank()) {
            return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        }
        val submitted = try {
            port.submit(
                current.deliveryId,
                Base64.getEncoder().encodeToString(intent),
                Base64.getEncoder().encodeToString(intentSig),
                opId, token,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProofState.Failed(PendingProofMessage.NETWORK)
        }
        if (submitted !is dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed) {
            val failure = submitted as dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Failure
            return ProofState.Failed(mapClaimFailure(failure.reason))
        }
        // Trusted acceptance of the server response (partial: service key
        // undistributed — typed, never promoted, never admission).
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
        return try {
            val binding = Base64.getDecoder().decode(submitted.claimBindingB64)
            val envelope = Base64.getDecoder().decode(submitted.envelopeB64)
            val envelopeSha = unhexStrict(submitted.envelopeSha256Hex)
            val claimSig = Base64.getDecoder().decode(submitted.claimSignature69B64)
            val senderVerify = com.google.crypto.tink.TinkProtoKeysetFormat
                .parseKeysetWithoutSecret(senderKeyBytes)
            val senderSig = senderSigStored
                ?: return ProofState.Failed(PendingProofMessage.GENERIC)
            when (
                dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.accept(
                    sealedEnvelope = envelope,
                    expectedEnvelopeSha256 = envelopeSha,
                    statementBytes = sBytes,
                    claimBytes = binding,
                    senderSignature = senderSig,
                    claimSignature = claimSig,
                    recipientPrivateHandle = hpke,
                    senderVerifyHandle = senderVerify,
                    // Service-key distribution pending: partial path only,
                    // typed untrusted, never promoted.
                    serviceVerifyHandle = null,
                    expected = dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.LocalExpectation(
                        recipientUserId = account.userId,
                        recipientKeyBundleId = account.bundleId,
                        recipientPublicKeyset = ownPublic,
                        nowEpochSeconds = clockSeconds(),
                        authorizedServiceKeyId = null,
                    ),
                )
            ) {
                is dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.EnvelopeAcceptance.Accepted ->
                    ProofState.Claimed(current.deliveryId, serviceVerified = true)
                is dev.hryshyn.remanence.core.crypto.PendingClaimedEnvelope.EnvelopeAcceptance.PartiallyAccepted ->
                    ProofState.Claimed(current.deliveryId, serviceVerified = false)
            }
        } catch (_: GeneralSecurityException) {
            ProofState.Failed(PendingProofMessage.GENERIC)
        } catch (_: Exception) {
            ProofState.Failed(PendingProofMessage.GENERIC)
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
        val token = bearer() ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            null
        } ?: return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        if (account.userId.isBlank() || account.bundleId.isBlank()) {
            return ProofState.Failed(PendingProofMessage.SESSION_CHANGED)
        }
        val options = try {
            optionsPort.options(deliveryId, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProofState.Failed(PendingProofMessage.NETWORK)
        }
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
