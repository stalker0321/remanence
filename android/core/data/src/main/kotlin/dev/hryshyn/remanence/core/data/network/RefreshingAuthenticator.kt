package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Immutable process-local binding for one authenticated resource request. */
data class SessionRequestLease(
    val ownerUserId: UserId,
    val incarnation: Long,
) {
    override fun toString(): String = "SessionRequestLease(<redacted>)"
}

/** Safe transport failure when a request crossed its session boundary. */
class RequestLeaseRejectedException : java.io.IOException()

/** Captures a lease before suspension and tags the corresponding request. */
class SessionRequestLeaseProvider internal constructor(
    private val coordinator: SessionRefreshCoordinator,
) {
    fun capture(): SessionRequestLease? = coordinator.captureRequestLease()

    fun admitOwner(
        ownerUserId: UserId,
        expectedLease: SessionRequestLease?,
    ): SessionRequestLease? = coordinator.admitOwnerLease(ownerUserId, expectedLease)

    fun tag(request: Request, lease: SessionRequestLease): Request =
        request.newBuilder()
            .tag(SessionRequestLease::class.java, lease)
            .build()

    fun accessTokenFor(lease: SessionRequestLease): String? =
        coordinator.accessTokenForLease(lease)

    /**
     * Final transport admission. Validation and bearer selection happen in
     * one publication-fence critical section; the caller must build the
     * Authorization header only from the returned value.
     */
    fun admit(lease: SessionRequestLease, existingBearer: String? = null): String? =
        coordinator.admitRequestLease(lease, existingBearer)

    fun isLive(lease: SessionRequestLease): Boolean =
        coordinator.isRequestLeaseLive(lease)
}

/**
 * In-memory holder for the live session credentials. The access token exists
 * ONLY here; persistence of the rotating refresh token stays outside this
 * layer behind [SessionRotationSink].
 */
class AuthTokenHolder(
    initialAccess: String? = null,
    initialRefresh: String? = null,
) {
    @Volatile
    var accessToken: String? = initialAccess
        private set

    @Volatile
    var refreshToken: String? = initialRefresh
        private set

    fun updateTokens(access: String, refresh: String) {
        this.accessToken = access
        this.refreshToken = refresh
    }

    fun clearSession() {
        this.accessToken = null
        this.refreshToken = null
    }
}

/**
 * Owner-bound refresh credential. The token is opaque and never included in
 * [toString]; callers must not log [refreshToken].
 */
data class BoundRefreshCredential(
    val ownerUserId: UserId,
    val refreshToken: String,
) {
    override fun toString(): String =
        "BoundRefreshCredential(ownerUserId=${ownerUserId.toRestString()})"
}

/**
 * The single persistence boundary for the sealed rotating refresh record.
 * Refresh coordination owns calls to this boundary; login/logout may still
 * use their existing account-flow ordering around it.
 */
fun interface RefreshTokenReader {
    fun read(): BoundRefreshCredential?
}

/** Result of one process-wide refresh-token operation. */
sealed interface CoordinatedRefreshOutcome {
    data class Rotated(
        val accessToken: String,
        val refreshToken: String,
        val lease: SessionRequestLease? = null,
    ) : CoordinatedRefreshOutcome

    /** Another caller rotated while this caller waited for the coordinator. */
    data class Reused(
        val accessToken: String,
        val lease: SessionRequestLease? = null,
    ) : CoordinatedRefreshOutcome

    data object NoToken : CoordinatedRefreshOutcome
    data object Rejected : CoordinatedRefreshOutcome
    data object Unreachable : CoordinatedRefreshOutcome
    data object Unavailable : CoordinatedRefreshOutcome
    data object Invalidated : CoordinatedRefreshOutcome
}

/**
 * Atomic rotation boundary invoked by the serialized refresher while still
 * holding its mutex: implementers must publish BOTH credentials to consumers
 * and persist the sealed rotating refresh token as ONE step. A throwing
 * [rotate] fails the whole refresh closed.
 */
interface SessionRotationSink {
    fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId)

    fun clear()
}

