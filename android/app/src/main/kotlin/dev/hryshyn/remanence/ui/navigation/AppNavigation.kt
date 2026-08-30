package dev.hryshyn.remanence.ui.navigation

import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource

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
        val activeKeyBundleId: String? = null,
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
        val ownerUserId: String,
        val source: CapsulePresentationSource,
        val scanGeneration: Int,
    ) : CapsuleAccess
}

/**
 * Every reachable top-level destination of the app. This sealed hierarchy is
 * the single source of truth for what can be navigated to; there is no
 * gallery, inbox, history, or deep-link destination by construction. The one
 * capsule presentation surface exists ONLY behind a live memory-only scan
 * grant plus a verified crypto result. Create and Scan are the only required
 * home entry points (docs/product.md section 7).
 */
sealed interface AppDestination {
    /** Authentication surface: sign-in, create account, device-loss warning. */
    data object Authentication : AppDestination

    /** Post-authentication home. */
    data object Home : AppDestination

    /** Sender flow entry: resolve recipient, capture, encrypt, publish. */
    data object Create : AppDestination

    /** Scan flow entry: front/back capture, local match, grant gate. */
    data object Scan : AppDestination

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
            if (requested is AppDestination.Capsule &&
                !accessAllows(authState, access, requested.grantId)
            ) {
                AppDestination.Home
            } else {
                requested
            }
    }

    private fun accessAllows(
        authState: AuthUiState.Authenticated,
        access: CapsuleAccess,
        requestedGrantId: String,
    ): Boolean =
        access is CapsuleAccess.Granted &&
            access.cryptoVerified &&
            access.grantId == requestedGrantId &&
            authState.userId == access.ownerUserId

    fun initialDestination(authState: AuthUiState): AppDestination =
        resolve(authState, AppDestination.Home)

    /** Exhaustive inventory of destinations; used by tests to prove no hidden routes exist. */
    fun allDestinations(): Set<AppDestination> = setOf(
        AppDestination.Authentication,
        AppDestination.Home,
        AppDestination.Create,
        AppDestination.Scan,
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
    internal fun grantCapsuleAccess(
        grantId: String,
        capsuleId: String,
        ownerUserId: String,
        source: CapsulePresentationSource,
        scanGeneration: Int,
    ) {
        capsuleAccess = CapsuleAccess.Granted(
            grantId = grantId,
            capsuleId = capsuleId,
            cryptoVerified = true,
            ownerUserId = ownerUserId,
            source = source,
            scanGeneration = scanGeneration,
        )
    }

    /** Leaving the capsule screen consumes the grant immediately. */
    fun consumeCapsuleAccess() {
        capsuleAccess = CapsuleAccess.None
        if (current is AppDestination.Capsule) {
            current = AppDestination.Home
        }
    }

    fun updateAuth(next: AuthUiState) {
        val previousAuth = authState
        val sameAuthenticatedBoundary = previousAuth is AuthUiState.Authenticated &&
            next is AuthUiState.Authenticated &&
            previousAuth.userId == next.userId &&
            previousAuth.activeKeyBundleId == next.activeKeyBundleId
        authState = next
        // A refresh for the same authenticated owner preserves the in-memory
        // presentation session (including rotation/recomposition). Any loss
        // or owner change drops it before resolving the guarded destination.
        if (sameAuthenticatedBoundary) {
            // Keep an already-authorized capsule destination stable across a
            // normal refresh. Revalidate its full grant id/owner binding, but
            // do not route through an intermediate destination snapshot.
            val currentDestination = current
            if (currentDestination is AppDestination.Capsule &&
                RouteGuard.resolve(next, currentDestination, capsuleAccess) != currentDestination
            ) {
                capsuleAccess = CapsuleAccess.None
                current = AppDestination.Home
            } else if (currentDestination !is AppDestination.Capsule) {
                current = RouteGuard.resolve(next, current, capsuleAccess)
            }
        } else {
            capsuleAccess = CapsuleAccess.None
            // Create/Scan own transient capture state. An authenticated
            // owner/key boundary is a real context exit even when both sides
            // are still Authenticated; do not leave those surfaces mounted
            // across A -> B or key-bundle changes.
            current = if (next is AuthUiState.Authenticated &&
                (current == AppDestination.Create || current == AppDestination.Scan)
            ) {
                AppDestination.Home
            } else {
                RouteGuard.resolve(next, current)
            }
        }
    }

    fun navigate(requested: AppDestination) {
        current = RouteGuard.resolve(authState, requested, capsuleAccess)
    }
}
