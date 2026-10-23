package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.model.UserId

/**
 * Explicit replacement hook owned by login/registration orchestration.
 * Acquire a non-closing lease before server auth; carry it through
 * [replace]. Logout retires the lease so a stale server outcome cannot
 * commit or reopen the domain.
 */
interface SessionReplacementPort {
    /** Non-closing account-boundary lease. Must not wait on network. */
    fun acquireLease(): Long

    /**
     * Serializes account replacement: reject a retired lease without mutation;
     * otherwise close refresh admission, run
     * [commitAccount], then atomically publish bound credentials and open
     * the domain only if [lease] is still current and the current account
     * owner equals [expectedOwner]. Failure after admission leaves the domain
     * closed with no usable published credentials. Must not perform server auth.
     */
    suspend fun replace(
        lease: Long,
        expectedOwner: UserId,
        accessToken: String,
        refreshToken: String,
        commitAccount: suspend () -> Unit,
    )

    /** Additional RAM operation fence, atomically checked before closing admission. */
    suspend fun replaceGuarded(
        lease: Long, expectedOwner: UserId, accessToken: String, refreshToken: String,
        admit: () -> Boolean, commitLocal: (() -> Unit) -> Unit, commitAccount: suspend () -> Unit,
    ) {
        // An adapter without publication-fenced admission must never silently weaken it.
        error("guarded replacement unavailable")
    }
}
