package postmark.core.data.network

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
    private val bareAuthRepository: AuthRepository,
    private val tokens: AuthTokenHolder,
    private val rotationSink: SessionRotationSink,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val staleAccessToken = response.request.header(AUTHORIZATION_HEADER)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.substring(BEARER_PREFIX.length)

        val freshAccessToken = try {
            runBlocking { refreshSerialized(staleAccessToken) }
        } catch (_: CancellationException) {
            return null
        } ?: return null

        return response.request.newBuilder()
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + freshAccessToken)
            .build()
    }

    /**
     * Only one caller performs the network refresh against the bare client;
     * concurrent waiters either reuse the rotated token or observe the clear.
     */
    private suspend fun refreshSerialized(staleAccessToken: String?): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val currentAccess = tokens.accessToken
                if (staleAccessToken != null && currentAccess != null && currentAccess != staleAccessToken) {
                    return@withContext currentAccess
                }
                val refreshToken = tokens.refreshToken ?: return@withContext null
                when (val result = bareAuthRepository.refresh(RefreshRequestDto(refreshToken))) {
                    is AuthResult.Success -> {
                        try {
                            // One atomic step: memory + sealed persistence.
                            rotationSink.rotate(result.value.accessToken, result.value.refreshToken)
                        } catch (_: Exception) {
                            rotationSink.clear()
                            tokens.clearSession()
                            return@withContext null
                        }
                        tokens.updateTokens(result.value.accessToken, result.value.refreshToken)
                        result.value.accessToken
                    }
                    is AuthResult.Failure -> {
                        rotationSink.clear()
                        tokens.clearSession()
                        null
                    }
                }
            }
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
            bareAuthRepository: AuthRepository,
            tokens: AuthTokenHolder,
            rotationSink: SessionRotationSink,
        ): RefreshingAuthenticator = RefreshingAuthenticator(bareAuthRepository, tokens, rotationSink)

        /**
         * Production stack: the returned builder gains the bearer interceptor
         * plus the serialized authenticator whose refresh round trips run on
         * the supplied bare (authenticator-free) repository/client.
         */
        fun attach(
            builder: OkHttpClient.Builder,
            bareAuthRepository: AuthRepository,
            tokens: AuthTokenHolder,
            rotationSink: SessionRotationSink,
        ): OkHttpClient.Builder = builder
            .addInterceptor(BearerAuthInterceptor(tokens))
            .authenticator(create(bareAuthRepository, tokens, rotationSink))

        /**
         * Convenience production wiring: builds a complete authenticated
         * client from an existing base client configuration.
         */
        fun authenticatedClient(
            base: OkHttpClient,
            bareAuthRepository: AuthRepository,
            tokens: AuthTokenHolder,
            rotationSink: SessionRotationSink,
        ): OkHttpClient = attach(base.newBuilder(), bareAuthRepository, tokens, rotationSink).build()
    }
}
