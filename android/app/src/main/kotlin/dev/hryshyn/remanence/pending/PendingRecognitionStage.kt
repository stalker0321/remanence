package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * T186 M14d-scan: reception-time staging of the kind-1 recognition
 * ciphertext (the sealed FRONT manifest) into the admitted row.
 *
 * Scan index enumeration is offline and token-less by design (mirroring
 * the v1 sealed Room rows): both admitters (claim-time
 * [PendingProofViewModel], sync-time [PendingClaimIngest]) fetch the
 * tiny kind-1 blob while they still hold a bearer, verify size +
 * SHA-256 against the verified S declaration, and persist the
 * ciphertext bytes inline in the row. The scan bridge later decrypts
 * them under the re-admitted capsule key with the genuine target AAD
 * — no network, no token, no key handling at reception beyond the
 * admission that already ran.
 *
 * Best-effort: any staging failure yields null/"" and the row is still
 * admitted (the gift opens normally; it simply does not enumerate
 * until a later sync upsert heals the row — upsert replaces). Only
 * [CancellationException] propagates.
 */
object PendingRecognitionStage {

    /** Single kind-1 (recognition, ordinal -1) binding from verified S. */
    data class Kind1Binding(
        val blobId: String,
        val ciphertextSize: Long,
        val ciphertextSha256: ByteArray,
    )

    fun interface BlobFetchPort {
        suspend fun blobBytes(
            deliveryId: String,
            blobId: String,
            token: String,
        ): PendingBlobBytesResult
    }

    /**
     * Selects the single kind-1/ordinal-1 binding from verified S.
     * Null when absent or ambiguous (photos-only publications carry no
     * FRONT and never enumerate — fail closed, never guessed).
     */
    fun selectKind1(statementBytes: ByteArray): Kind1Binding? {
        val statement = try {
            dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
                .parseFrom(statementBytes)
        } catch (_: Exception) {
            return null
        }
        val recognition = statement.artifactsList.filter { it.kind == 1 && it.ordinal == -1 }
        if (recognition.size != 1) return null
        val binding = recognition.single()
        if (binding.ciphertextSize < MIN_STAGED_BYTES ||
            binding.ciphertextSize > MAX_STAGED_BYTES
        ) {
            return null
        }
        val digest = binding.ciphertextSha256.toByteArray()
        if (digest.size != 32) return null
        val rawId = binding.blobId.toByteArray()
        if (rawId.size != 16) return null
        val buffer = java.nio.ByteBuffer.wrap(rawId)
        val blobId = java.util.UUID(buffer.long, buffer.long).toString()
        return Kind1Binding(blobId, binding.ciphertextSize, digest)
    }

    /**
     * Fetches and verifies the kind-1 ciphertext (exact size + SHA-256
     * against the S declaration, same checks as open-time). Returns the
     * caller-owned bytes, or null on any failure. Cancellation
     * propagates; nothing is persisted here.
     */
    suspend fun fetchVerified(
        deliveryId: String,
        token: String,
        binding: Kind1Binding,
        port: BlobFetchPort,
    ): ByteArray? {
        if (deliveryId.isBlank() || token.isBlank()) return null
        val bytes = when (
            val downloaded = try {
                port.blobBytes(deliveryId, binding.blobId, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
        ) {
            is PendingBlobBytesResult.Bytes -> downloaded.bytes
            is PendingBlobBytesResult.Failure -> return null
        }
        if (bytes.size.toLong() != binding.ciphertextSize) return null
        val actual = try {
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        } catch (_: Exception) {
            return null
        }
        if (!actual.contentEquals(binding.ciphertextSha256)) return null
        return bytes
    }

    /**
     * Lower bound: below an AEAD tag + framing no recognition ciphertext
     * is plausible. Upper bound: the staged bytes decrypt to a FRONT
     * within the frozen v1 fingerprint limit (referenced, never
     * redefined); the margin covers the AEAD tag. Anything outside fails
     * closed (no row field).
     */
    private const val MIN_STAGED_BYTES = 48L
    private val MAX_STAGED_BYTES =
        dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec.MAX_FINGERPRINT_BYTES + 128L
}
