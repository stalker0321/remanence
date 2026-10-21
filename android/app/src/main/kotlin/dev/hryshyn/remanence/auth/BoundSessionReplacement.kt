package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.data.network.SessionRefreshCoordinator
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production session replacement. [replacementMutex] serializes login vs
 * registration. The coordinator owns credential mutation: stage nothing in
 * the holder until [SessionRefreshCoordinator.publishBoundSession] atomically
 * persists sealed+memory credentials and opens the domain, or leaves no
 * usable credentials.
 */
class BoundSessionReplacement(
    private val coordinator: SessionRefreshCoordinator,
    private val currentAccountOwner: suspend () -> UserId?,
) : SessionReplacementPort {

    private val replacementMutex = Mutex()

    override fun acquireLease(): Long = coordinator.acquireAccountLease()

    override suspend fun replace(
        lease: Long,
        expectedOwner: UserId,
        accessToken: String,
        refreshToken: String,
        commitAccount: suspend () -> Unit,
    ) = replaceGuarded(lease, expectedOwner, accessToken, refreshToken, { true }, { it() }, commitAccount)

    override suspend fun replaceGuarded(
        lease: Long, expectedOwner: UserId, accessToken: String, refreshToken: String,
        admit: () -> Boolean, commitLocal: (() -> Unit) -> Unit, commitAccount: suspend () -> Unit,
    ) {
        replacementMutex.withLock {
            // Reject a retired response before account writes or fail-closed
            // cleanup. Neither belongs to this attempt after logout/new login.
            check(coordinator.closeAdmission(lease, admit)) { "bound session lease retired" }
            var opened = false
            try {
                commitAccount()
                val accountOwner = currentAccountOwner()
                check(coordinator.commitLocalRecovery(lease) {
                    commitLocal {
                        check(admit()) { "bound operation retired before publish" }
                        if (!coordinator.publishBoundSession(
                                lease = lease,
                                expectedOwner = expectedOwner,
                                accessToken = accessToken,
                                refreshToken = refreshToken,
                                currentAccountOwner = accountOwner,
                            )
                        ) {
                            throw IllegalStateException("bound session publish rejected")
                        }
                        opened = true
                    }
                }) { "bound session lease retired before publish" }
            } finally {
                if (!opened) {
                    coordinator.discardPublishedCredentials()
                }
            }
        }
    }
}
