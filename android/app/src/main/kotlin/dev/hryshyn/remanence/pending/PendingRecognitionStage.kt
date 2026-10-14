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
 * Best-effort at claim time (the claim stays truthful when staging
 * fails); REQUIRED at sync-ingest time (a fetch failure throws so the
 * feed cursor is preserved and the page retries — the cursor never
 * advances past a half-staged gift). Re-admission never clobbers a
 * good staged cipher with a blank ([keepVerifiedPrior]). Only
 * [CancellationException] propagates.
 */
object PendingRecognitionStage {

    /** Single kind-1 (recognition, ordinal -1) binding from verified S. */
    data class Kind1Binding(
        val blobId: String,
        val ciphertextSize: Long,
        val ciphertextSha256: ByteArray,
    )

    /**
     * T186 M14d-scan (T188 B3): three-way selection so callers can
     * distinguish a genuinely absent FRONT (photos-only publication —
     * admits normally, never enumerates) from a declared-but-unusable
     * one (ambiguous, malformed, out-of-bounds — the cursor-preserving
     * path must throw, never silently skip a signed declaration).
     */
    sealed interface Kind1Selection {
        /** No kind-1 binding declared: nothing to stage. */
        data object Absent : Kind1Selection

        /** Exactly one usable kind-1 binding. */
        data class Present(val binding: Kind1Binding) : Kind1Selection

        /** Declared but ambiguous or malformed: fail closed. */
        data object Unusable : Kind1Selection
    }

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
     * FRONT and never enumerate — fail closed, never guessed). Callers
     * with a durability obligation (cursor) use [selectKind1Detailed].
     */
    fun selectKind1(statementBytes: ByteArray): Kind1Binding? = when (
        val selection = selectKind1Detailed(statementBytes)
    ) {
        is Kind1Selection.Present -> selection.binding
        is Kind1Selection.Absent,
        is Kind1Selection.Unusable,
        -> null
    }

    /**
     * Three-way selection from verified S: empty kind-1 set is
     * [Kind1Selection.Absent]; exactly one well-formed binding is
     * [Kind1Selection.Present]; anything else declared is
     * [Kind1Selection.Unusable]. Unparseable input reads as Absent
     * (callers stage only after admission verified S).
     */
    fun selectKind1Detailed(statementBytes: ByteArray): Kind1Selection {
        val statement = try {
            dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
                .parseFrom(statementBytes)
        } catch (_: Exception) {
            return Kind1Selection.Absent
        }
        val recognition = statement.artifactsList.filter { it.kind == 1 && it.ordinal == -1 }
        if (recognition.isEmpty()) return Kind1Selection.Absent
        if (recognition.size != 1) return Kind1Selection.Unusable
        val binding = recognition.single()
        if (binding.ciphertextSize < MIN_STAGED_BYTES ||
            binding.ciphertextSize > MAX_STAGED_BYTES
        ) {
            return Kind1Selection.Unusable
        }
        val digest = binding.ciphertextSha256.toByteArray()
        if (digest.size != 32) return Kind1Selection.Unusable
        val rawId = binding.blobId.toByteArray()
        if (rawId.size != 16) return Kind1Selection.Unusable
        val buffer = java.nio.ByteBuffer.wrap(rawId)
        val blobId = java.util.UUID(buffer.long, buffer.long).toString()
        return Kind1Selection.Present(Kind1Binding(blobId, binding.ciphertextSize, digest))
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
     * No-blank-overwrite rule for re-admission: when a fresh fetch
     * yields nothing but the durable row already holds staged bytes,
     * keep them ONLY if they still verify against the CURRENT S
     * declaration (size + SHA-256). Stale bytes (re-published S) or
     * undecodable input yield "" — never a good cipher clobbered by a
     * blank, never stale bytes resurrected. Pure decode + hash.
     */
    fun keepVerifiedPrior(priorB64: String, statementBytes: ByteArray): String {
        if (priorB64.isBlank()) return ""
        val binding = selectKind1(statementBytes) ?: return ""
        val bytes = try {
            java.util.Base64.getDecoder().decode(priorB64)
        } catch (_: Exception) {
            return ""
        }
        if (bytes.size.toLong() != binding.ciphertextSize) return ""
        val actual = try {
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        } catch (_: Exception) {
            return ""
        }
        if (!actual.contentEquals(binding.ciphertextSha256)) return ""
        return priorB64
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
