package dev.hryshyn.remanence.session

import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of trying to establish the session needed by an account-scoped
 * background operation. Only [Ready] permits the operation to touch its
 * existing network/database path.
 */
internal sealed interface SessionOwnerResolution {
    data object Ready : SessionOwnerResolution

    /** The account/session is currently unavailable, but a later worker may retry. */
    data object Retryable : SessionOwnerResolution

    /** Logout or an absent local account closed the owner boundary. */
    data object NoOwner : SessionOwnerResolution

    /** The persisted/current owner is not the worker's immutable owner. */
    data object AccountChanged : SessionOwnerResolution

    /** The server definitively rejected the persisted session. */
    data object Rejected : SessionOwnerResolution

    /** The account exists but its local identity is not usable for this operation. */
    data object RecoveryRequired : SessionOwnerResolution
}

/**
 * Process-death entry point for account-scoped workers. It deliberately calls
 * the same bootstrap resolver used by the root, so a newly-created empty
 * [AuthTokenHolder][dev.hryshyn.remanence.core.data.network.AuthTokenHolder]
 * is populated through the shared [SessionRefreshCoordinator] before work
 * starts. No Activity or RootViewModel is required.
 */
internal class SessionOwnerCoordinator(
    private val sessionResolver: SessionStateResolver,
    private val currentOwner: suspend () -> UserId?,
    private val liveAccessToken: () -> String?,
) {

    suspend fun ensure(expectedOwner: UserId): SessionOwnerResolution {
        when (val before = readOwner()) {
            OwnerRead.Unavailable -> return SessionOwnerResolution.Retryable
            OwnerRead.Missing -> return SessionOwnerResolution.NoOwner
            is OwnerRead.Present -> if (before.owner != expectedOwner) {
                return SessionOwnerResolution.AccountChanged
            }
        }

        val state = try {
            sessionResolver.bootstrap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SessionOwnerResolution.Retryable
        }

        when (val after = readOwner()) {
            OwnerRead.Unavailable -> return SessionOwnerResolution.Retryable
            OwnerRead.Missing -> return SessionOwnerResolution.NoOwner
            is OwnerRead.Present -> if (after.owner != expectedOwner) {
                return SessionOwnerResolution.AccountChanged
            }
        }

        when (state) {
            is SessionState.Active -> {
                val resolvedOwner = state.userId
                    ?.let { runCatching { UserId.parseRest(it) }.getOrNull() }
                if (resolvedOwner != expectedOwner) {
                    return SessionOwnerResolution.AccountChanged
                }
                return if (liveAccessToken()?.isNotBlank() == true) {
                    SessionOwnerResolution.Ready
                } else {
                    SessionOwnerResolution.Retryable
                }
            }

            is SessionState.OfflineActive,
            SessionState.RequiresConnectivity,
            -> return SessionOwnerResolution.Retryable

            SessionState.SignedOut -> return SessionOwnerResolution.Rejected
            SessionState.RecoveryRequired -> return SessionOwnerResolution.RecoveryRequired
        }
    }

    private suspend fun readOwner(): OwnerRead = try {
        currentOwner()?.let(OwnerRead::Present) ?: OwnerRead.Missing
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OwnerRead.Unavailable
    }

    private sealed interface OwnerRead {
        data object Unavailable : OwnerRead
        data object Missing : OwnerRead
        data class Present(val owner: UserId) : OwnerRead
    }
}
