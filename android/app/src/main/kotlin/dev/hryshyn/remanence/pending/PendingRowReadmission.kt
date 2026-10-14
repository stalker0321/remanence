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
            is dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.Admitted ->
                acceptance.capsuleAeadKeyset
            is dev.hryshyn.remanence.core.crypto.PendingTrustedAdmission.Admission.PartiallyAdmitted ->
                throw IllegalStateException("pending service trust pending")
        }
    }

    private fun unhexStrict(hex: String): ByteArray {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "digest must be 64 hex"
        }
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
