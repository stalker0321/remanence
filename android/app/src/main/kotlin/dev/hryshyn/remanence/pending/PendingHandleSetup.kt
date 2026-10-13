package dev.hryshyn.remanence.pending

/**
 * T186 M2: one-time handle setup (T185 `api-contract.md` §2/R17, client side).
 *
 * - Locks NULL → one unique valid handle, exactly once (immutable, no
 *   auto-merge; mirrors [PendingReceivePolicy.bindHandleOnce]).
 * - Identical replay succeeds (idempotent); any different later value is
 *   `HANDLE_IMMUTABLE` (surfaced generically, never echoing the taken name).
 * - Legacy v1 handle mutation stays disabled in recovery-enabled mode —
 *   untouched here.
 * - Server is authoritative on commit; this machine only models the client
 *   request/response mapping for R17 (routes still scaffold).
 */
object PendingHandleSetup {

    sealed interface Result {
        data class Committed(val handle: String) : Result
        data class ReplayOk(val handle: String) : Result
        data object ImmutableConflict : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Maps a local request against the known bound handle and the server
     * problem code. [serverProblem] is the R17 `problem` string when the
     * server rejected, else null.
     */
    fun resolve(
        currentHandle: String?,
        requestedHandle: String,
        serverProblem: String?,
    ): Result {
        if (requestedHandle.isBlank()) return Result.Rejected("invalid-handle")
        if (currentHandle.isNullOrBlank()) {
            return when (serverProblem) {
                null -> Result.Committed(requestedHandle)
                "HANDLE_IMMUTABLE" -> Result.ImmutableConflict
                else -> Result.Rejected(serverProblem)
            }
        }
        return if (requestedHandle == currentHandle && serverProblem == null) {
            Result.ReplayOk(currentHandle)
        } else {
            Result.ImmutableConflict
        }
    }
}
