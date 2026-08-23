package app.postmark.memory.ui.navigation

/**
 * Session-level authentication state driving all route guards.
 * Deliberately separate from crypto-readiness: recovery-required accounts
 * authenticate but stay out of creation/scan flows until keys exist again.
 */
sealed interface AuthUiState {
    data object SignedOut : AuthUiState

    /** Password accepted by the server, but local private identity is absent. */
    data object RecoveryRequired : AuthUiState

    data class Authenticated(
        val userId: String,
        val handle: String,
    ) : AuthUiState
}

/**
 * Every reachable top-level destination of the app. This sealed hierarchy is
 * the single source of truth for what can be navigated to; there is no
 * capsule, gallery, inbox, history, or deep-link destination by construction.
 * Presentation of one capsule happens only through a future memory-only
 * scan-grant gate, never through a navigable route here.
 */
sealed interface AppDestination {
    /** Authentication surface: sign-in, create account, device-loss warning. */
    data object Authentication : AppDestination

    /** Post-authentication home. */
    data object Home : AppDestination
}

/** Pure routing rules; trivially unit-testable and free of Android types. */
object RouteGuard {

    fun resolve(authState: AuthUiState, requested: AppDestination): AppDestination = when (authState) {
        AuthUiState.SignedOut,
        AuthUiState.RecoveryRequired,
        -> AppDestination.Authentication

        is AuthUiState.Authenticated -> requested
    }

    fun initialDestination(authState: AuthUiState): AppDestination =
        resolve(authState, AppDestination.Home)

    /** Exhaustive inventory of destinations; used by tests to prove no hidden routes exist. */
    fun allDestinations(): Set<AppDestination> = setOf(
        AppDestination.Authentication,
        AppDestination.Home,
    )
}

/**
 * Holds the live navigation position and re-applies guards whenever the
 * authentication state changes (login, logout, process restart).
 */
class AppNavigationController(initialAuth: AuthUiState = AuthUiState.SignedOut) {

    var authState: AuthUiState = initialAuth
        private set

    var current: AppDestination = RouteGuard.initialDestination(initialAuth)
        private set

    fun updateAuth(next: AuthUiState) {
        authState = next
        // Re-resolve against the same logical target so a logout mid-app
        // lands on Authentication instead of leaving stale access.
        current = RouteGuard.resolve(next, current)
    }

    fun navigate(requested: AppDestination) {
        current = RouteGuard.resolve(authState, requested)
    }
}
