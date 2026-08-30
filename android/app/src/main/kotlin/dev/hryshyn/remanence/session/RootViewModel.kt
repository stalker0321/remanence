package dev.hryshyn.remanence.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AppNavigationController
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.ui.capsule.PresentationGrantBinding

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
 * FIX-REVIEW-03: capsule presentation is gated by THE one
 * [PresentationGrantAuthority] instance shared with the scan flow. This root
 * can never mint verified access out of strings - a grant ID must resolve,
 * unexpired, to its bound capsule ID through the authority before any
 * navigation or access binding.
 */
class RootViewModel internal constructor(
    private val sessionBootstrap: SessionStateResolver,
    /** Full teardown flow (server → session → local → grants); defaults to token-only clearing. */
    private val logoutAction: (suspend () -> Unit)? = null,
    /** Authoritative memory-only grant lifecycle; injected as the single instance. */
    private val presentationGrants: PresentationGrantAuthority = PresentationGrantAuthority(),
    /**
     * FIX-REVIEW2-03: same clock source as the authority's clock primitive;
     * schedules the exact
     * expiry wake-up for the presented capsule. Injected for determinism.
     */
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /** Best-effort owner-scoped upload discovery after a proven Active session. */
    private val resumeCapsuleUploads: suspend (UserId) -> Unit = {},
    /** Authenticated owner-scoped incoming chain enqueue after upload discovery. */
    private val scheduleIncomingSync: suspend (UserId) -> Unit = {},
    /** Authenticated startup cleanup before the account UI becomes visible. */
    private val recoverCreateStaging: suspend (UserId) -> Unit = {},
) : ViewModel() {

    /** Serializes root refresh requests while retaining one trailing request. */
    private val refreshMutex = Mutex()
    private var refreshRunning = false
    private var refreshPending = false
    private var refreshGeneration = 0L
    private val refreshWaiters = mutableListOf<CompletableDeferred<Unit>>()

    private val controller = AppNavigationController(AuthUiState.SignedOut)

    /**
     * FIX-REVIEW2-03: the one pending exact-expiry timer for the presented
     * grant, bound to this ViewModel's scope - process death and logout kill
     * it with the context; explicit close/exit cancels it.
     */
    private val expiryWatch: CapsuleExpiryWatch by lazy {
        CapsuleExpiryWatch(
            scope = viewModelScope,
            grants = presentationGrants,
            currentOwner = ::currentAuthenticatedOwner,
            clockMillis = clockMillis,
            onRevoked = ::revokePresentation,
        )
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

    /** Transient cleanup hooks keyed by the flow or presentation destination. */
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

    /**
     * Re-resolves the authenticated session when the app returns to the
     * foreground. Non-active states never reach either scheduling callback;
     * the existing account-scoped KEEP chains absorb duplicate attempts.
     */
    fun onAppForegrounded() {
        refreshAsync()
    }

    fun logout() {
        // No pending expiry timer may outlive the account context.
        expiryWatch.cancel()
        // Revoke at initiation, before asynchronous server/session teardown;
        // the later cleanup remains a defense against a late callback.
        revokeAllPresentation()
        viewModelScope.launch {
            // Full ordered teardown when wired; otherwise token-only clearing.
            (logoutAction ?: { sessionBootstrap.logout() })()
            // Any live scan grant was already revoked at account teardown
            // initiation; repeat is intentionally idempotent.
            revokeAllPresentation()
            // Every flow's transient state dies with the account context.
            AppDestination.Create.let { runTransientCleanups(it) }
            AppDestination.Scan.let { runTransientCleanups(it) }
            resolveNow()
        }
    }

    /** Home entry point: sender create flow (authenticated accounts only). */
    fun openCreate() {
        if (controller.current is AppDestination.Capsule) {
            expiryWatch.cancel()
            revokeAllPresentation()
        }
        _createSessionEpoch.value += 1
        controller.navigate(AppDestination.Create)
        _destination.value = controller.current
    }

    /** Home entry point: scan flow (authenticated accounts only). */
    fun openScan() {
        if (controller.current is AppDestination.Capsule) {
            expiryWatch.cancel()
            revokeAllPresentation()
        }
        _scanSessionEpoch.value += 1
        controller.navigate(AppDestination.Scan)
        _destination.value = controller.current
    }

    /**
     * Opens the capsule presentation behind a memory-only grant
     * (docs/architecture.md section 5). FIX-REVIEW-03: the grant ID is only a
     * routing handle - it must resolve, UNEXPIRED, to its bound capsule ID
     * through THE shared [PresentationGrantAuthority] before any navigation or access
     * binding. Arbitrary strings can never create verified access; the real
     * crypto verification already happened in the scan flow before this grant
     * was issued.
     */
    fun openCapsuleWithGrant(grantId: String) {
        openCapsuleWithGrant(grantId, null)
    }

    /**
     * Opens a grant and, when accepted, retains one cleanup for the capture
     * VM that handed the grant to presentation. The cleanup is keyed by the
     * opaque grant route and runs only when that presentation is revoked;
     * rotation and the Scan -> Capsule handoff do not run it.
     */
    internal fun openCapsuleWithGrant(
        grantId: String,
        onPresentationClosed: (() -> Unit)?,
    ): Boolean {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return false
        val authenticatedOwner = currentAuthenticatedOwner()
        val binding = authenticatedOwner?.let { presentationGrants.resolve(uuid, it) }
        if (binding == null) return false
        controller.grantCapsuleAccess(
            grantId = grantId,
            capsuleId = binding.capsuleId.toString(),
            ownerUserId = binding.ownerUserId.toRestString(),
            source = binding.source,
            scanGeneration = binding.scanGeneration,
        )
        controller.navigate(AppDestination.Capsule(grantId))
        onPresentationClosed?.let {
            registerPresentationCleanup(grantId, it)
        }
        _destination.value = controller.current
        // FIX-REVIEW2-03: schedule THIS presentation's exact-expiry wake-up.
        expiryWatch.watch(grantId)
        return true
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
        val owner = currentAuthenticatedOwner()
        val authorityBinding = owner?.let { presentationGrants.resolve(uuid, it) }
        check(
            controller.current is AppDestination.Capsule &&
                bound?.grantId == grantId &&
                authorityBinding != null &&
                bound.ownerUserId == authorityBinding.ownerUserId.toRestString() &&
                bound.capsuleId == authorityBinding.capsuleId.toString() &&
                bound.source == authorityBinding.source &&
                bound.scanGeneration == authorityBinding.scanGeneration,
        ) { "scan grant is no longer live" }
    }

    /**
     * FIX-REVIEW2-03: authoritative revocation of one presented grant -
     * ejects to Home through the guarded route and publishes the revocation
     * so the presentation releases every decrypted reference.
     */
    private fun revokePresentation(grantId: String) {
        expiryWatch.cancel(grantId)
        runCatching { UUID.fromString(grantId) }.getOrNull()?.let { presentationGrants.revoke(it) }
        val bound = controller.capsuleAccess as? CapsuleAccess.Granted
        if (controller.current is AppDestination.Capsule && bound?.grantId == grantId) {
            runTransientCleanups(AppDestination.Capsule(grantId))
            controller.consumeCapsuleAccess()
            _destination.value = controller.current
        }
        _capsuleRevocations.tryEmit(grantId)
    }

    /** Route construction/initialization failed; revoke without trusting UI state. */
    internal fun revokePresentationForRouteFailure(grantId: String) {
        revokePresentation(grantId)
    }

    /**
     * FIX-REVIEW-03: the authoritative resolver behind the capsule route.
     * The capsule ID comes from THE manager (expiry enforced) and must agree
     * with the access bound at navigation time. Expiry or consumption while
     * presenting ejects to Home on the next resolve.
     */
    fun capsuleIdFor(grantId: String): String? {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return null
        val authorityBinding = currentAuthenticatedOwner()
            ?.let { presentationGrants.resolve(uuid, it) }
        val resolvedCapsuleId = authorityBinding?.capsuleId?.toString()
        val bound = controller.capsuleAccess as? dev.hryshyn.remanence.ui.navigation.CapsuleAccess.Granted
        val trusted = authorityBinding != null &&
            bound?.grantId == grantId &&
            bound.capsuleId == resolvedCapsuleId &&
            bound.ownerUserId == authorityBinding.ownerUserId.toRestString() &&
            bound.source == authorityBinding.source &&
            bound.scanGeneration == authorityBinding.scanGeneration &&
            controller.current is AppDestination.Capsule
        if (!trusted) {
            if (controller.current is AppDestination.Capsule) {
                // Resolver-driven removal is a presentation exit too: revoke
                // the authority binding before dropping the guarded route.
                revokeAllPresentation()
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

    /** Exact in-memory source binding used by the opaque capsule route. */
    internal fun presentationGrantFor(grantId: String): PresentationGrantBinding? {
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return null
        val owner = currentAuthenticatedOwner() ?: return null
        return presentationGrants.resolve(uuid, owner)
    }

    /** Leaving the presentation consumes THE grant and ejects to Home. */
    fun closeCapsule() {
        expiryWatch.cancel()
        revokeAllPresentation()
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
        if (previous is AppDestination.Capsule || previous == AppDestination.Scan) {
            expiryWatch.cancel()
            revokeAllPresentation()
        }
        controller.navigate(AppDestination.Home)
        if (previous != controller.current || previous is AppDestination.Create || previous == AppDestination.Scan) {
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

    /** Module-internal cleanup owned by one accepted presentation grant. */
    internal fun registerPresentationCleanup(grantId: String, cleanup: () -> Unit) {
        registerTransientCleanup(AppDestination.Capsule(grantId), cleanup)
    }

    private fun runTransientCleanups(destination: AppDestination) {
        transientCleanups.remove(destination)?.forEach { cleanup -> cleanup() }
    }

    private fun revokeAllPresentation() {
        expiryWatch.cancel()
        val grantId = (controller.capsuleAccess as? CapsuleAccess.Granted)?.grantId
        presentationGrants.clearAll()
        controller.consumeCapsuleAccess()
        grantId?.let {
            runTransientCleanups(AppDestination.Capsule(it))
            _capsuleRevocations.tryEmit(it)
        }
    }

    private fun currentAuthenticatedOwner(): UserId? {
        val authenticated = _authState.value as? AuthUiState.Authenticated ?: return null
        return runCatching { UserId.parseRest(authenticated.userId) }.getOrNull()
    }

    private fun authBoundary(state: AuthUiState): AuthenticatedBoundary? =
        (state as? AuthUiState.Authenticated)?.let {
            AuthenticatedBoundary(it.userId, it.activeKeyBundleId)
        }

    private data class AuthenticatedBoundary(
        val userId: String,
        val activeKeyBundleId: String?,
    )

    override fun onCleared() {
        expiryWatch.cancel()
        revokeAllPresentation()
        super.onCleared()
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
        val waiter = CompletableDeferred<Unit>()
        val startsRunner = refreshMutex.withLock {
            refreshGeneration += 1
            refreshWaiters += waiter
            if (refreshRunning) {
                refreshPending = true
                false
            } else {
                refreshRunning = true
                true
            }
        }

        if (startsRunner) {
            runRefreshCoordinator()
        }

        try {
            waiter.await()
        } catch (cancelled: CancellationException) {
            refreshMutex.withLock { refreshWaiters.remove(waiter) }
            throw cancelled
        }
    }

    private suspend fun runRefreshCoordinator() {
        try {
            while (true) {
                val generation = refreshMutex.withLock { refreshGeneration }
                performResolveNow(generation)
                val waitersToComplete = refreshMutex.withLock {
                    if (refreshPending) {
                        refreshPending = false
                        null
                    } else {
                        refreshRunning = false
                        refreshWaiters.toList().also { refreshWaiters.clear() }
                    }
                }
                if (waitersToComplete == null) continue
                waitersToComplete.forEach { it.complete(Unit) }
                return
            }
        } catch (cancelled: CancellationException) {
            failRefreshWaiters(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            failRefreshWaiters(failure)
            throw failure
        }
    }

    private suspend fun failRefreshWaiters(failure: Throwable) {
        val waitersToComplete = refreshMutex.withLock {
            refreshRunning = false
            refreshPending = false
            refreshWaiters.toList().also { refreshWaiters.clear() }
        }
        waitersToComplete.forEach { it.completeExceptionally(failure) }
    }

    private suspend fun publishIfCurrent(
        generation: Long,
        next: AuthUiState,
    ): Boolean = refreshMutex.withLock {
        if (refreshGeneration != generation) {
            false
        } else {
            // Keep the generation check and state publication atomic so a
            // later request cannot be followed by a stale Authenticated state.
            publish(next)
            true
        }
    }

    private suspend fun isCurrentRefresh(generation: Long): Boolean =
        refreshMutex.withLock { refreshGeneration == generation }

    private suspend fun performResolveNow(generation: Long) {
        var activeUserId: String? = null
        val next = try {
            when (val resolved = sessionBootstrap.bootstrap()) {
                SessionState.SignedOut -> AuthUiState.SignedOut
                SessionState.RecoveryRequired -> AuthUiState.RecoveryRequired
                SessionState.RequiresConnectivity -> AuthUiState.RequiresConnectivity
                is SessionState.Active -> {
                    activeUserId = resolved.userId
                    AuthUiState.Authenticated(
                        userId = resolved.userId ?: "",
                        handle = resolved.handle ?: "",
                        activeKeyBundleId = resolved.activeKeyBundleId,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AuthUiState.RequiresConnectivity
        }
        val rawActiveUserId = activeUserId
        val activeOwner = rawActiveUserId
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UserId.parseRest(it) }.getOrNull() }
        if (next is AuthUiState.Authenticated && !rawActiveUserId.isNullOrBlank() && activeOwner == null) {
            if (!publishIfCurrent(generation, next)) return
            return
        }
        if (next is AuthUiState.Authenticated && activeOwner != null) {
            try {
                recoverCreateStaging(activeOwner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not expose the authenticated account while an in-scope
                // plaintext recovery sweep could not complete.
                publishIfCurrent(generation, AuthUiState.RequiresConnectivity)
                return
            }
        }
        if (!publishIfCurrent(generation, next)) return

        if (next is AuthUiState.Authenticated && activeOwner != null) {
            val owner = activeOwner
            if (!isCurrentRefresh(generation)) return
            try {
                resumeCapsuleUploads(owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Discovery is best-effort; authenticated navigation remains intact.
            }
            if (!isCurrentRefresh(generation)) return
            try {
                scheduleIncomingSync(owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Work scheduling is part of the bootstrap boundary: do not
                // leave a resolved-looking root when the authenticated chain
                // could not be accepted by WorkManager.
                publishIfCurrent(generation, AuthUiState.RequiresConnectivity)
            }
        }
    }

    private fun publish(next: AuthUiState) {
        val previousAuth = _authState.value
        val previousDestination = controller.current
        val ownerOrKeyBoundaryChanged = authBoundary(previousAuth) != authBoundary(next)
        if (ownerOrKeyBoundaryChanged) {
            // Account identity changes invalidate the prepared owner-bound
            // handle before the guarded controller publishes the new state.
            revokeAllPresentation()
        }
        _authState.value = next
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
