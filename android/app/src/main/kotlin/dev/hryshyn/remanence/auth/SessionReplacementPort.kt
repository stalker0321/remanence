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
     * Serializes account replacement: close refresh admission, run
     * [commitAccount], then atomically publish bound credentials and open
     * the domain only if [lease] is still current and the current account
     * owner equals [expectedOwner]. On failure the domain stays closed with
     * no usable published credentials. Must not perform server auth.
     */
    suspend fun replace(
        lease: Long,
        expectedOwner: UserId,
        accessToken: String,
        refreshToken: String,
        commitAccount: suspend () -> Unit,
    )
}