/**
 * Process-wide refresh and account-boundary. The mutex serializes stored-token
 * read and the refresh POST. A separate, short publication fence serializes
 * credential mutate, domain open/close, and lease retirement. Network is never
 * taken under that fence. Ordinary requests read the bearer only while the
 * domain is open; logout may still read the raw bearer for bare revocation.
 */
class SessionRefreshCoordinator internal constructor(
    private val bareAuthRepository: AuthRepository,
    private val tokens: AuthTokenHolder,
    private val refreshTokenReader: RefreshTokenReader,
    private val rotationSink: SessionRotationSink,
) {

    private val mutex = Mutex()
    private val publicationFence = java.util.concurrent.locks.ReentrantLock()
    private val accountLeaseEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    private val invalidationEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    private val rotationGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val domainOpen = java.util.concurrent.atomic.AtomicBoolean(true)
    private val installedOwner = java.util.concurrent.atomic.AtomicReference<UserId?>(null)

    /**
     * Test-only pause inside the publication fence, after the lock is held and
     * before lineage check plus sink mutate. Production leaves this null.
     */
    internal var onPublicationFence: (() -> Unit)? = null

    /**
     * Test-only pause after a bootstrap caller has chosen its expected owner
     * and before this coordinator takes its mutex. Production leaves this null.
     */
    internal var onBeforeRefreshMutex: (() -> Unit)? = null

    /** Test-only barrier after epoch capture and before dispatcher handoff. */
    internal var onBeforeBootstrapDispatch: (() -> Unit)? = null

    /**
     * Test-only pause after bound credentials are persisted and before the
     * domain is opened. Production leaves this null.
     */
    internal var onAfterBoundCredentialsPersisted: (() -> Unit)? = null

    /**
     * Test-only pause after a stored-credential read fails and before
     * publication-fenced cleanup. Production leaves this null.
     */
    internal var onBeforeReadFailureCleanup: (() -> Unit)? = null

    /** Test-only barrier for the lease admission race. */
    internal var onBeforeRequestLeaseTokenSelection: (() -> Unit)? = null

    /** Test-only barrier for an early coordinated Reused result. */
    internal var onBeforeReusedBearerSelection: (() -> Unit)? = null

    /**
     * Test-only pause after an exact bound-record match and before that
     * record is cleared, still holding the publication fence. Production
     * leaves this null.
     */
    var onAfterExactBoundClearMatch: (() -> Unit)? = null

    /**
     * Non-closing account-boundary lease. Login and registration acquire this
     * before their server calls and carry it through replacement. Logout's
     * [invalidate] retires it.
     */
    fun acquireAccountLease(): Long = accountLeaseEpoch.get()

    /**
     * Logout/account teardown: retires outstanding replacement leases and
     * closes the refresh domain. Does not clear the live bearer so
     * [rawAccessToken] remains available for best-effort revocation.
     */
    fun invalidate() {
        publicationFence.lock()
        try {
            accountLeaseEpoch.incrementAndGet()
            invalidationEpoch.incrementAndGet()
            installedOwner.set(null)
            domainOpen.set(false)
        } finally {
            publicationFence.unlock()
        }
    }

    /**
     * Replacement start: close refresh admission without retiring the
     * caller's lease and without clearing the live bearer.
     */
    fun closeAdmission() {
        publicationFence.lock()
        try {
            invalidationEpoch.incrementAndGet()
            installedOwner.set(null)
            domainOpen.set(false)
        } finally {
            publicationFence.unlock()
        }
    }

    /**
     * Reopens the refresh domain for [expectedOwner] after a successful
     * replacement credential install. In-flight operations from the previous
     * account stay invalidated. Network is never taken under this fence.
     */
    fun install(expectedOwner: UserId) {
        publicationFence.lock()
        try {
            invalidationEpoch.incrementAndGet()
            installedOwner.set(expectedOwner)
            domainOpen.set(true)
        } finally {
            publicationFence.unlock()
        }
    }

    /**
     * Atomically publish sealed+memory credentials and open the domain when
     * [lease] is still current and [currentAccountOwner] equals [expectedOwner].
     * On rejection, leaves no usable published credentials and keeps the
     * domain closed. Network is never taken under this fence.
     */
    fun publishBoundSession(
        lease: Long,
        expectedOwner: UserId,
        accessToken: String,
        refreshToken: String,
        currentAccountOwner: UserId?,
    ): Boolean {
        publicationFence.lock()
        try {
            onPublicationFence?.invoke()
            if (accountLeaseEpoch.get() != lease || currentAccountOwner != expectedOwner) {
                clearLocked()
                invalidationEpoch.incrementAndGet()
                installedOwner.set(null)
                domainOpen.set(false)
                return false
            }
            try {
                rotationSink.rotate(accessToken, refreshToken, expectedOwner)
                tokens.updateTokens(accessToken, refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearLocked()
                invalidationEpoch.incrementAndGet()
                installedOwner.set(null)
                domainOpen.set(false)
                return false
            }
            onAfterBoundCredentialsPersisted?.invoke()
            invalidationEpoch.incrementAndGet()
            installedOwner.set(expectedOwner)
            domainOpen.set(true)
            rotationGeneration.incrementAndGet()
            return true
        } finally {
            publicationFence.unlock()
        }
    }

    /**
     * Under the publication fence: clear sealed+memory credentials only when
     * they are still exactly [ownerUserId] + [refreshToken]. Replacement
     * publish cannot interleave.
     */
    fun clearExactBoundRecord(ownerUserId: UserId, refreshToken: String) {
        publicationFence.lock()
        try {
            val current = currentStoredCredentialOrNull() ?: return
            if (current.refreshToken != refreshToken || current.ownerUserId != ownerUserId) {
                return
            }
            onAfterExactBoundClearMatch?.invoke()
            val still = currentStoredCredentialOrNull() ?: return
            if (still.refreshToken == refreshToken && still.ownerUserId == ownerUserId) {
                clearLocked()
            }
        } finally {
            publicationFence.unlock()
        }
    }

    /** Fail-closed wipe of published credentials; domain stays closed. */
    fun discardPublishedCredentials() {
        publicationFence.lock()
        try {
            clearLocked()
            invalidationEpoch.incrementAndGet()
            installedOwner.set(null)
            domainOpen.set(false)
        } finally {
            publicationFence.unlock()
        }
    }

    /** Ordinary requests: bearer only while the refresh domain is open. */
    fun openDomainAccessToken(): String? {
        if (!domainOpen.get()) return null
        return tokens.accessToken
    }

    /** Captures the live owner/incarnation before a resource request suspends. */
    internal fun captureRequestLease(): SessionRequestLease? {
        publicationFence.lock()
        try {
            val owner = installedOwner.get() ?: return null
            if (!domainOpen.get() || tokens.accessToken.isNullOrBlank()) return null
            return SessionRequestLease(owner, invalidationEpoch.get())
        } finally {
            publicationFence.unlock()
        }
    }

    /** Validates the immutable request binding under the publication fence. */
    internal fun isRequestLeaseLive(lease: SessionRequestLease): Boolean {
        publicationFence.lock()
        try {
            return isRequestLeaseLiveLocked(lease)
        } finally {
            publicationFence.unlock()
        }
    }

    /** Returns a token only while the request's original lease is live. */
    internal fun accessTokenForLease(lease: SessionRequestLease): String? {
        return admitRequestLease(lease)
    }

    /**
     * Validates a request lease and selects its bearer under one short
     * publication fence. The fence is never held while OkHttp performs I/O.
     */
    internal fun admitRequestLease(
        lease: SessionRequestLease,
        existingBearer: String? = null,
    ): String? {
        publicationFence.lock()
        try {
            if (!isRequestLeaseLiveLocked(lease)) return null
            onBeforeRequestLeaseTokenSelection?.invoke()
            if (!isRequestLeaseLiveLocked(lease)) return null
            val accessToken = tokens.accessToken?.takeIf { it.isNotBlank() } ?: return null
            if (existingBearer != null && existingBearer != "${RefreshingAuthenticator.BEARER_PREFIX}$accessToken") {
                return null
            }
            return accessToken
        } finally {
            publicationFence.unlock()
        }
    }

    /** Logout-only raw bearer; not for ordinary requests. */
    fun rawAccessToken(): String? = tokens.accessToken

    internal fun installedOwnerOrNull(): UserId? = installedOwner.get()

    /**
     * Final owner/session admission for a bootstrap result. The expected
     * lease is checked together with the installed owner and live bearer
     * under the short publication fence; no token is returned or persisted.
     */
    fun admitOwnerLease(
        expectedOwner: UserId,
        expectedLease: SessionRequestLease?,
    ): SessionRequestLease? {
        publicationFence.lock()
        try {
            val owner = installedOwner.get()
            if (!domainOpen.get() || owner != expectedOwner) return null
            if (expectedLease != null && !isRequestLeaseLiveLocked(expectedLease)) return null
            if (tokens.accessToken.isNullOrBlank()) return null
            return expectedLease ?: SessionRequestLease(owner, invalidationEpoch.get())
        } finally {
            publicationFence.unlock()
        }
    }

    /** Reads and validates token presence under the same refresh mutex. */
    suspend fun hasStoredToken(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            readStoredCredentialLocked() != null
        }
    }

    /**
     * Refreshes for bootstrap against [expectedOwner]. The bound record is
     * read under the coordinator mutex before any POST; a different owner
     * fails closed without network.
     */
    suspend fun refreshForBootstrap(expectedOwner: UserId): CoordinatedRefreshOutcome {
        // Capture synchronously, before withContext can dispatch or suspend.
        // A delayed A invocation must not wake after logout/login and adopt
        // the new same-owner A2 lineage as if it were still A1.
        val expectedBootstrapEpoch = invalidationEpoch.get()
        onBeforeBootstrapDispatch?.invoke()
        return withContext(Dispatchers.IO) {
            onBeforeRefreshMutex?.invoke()
            val observedRotation = rotationGeneration.get()
            mutex.withLock {
                refreshLocked(
                    staleAccessToken = null,
                    observedRotation = observedRotation,
                    expectedOwner = expectedOwner,
                    expectedBootstrapEpoch = expectedBootstrapEpoch,
                )
            }
        }
    }

    /** Refreshes one 401 request; concurrent waiters reuse the rotated access token. */
    suspend fun refreshForAuthenticator(
        staleAccessToken: String?,
        requestLease: SessionRequestLease? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                when (
                    val outcome = refreshLocked(
                        staleAccessToken,
                        observedRotation = null,
                        expectedOwner = null,
                        expectedLease = requestLease,
                    )
                ) {
                    is CoordinatedRefreshOutcome.Rotated -> outcome.accessToken
                    is CoordinatedRefreshOutcome.Reused -> outcome.accessToken
                    else -> null
                }
            }
        }

    private suspend fun refreshLocked(
        staleAccessToken: String?,
        observedRotation: Long?,
        expectedOwner: UserId?,
        expectedLease: SessionRequestLease? = null,
        expectedBootstrapEpoch: Long? = null,
    ): CoordinatedRefreshOutcome {
        if (!domainOpen.get()) return CoordinatedRefreshOutcome.Invalidated
        if (expectedBootstrapEpoch != null && invalidationEpoch.get() != expectedBootstrapEpoch) {
            return CoordinatedRefreshOutcome.Invalidated
        }
        if (expectedLease != null && !isRequestLeaseLive(expectedLease)) {
            return CoordinatedRefreshOutcome.Invalidated
        }
        val stored = readStoredCredentialLocked()
            ?: return CoordinatedRefreshOutcome.NoToken
        if (expectedOwner != null && stored.ownerUserId != expectedOwner) {
            // Stored record is not the bootstrap owner; never wipe a later
            // installed account's bound credential.
            return CoordinatedRefreshOutcome.Invalidated
        }
        if (expectedLease != null && stored.ownerUserId != expectedLease.ownerUserId) {
            return CoordinatedRefreshOutcome.Invalidated
        }
        val storedRefreshToken = stored.refreshToken
        val storedOwner = stored.ownerUserId
        reusedOutcomeIfCurrent(
            staleAccessToken = staleAccessToken,
            observedRotation = observedRotation,
            storedRefreshToken = storedRefreshToken,
            expectedLease = expectedLease,
            expectedOwner = expectedOwner,
            expectedBootstrapEpoch = expectedBootstrapEpoch,
        )?.let { return it }

        // A login or another coordinated rotation may have replaced the
        // persisted token while this caller was waiting. The checks above
        // select a reusable bearer only under the publication fence.
        if (expectedLease != null && !isRequestLeaseLive(expectedLease)) {
            return CoordinatedRefreshOutcome.Invalidated
        }

        val operationEpoch = expectedBootstrapEpoch ?: invalidationEpoch.get()
        if (operationEpoch != invalidationEpoch.get()) {
            return CoordinatedRefreshOutcome.Invalidated
        }
        if (expectedLease != null && operationEpoch != expectedLease.incarnation) {
            return CoordinatedRefreshOutcome.Invalidated
        }
        val result = bareAuthRepository.refresh(RefreshRequestDto(storedRefreshToken))
        return publishUnderFence {
            if (!domainOpen.get() ||
                !publicationStillOwnsLineage(
                    operationEpoch,
                    storedRefreshToken,
                    storedOwner,
                    expectedLease,
                    expectedOwner,
                )
            ) {
                CoordinatedRefreshOutcome.Invalidated
            } else {
                when (result) {
                    is AuthResult.Success -> try {
                        rotationSink.rotate(
                            result.value.accessToken,
                            result.value.refreshToken,
                            storedOwner,
                        )
                        tokens.updateTokens(
                            result.value.accessToken,
                            result.value.refreshToken,
                        )
                        if (expectedOwner != null &&
                            !installBootstrapOwnerLocked(
                                expectedOwner = expectedOwner,
                                expectedRefreshToken = result.value.refreshToken,
                            )
                        ) {
                            // The sink may have accepted the rotated lineage,
                            // but the owner boundary is no longer the one
                            // that requested bootstrap. Do not expose a
                            // credential without its owner/incarnation lease.
                            clearLocked()
                            CoordinatedRefreshOutcome.Invalidated
                        } else {
                            rotationGeneration.incrementAndGet()
                            CoordinatedRefreshOutcome.Rotated(
                                accessToken = result.value.accessToken,
                                refreshToken = result.value.refreshToken,
                                lease = expectedOwner?.let {
                                    SessionRequestLease(it, invalidationEpoch.get())
                                },
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        clearIfExactRecord(storedRefreshToken, storedOwner, expectedLease)
                        CoordinatedRefreshOutcome.Unavailable
                    }

                    is AuthResult.Failure -> when {
                        result.reason == AuthFailure.NETWORK ->
                            CoordinatedRefreshOutcome.Unreachable
                        result.reason == AuthFailure.HTTP &&
                            result.httpStatus in setOf(401, 403, 409) -> {
                            clearIfExactRecord(storedRefreshToken, storedOwner, expectedLease)
                            CoordinatedRefreshOutcome.Rejected
                        }
                        else -> CoordinatedRefreshOutcome.Unavailable
                    }
                }
            }
        }
    }

    private fun <T> publishUnderFence(block: () -> T): T {
        publicationFence.lock()
        try {
            onPublicationFence?.invoke()
            return block()
        } finally {
            publicationFence.unlock()
        }
    }

    /**
     * All early Reused branches share one fenced validation/selection point.
     * The test barrier is deliberately inside the short fence and cannot
     * perform network I/O; it lets boundary tests rotate the lease before the
     * final recheck without widening the production critical section.
     */
    private fun reusedOutcomeIfCurrent(
        staleAccessToken: String?,
        observedRotation: Long?,
        storedRefreshToken: String,
        expectedLease: SessionRequestLease?,
        expectedOwner: UserId?,
        expectedBootstrapEpoch: Long?,
    ): CoordinatedRefreshOutcome? {
        publicationFence.lock()
        try {
            if (expectedBootstrapEpoch != null && invalidationEpoch.get() != expectedBootstrapEpoch) {
                return CoordinatedRefreshOutcome.Invalidated
            }
            if (expectedLease != null && !isRequestLeaseLiveLocked(expectedLease)) {
                return CoordinatedRefreshOutcome.Invalidated
            }
            onBeforeReusedBearerSelection?.invoke()
            if (expectedBootstrapEpoch != null && invalidationEpoch.get() != expectedBootstrapEpoch) {
                return CoordinatedRefreshOutcome.Invalidated
            }
            if (expectedLease != null && !isRequestLeaseLiveLocked(expectedLease)) {
                return CoordinatedRefreshOutcome.Invalidated
            }
            val currentStored = if (expectedOwner != null) {
                currentStoredCredentialOrNull()?.takeIf { it.ownerUserId == expectedOwner }
                    ?: return CoordinatedRefreshOutcome.Invalidated
            } else {
                null
            }
            val currentAccessToken = tokens.accessToken?.takeIf { it.isNotBlank() } ?: return null
            val refreshLineageChanged = tokens.refreshToken?.let { it != storedRefreshToken } == true
            val rotationObserved = observedRotation != null &&
                rotationGeneration.get() != observedRotation
            val staleRequest = staleAccessToken != null && currentAccessToken != staleAccessToken
            return if (staleRequest || rotationObserved || refreshLineageChanged) {
                if (expectedOwner != null &&
                    !installBootstrapOwnerLocked(
                        expectedOwner = expectedOwner,
                        expectedRefreshToken = currentStored?.refreshToken,
                    )
                ) {
                    CoordinatedRefreshOutcome.Invalidated
                } else {
                    CoordinatedRefreshOutcome.Reused(
                        accessToken = currentAccessToken,
                        lease = expectedOwner?.let {
                            SessionRequestLease(it, invalidationEpoch.get())
                        },
                    )
                }
            } else {
                null
            }
        } finally {
            publicationFence.unlock()
        }
    }

    private fun publicationStillOwnsLineage(
        operationEpoch: Long,
        storedRefreshToken: String,
        storedOwner: UserId,
        expectedLease: SessionRequestLease?,
        expectedOwner: UserId?,
    ): Boolean {
        if (operationEpoch != invalidationEpoch.get()) return false
        if (expectedLease != null && !isRequestLeaseLiveLocked(expectedLease)) return false
        if (expectedOwner != null && installedOwner.get()?.let { it != expectedOwner } == true) {
            return false
        }
        val current = currentStoredCredentialOrNull() ?: return false
        return current.refreshToken == storedRefreshToken &&
            current.ownerUserId == storedOwner &&
            (expectedLease == null || current.ownerUserId == expectedLease.ownerUserId) &&
            (expectedOwner == null || current.ownerUserId == expectedOwner)
    }

    /**
     * Publishes the in-memory owner only after the refreshed credential
     * lineage is still the one belonging to [expectedOwner]. This is called
     * while [publicationFence] is held, never across the refresh network
     * request. A cold process starts with no installed owner; successful
     * bootstrap creates its first incarnation here. Reuse by the same live
     * owner keeps that incarnation stable for existing request leases.
     */
    private fun installBootstrapOwnerLocked(
        expectedOwner: UserId,
        expectedRefreshToken: String?,
    ): Boolean {
        if (!domainOpen.get()) return false
        if (installedOwner.get()?.let { it != expectedOwner } == true) return false
        val current = currentStoredCredentialOrNull() ?: return false
        if (current.ownerUserId != expectedOwner) return false
        if (expectedRefreshToken != null && current.refreshToken != expectedRefreshToken) return false
        if (tokens.refreshToken?.takeIf { it.isNotBlank() } != current.refreshToken) return false
        if (installedOwner.get() == null) {
            // A cold process has no prior request lease to retire. Keeping
            // this process-local epoch stable also lets a concurrent
            // bootstrap waiter reuse this same publication; logout and
            // replacement still advance the epoch before any new owner.
            installedOwner.set(expectedOwner)
        }
        return true
    }

    private fun currentStoredCredentialOrNull(): BoundRefreshCredential? = try {
        refreshTokenReader.read()?.takeIf { it.refreshToken.isNotBlank() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun readStoredCredentialLocked(): BoundRefreshCredential? {
        val memoryRefresh = tokens.refreshToken
        return try {
            refreshTokenReader.read()?.takeIf { it.refreshToken.isNotBlank() }
                ?: recoverReadFailureLocked(memoryRefresh)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recoverReadFailureLocked(memoryRefresh)
        }
    }

    /**
     * Read-failure cleanup shares the publication fence with replacement
     * publish so a stale A failure cannot erase a later B. Re-reads under
     * the fence; a published winner is returned, never cleared.
     */
    private fun recoverReadFailureLocked(memoryRefresh: String?): BoundRefreshCredential? {
        onBeforeReadFailureCleanup?.invoke()
        return publishUnderFence {
            val current = currentStoredCredentialOrNull()
            if (current != null) {
                current
            } else {
                if (memoryRefresh != null && tokens.refreshToken == memoryRefresh) {
                    clearLocked()
                }
                null
            }
        }
    }

    private fun clearIfExactRecord(
        storedRefreshToken: String,
        storedOwner: UserId,
        expectedLease: SessionRequestLease? = null,
    ) {
        if (expectedLease != null && !isRequestLeaseLiveLocked(expectedLease)) return
        val current = currentStoredCredentialOrNull() ?: return
        if (current.refreshToken == storedRefreshToken && current.ownerUserId == storedOwner) {
            clearLocked()
        }
    }

    private fun clearLocked() {
        try {
            rotationSink.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            tokens.clearSession()
        }
        tokens.clearSession()
        rotationGeneration.incrementAndGet()
    }

    private fun isRequestLeaseLiveLocked(lease: SessionRequestLease): Boolean =
        domainOpen.get() &&
            installedOwner.get() == lease.ownerUserId &&
            invalidationEpoch.get() == lease.incarnation
}

/**
 * Adds the memory-only bearer access token to outgoing API requests.
 * Unauthenticated auth endpoints are passed through. While the domain is
 * closed ([accessToken] is null), explicit ordinary Authorization is
 * stripped; only a bare logout client may send a raw revocation bearer.
 */
class BearerAuthInterceptor internal constructor(
    private val requestLeases: SessionRequestLeaseProvider? = null,
    private val accessToken: () -> String?,
) : Interceptor {

    internal constructor(tokens: AuthTokenHolder) : this(accessToken = { tokens.accessToken })

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (UNAUTHENTICATED_PATH_SUFFIXES.any { request.url.encodedPath.endsWith(it) }) {
            return chain.proceed(request)
        }
        if (requestLeases != null) {
            val lease = request.tag(SessionRequestLease::class.java)
                ?: throw RequestLeaseRejectedException()
            val existing = request.header(AUTHORIZATION_HEADER)
            val live = requestLeases.admit(lease, existing)
                ?: throw RequestLeaseRejectedException()
            if (existing != null) {
                return chain.proceed(request)
            }
            return chain.proceed(
                request.newBuilder()
                    .header(AUTHORIZATION_HEADER, RefreshingAuthenticator.BEARER_PREFIX + live)
                    .build(),
            )
        }
        val live = accessToken()
        val existing = request.header(AUTHORIZATION_HEADER)
        if (live == null) {
            val stripped = if (existing != null) {
                request.newBuilder().removeHeader(AUTHORIZATION_HEADER).build()
            } else {
                request
            }
            return chain.proceed(stripped)
        }
        if (existing != null) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header(AUTHORIZATION_HEADER, RefreshingAuthenticator.BEARER_PREFIX + live)
                .build(),
        )
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"

        /** Endpoints that must never carry (or need) the session bearer token. */
        val UNAUTHENTICATED_PATH_SUFFIXES = listOf(
            "v1/auth/register",
            "v1/auth/login",
            "v1/auth/refresh",
        )
    }
}

/**
 * OkHttp [Authenticator] that serializes exactly one `/v1/auth/refresh` round
 * trip for any burst of concurrent 401 responses and never retries a single
 * request more than once. The refresh itself runs on a SEPARATE bare client
 * without this authenticator or the bearer interceptor, so a rejected refresh
 * can never recurse. On success, rotation is published through the
 * [SessionRotationSink] (memory plus sealed persistence, atomically, inside
 * the serialization mutex); concurrent waiters observe the changed access
 * token and reuse it without another round trip. When the refresh fails
 * (including replay detection), the sink clears both memory and sealed
 * storage and the original 401 propagates.
 */
class RefreshingAuthenticator internal constructor(
    private val refreshCoordinator: SessionRefreshCoordinator,
    private val requestLeases: SessionRequestLeaseProvider? = null,
) : Authenticator {

    /** Test-only barrier after refresh and before follow-up admission. */
    internal var onAfterRefreshBeforeFollowUp: (() -> Unit)? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val requestLease = response.request.tag(SessionRequestLease::class.java)
        if (requestLeases != null && requestLease == null) return null
        if (requestLease != null && requestLeases != null && !requestLeases.isLive(requestLease)) {
            return null
        }
        val staleAccessToken = response.request.header(AUTHORIZATION_HEADER)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.substring(BEARER_PREFIX.length)

        val freshAccessToken = try {
            runBlocking {
                refreshCoordinator.refreshForAuthenticator(staleAccessToken, requestLease)
            }
        } catch (_: CancellationException) {
            return null
        } ?: return null

        onAfterRefreshBeforeFollowUp?.invoke()
        val followUpAccessToken = if (requestLeases != null && requestLease != null) {
            requestLeases.admit(requestLease)
        } else {
            freshAccessToken
        } ?: return null

        return response.request.newBuilder()
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + followUpAccessToken)
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        const val BEARER_PREFIX: String = "Bearer "

        private const val AUTHORIZATION_HEADER = "Authorization"

        internal fun create(
            refreshCoordinator: SessionRefreshCoordinator,
            requestLeases: SessionRequestLeaseProvider? = null,
        ): RefreshingAuthenticator = RefreshingAuthenticator(refreshCoordinator, requestLeases)

        /**
         * Production stack: the returned builder gains the bearer interceptor
         * plus the serialized authenticator whose refresh round trips run on
         * the supplied bare (authenticator-free) repository/client.
         */
        fun attach(
            builder: OkHttpClient.Builder,
            refreshCoordinator: SessionRefreshCoordinator,
        ): OkHttpClient.Builder = attachConfigured(
            builder = builder,
            refreshCoordinator = refreshCoordinator,
            requestLeases = SessionRequestLeaseProvider(refreshCoordinator),
        )

        /** Explicit raw transport seam retained only for core tests. */
        internal fun attachForTests(
            builder: OkHttpClient.Builder,
            refreshCoordinator: SessionRefreshCoordinator,
        ): OkHttpClient.Builder = attachConfigured(
            builder = builder,
            refreshCoordinator = refreshCoordinator,
            requestLeases = null,
        )

        private fun attachConfigured(
            builder: OkHttpClient.Builder,
            refreshCoordinator: SessionRefreshCoordinator,
            requestLeases: SessionRequestLeaseProvider?,
        ): OkHttpClient.Builder {
            return builder
                .addInterceptor(
                    BearerAuthInterceptor(
                        accessToken = { refreshCoordinator.openDomainAccessToken() },
                        requestLeases = requestLeases,
                    ),
                )
                .authenticator(create(refreshCoordinator, requestLeases))
        }

        /**
         * Convenience production wiring: builds a complete authenticated
         * client from an existing base client configuration.
         */
        fun authenticatedClient(
            base: OkHttpClient,
            refreshCoordinator: SessionRefreshCoordinator,
        ): OkHttpClient = attach(base.newBuilder(), refreshCoordinator).build()
    }
}
