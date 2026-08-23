package postmark.core.data.network

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * In-memory holder for the live session credentials. Persistence of the
 * refresh token stays outside this layer (sealed session storage).
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
 * OkHttp [Authenticator] that serializes exactly one `/v1/auth/refresh` round
 * trip for any burst of concurrent 401 responses and never retries a single
 * request more than once. When the refresh fails (including replay detection),
 * the in-memory session is cleared and the original 401 propagates.
 */
class RefreshingAuthenticator internal constructor(
    private val authRepository: AuthRepository,
    private val tokens: AuthTokenHolder,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val staleAccessToken = response.request.header("Authorization")
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.substring(BEARER_PREFIX.length)

        val freshAccessToken = try {
            runBlocking { refreshSerialized(staleAccessToken) }
        } catch (_: CancellationException) {
            return null
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", BEARER_PREFIX + freshAccessToken)
            .build()
    }

    /**
     * Only one caller performs the network refresh; concurrent waiters observe
     * a changed access token and reuse it without another round trip.
     */
    private suspend fun refreshSerialized(staleAccessToken: String?): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val currentAccess = tokens.accessToken
                if (staleAccessToken != null && currentAccess != null && currentAccess != staleAccessToken) {
                    return@withContext currentAccess
                }
                val refreshToken = tokens.refreshToken ?: return@withContext null
                when (val result = authRepository.refresh(RefreshRequestDto(refreshToken))) {
                    is AuthResult.Success -> {
                        tokens.updateTokens(result.value.accessToken, result.value.refreshToken)
                        result.value.accessToken
                    }
                    is AuthResult.Failure -> {
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
        /** Wires [authenticator] onto an OkHttp client builder. */
        fun attach(builder: okhttp3.OkHttpClient.Builder, authRepository: AuthRepository, tokens: AuthTokenHolder): okhttp3.OkHttpClient.Builder =
            builder.authenticator(RefreshingAuthenticator(authRepository, tokens))

        const val BEARER_PREFIX: String = "Bearer "

        internal fun create(authRepository: AuthRepository, tokens: AuthTokenHolder): RefreshingAuthenticator =
            RefreshingAuthenticator(authRepository, tokens)
    }
}
