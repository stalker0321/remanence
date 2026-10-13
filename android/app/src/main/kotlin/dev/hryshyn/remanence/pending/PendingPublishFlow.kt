package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.PendingArtifactDeclaration
import dev.hryshyn.remanence.core.data.network.PendingBlobResult
import dev.hryshyn.remanence.core.data.network.PendingDraftResult
import dev.hryshyn.remanence.core.data.network.PendingFinalizeResult
import dev.hryshyn.remanence.core.data.network.PendingPublishFailure
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * T186 M11: v2 sender publication flow (frozen R1 → R3 → R4 → R5).
 *
 * Consumes the durable [PendingSendRouteFlow.FrozenIdentity.Pending]
 * end-to-end (route/target/normalized/channel/deadline/service-key/
 * expiry — never route-id + mode only) and publishes sealed artifacts
 * through the real R3/R4/R5 ports: R3 draft → R4 blob uploads → R5
 * finalize → handwritten 5-char locator code result.
 *
 * Transport orchestration only: sealed ciphertext, canonical S +
 * signature, and escrow arrive via [MaterialPort] (built with
 * `core:crypto` `PendingPublishCrypto`; inner payloads unchanged).
 * Route freeze is enforced (PENDING_V2 only — no v1→pending fallback;
 * known-v1 takes the separate R2 path). Every network call captures a
 * fresh lease, sends with its token, and rechecks owner/incarnation
 * liveness after the await; the local sender account must be the lease
 * owner at entry and after every await.
 */
