package app.postmark.memory.session

import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AppNavigationController
import app.postmark.memory.ui.navigation.AuthUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * I03 root state: the single auth state plus guarded navigation position that
 * MainActivity renders. Cold start resolves through [SessionBootstrap] (small
 * local-file reads, safe to run synchronously); successful login/registration
 * re-resolves; logout clears and returns to authentication.
 */
class RootViewModel(
    private val sessionBootstrap: SessionBootstrap,
) {

    private val controller = AppNavigationController(AuthUiState.SignedOut)

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _destination = MutableStateFlow<AppDestination>(AppDestination.Authentication)
    val destination: StateFlow<AppDestination> = _destination.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads persisted facts after a successful login/registration. */
    fun onSessionEstablished() {
        refresh()
    }

    fun logout() {
        sessionBootstrap.logout()
        refresh()
    }

    private fun refresh() {
        val next = when (val resolved = sessionBootstrap.bootstrap()) {
            SessionState.SignedOut -> AuthUiState.SignedOut
            SessionState.RecoveryRequired -> AuthUiState.RecoveryRequired
            is SessionState.Active ->
                AuthUiState.Authenticated(
                    userId = resolved.userId ?: "",
                    handle = resolved.handle ?: "",
                )
        }
        val previousAuth = _authState.value
        _authState.value = next
        controller.updateAuth(next)
        // Becoming authenticated lands on Home; losing authentication is
        // already redirected by the controller's guard.
        if (next is AuthUiState.Authenticated &&
            previousAuth !is AuthUiState.Authenticated
        ) {
            controller.navigate(AppDestination.Home)
        }
        _destination.value = controller.current
    }
}
