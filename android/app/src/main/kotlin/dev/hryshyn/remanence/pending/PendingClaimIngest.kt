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
 * One held transaction (T188 REDO): the caller passes its ORIGINAL
 * lease; this unit re-pins owner AND incarnation against a fresh
 * capture, rechecks liveness after EVERY await (R12, R18, directory,
 * keys) and BEFORE every effect (fetch, admission, upsert), and
 * re-reads the bearer for the same live lease per call — a same-owner
 * replacement can never slip a new incarnation in, and an outer
 * post-ingest fence cannot undo side effects because every effect was
 * already fenced. The FIRST failure throws (the sync keeps its cursor
 * — failure and process loss cannot lose the page; retry is idempotent
 * and owner-fenced).
 *
 * Per feed item, in order: R12 redownload (same envelope bytes) → R18
 * control (exact S + sender signature for H re-derivation) → directory
 * sender-bundle owner + verify key → live recipient keys → strict
 * trusted admission (pair gate, sender + deployment-pinned service
 * signatures, D/KC recomputation) → feed ids bound to the verified
 * bytes → durable admitted row. Every admission outcome (Accepted AND
 * PartiallyAdmitted) is zeroized in `finally`.
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
    private val sessionGate: PendingRetirementSessionGate,
    private val accountProvider: suspend () -> ProofAccount?,
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

    private data class Txn(
        val lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        val ownerId: String,
    )

    private fun beginTxn(
        expectedLease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
    ): Txn? {
        val lease = try {
            sessionGate.capture()
        } catch (_: Exception) {
            return null
        } ?: return null
        val ownerId = lease.ownerUserId.toRestString()
        if (ownerId.isBlank()) return null
        // Pin owner AND incarnation to the caller's ORIGINAL capture —
        // a same-owner replacement fails closed here, never later.
        if (lease.ownerUserId != expectedLease.ownerUserId ||
            lease.incarnation != expectedLease.incarnation
        ) {
            return null
        }
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
        // Re-read liveness AFTER the account await.
        if (!txnLive(txn)) return false
        return account != null && !account.userId.isBlank() && account.userId == txn.ownerId
    }

    /**
     * Ingests every item for [ownerUserId] under [expectedLease] (the
     * caller's ORIGINAL capture). Throws on the first failure (terminal
     * or transient alike — the caller keeps its cursor and retries the
     * same page).
     */
    suspend fun ingest(
        ownerUserId: String,
        expectedLease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        items: List<FeedItemInputs>,
    ) {
        require(ownerUserId.isNotBlank()) { "pending ingest owner required" }
        val txn = beginTxn(expectedLease)
            ?: throw IllegalStateException("pending session changed")
        if (!checkTxn(txn)) throw IllegalStateException("pending session changed")
        if (txn.ownerId != ownerUserId) throw IllegalStateException("pending owner mismatch")
        val pin = try {
            servicePinPort()
        } catch (_: Exception) {
            null
        }
        val serviceVerify = pin?.let { parseServicePin(it) }
        val serviceKeyId = serviceVerify?.let { pin?.deploymentUuid }
        for (item in items) {
            if (!checkTxn(txn)) throw IllegalStateException("pending session changed")
            ingestOne(ownerUserId, txn, item, serviceVerify, serviceKeyId)
        }
    }

    private suspend fun ingestOne(
        ownerUserId: String,
        txn: Txn,
        item: FeedItemInputs,
        serviceVerify: KeysetHandle?,
        serviceKeyId: String?,
    ) {
        require(item.capsuleId.isNotBlank() && item.deliveryId.isNotBlank() &&
            item.deliveryTargetId.isNotBlank() && item.claimId.isNotBlank() &&
            item.envelopeRef.isNotBlank() && item.manifestRef.isNotBlank()) {
            "pending feed item is malformed"
        }
        val claimToken = txnBearer(txn) ?: throw IllegalStateException("pending session changed")
        val claimed = when (
            val redownloaded = try {
                claimPort.claim(item.deliveryId, claimToken)
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
        if (!checkTxn(txn)) throw IllegalStateException("pending session changed")
        val controlToken = txnBearer(txn) ?: throw IllegalStateException("pending session changed")
        val control = when (
            val retrieved = try {
                controlPort.control(item.deliveryId, controlToken)
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
        // The fetched bodies must describe THIS feed item — never index
        // a different capsule/target/claim than the page row.
        if (control.capsuleId != item.capsuleId ||
            control.deliveryTargetId != item.deliveryTargetId ||
            control.deliveryId != item.deliveryId ||
            control.claimId != item.claimId ||
            claimed.claimId != item.claimId
        ) {
            throw IllegalStateException("pending item binding mismatch")
        }
        if (!checkTxn(txn)) throw IllegalStateException("pending session changed")
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
        if (!checkTxn(txn)) throw IllegalStateException("pending session changed")
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
        if (!checkTxn(txn)) throw IllegalStateException("pending session changed")
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
        try {
            // Only Accepted rows admit (partial stays untrusted, never
            // the index) — and EVERY outcome is zeroized below, so a
            // PartiallyAdmitted copy can never survive a throw.
            val admitted = acceptance as? dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.Admitted
                ?: throw IllegalStateException("pending service trust pending")
            // Bind the feed ids to the VERIFIED bytes (never the
            // unsigned JSON echo): the row must describe the capsule
            // the signatures actually admitted.
            requireVerifiedItemBinding(statementBytes, binding, item)
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
            if (!checkTxn(txn)) throw IllegalStateException("pending session changed")
            if (!admittedStore.upsert(row)) {
                throw IllegalStateException("pending admitted index not durable")
            }
        } finally {
            when (acceptance) {
                is dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.Admitted ->
                    acceptance.capsuleAeadKeyset.fill(0)
                is dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.PartiallyAdmitted ->
                    acceptance.capsuleAeadKeyset.fill(0)
            }
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

    /**
     * T186 (T187 B1): binds the feed row to the cryptographically
     * VERIFIED bytes — UUIDs straight from the accepted S/R protos
     * (canonical 16-byte network order via the frozen codec, same
     * layout the verifiers check). A substituted feed item (or echo
     * JSON) naming a different capsule/target/claim than the admitted
     * S/R throws and writes no row.
     */
    private fun requireVerifiedItemBinding(
        statementBytes: ByteArray,
        claimBytes: ByteArray,
        item: FeedItemInputs,
    ) {
        val statement = try {
            dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
                .parseFrom(statementBytes)
        } catch (_: Exception) {
            throw IllegalStateException("pending publish statement malformed")
        }
        val claim = try {
            dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
                .parseFrom(claimBytes)
        } catch (_: Exception) {
            throw IllegalStateException("pending claim binding malformed")
        }
        if (uuidString(statement.capsuleId.toByteArray()) != item.capsuleId ||
            uuidString(statement.deliveryTargetId.toByteArray()) != item.deliveryTargetId ||
            uuidString(claim.claimId.toByteArray()) != item.claimId
        ) {
            throw IllegalStateException("pending item binding mismatch")
        }
    }

    private fun uuidString(raw16: ByteArray): String {
        require(raw16.size == 16) { "pending id malformed" }
        val buffer = java.nio.ByteBuffer.wrap(raw16)
        return java.util.UUID(buffer.long, buffer.long).toString()
    }

    private fun unhexStrict(hex: String): ByteArray {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "digest must be 64 hex"
        }
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