class PendingPublishFlow(
    private val draftPort: DraftPort,
    private val blobPort: BlobPort,
    private val finalizePort: FinalizePort,
    private val materialPort: MaterialPort,
    private val sessionGate: PendingRetirementSessionGate,
    private val accountProvider: suspend () -> ProofAccount?,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    scope: CoroutineScope? = null,
) {

    fun interface DraftPort {
        suspend fun draft(
            routeId: String,
            capsuleId: String,
            deliveryTargetId: String,
            channel: String,
            destination: String,
            salt32: ByteArray,
            claimExpiresAt: Long,
            artifacts: List<PendingArtifactDeclaration>,
            idempotencyKey: String,
            token: String,
        ): PendingDraftResult
    }

    fun interface BlobPort {
        suspend fun upload(
            deliveryId: String,
            blobId: String,
            ciphertext: ByteArray,
            declaredSize: Long,
            declaredSha256Hex: String,
            token: String,
        ): PendingBlobResult
    }

    fun interface FinalizePort {
        suspend fun finalize(
            deliveryId: String,
            publishStatementB64: String,
            publishSignature69B64: String,
            escrowPayloadB64: String,
            idempotencyKey: String,
            token: String,
        ): PendingFinalizeResult
    }

    /** Sealed publication material (frozen crypto, caller-built). */
    data class ArtifactMaterial(
        val blobId: String,
        val kind: Int,
        val ordinal: Int,
        val ciphertext: ByteArray,
    )

    data class PublishMaterial(
        val salt32: ByteArray,
        val claimExpiresAt: Long,
        val artifacts: List<ArtifactMaterial>,
        val statementB64: String,
        val statementSignature69B64: String,
        val escrowPayloadB64: String,
    )

    fun interface MaterialPort {
        suspend fun material(
            frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        ): PublishMaterial?
    }

    sealed interface State {
        data object Idle : State
        data object Drafting : State
        data class Uploading(val uploaded: Int, val total: Int) : State
        data object Finalizing : State

        /** Handwritten 5-char code result (server-masked hint beside it). */
        data class Published(
            val deliveryId: String,
            val locatorCode: String,
            val hint: String,
        ) : State

        data class Failed(val reason: Reason) : State
    }

    enum class Reason {
        NETWORK,
        SESSION_CHANGED,
        CONFLICT,
        BLOCKED,
        GENERIC,
    }

    private val launchScope: CoroutineScope? = scope

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

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

    /**
     * Publishes one frozen pending route. Suspends (no owned scope kept;
     * callers launch). Idempotency keys are caller-supplied per stage so
     * retries replay canonically.
     */
    suspend fun publish(
        frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        capsuleId: String,
        draftIdempotencyKey: String,
        finalizeIdempotencyKey: String,
    ): State {
        _state.value = State.Drafting
        return try {
            publishFlow(frozen, capsuleId, draftIdempotencyKey, finalizeIdempotencyKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            State.Failed(Reason.GENERIC).also { _state.value = it }
        }.also { _state.value = it }
    }

    /** Fire-and-forget entry when a scope is provided. */
    fun publishAsync(
        frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        capsuleId: String,
        draftIdempotencyKey: String,
        finalizeIdempotencyKey: String,
    ) {
        val scope = launchScope ?: return
        scope.launch {
            publish(frozen, capsuleId, draftIdempotencyKey, finalizeIdempotencyKey)
        }
    }

    private suspend fun publishFlow(
        frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        capsuleId: String,
        draftKey: String,
        finalizeKey: String,
    ): State {
        val creds = creds() ?: return State.Failed(Reason.SESSION_CHANGED)
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            null
        } ?: return State.Failed(Reason.SESSION_CHANGED)
        if (account.userId.isBlank() || account.userId != creds.ownerId) {
            return State.Failed(Reason.SESSION_CHANGED)
        }
        val material = try {
            materialPort.material(frozen)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return State.Failed(Reason.BLOCKED)
        if (material.salt32.size != 32 || material.artifacts.isEmpty()) {
            return State.Failed(Reason.BLOCKED)
        }
        val declarations = material.artifacts.map { artifact ->
            PendingArtifactDeclaration(
                blobId = artifact.blobId,
                kind = artifact.kind,
                ordinal = artifact.ordinal,
                ciphertextSize = artifact.ciphertext.size.toLong(),
                ciphertextSha256Hex = shaHex(artifact.ciphertext),
            )
        }
        _state.value = State.Drafting
        val draft = try {
            draftPort.draft(
                frozen.routeId, capsuleId, frozen.deliveryTargetId,
                frozen.channel, frozen.normalizedDestination,
                material.salt32, material.claimExpiresAt,
                declarations, draftKey, creds.token,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return State.Failed(Reason.NETWORK)
        }
        if (!credsLive(creds)) return State.Failed(Reason.SESSION_CHANGED)
        val deliveryId = when (draft) {
            is PendingDraftResult.Draft -> draft.deliveryId
            is PendingDraftResult.Failure -> return State.Failed(mapFailure(draft.reason))
        }
        var uploaded = 0
        for (artifact in material.artifacts) {
            _state.value = State.Uploading(uploaded, material.artifacts.size)
            // Per-blob lease freshness: re-capture before each upload.
            val blobCreds = creds() ?: return State.Failed(Reason.SESSION_CHANGED)
            val blobAccount = try {
                accountProvider()
            } catch (_: Exception) {
                null
            }
            if (blobAccount == null || blobAccount.userId != blobCreds.ownerId) {
                return State.Failed(Reason.SESSION_CHANGED)
            }
            val declaration = declarations[uploaded]
            val uploadedResult = try {
                blobPort.upload(
                    deliveryId, artifact.blobId, artifact.ciphertext,
                    declaration.ciphertextSize, declaration.ciphertextSha256Hex,
                    blobCreds.token,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return State.Failed(Reason.NETWORK)
            }
            if (!credsLive(blobCreds)) return State.Failed(Reason.SESSION_CHANGED)
            when (uploadedResult) {
                is PendingBlobResult.Uploaded -> uploaded += 1
                is PendingBlobResult.Failure -> return State.Failed(mapFailure(uploadedResult.reason))
            }
        }
        _state.value = State.Finalizing
        val finalCreds = creds() ?: return State.Failed(Reason.SESSION_CHANGED)
        val finalAccount = try {
            accountProvider()
        } catch (_: Exception) {
            null
        }
        if (finalAccount == null || finalAccount.userId != finalCreds.ownerId) {
            return State.Failed(Reason.SESSION_CHANGED)
        }
        val finalized = try {
            finalizePort.finalize(
                deliveryId, material.statementB64, material.statementSignature69B64,
                material.escrowPayloadB64, finalizeKey, finalCreds.token,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return State.Failed(Reason.NETWORK)
        }
        if (!credsLive(finalCreds)) return State.Failed(Reason.SESSION_CHANGED)
        return when (finalized) {
            is PendingFinalizeResult.Finalized ->
                State.Published(finalized.deliveryId, finalized.locatorCode, finalized.hint)
            is PendingFinalizeResult.Failure -> State.Failed(mapFailure(finalized.reason))
        }
    }

    private fun shaHex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun mapFailure(reason: PendingPublishFailure): Reason = when (reason) {
        PendingPublishFailure.NETWORK,
        PendingPublishFailure.RATE_LIMITED,
        -> Reason.NETWORK
        PendingPublishFailure.AUTH_INVALID -> Reason.SESSION_CHANGED
        PendingPublishFailure.ROUTE_STALE,
        PendingPublishFailure.PROTOCOL_UNSUPPORTED,
        -> Reason.BLOCKED
        PendingPublishFailure.IDEMPOTENCY_CONFLICT -> Reason.CONFLICT
        else -> Reason.GENERIC
    }
}
