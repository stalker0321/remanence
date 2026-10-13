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

/** Authenticated account material for the proof/claim flow. */
data class ProofAccount(
    val userId: String,
    val bundleId: String,
)

/**
 * T186 M7c: receiving proof + claim flow (frozen R7/R8/R9/R10, exact §8).
 *
 * lookup → R7 continue → (sync: hand back to scan | proof: verify S
 * structure → R8 attempt → R9 code → VERIFIED → R10 options → open
 * challenge nonce → build + sign intent → IntentReady).
 *
 * Trust notes: the S SIGNATURE cannot be verified client-side (no sender
 * key on record — the server checked it at finalize); S gets structural
 * + canonical + D/KC-presence checks, and full agreement re-runs at
 * envelope acceptance. R11 SUBMIT waits on an explicit block: the auth
 * `session_id` is not persisted client-side, so the intent is built and
 * signed but never sent until that handoff. Nothing is faked, nothing is
 * auto-opened; the gift opens later through ordinary sync/scan.
 */
class PendingProofViewModel(
    private val continuePort: ProofContinuePort,
    private val verifyPort: ProofVerifyPort,
    private val optionsPort: ProofOptionsPort,
    private val tokenProvider: suspend () -> String?,
    private val accountProvider: suspend () -> ProofAccount?,
    private val signingKeyProvider: suspend () -> KeysetHandle?,
    private val hpkeKeyProvider: suspend () -> KeysetHandle?,
    /**
     * Explicit block: the auth `session_id` is not persisted client-side,
     * so R11 submit waits its handoff. Null keeps the flow at a verified,
     * signed, ready-to-submit intent (BLOCKED state) instead of inventing
     * an id. Tests supply one; production returns null until handed off.
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
                // Structural S gate (signature needs the sender key, which
                // only the server holds — it checked at finalize).
                val sBytes = try {
                    Base64.getDecoder().decode(result.publishStatementB64)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return ProofState.Failed(PendingProofMessage.GENERIC)
                try {
                    PendingPublishVerifier.verifyStructure(sBytes)
                } catch (_: GeneralSecurityException) {
                    return ProofState.Failed(PendingProofMessage.GENERIC)
                } catch (_: Exception) {
                    return ProofState.Failed(PendingProofMessage.GENERIC)
                }
                proof = result
                statementBytes = sBytes
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
            val intent = PendingClaimIntent.buildCanonical(
                PendingClaimIntent.Fields(
                    operationId = java.util.UUID.randomUUID().toString(),
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
            PendingClaimIntent.signIntent(signing, intent)
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
