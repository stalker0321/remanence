package dev.hryshyn.remanence.pending

import com.google.crypto.tink.KeysetHandle
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * T186 M14d-scan: row re-admission for scan-time opening (the REAL
 * caller of [PendingTrustedAdmission] outside ingest/claim).
 *
 * Re-opens one durable admitted row with live keys: R12 envelope
 * bytes from the row, S/signature bytes from the row, directory pair
 * and recipient keys fresh, deployment pin selected. Returns the
 * caller-owned capsule keyset bytes (zeroize after use — the caller
 * seals or wipes them, never the index). Any mismatch with the
 * admission-time verification throws and yields no key.
 *
 * Used by the scan candidate bridge (recognition manifest decrypt)
 * and, next, by the grant/open path. Never persisted, never logged.
 */
object PendingRowReadmission {

    /**
     * Opens the capsule key for one admitted row. Null inputs fail
     * closed; a `PartiallyAdmitted` outcome (service trust pending)
     * yields no key — scan matching waits for full admission.
     */
    @Suppress("LongParameterList")
    suspend fun openKey(
        row: PendingAdmittedGiftStore.AdmittedGift,
        ownerUserId: String,
        directoryOwnerUserId: String,
        senderVerifyHandle: KeysetHandle,
        recipientPrivateHandle: KeysetHandle,
        recipientPublicKeyset: ByteArray,
        serviceVerifyHandle: KeysetHandle?,
        authorizedServiceKeyId: String?,
        nowEpochSeconds: Long,
    ): ByteArray {
        require(row.ownerUserId.isNotBlank() && row.ownerUserId == ownerUserId) {
            "pending admitted row owner mismatch"
        }
        val sealedEnvelope = try {
            Base64.getDecoder().decode(row.envelopeB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached envelope malformed")
        }
        val statementBytes = try {
            Base64.getDecoder().decode(row.statementB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement malformed")
        }
        val claimBytes = try {
            Base64.getDecoder().decode(row.claimBindingB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached binding malformed")
        }
        val senderSig = try {
            Base64.getDecoder().decode(row.publishSignature69B64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached signature malformed")
        }
        val claimSig = try {
            Base64.getDecoder().decode(row.claimSignature69B64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached claim signature malformed")
        }
        val envelopeSha = unhexStrict(row.envelopeSha256Hex)
        val acceptance = try {
            dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.accept(
                sealedEnvelope = sealedEnvelope,
                expectedEnvelopeSha256 = envelopeSha,
                statementBytes = statementBytes,
                claimBytes = claimBytes,
                senderSignature = senderSig,
                claimSignature = claimSig,
                recipientPrivateHandle = recipientPrivateHandle,
                senderVerifyHandle = senderVerifyHandle,
                serviceVerifyHandle = serviceVerifyHandle,
                expected = dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.LocalExpectation(
                    recipientUserId = ownerUserId,
                    recipientKeyBundleId = row.recipientKeyBundleId,
                    directoryBundleOwnerUserId = directoryOwnerUserId,
                    recipientPublicKeyset = recipientPublicKeyset,
                    nowEpochSeconds = nowEpochSeconds,
                    authorizedServiceKeyId = authorizedServiceKeyId,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalStateException("pending row re-admission rejected")
        }
        return when (acceptance) {
            is dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.Admitted -> {
                // The verified S/R bytes must describe THIS row (capsule,
                // target, claim, H) — internal S/R agreement alone is not
                // enough: a hand-edited index row could otherwise pair one
                // row's ids with another row's sealed bytes and still open.
                try {
                    requireRowBinding(statementBytes, claimBytes, row)
                    acceptance.capsuleAeadKeyset
                } catch (_: Exception) {
                    acceptance.capsuleAeadKeyset.fill(0)
                    throw IllegalStateException("pending row binding mismatch")
                }
            }
            is dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.PartiallyAdmitted -> {
                // Caller-copy contract (same as ingest/claim): the
                // returned key copy is zeroized on EVERY branch, success
                // and failure alike — a partial copy never survives.
                acceptance.capsuleAeadKeyset.fill(0)
                throw IllegalStateException("pending service trust pending")
            }
        }
    }

    /**
     * Binds the durable row columns to the cryptographically VERIFIED
     * bytes (same gate shape as the ingest substitution check, but
     * against the row instead of the feed item): S.capsule/target and
     * R.claim must name this exact row, and H must equal the recomputed
     * statement hash. Pure parse + hash — no suspension, no
     * cancellation to propagate.
     */
    private fun requireRowBinding(
        statementBytes: ByteArray,
        claimBytes: ByteArray,
        row: PendingAdmittedGiftStore.AdmittedGift,
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
        if (uuidString(statement.capsuleId.toByteArray()) != row.capsuleId ||
            uuidString(statement.deliveryTargetId.toByteArray()) != row.deliveryTargetId ||
            uuidString(claim.claimId.toByteArray()) != row.claimId
        ) {
            throw IllegalStateException("pending row binding mismatch")
        }
        val statementShaHex = try {
            java.security.MessageDigest.getInstance("SHA-256").digest(statementBytes)
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            throw IllegalStateException("pending statement hash unavailable")
        }
        if (statementShaHex != row.statementSha256Hex) {
            throw IllegalStateException("pending statement hash mismatch")
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
