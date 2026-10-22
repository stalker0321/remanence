package dev.hryshyn.remanence.ui.create

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.hryshyn.remanence.core.data.network.DirectoryFailure
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId

/** Port over the authenticated handle-directory endpoint. */
fun interface RecipientDirectoryPort {
    suspend fun lookup(rawHandle: String): DirectoryLookupResult
}

/** Live state of the recipient resolution step inside one creation session. */
sealed interface RecipientLookupUiState {
    data object Idle : RecipientLookupUiState

    data object LookingUp : RecipientLookupUiState

    /** Snapshot bound at confirmation time; never cached beyond the create flow. */
    data class Resolved(val snapshot: ResolvedHandleSnapshot) : RecipientLookupUiState

    data object NotFound : RecipientLookupUiState

    enum class Failure { NETWORK, SIGN_IN, LOOKUP, SERVER, UNEXPECTED, RECENT_IDENTITY }
    /** Diagnostic copy retained for older tests; UI renders only [reason]. */
    data class Failed(val message: String, val reason: Failure = Failure.UNEXPECTED) : RecipientLookupUiState
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
    private val sessionOwnerProvider: suspend () -> String? = { accessTokenProvider() },
    private val sessionBoundaryEpoch: () -> Long = { 0L },
    private val registerSessionBoundary: (((() -> Unit)) -> (() -> Unit))? = null,
    /**
     * BUG-014: bounded single recovery attempt when a lookup starts with a
     * null in-memory token. Defaults to [SessionRecovery.NO_SESSION], which
     * preserves the historical immediate failure. Production wires one refresh
     * through the existing session coordinator path (see CreateViewModel).
     */
    private val sessionRefresh: suspend () -> SessionRecovery = { SessionRecovery.NO_SESSION },
) {

    /**
     * Outcome of the single bounded session recovery attempt. Only
     * [RECOVERED] re-reads the token and performs the lookup; the other two
     * keep the failure bounded with the correct copy.
     */
    enum class SessionRecovery { RECOVERED, NO_SESSION, NETWORK_FAILED }

    private var lookupJob: Job? = null
    private var generation: Long = 0
    private val unregisterSessionBoundary: (() -> Unit)? = registerSessionBoundary?.invoke {
        reset()
    }

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
        generation++
        lookupJob?.cancel()
        lookupJob = null
        _handle.value = value
        _state.value = RecipientLookupUiState.Idle
    }

    /** FIX-REVIEW-02: drops the typed handle and any lookup outcome. */
    fun reset() {
        generation++
        lookupJob?.cancel()
        lookupJob = null
        _handle.value = ""
        _state.value = RecipientLookupUiState.Idle
    }

    fun lookup() = startLookup(expectedRecipient = null)

    /** A recent is an identity suggestion, never a cached directory/key result. */
    fun lookupRecent(expectedUserId: String) {
        if (!canLookup) return
        val expected = runCatching { UserId.parseRest(expectedUserId) }.getOrNull()
        if (expected == null) {
            _state.value = recentIdentityFailure()
            return
        }
        startLookup(expected)
    }

    private fun recentIdentityFailure() = RecipientLookupUiState.Failed(
        "Recent recipient identity could not be verified.", RecipientLookupUiState.Failure.RECENT_IDENTITY,
    )

    private fun startLookup(expectedRecipient: UserId?) {
        if (!canLookup) return
        val rawHandle = _handle.value
        val lookupGeneration = ++generation
        val boundaryEpoch = sessionBoundaryEpoch()
        lookupJob?.cancel()
        // Recovery and directory lookup are one user attempt. Mark it busy
        // before launching so a second tap cannot cancel/restart recovery.
        _state.value = RecipientLookupUiState.LookingUp
        lookupJob = scope.launch {
            fun isCurrent(): Boolean {
                if (generation != lookupGeneration || _handle.value != rawHandle) return false
                if (sessionBoundaryEpoch() != boundaryEpoch) {
                    _state.value = RecipientLookupUiState.Idle
                    return false
                }
                return true
            }
            if (accessTokenProvider() == null) {
                // One bounded recovery; its result belongs to the same input
                // and session boundary even when the provider ignores cancel.
                val recovery = try {
                    sessionRefresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SessionRecovery.NETWORK_FAILED
                }
                if (!isCurrent()) return@launch
                val fresh = accessTokenProvider()
                if (recovery != SessionRecovery.RECOVERED || fresh == null) {
                    _state.value = if (recovery == SessionRecovery.NETWORK_FAILED) {
                        RecipientLookupUiState.Failed("Network unreachable. Try again later.", RecipientLookupUiState.Failure.NETWORK)
                    } else {
                        RecipientLookupUiState.Failed("Sign in first.", RecipientLookupUiState.Failure.SIGN_IN)
                    }
                    return@launch
                }
            }
            try {
                val expectedOwner = sessionOwnerProvider() ?: run {
                    if (isCurrent()) {
                        _state.value = RecipientLookupUiState.Failed("Sign in first.", RecipientLookupUiState.Failure.SIGN_IN)
                    }
                    return@launch
                }
                if (!isCurrent()) return@launch
                val result = try {
                    mapResult(directory.lookup(rawHandle))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    RecipientLookupUiState.Failed("Unexpected error. Try again later.")
                }
                if (!isCurrent()) return@launch
                val currentOwner = sessionOwnerProvider()
                if (isCurrent()) {
                    _state.value = when {
                        currentOwner != expectedOwner ->
                            RecipientLookupUiState.Failed("Sign in first.", RecipientLookupUiState.Failure.SIGN_IN)
                        expectedRecipient != null && result is RecipientLookupUiState.Resolved &&
                            result.snapshot.userId != expectedRecipient -> recentIdentityFailure()
                        else -> result
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Owner-store reads are part of the same bounded attempt as
                // directory IO. Never expose storage details or bind a result
                // whose owner could not be freshly checked; allow a retry.
                if (isCurrent()) {
                    _state.value = RecipientLookupUiState.Failed("Unexpected error. Try again later.")
                }
            }
        }
    }

    fun close() {
        reset()
        unregisterSessionBoundary?.invoke()
    }

    private fun mapResult(result: DirectoryLookupResult): RecipientLookupUiState = when (result) {
        is DirectoryLookupResult.Found -> RecipientLookupUiState.Resolved(result.snapshot)
        DirectoryLookupResult.NotFound -> RecipientLookupUiState.NotFound
        is DirectoryLookupResult.Failure -> when (result.reason) {
            DirectoryFailure.NOT_FOUND -> RecipientLookupUiState.NotFound
            DirectoryFailure.NETWORK -> RecipientLookupUiState.Failed("Network unreachable. Try again later.", RecipientLookupUiState.Failure.NETWORK)
            DirectoryFailure.HTTP -> RecipientLookupUiState.Failed("Lookup failed. Try again later.", RecipientLookupUiState.Failure.LOOKUP)
            DirectoryFailure.INVALID_RESPONSE -> RecipientLookupUiState.Failed("Unexpected server response.", RecipientLookupUiState.Failure.SERVER)
        }
    }
}
