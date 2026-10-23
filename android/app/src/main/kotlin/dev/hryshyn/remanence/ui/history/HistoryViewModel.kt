package dev.hryshyn.remanence.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val owner: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<OutgoingItem> = emptyList(),
    val nextCursor: String? = null,
    val error: HistoryFailure? = null,
    val selectedKey: String? = null,
    val confirmKey: String? = null,
    val cancelling: Boolean = false,
    val outcome: HistoryCancelResult? = null,
) {
    val selected: OutgoingItem? get() = items.find { it.key == selectedKey }
}
data class RecentUiState(
    val owner: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<RecentRecipient> = emptyList(),
    val error: HistoryFailure? = null,
)

/** Memory-only metadata. A route selection is never a presentation grant. */
class HistoryViewModel internal constructor(
    private val port: OutgoingHistoryPort,
    private val captureLease: () -> SessionRequestLease?,
    private val leaseLive: (SessionRequestLease) -> Boolean,
    private val boundary: SessionBoundary,
    private val pendingEnabled: Boolean,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private var owner: UserId? = null
    private var lease: SessionRequestLease? = null
    private var epoch = boundary.currentEpoch()
    private var generation = 0L
    private var historyJob: Job? = null
    private var recentJob: Job? = null
    private var cancelJob: Job? = null
    private val _state = MutableStateFlow(HistoryUiState())
    val state = _state.asStateFlow()
    private val _recent = MutableStateFlow(RecentUiState())
    val recent = _recent.asStateFlow()
    private val unregister = boundary.register(::clearSession)

    fun bind(rawOwner: String?) {
        val next = rawOwner?.let { runCatching { UserId.parseRest(it) }.getOrNull() }
        val nextLease = captureLease()?.takeIf { it.ownerUserId == next && leaseLive(it) }
        if (owner == next && lease == nextLease && epoch == boundary.currentEpoch() &&
            _state.value.owner == rawOwner) return
        clearSession()
        if (next == null) return
        if (nextLease == null) {
            showSessionRequired(rawOwner)
            return
        }
        owner = next
        lease = nextLease
        _state.value = HistoryUiState(owner = rawOwner)
        _recent.value = RecentUiState(owner = rawOwner)
    }

    private fun clearSession() {
        generation++
        historyJob?.cancel(); recentJob?.cancel(); cancelJob?.cancel()
        historyJob = null; recentJob = null; cancelJob = null
        owner = null
        lease = null
        epoch = boundary.currentEpoch()
        _state.value = HistoryUiState()
        _recent.value = RecentUiState()
    }

    private fun live(): Boolean = lease?.let { it.ownerUserId == owner && leaseLive(it) } == true &&
        epoch == boundary.currentEpoch()

    private fun showSessionRequired(rawOwner: String?) {
        // Retain only the route's owner fence, never its private metadata.
        _state.value = HistoryUiState(owner = rawOwner, error = HistoryFailure.SIGN_IN)
        _recent.value = RecentUiState(owner = rawOwner, error = HistoryFailure.SIGN_IN)
    }

    private fun retireRejectedSession() {
        val routeOwner = owner?.toRestString()
        clearSession()
        showSessionRequired(routeOwner)
    }

    private fun publish(ticket: Long, block: () -> Unit) {
        boundary.withCurrentLease(epoch) {
            if (generation == ticket) {
                if (live()) block()
                else {
                    // HTTP/authenticator can retire the lease before Root
                    // publishes its next auth state. Do not leave a stale page
                    // or recents under an eternal loading indicator meanwhile.
                    retireRejectedSession()
                }
            }
        }
    }

    fun refresh() = load(more = false)
    fun loadMore() = load(more = true)
    private fun load(more: Boolean) {
        val expectedOwner = owner ?: return
        val previous = _state.value
        if (!live() || previous.loading || previous.cancelling) return
        val cursor = if (more) previous.nextCursor ?: return else null
        val ticket = generation
        _state.value = previous.copy(loading = true, error = null, confirmKey = null,
            items = if (more) previous.items else emptyList(), nextCursor = if (more) cursor else null)
        historyJob = scope.launch {
            val result = try { port.outgoing(expectedOwner, cursor) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { HistoryResult.Failed(HistoryFailure.SERVER) }
            publish(ticket) {
                if (result is HistoryResult.Failed && result.reason == HistoryFailure.SIGN_IN) {
                    retireRejectedSession()
                    return@publish
                }
                _state.value = when (result) {
                    is HistoryResult.Loaded -> {
                        val items = (if (more) previous.items + result.value.items else result.value.items)
                            .distinctBy { it.key }
                        _state.value.copy(loading = false, loaded = true, items = items,
                            nextCursor = result.value.nextCursor,
                            // Navigation can change while GET is in flight.
                            // Never reopen a detail the user has left, or undo
                            // a newer selection made during pagination.
                            selectedKey = _state.value.selectedKey?.takeIf { key -> items.any { it.key == key } })
                    }
                    is HistoryResult.Failed -> _state.value.copy(loading = false, error = result.reason)
                }
            }
        }
    }

    fun loadRecent() {
        val expectedOwner = owner ?: return
        if (!live() || _recent.value.loading) return
        val ticket = generation
        _recent.value = RecentUiState(owner = expectedOwner.toRestString(), loading = true)
        recentJob = scope.launch {
            val result = try { port.recent(expectedOwner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { HistoryResult.Failed(HistoryFailure.SERVER) }
            publish(ticket) {
                if (result is HistoryResult.Failed && result.reason == HistoryFailure.SIGN_IN) {
                    retireRejectedSession()
                    return@publish
                }
                _recent.value = when (result) {
                    is HistoryResult.Loaded -> RecentUiState(expectedOwner.toRestString(), loaded = true, items = result.value)
                    is HistoryResult.Failed -> RecentUiState(expectedOwner.toRestString(), error = result.reason)
                }
            }
        }
    }

    /** Suggestion only; the caller must perform the existing fresh lookup/confirm. */
    fun recentForLookup(userId: String): RecentRecipient? =
        if (live()) _recent.value.items.find { it.userId == userId } else null

    fun select(key: String) {
        if (live() && !_state.value.cancelling && _state.value.items.any { it.key == key })
            _state.value = _state.value.copy(selectedKey = key, confirmKey = null, outcome = null)
    }
    fun backToList() {
        _state.value = _state.value.copy(selectedKey = null, confirmKey = null)
    }
    fun codeForCopy(key: String): String? =
        if (live()) _state.value.items.find { it.key == key }?.code else null

    fun canCancel(item: OutgoingItem): Boolean = live() && item.detailsAvailable &&
        !_state.value.loading && !_state.value.cancelling && when (item.cancelAction) {
            HistoryCancelAction.REVOKE_V1 -> true
            HistoryCancelAction.CANCEL_PENDING_V2 -> pendingEnabled
            null -> false
        }

    fun requestCancel(key: String) {
        val item = _state.value.items.find { it.key == key } ?: return
        if (canCancel(item)) _state.value = _state.value.copy(confirmKey = key, outcome = null)
    }
    fun dismissCancel() { _state.value = _state.value.copy(confirmKey = null) }

    fun confirmCancel() {
        val expectedOwner = owner ?: return
        val item = _state.value.items.find { it.key == _state.value.confirmKey } ?: return
        if (!canCancel(item)) return
        val ticket = generation
        _state.value = _state.value.copy(confirmKey = null, cancelling = true, outcome = null)
        cancelJob = scope.launch {
            val outcome = try { port.cancel(expectedOwner, item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { HistoryCancelResult.UNKNOWN }
            publish(ticket) {
                if (outcome == HistoryCancelResult.SIGN_IN) {
                    retireRejectedSession()
                    return@publish
                }
                _state.value = _state.value.copy(cancelling = false, outcome = outcome)
                // POST is never retried. GET refetches authoritative status even
                // after conflict/lost ACK; a failed GET stays an honest error.
                refresh()
            }
        }
    }

    override fun onCleared() {
        clearSession()
        unregister()
    }
}
