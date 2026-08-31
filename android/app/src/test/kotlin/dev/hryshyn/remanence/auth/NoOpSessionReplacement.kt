package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.model.UserId

internal object NoOpSessionReplacement : SessionReplacementPort {
    override fun acquireLease(): Long = 0L

    override suspend fun replace(
        lease: Long,
        expectedOwner: UserId,
        accessToken: String,
        refreshToken: String,
        commitAccount: suspend () -> Unit,
    ) {
        commitAccount()
    }
}
