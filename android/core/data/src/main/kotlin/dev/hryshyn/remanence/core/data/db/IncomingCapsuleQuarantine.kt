package dev.hryshyn.remanence.core.data.db

import kotlin.coroutines.cancellation.CancellationException

/**
 * Redacted outcomes of the exact incoming-capsule quarantine CAS.
 *
 * No outcome reveals whether a different owner has a row for the requested
 * capsule. The operation changes only the capsule material state; blob and
 * filesystem state are deliberately outside this boundary.
 */
sealed interface IncomingCapsuleQuarantineResult {
    data object Quarantined : IncomingCapsuleQuarantineResult

    data object AlreadyCorrupt : IncomingCapsuleQuarantineResult

    data object MissingOrForeignOwner : IncomingCapsuleQuarantineResult

    data object ConcurrentOrStateChanged : IncomingCapsuleQuarantineResult

    data object DatabaseUnavailable : IncomingCapsuleQuarantineResult
}

/**
 * Resolves the result of the exact CAS without exposing provider exceptions.
 * This is internal so the cancellation and zero-row policy can be tested
 * without replacing the real Room DAO in the database tests.
 */
internal suspend fun resolveIncomingCapsuleQuarantine(
    compareAndSet: suspend () -> Int,
    rereadOwnedCapsule: suspend () -> IncomingCapsuleEntity?,
): IncomingCapsuleQuarantineResult = try {
    if (compareAndSet() == 1) {
        IncomingCapsuleQuarantineResult.Quarantined
    } else {
        when (val row = rereadOwnedCapsule()) {
            null -> IncomingCapsuleQuarantineResult.MissingOrForeignOwner
            else -> if (
                row.serverStatus == "READY" &&
                row.materialState == dev.hryshyn.remanence.core.model.LocalMaterialState.CORRUPT
            ) {
                IncomingCapsuleQuarantineResult.AlreadyCorrupt
            } else {
                IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged
            }
        }
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    IncomingCapsuleQuarantineResult.DatabaseUnavailable
}
