package dev.hryshyn.remanence.pending

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException

/**
 * T186 M12d: shared pending-publication runner (T189 map §4). One held
 * transaction over the whole R3→R4→R5 sequence: the ORIGINAL owner +
 * lease (incarnation pinned) is rechecked after the caller's local
 * awaits and BEFORE every network side effect; bearers are re-read for
 * the same live lease per call, never substituted. Both the sender
 * sheet VM and the Create publish boundary drive this runner, so the
 * fence discipline is identical in both callers.
 *
 * Payload modes: [Authored.manifests] null ⇒ photos-only publication
 * (sheet path with retained originals); non-null ⇒ full
 * recognition+content+photos publication with byte-identical inner
 * bytes (Create boundary path). Declarations always feed S sorted
 * `(kind, ordinal, blob_id)`.
 */
object PendingBoundaryPublish {

    data class PhotoBytes(
        val jpeg: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    )

    data class ManifestInputs(
        val senderHandleSnapshot: String,
        val frontFingerprintBytes: ByteArray,
        val noteUtf8: String?,
        val expression: PendingCapsulePublisher.ExpressionInput?,
    )

    data class Authored(
        val capsuleId: String,
        val photos: List<PhotoBytes>,
        val manifests: ManifestInputs?,
        val claimExpiresAt: Long,
        val createdAtEpochSeconds: Long,
    )

    data class SenderKeys(
        val userId: String,
        val bundleId: String,
        val signingHandle: KeysetHandle,
        val capsuleKeyset: KeysetHandle,
        val salt32: ByteArray,
    )

    enum class Reason {
        NETWORK,
        SESSION_CHANGED,
        CONFLICT,
        BLOCKED,
        RECOVERY_REQUIRED,
        SERVICE_UNAVAILABLE,
        VALIDATION,
        GENERIC,
    }

    sealed interface Result {
        data class Published(
            val deliveryId: String,
            val locatorCode: String,
            val hint: String,
        ) : Result

        data class Failed(val reason: Reason) : Result
    }

    private data class Txn(
        val lease: SessionRequestLease,
        val ownerId: String,
    )

