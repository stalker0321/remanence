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

    /** Sealed refresh token exists but the cold-start refresh could not complete. */
    data object RequiresConnectivity : AuthUiState

    data class Authenticated(
        val userId: String,
        val handle: String,
    ) : AuthUiState
}

/**
 * Memory-only scan-grant state backing the capsule route
 * (docs/architecture.md section 5). The route carries a random grant ID;
 * reaching it additionally requires the recipient envelope, statement,
 * hashes, and AEAD integrity to have been VERIFIED - possession of a grant
 * string alone never opens anything.
 */
sealed interface CapsuleAccess {
    data object None : CapsuleAccess

    data class Granted(
        val grantId: String,
        val capsuleId: String,
        val cryptoVerified: Boolean,
    ) : CapsuleAccess
}

/**
 * Every reachable top-level destination of the app. This sealed hierarchy is
 * the single source of truth for what can be navigated to; there is no
 * gallery, inbox, history, or deep-link destination by construction. The one
 * capsule presentation surface exists ONLY behind a live memory-only scan
 * grant plus a verified crypto result.
 */
sealed interface AppDestination {
    /** Authentication surface: sign-in, create account, device-loss warning. */
    data object Authentication : AppDestination

    /** Post-authentication home. */
    data object Home : AppDestination

    /**
     * Presentation of ONE scanned capsule, addressable only by its random
     * in-memory grant ID - never by capsule ID, index position, or history.
     */
    data class Capsule(val grantId: String) : AppDestination
}

/** Pure routing rules; trivially unit-testable and free of Android types. */
object RouteGuard {

    fun resolve(authState: AuthUiState, requested: AppDestination): AppDestination =
        resolve(authState, requested, CapsuleAccess.None)

    fun resolve(
        authState: AuthUiState,
        requested: AppDestination,
        access: CapsuleAccess,
    ): AppDestination = when (authState) {
        AuthUiState.SignedOut,
        AuthUiState.RecoveryRequired,
        AuthUiState.RequiresConnectivity,
        -> AppDestination.Authentication

        is AuthUiState.Authenticated ->
            if (requested is AppDestination.Capsule && !accessAllows(access, requested.grantId)) {
                AppDestination.Home
            } else {
                requested
            }
    }

    private fun accessAllows(access: CapsuleAccess, requestedGrantId: String): Boolean =
        access is CapsuleAccess.Granted &&
            access.cryptoVerified &&
            access.grantId == requestedGrantId

    fun initialDestination(authState: AuthUiState): AppDestination =
        resolve(authState, AppDestination.Home)

    /** Exhaustive inventory of destinations; used by tests to prove no hidden routes exist. */
    fun allDestinations(): Set<AppDestination> = setOf(
        AppDestination.Authentication,
        AppDestination.Home,
        AppDestination.Capsule("inventory-probe"),
    )
}

/**
 * Holds the live navigation position, the current memory-only grant, and
 * re-applies guards whenever the authentication state changes (login, logout,
 * process restart). The grant lives here exactly as long as the scan flow;
 * leaving the capsule screen or logging out drops it.
 */
class AppNavigationController(initialAuth: AuthUiState = AuthUiState.SignedOut) {

    var authState: AuthUiState = initialAuth
        private set

    var current: AppDestination = RouteGuard.initialDestination(initialAuth)
        private set

    var capsuleAccess: CapsuleAccess = CapsuleAccess.None
        private set

    /** Called after issue+crypto verification succeeded for one scanned capsule. */
    fun grantCapsuleAccess(grantId: String, capsuleId: String) {
        capsuleAccess = CapsuleAccess.Granted(grantId, capsuleId, cryptoVerified = true)
    }

    /** Leaving the capsule screen consumes the grant immediately. */
    fun consumeCapsuleAccess() {
        capsuleAccess = CapsuleAccess.None
        if (current is AppDestination.Capsule) {
            current = AppDestination.Home
        }
    }

    fun updateAuth(next: AuthUiState) {
        authState = next
        // Re-resolve against the same logical target so a logout mid-app
        // lands on Authentication instead of leaving stale access, and drop
        // the grant so nothing survives an account-context change.
        capsuleAccess = CapsuleAccess.None
        current = RouteGuard.resolve(next, current)
    }

    fun navigate(requested: AppDestination) {
        current = RouteGuard.resolve(authState, requested, capsuleAccess)
    }
}
