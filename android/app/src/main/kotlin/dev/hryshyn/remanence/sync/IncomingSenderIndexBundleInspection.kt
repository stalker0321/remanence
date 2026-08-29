package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.index.SenderIndexBundleReadRequest
import dev.hryshyn.remanence.index.SenderIndexBundleReadResult
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId

/** The redacted owner/capsule request for durable sender-index replay proof. */
class IncomingSenderIndexBundleInspectionRequest(
    val authenticatedOwnerUserId: UserId?,
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
) {
    override fun toString(): String =
        "IncomingSenderIndexBundleInspectionRequest(<redacted>)"
}

enum class IncomingSenderIndexBundleInspectionUnavailableReason {
    LOCAL_STORAGE,
    SEALER_UNAVAILABLE,
}

/** An opaque close-only handle; no A12a path, ciphertext, or plaintext escapes. */
class IncomingSenderIndexBundleInspectionSnapshot internal constructor(
    private val closeDelegate: () -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        // Replay classification and account checks are already authoritative;
        // cleanup must not replace that result with a close/wipe failure.
        try {
            closeDelegate()
        } catch (_: Exception) {
            // The reader owns best-effort in-memory wiping. Keep this opaque
            // replay handle idempotent and non-throwing at the coordinator boundary.
        }
    }

    override fun toString(): String =
        "IncomingSenderIndexBundleInspectionSnapshot(<redacted>)"
}

/** Narrow replay-only inspection result consumed by the acceptance coordinator. */
sealed interface IncomingSenderIndexBundleInspectionResult {
    class Available internal constructor(
        internal val snapshot: IncomingSenderIndexBundleInspectionSnapshot,
    ) : IncomingSenderIndexBundleInspectionResult {
        override fun toString(): String =
            "IncomingSenderIndexBundleInspectionResult.Available(<redacted>)"
    }

    data object Missing : IncomingSenderIndexBundleInspectionResult
    data object Invalid : IncomingSenderIndexBundleInspectionResult

    data class Unavailable(
        val reason: IncomingSenderIndexBundleInspectionUnavailableReason,
    ) : IncomingSenderIndexBundleInspectionResult
}

/** Read-only inspection dependency for the INDEX_CACHED+CACHED replay path. */
fun interface IncomingSenderIndexBundleInspectionPort {
    suspend fun inspect(
        request: IncomingSenderIndexBundleInspectionRequest,
    ): IncomingSenderIndexBundleInspectionResult
}

/** Production adapter that keeps the A12b2 reader snapshot opaque to A11d1. */
class SenderIndexBundleInspectionAdapter(
    private val reader: SenderIndexBundleReader,
) : IncomingSenderIndexBundleInspectionPort {

    override suspend fun inspect(
        request: IncomingSenderIndexBundleInspectionRequest,
    ): IncomingSenderIndexBundleInspectionResult = when (
        val result = reader.inspect(
            SenderIndexBundleReadRequest(
                authenticatedOwnerUserId = request.authenticatedOwnerUserId,
                ownerUserId = request.ownerUserId,
                capsuleId = request.capsuleId,
            ),
        )
    ) {
        is SenderIndexBundleReadResult.Available ->
            IncomingSenderIndexBundleInspectionResult.Available(
                IncomingSenderIndexBundleInspectionSnapshot { result.snapshot.close() },
            )
        SenderIndexBundleReadResult.Missing ->
            IncomingSenderIndexBundleInspectionResult.Missing
        is SenderIndexBundleReadResult.Corrupt ->
            IncomingSenderIndexBundleInspectionResult.Invalid
        is SenderIndexBundleReadResult.Unavailable ->
            IncomingSenderIndexBundleInspectionResult.Unavailable(
                when (result.reason) {
                    dev.hryshyn.remanence.index.SenderIndexBundleReadUnavailableReason.LOCAL_STORAGE ->
                        IncomingSenderIndexBundleInspectionUnavailableReason.LOCAL_STORAGE
                    dev.hryshyn.remanence.index.SenderIndexBundleReadUnavailableReason.SEALER_UNAVAILABLE ->
                        IncomingSenderIndexBundleInspectionUnavailableReason.SEALER_UNAVAILABLE
                },
            )
    }
}