    @Suppress("LongParameterList")
    suspend fun run(
        frozen: PendingSendRouteFlow.FrozenIdentity.Pending,
        authored: Authored,
        identity: PendingSenderIdentity,
        pin: PendingServicePin,
        sessionGate: PendingRetirementSessionGate,
        accountProvider: suspend () -> ProofAccount?,
        draftPort: PendingPublishFlow.DraftPort,
        blobPort: PendingPublishFlow.BlobPort,
        finalizePort: PendingPublishFlow.FinalizePort,
        expectedLease: dev.hryshyn.remanence.core.data.network.SessionRequestLease? = null,
    ): Result {
        if (!PendingServicePinConfig.usable(pin)) return Result.Failed(Reason.SERVICE_UNAVAILABLE)
        if (identity.userId.isBlank() || identity.bundleId.isBlank()) {
            return Result.Failed(Reason.RECOVERY_REQUIRED)
        }
        if (authored.photos.isEmpty()) return Result.Failed(Reason.BLOCKED)
        val txn = beginTxn(sessionGate, expectedLease) ?: return Result.Failed(Reason.SESSION_CHANGED)
        if (!checkTxn(sessionGate, accountProvider, txn)) return Result.Failed(Reason.SESSION_CHANGED)
        if (identity.userId != txn.ownerId) return Result.Failed(Reason.SESSION_CHANGED)
        val keys = try {
            SenderKeys(
                identity.userId, identity.bundleId, identity.signingHandle,
                CapsuleKeysetGenerator().generate(),
                ByteArray(32).also { SecureRandom().nextBytes(it) },
            )
        } catch (_: Exception) {
            return Result.Failed(Reason.GENERIC)
        }
        val publication = try {
            if (authored.manifests == null) {
                PendingCapsulePublisher.publish(
                    capsuleId = authored.capsuleId,
                    senderUserId = keys.userId,
                    senderKeyBundleId = keys.bundleId,
                    deliveryTargetId = frozen.deliveryTargetId,
                    serviceKeyId = pin.deploymentUuid,
                    channel = channelOf(frozen) ?: return Result.Failed(Reason.BLOCKED),
                    normalizedDestination = frozen.normalizedDestination,
                    salt32 = keys.salt32,
                    claimExpiresAt = authored.claimExpiresAt,
                    createdAtEpochSeconds = authored.createdAtEpochSeconds,
                    signingHandle = keys.signingHandle,
                    capsuleKeyset = keys.capsuleKeyset,
                    photos = authored.photos.map { it.jpeg },
                )
            } else {
                PendingCapsulePublisher.publishFull(
                    capsuleId = authored.capsuleId,
                    senderUserId = keys.userId,
                    senderKeyBundleId = keys.bundleId,
                    deliveryTargetId = frozen.deliveryTargetId,
                    serviceKeyId = pin.deploymentUuid,
                    channel = channelOf(frozen) ?: return Result.Failed(Reason.BLOCKED),
                    normalizedDestination = frozen.normalizedDestination,
                    salt32 = keys.salt32,
                    claimExpiresAt = authored.claimExpiresAt,
                    createdAtEpochSeconds = authored.createdAtEpochSeconds,
                    signingHandle = keys.signingHandle,
                    capsuleKeyset = keys.capsuleKeyset,
                    senderHandleSnapshot = authored.manifests.senderHandleSnapshot,
                    frontFingerprintBytes = authored.manifests.frontFingerprintBytes,
                    photos = authored.photos.map {
                        PendingCapsulePublisher.PhotoInput(it.jpeg, it.widthPx, it.heightPx)
                    },
                    noteUtf8 = authored.manifests.noteUtf8,
                    expression = authored.manifests.expression,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return Result.Failed(Reason.VALIDATION)
        } catch (_: Exception) {
            return Result.Failed(Reason.GENERIC)
        }
        // Crypto framing is local work past an await boundary: recheck
        // BEFORE the flow's first network side effect.
        if (!checkTxn(sessionGate, accountProvider, txn)) return Result.Failed(Reason.SESSION_CHANGED)
        val flow = PendingPublishFlow(
            draftPort = draftPort,
            blobPort = blobPort,
            finalizePort = finalizePort,
            materialPort = PendingPublishFlow.MaterialPort {
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
            sessionGate = sessionGate,
            accountProvider = accountProvider,
            scope = null,
        )
        return when (
            val published = flow.publish(
                frozen, authored.capsuleId,
                java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(),
                txnLease = txn.lease,
            )
        ) {
            is PendingPublishFlow.State.Published ->
                Result.Published(published.deliveryId, published.locatorCode, published.hint)
            is PendingPublishFlow.State.Failed -> Result.Failed(
                when (published.reason) {
                    PendingPublishFlow.Reason.NETWORK -> Reason.NETWORK
                    PendingPublishFlow.Reason.SESSION_CHANGED -> Reason.SESSION_CHANGED
                    PendingPublishFlow.Reason.CONFLICT -> Reason.CONFLICT
                    PendingPublishFlow.Reason.BLOCKED -> Reason.BLOCKED
                    else -> Reason.GENERIC
                },
            )
            else -> Result.Failed(Reason.GENERIC)
        }
    }

    private fun channelOf(frozen: PendingSendRouteFlow.FrozenIdentity.Pending): Int? = when (frozen.channel) {
        "email" -> dev.hryshyn.remanence.core.crypto.PendingPublishCrypto.CHANNEL_EMAIL
        "sms" -> dev.hryshyn.remanence.core.crypto.PendingPublishCrypto.CHANNEL_SMS
        else -> null
    }

    private fun beginTxn(
        sessionGate: PendingRetirementSessionGate,
        expectedLease: dev.hryshyn.remanence.core.data.network.SessionRequestLease?,
    ): Txn? {
        val lease = try {
            sessionGate.capture()
        } catch (_: Exception) {
            return null
        } ?: return null
        val ownerId = lease.ownerUserId.toRestString()
        if (ownerId.isBlank()) return null
        // T186 M12e (T188 B4): pin owner AND incarnation to the caller's
        // ORIGINAL capture — same-owner relogin fails closed here.
        if (expectedLease != null &&
            (lease.ownerUserId != expectedLease.ownerUserId ||
                lease.incarnation != expectedLease.incarnation)
        ) {
            return null
        }
        return Txn(lease, ownerId)
    }

    private suspend fun checkTxn(
        sessionGate: PendingRetirementSessionGate,
        accountProvider: suspend () -> ProofAccount?,
        txn: Txn,
    ): Boolean {
        val live = try {
            sessionGate.isLive(txn.lease)
        } catch (_: Exception) {
            return false
        }
        if (!live) return false
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            return false
        }
        // T186 M12e (T188 B3): re-read liveness AFTER the account await —
        // the lease may have died while it was in flight.
        val stillLive = try {
            sessionGate.isLive(txn.lease)
        } catch (_: Exception) {
            return false
        }
        if (!stillLive) return false
        return account != null && !account.userId.isBlank() && account.userId == txn.ownerId
    }
}
