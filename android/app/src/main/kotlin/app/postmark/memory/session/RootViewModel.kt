package app.postmark.memory.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AppNavigationController
import app.postmark.memory.ui.navigation.AuthUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * I03/FIX-M1-007-08 root state: the single auth state plus guarded navigation
 * position that MainActivity renders. Cold start resolves through
 * [SessionStateResolver] on [viewModelScope] because it performs a live
 * refresh round trip; the root NEVER flips to Authenticated on persisted bytes
 * alone - only after the async auth result reaches its terminal state.
 * Successful login/registration re-resolves; logout clears and returns to
 * authentication. The scope dies with this ViewModel, so nothing leaks past
 * the screen lifecycle.
 */
class RootViewModel(
    private val sessionBootstrap: SessionStateResolver,
    /** Full teardown flow (server → session → local → grants); defaults to token-only clearing. */
    private val logoutAction: (suspend () -> Unit)? = null,
) : ViewModel() {

    private val controller = AppNavigationController(AuthUiState.SignedOut)

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _destination = MutableStateFlow<AppDestination>(AppDestination.Authentication)
    val destination: StateFlow<AppDestination> = _destination.asStateFlow()

    init {
        refreshAsync()
    }

    /**
     * Re-resolves persisted facts after an async auth flow reaches its
     * terminal success - never called mid-flight by the UI.
     */
    fun onSessionEstablished() {
        refreshAsync()
    }

    fun logout() {
        viewModelScope.launch {
            // Full ordered teardown when wired; otherwise token-only clearing.
            (logoutAction ?: { sessionBootstrap.logout() })()
            // Any live scan grant dies with the account context.
            controller.consumeCapsuleAccess()
            resolveNow()
        }
    }

    /** Home entry point: sender create flow (authenticated accounts only). */
    fun openCreate() {
        controller.navigate(AppDestination.Create)
        _destination.value = controller.current
    }

    /** Home entry point: scan flow (authenticated accounts only). */
    fun openScan() {
        controller.navigate(AppDestination.Scan)
        _destination.value = controller.current
    }

    /** Returns from Create/Scan to Home; transient flow state dies with them. */
    fun returnToHome() {
        controller.navigate(AppDestination.Home)
        _destination.value = controller.current
    }

    private fun refreshAsync() {
        viewModelScope.launch {
            resolveNow()
        }
    }

    /**
     * Runs one full bootstrap resolution and publishes ONLY its terminal
     * outcome - intermediate failures degrade to RequiresConnectivity rather
     * than silently presenting a stale authenticated root.
     */
    suspend fun resolveNow() {
        val next = try {
            when (val resolved = sessionBootstrap.bootstrap()) {
                SessionState.SignedOut -> AuthUiState.SignedOut
                SessionState.RecoveryRequired -> AuthUiState.RecoveryRequired
                SessionState.RequiresConnectivity -> AuthUiState.RequiresConnectivity
                is SessionState.Active ->
                    AuthUiState.Authenticated(
                        userId = resolved.userId ?: "",
                        handle = resolved.handle ?: "",
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AuthUiState.RequiresConnectivity
        }
        publish(next)
    }

    private fun publish(next: AuthUiState) {
        val previousAuth = _authState.value
        _authState.value = next
        controller.updateAuth(next)
        // Becoming authenticated lands on Home; losing authentication is
        // already redirected by the controller's guard.
        if (next is AuthUiState.Authenticated && previousAuth !is AuthUiState.Authenticated) {
            controller.navigate(AppDestination.Home)
        }
        _destination.value = controller.current
    }
}
