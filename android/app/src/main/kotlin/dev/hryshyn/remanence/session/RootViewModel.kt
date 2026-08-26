package dev.hryshyn.remanence.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AppNavigationController
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.hryshyn.remanence.core.recognition.ScanGrantManager

/**
 * I03/FIX-M1-007-08 root state: the single auth state plus guarded navigation
 * position that MainActivity renders. Cold start resolves through
 * [SessionStateResolver] on [viewModelScope] because it performs a live
 * refresh round trip; the root NEVER flips to Authenticated on persisted bytes
 * alone - only after the async auth result reaches its terminal state.
 * Successful login/registration re-resolves; logout clears and returns to
 * authentication. The scope dies with this ViewModel, so nothing leaks past
 * the screen lifecycle.
 *
 * FIX-REVIEW-03: capsule presentation is gated by THE one [ScanGrantManager]
 * instance shared with the scan flow. This root can never mint verified
 * access out of strings - a grant ID must resolve, unexpired, to its bound
 * capsule ID through the manager before any navigation or access binding.
 */
class RootViewModel(
    private val sessionBootstrap: SessionStateResolver,
    /** Full teardown flow (server → session → local → grants); defaults to token-only clearing. */
    private val logoutAction: (suspend () -> Unit)? = null,
    /** Authoritative memory-only grant lifecycle; injected as the single instance. */
    private val grants: ScanGrantManager = ScanGrantManager(clockMillis = System::currentTimeMillis),
    /**
     * FIX-REVIEW2-03: same clock source as [grants]; schedules the exact
     * expiry wake-up for the presented capsule. Injected for determinism.
     */
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val controller = AppNavigationController(AuthUiState.SignedOut)

    /**
     * FIX-REVIEW2-03: the one pending exact-expiry timer for the presented
     * grant, bound to this ViewModel's scope - process death and logout kill
     * it with the context; explicit close/exit cancels it.
     */
    private val expiryWatch: CapsuleExpiryWatch by lazy {
        CapsuleExpiryWatch(viewModelScope, grants, clockMillis, ::revokePresentation)
    }

    /**
     * FIX-REVIEW2-03: emitted when the authoritative lifecycle revokes a
     * presented grant (exact expiry). The presentation route closes its
     * state, releasing every decrypted reference.
     */
    private val _capsuleRevocations = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val capsuleRevocations: SharedFlow<String> = _capsuleRevocations.asSharedFlow()

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _destination = MutableStateFlow<AppDestination>(AppDestination.Authentication)
    val destination: StateFlow<AppDestination> = _destination.asStateFlow()

    /**
     * FIX-REVIEW-02: monotonically increasing flow-session epochs. Every entry
     * into Create/Scan bumps its epoch, and the screen's beginSession(epoch)
     * starts a genuinely fresh session when the value differs - the Activity-
     * scoped ViewModels can never leak a previous session across re-entry,
     * while rotation (same epoch) keeps an in-progress session intact.
     */
    private val _createSessionEpoch = MutableStateFlow(0L)
    val createSessionEpoch: StateFlow<Long> = _createSessionEpoch.asStateFlow()

    private val _scanSessionEpoch = MutableStateFlow(0L)
    val scanSessionEpoch: StateFlow<Long> = _scanSessionEpoch.asStateFlow()

    /** Per-flow transient cleanup hooks keyed by the flow destination. */
    private val transientCleanups = mutableMapOf<AppDestination, MutableList<() -> Unit>>()

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
        // No pending expiry timer may outlive the account context.
        expiryWatch.cancel()
        viewModelScope.launch {
            // Full ordered teardown when wired; otherwise token-only clearing.
            (logoutAction ?: { sessionBootstrap.logout() })()
            // Any live scan grant dies with the account context.
            controller.consumeCapsuleAccess()
            grants.clearAll()
            // Every flow's transient state dies with the account context.
            AppDestination.Create.let { runTransientCleanups(it) }
            AppDestination.Scan.let { runTransientCleanups(it) }
            resolveNow()
        }
    }

    /** Home entry point: sender create flow (authenticated accounts only). */
    fun openCreate() {
        _createSessionEpoch.value += 1
        controller.navigate(AppDestination.Create)
        _destination.value = controller.current
    }

    /** Home entry point: scan flow (authenticated accounts only). */
    fun openScan() {
        _scanSessionEpoch.value += 1
        controller.navigate(AppDestination.Scan)
        _destination.value = controller.current
    }

    /**
     * Opens the capsule presentation behind a memory-only grant
     * (docs/architecture.md section 5). FIX-REVIEW-03: the grant ID is only a
     * routing handle - it must resolve, UNEXPIRED, to its bound capsule ID
     * through THE shared [ScanGrantManager] before any navigation or access
     * binding. Arbitrary strings can never create verified access; the real
     * crypto verification already happened in the scan flow before this grant
     * was issued.
     */
    fun openCapsuleWithGrant(grantId: String) {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return
        val capsuleId = grants.resolveCapsuleId(uuid) ?: return
        controller.grantCapsuleAccess(grantId, capsuleId.toString())
        controller.navigate(AppDestination.Capsule(grantId))
        _destination.value = controller.current
        // FIX-REVIEW2-03: schedule THIS presentation's exact-expiry wake-up.
        expiryWatch.watch(grantId)
    }

    /**
     * FIX-REVIEW2-03: every on-demand decrypt/page load validates through THE
     * authoritative manager first; an expired, consumed, or wrong grant never
     * decrypts anything.
     */
    fun requireLivePresentationGrant(grantId: String) {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull()
            ?: throw IllegalStateException("malformed grant id")
        val bound = controller.capsuleAccess as? CapsuleAccess.Granted
        check(
            controller.current is AppDestination.Capsule &&
                bound?.grantId == grantId &&
                grants.resolveCapsuleId(uuid) != null,
        ) { "scan grant is no longer live" }
    }

    /**
     * FIX-REVIEW2-03: authoritative revocation of one presented grant -
     * ejects to Home through the guarded route and publishes the revocation
     * so the presentation releases every decrypted reference.
     */
    private fun revokePresentation(grantId: String) {
        val bound = controller.capsuleAccess as? CapsuleAccess.Granted
        if (controller.current is AppDestination.Capsule && bound?.grantId == grantId) {
            controller.consumeCapsuleAccess()
            _destination.value = controller.current
        }
        _capsuleRevocations.tryEmit(grantId)
    }

    /**
     * FIX-REVIEW-03: the authoritative resolver behind the capsule route.
     * The capsule ID comes from THE manager (expiry enforced) and must agree
     * with the access bound at navigation time. Expiry or consumption while
     * presenting ejects to Home on the next resolve.
     */
    fun capsuleIdFor(grantId: String): String? {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return null
        val resolvedCapsuleId = grants.resolveCapsuleId(uuid)?.toString()
        val bound = controller.capsuleAccess as? dev.hryshyn.remanence.ui.navigation.CapsuleAccess.Granted
        val trusted = resolvedCapsuleId != null &&
            bound?.grantId == grantId &&
            bound.capsuleId == resolvedCapsuleId &&
            controller.current is AppDestination.Capsule
        if (!trusted) {
            if (controller.current is AppDestination.Capsule) {
                controller.consumeCapsuleAccess()
                controller.navigate(AppDestination.Home)
                _destination.value = controller.current
            }
            return null
        }
        return resolvedCapsuleId
    }

    /** Module-internal view of the live grant binding (never persisted). */
    internal val liveCapsuleAccess: dev.hryshyn.remanence.ui.navigation.CapsuleAccess
        get() = controller.capsuleAccess

    /** Module-internal handle of THE authoritative grant manager. */
    internal val scanGrants: ScanGrantManager get() = grants

    /** Leaving the presentation consumes THE grant and ejects to Home. */
    fun closeCapsule() {
        expiryWatch.cancel()
        val bound = controller.capsuleAccess as? dev.hryshyn.remanence.ui.navigation.CapsuleAccess.Granted
        runCatching { UUID.fromString(bound?.grantId ?: "") }.getOrNull()?.let { grants.consume(it) }
        controller.consumeCapsuleAccess()
        controller.navigate(AppDestination.Home)
        _destination.value = controller.current
    }

    /**
     * Returns from Create/Scan to Home. Leaving a flow RUNS its registered
     * transient-state cleanups - confirmed recipients, staged captures, and
     * scan sessions never survive their surface.
     *
     * FIX-REVIEW2-02: leaving Scan invalidates THE authoritative grant
     * manager itself, not just the bound controller access - a grant issued
     * right before a navigation effect (or racing a Back press) dies with the
     * flow and can never resolve afterwards.
     */
    fun returnToHome() {
        val previous = controller.current
        controller.navigate(AppDestination.Home)
        if (previous != controller.current) {
            if (previous == AppDestination.Scan) {
                expiryWatch.cancel()
                controller.consumeCapsuleAccess()
                grants.clearAll()
            }
            runTransientCleanups(previous)
        }
        _destination.value = controller.current
    }

    /**
     * Registers per-flow transient cleanup. Cleanups run when the flow is
     * left, on logout, or when authentication is lost - never on rotation.
     */
    fun registerTransientCleanup(destination: AppDestination, cleanup: () -> Unit) {
        transientCleanups.getOrPut(destination) { mutableListOf() }.add(cleanup)
    }

    private fun runTransientCleanups(destination: AppDestination) {
        transientCleanups.remove(destination)?.forEach { cleanup -> cleanup() }
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
        val previousDestination = controller.current
        controller.updateAuth(next)
        // Losing authentication ejects from any flow: its transient state dies.
        if (previousDestination != controller.current &&
            (previousDestination is AppDestination.Create || previousDestination is AppDestination.Scan)
        ) {
            runTransientCleanups(previousDestination)
        }
        // Becoming authenticated lands on Home; losing authentication is
        // already redirected by the controller's guard.
        if (next is AuthUiState.Authenticated && previousAuth !is AuthUiState.Authenticated) {
            controller.navigate(AppDestination.Home)
        }
        _destination.value = controller.current
    }
}
