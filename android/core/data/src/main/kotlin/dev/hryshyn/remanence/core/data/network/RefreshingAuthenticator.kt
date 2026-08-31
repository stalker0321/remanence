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
    ) : CoordinatedRefreshOutcome

    /** Another caller rotated while this caller waited for the coordinator. */
    data class Reused(val accessToken: String) : CoordinatedRefreshOutcome

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

    /** Logout-only raw bearer; not for ordinary requests. */
    fun rawAccessToken(): String? = tokens.accessToken

    internal fun installedOwnerOrNull(): UserId? = installedOwner.get()

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
    suspend fun refreshForBootstrap(expectedOwner: UserId): CoordinatedRefreshOutcome =
        withContext(Dispatchers.IO) {
            onBeforeRefreshMutex?.invoke()
            val observedRotation = rotationGeneration.get()
            mutex.withLock {
                refreshLocked(
                    staleAccessToken = null,
                    observedRotation = observedRotation,
                    expectedOwner = expectedOwner,
                )
            }
        }

    /** Refreshes one 401 request; concurrent waiters reuse the rotated access token. */
    suspend fun refreshForAuthenticator(staleAccessToken: String?): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                when (
                    val outcome = refreshLocked(
                        staleAccessToken,
                        observedRotation = null,
                        expectedOwner = null,
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
    ): CoordinatedRefreshOutcome {
        if (!domainOpen.get()) return CoordinatedRefreshOutcome.Invalidated
        val stored = readStoredCredentialLocked()
            ?: return CoordinatedRefreshOutcome.NoToken
        if (expectedOwner != null && stored.ownerUserId != expectedOwner) {
            // Stored record is not the bootstrap owner; never wipe a later
            // installed account's bound credential.
            return CoordinatedRefreshOutcome.Invalidated
        }
        val storedRefreshToken = stored.refreshToken
        val storedOwner = stored.ownerUserId
        val currentAccessToken = tokens.accessToken

        if (staleAccessToken != null &&
            currentAccessToken != null &&
            currentAccessToken != staleAccessToken
        ) {
            return CoordinatedRefreshOutcome.Reused(currentAccessToken)
        }
        if (observedRotation != null &&
            rotationGeneration.get() != observedRotation &&
            currentAccessToken != null
        ) {
            return CoordinatedRefreshOutcome.Reused(currentAccessToken)
        }

        // A login or another coordinated rotation may have replaced the
        // persisted token while this caller was waiting. Never send the older
        // persisted lineage when a current in-memory pair is available.
        val currentRefreshToken = tokens.refreshToken
        if (currentRefreshToken != null &&
            currentRefreshToken != storedRefreshToken &&
            currentAccessToken != null
        ) {
            return CoordinatedRefreshOutcome.Reused(currentAccessToken)
        }

        val operationEpoch = invalidationEpoch.get()
        val result = bareAuthRepository.refresh(RefreshRequestDto(storedRefreshToken))
        return publishUnderFence {
            if (!domainOpen.get() ||
                !publicationStillOwnsLineage(operationEpoch, storedRefreshToken, storedOwner)
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
                        rotationGeneration.incrementAndGet()
                        CoordinatedRefreshOutcome.Rotated(
                            accessToken = result.value.accessToken,
                            refreshToken = result.value.refreshToken,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        clearIfExactRecord(storedRefreshToken, storedOwner)
                        CoordinatedRefreshOutcome.Unavailable
                    }

                    is AuthResult.Failure -> when {
                        result.reason == AuthFailure.NETWORK ->
                            CoordinatedRefreshOutcome.Unreachable
                        result.reason == AuthFailure.HTTP &&
                            result.httpStatus in setOf(401, 403, 409) -> {
                            clearIfExactRecord(storedRefreshToken, storedOwner)
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

    private fun publicationStillOwnsLineage(
        operationEpoch: Long,
        storedRefreshToken: String,
        storedOwner: UserId,
    ): Boolean {
        if (operationEpoch != invalidationEpoch.get()) return false
        val current = currentStoredCredentialOrNull() ?: return false
        return current.refreshToken == storedRefreshToken && current.ownerUserId == storedOwner
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

    private fun clearIfExactRecord(storedRefreshToken: String, storedOwner: UserId) {
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
}

/**
 * Adds the memory-only bearer access token to outgoing API requests.
 * Unauthenticated auth endpoints are passed through. While the domain is
 * closed ([accessToken] is null), explicit ordinary Authorization is
 * stripped; only a bare logout client may send a raw revocation bearer.
 */
class BearerAuthInterceptor(
    private val accessToken: () -> String?,
) : Interceptor {

    constructor(tokens: AuthTokenHolder) : this({ tokens.accessToken })

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (UNAUTHENTICATED_PATH_SUFFIXES.any { request.url.encodedPath.endsWith(it) }) {
            return chain.proceed(request)
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
        if (existing != null) {
            return chain.proceed(request)
        }
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
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val staleAccessToken = response.request.header(AUTHORIZATION_HEADER)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.substring(BEARER_PREFIX.length)

        val freshAccessToken = try {
            runBlocking { refreshCoordinator.refreshForAuthenticator(staleAccessToken) }
        } catch (_: CancellationException) {
            return null
        } ?: return null

        return response.request.newBuilder()
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + freshAccessToken)
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
        ): RefreshingAuthenticator = RefreshingAuthenticator(refreshCoordinator)

        /**
         * Production stack: the returned builder gains the bearer interceptor
         * plus the serialized authenticator whose refresh round trips run on
         * the supplied bare (authenticator-free) repository/client.
         */
        fun attach(
            builder: OkHttpClient.Builder,
            refreshCoordinator: SessionRefreshCoordinator,
        ): OkHttpClient.Builder = builder
            .addInterceptor(BearerAuthInterceptor { refreshCoordinator.openDomainAccessToken() })
            .authenticator(create(refreshCoordinator))

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
