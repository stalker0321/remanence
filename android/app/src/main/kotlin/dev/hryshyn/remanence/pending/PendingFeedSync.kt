package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.PendingFeedItem
import dev.hryshyn.remanence.core.data.network.PendingFeedResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * T186 M13: receiver-owned v2 feed sync (one page per call).
 *
 * Consumes the claim-time v2 feed for the fixed claim owner through
 * the real feed port: capture a fresh lease, fetch one page with its
 * bearer, recheck owner/incarnation liveness after the await, then
 * persist the next cursor (owner-fenced) and publish the items.
 * Failures keep the old cursor (the next sync retries the same page —
 * the feed is idempotent). Disabled recipients keep already-delivered
 * envelopes locally (no remote revocation is ever issued here).
 * Admission of fetched envelopes into scan/display is the next
 * milestone — this component only syncs and persists feed position.
 */
class PendingFeedSync(
    private val fetchPort: FetchPort,
    private val cursorStore: PendingFeedCursorStore,
    private val sessionGate: PendingRetirementSessionGate,
    private val accountProvider: suspend () -> ProofAccount?,
    scope: CoroutineScope? = null,
) {

    fun interface FetchPort {
        suspend fun page(cursor: String?, limit: Int, token: String): PendingFeedResult
    }

    sealed interface State {
        data object Idle : State
        data object Syncing : State
        data class Synced(val items: List<PendingFeedItem>, val exhausted: Boolean) : State
        data class Failed(val reason: Reason) : State
    }

    enum class Reason {
        NETWORK,
        SESSION_CHANGED,
        GENERIC,
    }

    private val launchScope: CoroutineScope? = scope

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun beginTxn(): Txn? {
        val lease = try {
            sessionGate.capture()
        } catch (_: Exception) {
            return null
        } ?: return null
        val ownerId = lease.ownerUserId.toRestString()
        if (ownerId.isBlank()) return null
        return Txn(lease, ownerId)
    }

    private fun txnLive(txn: Txn): Boolean = try {
        sessionGate.isLive(txn.lease)
    } catch (_: Exception) {
        false
    }

    private fun txnBearer(txn: Txn): String? {
        if (!txnLive(txn)) return null
        return try {
            sessionGate.accessTokenFor(txn.lease).takeIf { !it.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun checkTxn(txn: Txn): Boolean {
        if (!txnLive(txn)) return false
        val account = try {
            accountProvider()
        } catch (_: Exception) {
            return false
        }
        // T188 B3: re-read liveness AFTER the account await.
        if (!txnLive(txn)) return false
        return account != null && !account.userId.isBlank() && account.userId == txn.ownerId
    }

    private data class Txn(
        val lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        val ownerId: String,
    )

    /**
     * Fetches one page for the current owner. Suspends; callers loop
     * until [State.Synced.exhausted].
     */
    suspend fun sync(limit: Int = 50): State {
        _state.value = State.Syncing
        return try {
            syncPage(limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            State.Failed(Reason.GENERIC).also { _state.value = it }
        }.also { _state.value = it }
    }

    fun syncAsync(limit: Int = 50) {
        val scope = launchScope ?: return
        scope.launch { sync(limit) }
    }

    private suspend fun syncPage(limit: Int): State {
        val txn = beginTxn() ?: return State.Failed(Reason.SESSION_CHANGED)
        if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
        val cursor = cursorStore.peek(txn.ownerId)
        val token = txnBearer(txn) ?: return State.Failed(Reason.SESSION_CHANGED)
        val page = try {
            fetchPort.page(cursor, limit, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return State.Failed(Reason.NETWORK)
        }
        if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
        return when (page) {
            is PendingFeedResult.Page -> {
                // Durable position only after a fenced success, for the
                // SAME owner that fetched it.
                if (!checkTxn(txn)) return State.Failed(Reason.SESSION_CHANGED)
                try {
                    cursorStore.save(txn.ownerId, page.nextCursor)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Cursor loss only repeats a page (idempotent feed).
                }
                State.Synced(page.items, page.nextCursor.isEmpty())
            }
            is PendingFeedResult.Failure -> State.Failed(Reason.NETWORK)
        }
    }
}
