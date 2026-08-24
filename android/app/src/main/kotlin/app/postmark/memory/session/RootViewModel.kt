package app.postmark.memory.session

import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AppNavigationController
import app.postmark.memory.ui.navigation.AuthUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * I03 root state: the single auth state plus guarded navigation position that
 * MainActivity renders. Cold start resolves through [SessionBootstrap] on a
 * background scope because it performs a live refresh round trip; the root
 * NEVER flips to Authenticated on persisted bytes alone - only after the
 * async auth result reaches its terminal state. Successful login/registration
 * re-resolves; logout clears and returns to authentication.
 */
class RootViewModel(
    private val sessionBootstrap: SessionStateResolver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val controller = AppNavigationController(AuthUiState.SignedOut)

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _destination = MutableStateFlow<AppDestination>(AppDestination.Authentication)
    val destination: StateFlow<AppDestination> = _destination.asStateFlow()

    init {
        refreshAsync()
    }

    /** Re-resolves persisted facts after a successful login/registration. */
    fun onSessionEstablished() {
        refreshAsync()
    }

    fun logout() {
        scope.launch {
            sessionBootstrap.logout()
            resolveNow()
        }
    }

    /** Stops background resolution; called when the owning screen goes away. */
    fun dispose() {
        scope.cancel()
    }

    private fun refreshAsync() {
        scope.launch {
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
