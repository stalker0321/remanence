package dev.hryshyn.remanence.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher
import dev.hryshyn.remanence.core.crypto.PendingPublishCrypto
import dev.hryshyn.remanence.core.data.network.SendRouteFailure
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Authenticated sender identity + ARK-ready keys for one publication. */
data class PendingSenderIdentity(
    val userId: String,
    val bundleId: String,
    val signingHandle: KeysetHandle,
)

/** Raw sender content for one pending gift (photo JPEGs as authored). */
data class PendingSenderContent(
    val photoJpegs: List<ByteArray>,
)

fun interface PendingSenderContentPort {
    suspend fun content(capsuleId: String): PendingSenderContent?
}

fun interface PendingSenderIdentityPort {
    suspend fun identity(): PendingSenderIdentity?
}

/**
 * T186 M12: actual v2 sender caller (isolated EMAIL/PHONE destination →
 * existing R1 freeze → real R3/R4/R5 publication → displayed 5-char
 * code). Owns the `PendingSendRouteFlow` freeze and drives a caller-built
 * `PendingPublishFlow`; crypto framing via `PendingCapsulePublisher`
 * (photo-only v2; manifest/note binding follows, never faked).
 *
 * Branch discipline: `EXISTING_USER_V1` hands the frozen v1 identity out
 * for the existing zero-escrow pipeline (no pending material, no
 * escrow, no fallback ever); `PENDING_V2` requires the out-of-band
 * service pin (null ⇒ BLOCKED, never an invented UUID), ARK-ready
 * identity (null ⇒ recovery BLOCKED) and content (null ⇒ BLOCKED).
 * One held transaction (T188 C3): the ORIGINAL R1 owner + lease is
 * rechecked after the identity/content awaits and before every side
 * effect, and pinned across the publish-flow boundary; bearers are
 * re-read for the same live lease per call, never substituted.
 */
