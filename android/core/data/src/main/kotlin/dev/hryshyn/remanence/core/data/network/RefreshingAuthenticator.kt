package dev.hryshyn.remanence.core.data.network

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
 * The single persistence boundary for the sealed rotating refresh token.
 * Refresh coordination owns calls to this boundary; login/logout may still
 * use their existing account-flow ordering around it.
 */
fun interface RefreshTokenReader {
    fun read(): String?
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
    fun rotate(accessToken: String, refreshToken: String)

    fun clear()
}

/**
 * Process-wide refresh boundary shared by cold-start/foreground bootstrap and
 * OkHttp 401 authentication. The mutex serializes stored-token read and the
 * refresh POST. A separate, short publication fence serializes account-boundary
 * [invalidate] with the final lineage check and sink rotate/clear so a stale
 * refresh cannot publish after logout or a replacement login. The network POST
 * is never run under that fence.
 */
class SessionRefreshCoordinator internal constructor(
    private val bareAuthRepository: AuthRepository,
    private val tokens: AuthTokenHolder,
    private val refreshTokenReader: RefreshTokenReader,
    private val rotationSink: SessionRotationSink,
) {

    private val mutex = Mutex()
    private val publicationFence = java.util.concurrent.locks.ReentrantLock()
    private val invalidationEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    private val rotationGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Test-only pause inside the publication fence, after the lock is held and
     * before lineage check plus sink mutate. Production leaves this null.
     */
    internal var onPublicationFence: (() -> Unit)? = null

    /**
     * Account-boundary fence: waits only for in-flight check+sink publication,
     * never for a network POST, then invalidates any later publication.
     */
    fun invalidate() {
        publicationFence.lock()
        try {
            invalidationEpoch.incrementAndGet()
        } finally {
            publicationFence.unlock()
        }
    }

    /** Reads and validates token presence under the same refresh mutex. */
    suspend fun hasStoredToken(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            readStoredTokenLocked() != null
        }
    }

    /** Refreshes for bootstrap; a rotation that happened while waiting is reused. */
    suspend fun refreshForBootstrap(): CoordinatedRefreshOutcome = withContext(Dispatchers.IO) {
        val observedRotation = rotationGeneration.get()
        mutex.withLock {
            refreshLocked(
                staleAccessToken = null,
                observedRotation = observedRotation,
            )
        }
    }

    /** Refreshes one 401 request; concurrent waiters reuse the rotated access token. */
    suspend fun refreshForAuthenticator(staleAccessToken: String?): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                when (val outcome = refreshLocked(staleAccessToken, observedRotation = null)) {
                    is CoordinatedRefreshOutcome.Rotated -> outcome.accessToken
                    is CoordinatedRefreshOutcome.Reused -> outcome.accessToken
                    else -> null
                }
            }
        }

    private suspend fun refreshLocked(
        staleAccessToken: String?,
        observedRotation: Long?,
    ): CoordinatedRefreshOutcome {
        val storedRefreshToken = readStoredTokenLocked()
            ?: return CoordinatedRefreshOutcome.NoToken
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
        return when (result) {
            is AuthResult.Success -> publishUnderFence {
                if (!publicationStillOwnsLineage(operationEpoch, storedRefreshToken)) {
                    CoordinatedRefreshOutcome.Invalidated
                } else {
                    try {
                        rotationSink.rotate(
                            result.value.accessToken,
                            result.value.refreshToken,
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
                        if (currentStoredTokenOrNull() == storedRefreshToken) {
                            clearLocked()
                        }
                        CoordinatedRefreshOutcome.Unavailable
                    }
                }
            }

            is AuthResult.Failure -> when {
                result.reason == AuthFailure.NETWORK -> CoordinatedRefreshOutcome.Unreachable
                result.reason == AuthFailure.HTTP &&
                    result.httpStatus in setOf(401, 403, 409) -> publishUnderFence {
                    if (currentStoredTokenOrNull() == storedRefreshToken) {
                        clearLocked()
                    }
                    CoordinatedRefreshOutcome.Rejected
                }
                else -> CoordinatedRefreshOutcome.Unavailable
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

    private fun publicationStillOwnsLineage(operationEpoch: Long, storedRefreshToken: String): Boolean =
        operationEpoch == invalidationEpoch.get() &&
            currentStoredTokenOrNull() == storedRefreshToken

    private fun currentStoredTokenOrNull(): String? = try {
        refreshTokenReader.read()?.takeIf { it.isNotBlank() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun readStoredTokenLocked(): String? = try {
        refreshTokenReader.read()?.takeIf { it.isNotBlank() }
            ?: run {
                if (tokens.refreshToken != null) clearLocked()
                null
            }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        clearLocked()
        null
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

    internal fun tokensForInterceptor(): AuthTokenHolder = tokens
}

/**
 * Adds the memory-only bearer access token to outgoing API requests. Requests
 * that already carry an Authorization header (explicit logout) and the three
 * unauthenticated auth endpoints are passed through untouched.
 */
class BearerAuthInterceptor(
    private val tokens: AuthTokenHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokens.accessToken
        if (token == null ||
            request.header(AUTHORIZATION_HEADER) != null ||
            UNAUTHENTICATED_PATH_SUFFIXES.any { request.url.encodedPath.endsWith(it) }
        ) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header(AUTHORIZATION_HEADER, RefreshingAuthenticator.BEARER_PREFIX + token)
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
            .addInterceptor(BearerAuthInterceptor(refreshCoordinator.tokensForInterceptor()))
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
