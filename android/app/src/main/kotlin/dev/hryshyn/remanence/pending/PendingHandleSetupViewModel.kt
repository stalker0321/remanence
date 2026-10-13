package dev.hryshyn.remanence.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.core.data.network.HandleCommitFailure
import dev.hryshyn.remanence.core.data.network.HandleCommitPort
import dev.hryshyn.remanence.core.data.network.HandleCommitResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.session.PersistedAccountSummary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * T186 M7a: first-send handle commit (frozen R17) with real callsites.
 *
 * Flow: validate locally → capture current account + session lease →
 * `POST v2/me/handle` → recheck lease liveness AND expected response user
 * → owner-fenced Room handle update. Only the handle column is written:
 * UUID, key bundle, private keys, PRF state and tokens are preserved (no
 * registration regen, no empty placeholder). Identical replay succeeds;
 * a conflicting handle surfaces generically (never echoing taken names).
 */
class PendingHandleSetupViewModel(
    private val r17: HandleCommitPort,
    private val loadAccount: suspend () -> PersistedAccountSummary?,
    private val captureLease: () -> SessionRequestLease?,
    private val tokenFor: (SessionRequestLease) -> String?,
    private val leaseLive: (SessionRequestLease) -> Boolean,
    private val recordHandle: suspend (userId: String, handle: String) -> Unit,
    scope: CoroutineScope? = null,
) : ViewModel() {

    sealed interface SetupState {
        data object Idle : SetupState
        data object Working : SetupState
        data class Committed(val handle: String) : SetupState
        data class Failed(val message: PendingHandleSetupMessage) : SetupState
    }

    enum class PendingHandleSetupMessage {
        INVALID_HANDLE,
        CONFLICT,
        NETWORK,
        SESSION_CHANGED,
        GENERIC,
    }

    private val launchScope: CoroutineScope = scope ?: viewModelScope

    private val _field = MutableStateFlow("")
    val field: StateFlow<String> = _field.asStateFlow()

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun onFieldChange(value: String) {
        _field.value = value
        if (_state.value is SetupState.Failed) _state.value = SetupState.Idle
    }

    /**
     * Resets terminal states on account change (never clobbers in-flight
     * work); the field is preserved for retry.
     */
    fun onAuthChanged() {
        if (_state.value is SetupState.Working) return
        _state.value = SetupState.Idle
    }

    fun canSubmit(raw: String = _field.value): Boolean = try {
        NormalizedHandle.parse(raw)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    fun save() {
        if (_state.value is SetupState.Working) return
        val normalized = try {
            NormalizedHandle.parse(_field.value).value
        } catch (_: IllegalArgumentException) {
            _state.value = SetupState.Failed(PendingHandleSetupMessage.INVALID_HANDLE)
            return
        }
        _state.value = SetupState.Working
        launchScope.launch {
            _state.value = try {
                commit(normalized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SetupState.Failed(PendingHandleSetupMessage.GENERIC)
            }
        }
    }

    private suspend fun commit(normalized: String): SetupState {
        val account = try {
            loadAccount()
        } catch (_: Exception) {
            null
        } ?: return SetupState.Failed(PendingHandleSetupMessage.SESSION_CHANGED)
        if (account.userId.isBlank()) {
            return SetupState.Failed(PendingHandleSetupMessage.SESSION_CHANGED)
        }
        // Already bound: identical replay is success, anything else is over
        // (immutability enforced without another network round trip).
        if (!account.handle.isNullOrBlank()) {
            return if (account.handle == normalized) {
                SetupState.Committed(normalized)
            } else {
                SetupState.Failed(PendingHandleSetupMessage.CONFLICT)
            }
        }
        val lease = try {
            captureLease()
        } catch (_: Exception) {
            null
        } ?: return SetupState.Failed(PendingHandleSetupMessage.SESSION_CHANGED)
        val token = try {
            tokenFor(lease)
        } catch (_: Exception) {
            null
        }
        if (token.isNullOrBlank()) {
            return SetupState.Failed(PendingHandleSetupMessage.SESSION_CHANGED)
        }
        val result = try {
            r17.commit(normalized, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SetupState.Failed(PendingHandleSetupMessage.NETWORK)
        }
        // Fences AFTER the await: the lease must still be live and the
        // response user must be the account we committed for.
        val live = try {
            leaseLive(lease)
        } catch (_: Exception) {
            false
        }
        if (!live) return SetupState.Failed(PendingHandleSetupMessage.SESSION_CHANGED)
        return when (result) {
            is HandleCommitResult.Committed -> {
                if (result.handle != normalized || result.userId != account.userId) {
                    SetupState.Failed(PendingHandleSetupMessage.SESSION_CHANGED)
                } else {
                    // Owner-fenced Room update: re-read, require the SAME
                    // account, write the handle column only.
                    val current = try {
                        loadAccount()
                    } catch (_: Exception) {
                        null
                    }
                    if (current == null || current.userId != account.userId) {
                        SetupState.Failed(PendingHandleSetupMessage.SESSION_CHANGED)
                    } else {
                        try {
                            recordHandle(account.userId, normalized)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            return SetupState.Failed(PendingHandleSetupMessage.GENERIC)
                        }
                        SetupState.Committed(normalized)
                    }
                }
            }
            is HandleCommitResult.Failure -> when (result.reason) {
                HandleCommitFailure.NETWORK -> SetupState.Failed(PendingHandleSetupMessage.NETWORK)
                HandleCommitFailure.HANDLE_IMMUTABLE ->
                    SetupState.Failed(PendingHandleSetupMessage.CONFLICT)
                else -> if (result.retryable) {
                    SetupState.Failed(PendingHandleSetupMessage.NETWORK)
                } else {
                    SetupState.Failed(PendingHandleSetupMessage.GENERIC)
                }
            }
        }
    }
}