class PendingSenderViewModel(
    private val freezePort: PendingSendRouteFlow.SendRoutePort,
    private val publishFlowFactory: (PendingPublishFlow.MaterialPort) -> PendingPublishFlow,
    private val contentPort: PendingSenderContentPort,
    private val identityPort: PendingSenderIdentityPort,
    private val servicePinProvider: () -> PendingServicePin?,
    private val sessionGate: PendingRetirementSessionGate,
    private val accountProvider: suspend () -> ProofAccount?,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    scope: CoroutineScope? = null,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Freezing : State

        /**
         * Known bound v1 route: hand this frozen identity to the existing
         * zero-escrow pipeline. Nothing pending was built — no escrow, no
         * code, no fallback in either direction.
         */
        data class KnownV1(
            val identity: PendingSendRouteFlow.FrozenIdentity.V1,
        ) : State

        data object Publishing : State
        data class Published(
            val deliveryId: String,
            val locatorCode: String,
            val hint: String,
        ) : State

        data class Failed(val message: Message) : State
    }

    enum class Message {
        NETWORK,
        SESSION_CHANGED,
        CONFLICT,
        BLOCKED,
        RECOVERY_REQUIRED,
        SERVICE_UNAVAILABLE,
        VALIDATION,
        GENERIC,
    }

    private val launchScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _destination = MutableStateFlow("")
    val destination: StateFlow<String> = _destination.asStateFlow()

    private val _kind = MutableStateFlow("email")
    val kind: StateFlow<String> = _kind.asStateFlow()

    fun onDestinationChange(value: String) {
        _destination.value = value
    }

    fun onKindChange(kind: String) {
        if (kind == "email" || kind == "phone") _kind.value = kind
    }

    /**
     * T186 M12b (T188 C3): one held sender transaction — the ORIGINAL
     * owner + lease (incarnation pinned). Rechecked after EVERY await
     * (identity, content, R1) and BEFORE every side effect; the same
     * owner is pinned across the publish-flow boundary via [Txn.ownerId].
     */
    private data class Txn(
        val lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        val ownerId: String,
    )

    private fun beginTxn(): Txn? {
        val lease = try {
            sessionGate.capture()
        } catch (_: Exception) {
            return null
        } ?: return null
        val ownerId = lease.ownerUserId.toRestString()
        if (ownerId.isBlank()) return null
        return Txn(lease, ownerId)
    }

    private fun txnLive(txn: Txn): Boolean = try {
        sessionGate.isLive(txn.lease)
    } catch (_: Exception) {
        false
    }

    private fun txnBearer(txn: Txn): String? {
        if (!txnLive(txn)) return null
        return try {
            sessionGate.accessTokenFor(txn.lease).takeIf { !it.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun checkTxn(txn: Txn): Boolean {
        if (!txnLive(txn)) return false
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            return false
        }
        return account != null && !account.userId.isBlank() && account.userId == txn.ownerId
    }

    fun send() {
        if (_state.value !is State.Idle && _state.value !is State.Failed) return
        val rawDestination = _destination.value.trim()
        val kind = _kind.value
        if (rawDestination.isBlank() || (kind != "email" && kind != "phone")) {
            _state.value = State.Failed(Message.VALIDATION)
            return
        }
        _state.value = State.Freezing
        launchScope.launch {
            _state.value = try {
                sendFlow(rawDestination, kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                State.Failed(Message.GENERIC)
            }
        }
    }

    fun reset() {
        _state.value = State.Idle
    }

    private suspend fun sendFlow(rawDestination: String, kind: String): State {
        val txn = beginTxn() ?: return State.Failed(Message.SESSION_CHANGED)
        if (!checkTxn(txn)) return State.Failed(Message.SESSION_CHANGED)
        val capsuleId = java.util.UUID.randomUUID().toString()
        val draftKey = java.util.UUID.randomUUID().toString()
        val freezeToken = txnBearer(txn) ?: return State.Failed(Message.SESSION_CHANGED)
        val routeFlow = PendingSendRouteFlow(freezePort)
        val frozen = try {
            routeFlow.freeze(capsuleId, kind, rawDestination, draftKey, freezeToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return State.Failed(Message.NETWORK)
        }
        if (!checkTxn(txn)) return State.Failed(Message.SESSION_CHANGED)
        return when (frozen) {
            is PendingSendRouteFlow.State.Frozen -> when (val identity = frozen.identity) {
                is PendingSendRouteFlow.FrozenIdentity.V1 -> State.KnownV1(identity)
                is PendingSendRouteFlow.FrozenIdentity.Pending ->
                    publishPending(txn, identity, capsuleId)
            }
            is PendingSendRouteFlow.State.Failed -> State.Failed(mapRouteFailure(frozen.reason))
            else -> State.Failed(Message.GENERIC)
        }
    }

    private suspend fun publishPending(
        txn: Txn,
        frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        capsuleId: String,
    ): State {
        // Out-of-band pin usability (not blankness): a malformed
        // configured pin BLOCKS publication instead of minting S under
        // a key that can never verify.
        val pin = try {
            servicePinProvider()
        } catch (_: Exception) {
            null
        }
        if (!PendingServicePinConfig.usable(pin)) {
            return State.Failed(Message.SERVICE_UNAVAILABLE)
        }
        val serviceKeyId = pin!!.deploymentUuid
        // ARK-readiness: signing keys must unwrap (recovery otherwise).
        // Both awaits are fenced against the ORIGINAL transaction owner.
        val identity = try {
            identityPort.identity()
        } catch (_: Exception) {
            null
        } ?: return State.Failed(Message.RECOVERY_REQUIRED)
        if (!checkTxn(txn)) return State.Failed(Message.SESSION_CHANGED)
        if (identity.userId.isBlank() || identity.bundleId.isBlank()) {
            return State.Failed(Message.RECOVERY_REQUIRED)
        }
        if (identity.userId != txn.ownerId) {
            // Identity drifted from the ORIGINAL transaction owner.
            return State.Failed(Message.SESSION_CHANGED)
        }
        val content = try {
            contentPort.content(capsuleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (!checkTxn(txn)) return State.Failed(Message.SESSION_CHANGED)
        val photos = content?.photoJpegs
        if (photos.isNullOrEmpty()) return State.Failed(Message.BLOCKED)
        val channel = when (frozen.channel) {
            "email" -> PendingPublishCrypto.CHANNEL_EMAIL
            "sms" -> PendingPublishCrypto.CHANNEL_SMS
            else -> return State.Failed(Message.BLOCKED)
        }
        val capsuleKeyset = try {
            CapsuleKeysetGenerator().generate()
        } catch (_: Exception) {
            return State.Failed(Message.GENERIC)
        }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val finalizeKey = java.util.UUID.randomUUID().toString()
        val publication = try {
            PendingCapsulePublisher.publish(
                capsuleId = capsuleId,
                senderUserId = identity.userId,
                senderKeyBundleId = identity.bundleId,
                deliveryTargetId = frozen.deliveryTargetId,
                serviceKeyId = serviceKeyId,
                channel = channel,
                normalizedDestination = frozen.normalizedDestination,
                salt32 = salt,
                claimExpiresAt = frozen.claimDeadline,
                createdAtEpochSeconds = clockSeconds(),
                signingHandle = identity.signingHandle,
                capsuleKeyset = capsuleKeyset,
                photos = photos,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return State.Failed(Message.VALIDATION)
        } catch (_: Exception) {
            return State.Failed(Message.GENERIC)
        }
        _state.value = State.Publishing
        // Pin the ORIGINAL R1 transaction owner across the flow
        // boundary: a switch since freeze fails closed inside.
        if (!checkTxn(txn)) return State.Failed(Message.SESSION_CHANGED)
        val flow = publishFlowFactory(
            PendingPublishFlow.MaterialPort {
                PendingPublishFlow.PublishMaterial(
                    salt32 = publication.salt32,
                    claimExpiresAt = publication.claimExpiresAt,
                    artifacts = publication.artifacts.map {
                        PendingPublishFlow.ArtifactMaterial(
                            it.blobId, it.kind, it.ordinal, it.ciphertext,
                        )
                    },
                    statementB64 = java.util.Base64.getEncoder()
                        .encodeToString(publication.statement),
                    statementSignature69B64 = java.util.Base64.getEncoder()
                        .encodeToString(publication.signature),
                    escrowPayloadB64 = java.util.Base64.getEncoder()
                        .encodeToString(publication.escrow),
                )
            },
        )
        return when (
            val published = flow.publish(
                frozen, capsuleId,
                java.util.UUID.randomUUID().toString(), finalizeKey,
                txnOwnerId = txn.ownerId,
            )
        ) {
            is PendingPublishFlow.State.Published ->
                State.Published(published.deliveryId, published.locatorCode, published.hint)
            is PendingPublishFlow.State.Failed -> State.Failed(mapPublishReason(published.reason))
            else -> State.Failed(Message.GENERIC)
        }
    }

    private fun mapRouteFailure(reason: SendRouteFailure): Message = when (reason) {
        SendRouteFailure.NETWORK,
        SendRouteFailure.RATE_LIMITED,
        -> Message.NETWORK
        SendRouteFailure.AUTH_INVALID -> Message.SESSION_CHANGED
        SendRouteFailure.HANDLE_REQUIRED_FOR_SEND,
        SendRouteFailure.ADDRESS_UNAVAILABLE,
        SendRouteFailure.VALIDATION_FAILED,
        -> Message.VALIDATION
        else -> Message.GENERIC
    }

    private fun mapPublishReason(reason: PendingPublishFlow.Reason): Message = when (reason) {
        PendingPublishFlow.Reason.NETWORK -> Message.NETWORK
        PendingPublishFlow.Reason.SESSION_CHANGED -> Message.SESSION_CHANGED
        PendingPublishFlow.Reason.CONFLICT -> Message.CONFLICT
        PendingPublishFlow.Reason.BLOCKED -> Message.BLOCKED
        else -> Message.GENERIC
    }
}
