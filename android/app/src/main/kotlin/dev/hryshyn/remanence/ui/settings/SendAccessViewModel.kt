package dev.hryshyn.remanence.ui.settings

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

data class SendAccessUiState(
    val owner: String? = null,
    val loading: Boolean = false,
    val value: SendAccess? = null,
    val error: SendAccessFailure? = null,
    val hidden: Boolean = true,
)

/** Settings lifetime only. No publisher, work scheduler or persistent store. */
class SendAccessViewModel internal constructor(
    private val port: SendAccessPort,
    private val captureLease: () -> SessionRequestLease?,
    private val leaseLive: (SessionRequestLease) -> Boolean,
    private val boundary: SessionBoundary,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private var lease: SessionRequestLease? = null
    private var epoch = boundary.currentEpoch()
    private var generation = 0L
    private var job: Job? = null
    private val _state = MutableStateFlow(SendAccessUiState())
    val state = _state.asStateFlow()
    private val unregister = boundary.register(::leave)

    /** Called on mount/owner change. Ordinary bearer rotation retains the lease. */
    fun enter(rawOwner: String?) {
        val owner = rawOwner?.let { runCatching { UserId.parseRest(it) }.getOrNull() }
        val next = captureLease()?.takeIf { it.ownerUserId == owner && leaseLive(it) }
        if (next != null && next == lease && epoch == boundary.currentEpoch()) return
        leave()
        if (owner == null) return
        if (next == null) {
            _state.value = SendAccessUiState(owner = rawOwner, error = SendAccessFailure.SIGN_IN, hidden = false)
            return
        }
        lease = next
        _state.value = SendAccessUiState(owner = rawOwner)
        refresh()
    }

    fun leave() {
        generation++
        job?.cancel(); job = null
        lease = null
        epoch = boundary.currentEpoch()
        _state.value = SendAccessUiState()
    }

    fun refresh() {
        val expectedLease = lease ?: return
        if (_state.value.loading) return
        if (!leaseLive(expectedLease) || epoch != boundary.currentEpoch()) {
            rejectSession()
            return
        }
        val ticket = ++generation
        val expectedEpoch = epoch
        val owner = expectedLease.ownerUserId.toRestString()
        _state.value = SendAccessUiState(owner = owner, loading = true, hidden = false)
        job = scope.launch {
            val result = try { port.read(expectedLease.ownerUserId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SendAccessResult.Failed(SendAccessFailure.UNAVAILABLE) }
            boundary.withCurrentLease(expectedEpoch) {
                if (generation != ticket || lease != expectedLease) return@withCurrentLease
                if (!leaseLive(expectedLease) ||
                    result == SendAccessResult.Failed(SendAccessFailure.SIGN_IN)) {
                    rejectSession()
                    return@withCurrentLease
                }
                _state.value = when (result) {
                    SendAccessResult.Hidden -> SendAccessUiState(owner = owner)
                    is SendAccessResult.Loaded -> SendAccessUiState(owner = owner, value = result.value, hidden = false)
                    is SendAccessResult.Failed -> SendAccessUiState(owner = owner, error = result.reason, hidden = false)
                }
            }
        }
    }

    private fun rejectSession() {
        val owner = _state.value.owner
        leave()
        _state.value = SendAccessUiState(owner = owner, error = SendAccessFailure.SIGN_IN, hidden = false)
    }
    override fun onCleared() { leave(); unregister() }
}
