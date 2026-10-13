package dev.hryshyn.remanence.pending

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.data.network.PendingControlResult
import dev.hryshyn.remanence.core.data.network.PendingRedownloadResult
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * T186 M14b-ingest: receiver-owned claim ingestion for one synced feed
 * page (the REAL caller of [PendingTrustedAdmission] and
 * [PendingAdmittedGiftStore]).
 *
 * Per feed item, in order: R12 redownload (same envelope bytes) → R18
 * control (exact S + sender signature for H re-derivation) → directory
 * sender-bundle owner + verify key → live recipient keys → strict
 * trusted admission (pair gate, sender + deployment-pinned service
 * signatures, D/KC recomputation) → durable admitted row. The FIRST
 * failure throws (the sync keeps its cursor — failure and process
 * loss cannot lose the page; retry is idempotent and owner-fenced).
 *
 * No plaintext, keysets or private material are persisted — only the
 * sealed opaque envelope bytes travel into the index. Manifest/blob
 * bytes download at open time (next milestone). Disabled recipients
 * keep already-delivered rows (no remote revocation here).
 */
class PendingClaimIngest(
    private val claimPort: ClaimFetchPort,
    private val controlPort: ControlFetchPort,
    private val directoryPort: DirectoryPort,
    private val recipientKeysPort: RecipientKeysPort,
    private val servicePinPort: () -> PendingServicePin?,
    private val admittedStore: PendingAdmittedGiftStore,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    fun interface ClaimFetchPort {
        suspend fun claim(
            deliveryId: String,
            token: String,
        ): PendingRedownloadResult
    }

    fun interface ControlFetchPort {
        suspend fun control(
            deliveryId: String,
            token: String,
        ): PendingControlResult
    }

    /** Directory sender bundle: owner + verify keyset bytes (public only). */
    data class DirectoryBundle(
        val ownerUserId: String,
        val signingPublicKeyset: ByteArray,
    )

    fun interface DirectoryPort {
        suspend fun bundle(bundleId: String): DirectoryBundle?
    }

    /** Live recipient identity: keys must unwrap (ARK-ready). */
    data class RecipientKeys(
        val userId: String,
        val bundleId: String,
        val privateHandle: KeysetHandle,
        val publicKeysetBytes: ByteArray,
    )

    fun interface RecipientKeysPort {
        suspend fun keys(): RecipientKeys?
    }

    data class FeedItemInputs(
        val capsuleId: String,
        val deliveryId: String,
        val deliveryTargetId: String,
        val claimId: String,
        val availableAt: Long,
        val envelopeRef: String,
        val manifestRef: String,
        val blobRefs: List<String>,
    )

    /**
     * Ingests every item for [ownerUserId] with [token]. Throws on the
     * first failure (terminal or transient alike — the caller keeps its
     * cursor and retries the same page).
     */
    suspend fun ingest(
        ownerUserId: String,
        token: String,
        items: List<FeedItemInputs>,
    ) {
        require(ownerUserId.isNotBlank()) { "pending ingest owner required" }
        require(token.isNotBlank()) { "pending ingest bearer required" }
        val pin = try {
            servicePinPort()
        } catch (_: Exception) {
            null
        }
        val serviceVerify = pin?.let { parseServicePin(it) }
        val serviceKeyId = serviceVerify?.let { pin?.deploymentUuid }
        for (item in items) {
            ingestOne(ownerUserId, token, item, serviceVerify, serviceKeyId)
        }
    }

    private suspend fun ingestOne(
        ownerUserId: String,
        token: String,
        item: FeedItemInputs,
        serviceVerify: KeysetHandle?,
        serviceKeyId: String?,
    ) {
        require(item.capsuleId.isNotBlank() && item.deliveryId.isNotBlank() &&
            item.deliveryTargetId.isNotBlank() && item.claimId.isNotBlank() &&
            item.envelopeRef.isNotBlank() && item.manifestRef.isNotBlank()) {
            "pending feed item is malformed"
        }
        val claimed = when (
            val redownloaded = try {
                claimPort.claim(item.deliveryId, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw IllegalStateException("pending claim fetch failed")
            }
        ) {
            is PendingRedownloadResult.Redownloaded -> redownloaded
            is PendingRedownloadResult.Failure ->
                throw IllegalStateException("pending claim fetch failed")
        }
        val control = when (
            val retrieved = try {
                controlPort.control(item.deliveryId, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw IllegalStateException("pending control fetch failed")
            }
        ) {
            is PendingControlResult.Control -> retrieved
            is PendingControlResult.Failure ->
                throw IllegalStateException("pending control fetch failed")
        }
        val statementBytes = try {
            Base64.getDecoder().decode(control.publishStatementB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending control statement malformed")
        }
        val senderBundleId = senderBundleOf(statementBytes)
        val directory = try {
            directoryPort.bundle(senderBundleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw IllegalStateException("pending sender bundle unknown")
        val senderVerify = try {
            com.google.crypto.tink.TinkProtoKeysetFormat
                .parseKeysetWithoutSecret(directory.signingPublicKeyset)
        } catch (_: Exception) {
            throw IllegalStateException("pending sender keyset malformed")
        }
        val recipient = try {
            recipientKeysPort.keys()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw IllegalStateException("pending recipient keys unavailable")
        if (recipient.userId.isBlank() || recipient.userId != ownerUserId) {
            throw IllegalStateException("pending recipient owner mismatch")
        }
        val envelope = try {
            Base64.getDecoder().decode(claimed.envelopeB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending envelope malformed")
        }
        val envelopeSha = unhexStrict(claimed.envelopeSha256Hex)
        val binding = try {
            Base64.getDecoder().decode(claimed.claimBindingB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending claim binding malformed")
        }
        val claimSig = try {
            Base64.getDecoder().decode(claimed.claimSignature69B64)
        } catch (_: Exception) {
            throw IllegalStateException("pending claim signature malformed")
        }
        val senderSig = try {
            Base64.getDecoder().decode(control.publishSignature69B64)
        } catch (_: Exception) {
            throw IllegalStateException("pending publish signature malformed")
        }
        val acceptance = try {
            dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.accept(
                sealedEnvelope = envelope,
                expectedEnvelopeSha256 = envelopeSha,
                statementBytes = statementBytes,
                claimBytes = binding,
                senderSignature = senderSig,
                claimSignature = claimSig,
                recipientPrivateHandle = recipient.privateHandle,
                senderVerifyHandle = senderVerify,
                serviceVerifyHandle = serviceVerify,
                expected = dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.LocalExpectation(
                    recipientUserId = recipient.userId,
                    recipientKeyBundleId = recipient.bundleId,
                    directoryBundleOwnerUserId = directory.ownerUserId,
                    recipientPublicKeyset = recipient.publicKeysetBytes,
                    nowEpochSeconds = clockSeconds(),
                    authorizedServiceKeyId = serviceKeyId,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException("pending trusted admission rejected")
        }
        // Only Accepted rows admit (partial stays untrusted, never the
        // index). The caller-owned copies are zeroized right after the
        // sealed bytes are snapshotted for the row.
        val admitted = acceptance as? dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.Admitted
            ?: throw IllegalStateException("pending service trust pending")
        try {
            val row = PendingAdmittedGiftStore.AdmittedGift(
                ownerUserId = ownerUserId,
                capsuleId = item.capsuleId,
                deliveryId = item.deliveryId,
                deliveryTargetId = item.deliveryTargetId,
                claimId = item.claimId,
                availableAt = item.availableAt,
                envelopeB64 = claimed.envelopeB64,
                envelopeSha256Hex = claimed.envelopeSha256Hex,
                envelopeRef = item.envelopeRef,
                manifestRef = item.manifestRef,
                blobRefs = item.blobRefs.toList(),
                completedAt = null,
                admittedAtMillis = System.currentTimeMillis(),
            )
            if (!admittedStore.upsert(row)) {
                throw IllegalStateException("pending admitted index not durable")
            }
        } finally {
            admitted.capsuleAeadKeyset.fill(0)
        }
    }

    private fun senderBundleOf(statementBytes: ByteArray): String {
        val statement = try {
            dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
                .parseFrom(statementBytes)
        } catch (_: Exception) {
            throw IllegalStateException("pending publish statement malformed")
        }
        val raw = statement.senderKeyBundleId.toByteArray()
        require(raw.size == 16) { "pending sender bundle id malformed" }
        val buffer = java.nio.ByteBuffer.wrap(raw)
        return java.util.UUID(buffer.long, buffer.long).toString()
    }

    private fun parseServicePin(pin: PendingServicePin): KeysetHandle? =
        PendingServicePinConfig.parseVerificationKeyset(pin.publicKeysetB64)

    private fun unhexStrict(hex: String): ByteArray {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "digest must be 64 hex"
        }
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
