package dev.hryshyn.remanence.ui.create

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.hryshyn.remanence.core.data.network.DirectoryFailure
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.NormalizedHandle

/** Port over the authenticated handle-directory endpoint. */
fun interface RecipientDirectoryPort {
    suspend fun lookup(rawHandle: String, accessToken: String): DirectoryLookupResult
}

/** Live state of the recipient resolution step inside one creation session. */
sealed interface RecipientLookupUiState {
    data object Idle : RecipientLookupUiState

    data object LookingUp : RecipientLookupUiState

    /** Snapshot bound at confirmation time; never cached beyond the create flow. */
    data class Resolved(val snapshot: ResolvedHandleSnapshot) : RecipientLookupUiState

    data object NotFound : RecipientLookupUiState

    data class Failed(val message: String) : RecipientLookupUiState
}

/**
 * Holds the typed handle and its resolution outcome for the current create
 * session. The snapshot is kept only while the user confirms the recipient;
 * nothing is cached beyond this flow (docs/security.md section 8).
 */
class RecipientPickerViewModel(
    private val directory: RecipientDirectoryPort,
    private val accessTokenProvider: () -> String?,
    private val scope: CoroutineScope,
) {

    private val _handle = MutableStateFlow("")
    val handle: StateFlow<String> = _handle.asStateFlow()

    private val _state = MutableStateFlow<RecipientLookupUiState>(RecipientLookupUiState.Idle)
    val state: StateFlow<RecipientLookupUiState> = _state.asStateFlow()

    /** True when the typed text is a well-formed handle and can be looked up. */
    val canLookup: Boolean
        get() = _state.value !is RecipientLookupUiState.LookingUp &&
            _handle.value.isNotEmpty() &&
            runCatching { NormalizedHandle.parse(_handle.value) }.isSuccess

    fun onHandleChange(value: String) {
        _handle.value = value
        if (_state.value is RecipientLookupUiState.LookingUp) return
        _state.value = RecipientLookupUiState.Idle
    }

    /** FIX-REVIEW-02: drops the typed handle and any lookup outcome. */
    fun reset() {
        _handle.value = ""
        _state.value = RecipientLookupUiState.Idle
    }

    fun lookup() {
        val token = accessTokenProvider() ?: run {
            _state.value = RecipientLookupUiState.Failed("Sign in first.")
            return
        }
        if (!canLookup) return
        _state.value = RecipientLookupUiState.LookingUp
        scope.launch {
            _state.value = try {
                mapResult(directory.lookup(_handle.value, token))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RecipientLookupUiState.Failed("Unexpected error. Try again later.")
            }
        }
    }

    private fun mapResult(result: DirectoryLookupResult): RecipientLookupUiState = when (result) {
        is DirectoryLookupResult.Found -> RecipientLookupUiState.Resolved(result.snapshot)
        DirectoryLookupResult.NotFound -> RecipientLookupUiState.NotFound
        is DirectoryLookupResult.Failure -> when (result.reason) {
            DirectoryFailure.NOT_FOUND -> RecipientLookupUiState.NotFound
            DirectoryFailure.NETWORK -> RecipientLookupUiState.Failed("Network unreachable. Try again later.")
            DirectoryFailure.HTTP -> RecipientLookupUiState.Failed("Lookup failed. Try again later.")
            DirectoryFailure.INVALID_RESPONSE -> RecipientLookupUiState.Failed("Unexpected server response.")
        }
    }
}
