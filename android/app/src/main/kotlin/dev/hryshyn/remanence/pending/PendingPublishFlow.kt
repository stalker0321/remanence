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
 * known-v1 takes the separate R2 path). One held transaction: the
 * ORIGINAL owner + lease (incarnation pinned) is captured at entry and
 * rechecked after EVERY await — including the local material await —
 * and BEFORE every network side effect; bearers are re-read for the
 * same live lease per call, never substituted. An optional
 * [txnOwnerId] pins the original owner across a caller boundary.
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

    /**
     * T186 M12b (T188 C3): one held transaction — the ORIGINAL owner +
     * lease (owner AND incarnation pinned in the captured lease object).
     * After EVERY await (local material/identity/content work and every
     * network call) and BEFORE every side effect, [checkTxn] requires
     * the SAME lease still live and the current account still the
     * ORIGINAL owner. Bearers are re-read for the same live lease per
     * call (ordinary rotation never bumps the incarnation, so same-owner
     * refresh keeps working); a NEW lease is never substituted, so an
     * A→B switch cannot continue B-side work for A's frozen route.
     */
    private data class Txn(
        val lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        val ownerId: String,
    )

    private fun beginTxn(expectedOwnerId: String? = null): Txn? {
        val lease = try {
            sessionGate.capture()
        } catch (_: Exception) {
            return null
        } ?: return null
        val ownerId = lease.ownerUserId.toRestString()
        if (ownerId.isBlank()) return null
        if (expectedOwnerId != null && ownerId != expectedOwnerId) return null
        return Txn(lease, ownerId)
    }

    private fun txnLive(txn: Txn): Boolean = try {
        sessionGate.isLive(txn.lease)
    } catch (_: Exception) {
        false
    }

    /** Fresh bearer for the SAME live lease (null when dead/rotated-out). */
    private fun txnBearer(txn: Txn): String? {
        if (!txnLive(txn)) return null
        return try {
            sessionGate.accessTokenFor(txn.lease).takeIf { !it.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Owner fence against the ORIGINAL transaction owner (not a new lease). */
    private suspend fun checkTxn(txn: Txn): Boolean {
        if (!txnLive(txn)) return false
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            return false
        }
        return account != null && !account.userId.isBlank() && account.userId == txn.ownerId
    }

    /**
     * Publishes one frozen pending route. Suspends (no owned scope kept;
     * callers launch). Idempotency keys are caller-supplied per stage so
     * retries replay canonically. [txnOwnerId] pins the ORIGINAL
     * transaction owner across a caller boundary (e.g. the sender
     * ViewModel's R1 owner): a lease captured here for a different owner
     * fails closed before any side effect.
     */
    suspend fun publish(
        frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        capsuleId: String,
        draftIdempotencyKey: String,
        finalizeIdempotencyKey: String,
        txnOwnerId: String? = null,
    ): State {
        _state.value = State.Drafting
        return try {
            publishFlow(frozen, capsuleId, draftIdempotencyKey, finalizeIdempotencyKey, txnOwnerId)
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
        txnOwnerId: String? = null,
    ) {
        val scope = launchScope ?: return
        scope.launch {
            publish(frozen, capsuleId, draftIdempotencyKey, finalizeIdempotencyKey, txnOwnerId)
        }
    }

    private suspend fun publishFlow(
        frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        capsuleId: String,
        draftKey: String,
        finalizeKey: String,
        txnOwnerId: String?,
    ): State {
        val txn = beginTxn(txnOwnerId) ?: return State.Failed(Reason.SESSION_CHANGED)
        if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
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
        // The material await may have outlived the lease: recheck BEFORE
        // the first network side effect (R3), with a fresh bearer.
        if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
        val draftToken = txnBearer(txn) ?: return State.Failed(Reason.SESSION_CHANGED)
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
                declarations, draftKey, draftToken,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return State.Failed(Reason.NETWORK)
        }
        if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
        val deliveryId = when (draft) {
            is PendingDraftResult.Draft -> draft.deliveryId
            is PendingDraftResult.Failure -> return State.Failed(mapFailure(draft.reason))
        }
        var uploaded = 0
        for (artifact in material.artifacts) {
            _state.value = State.Uploading(uploaded, material.artifacts.size)
            // Same transaction owner/incarnation for every blob stage —
            // never a substituted lease — with a fresh bearer per call.
            if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
            val blobToken = txnBearer(txn) ?: return State.Failed(Reason.SESSION_CHANGED)
            val declaration = declarations[uploaded]
            val uploadedResult = try {
                blobPort.upload(
                    deliveryId, artifact.blobId, artifact.ciphertext,
                    declaration.ciphertextSize, declaration.ciphertextSha256Hex,
                    blobToken,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return State.Failed(Reason.NETWORK)
            }
            if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
            when (uploadedResult) {
                is PendingBlobResult.Uploaded -> uploaded += 1
                is PendingBlobResult.Failure -> return State.Failed(mapFailure(uploadedResult.reason))
            }
        }
        _state.value = State.Finalizing
        if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
        val finalToken = txnBearer(txn) ?: return State.Failed(Reason.SESSION_CHANGED)
        val finalized = try {
            finalizePort.finalize(
                deliveryId, material.statementB64, material.statementSignature69B64,
                material.escrowPayloadB64, finalizeKey, finalToken,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return State.Failed(Reason.NETWORK)
        }
        if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
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
