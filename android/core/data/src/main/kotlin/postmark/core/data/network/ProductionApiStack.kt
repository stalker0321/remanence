package postmark.core.data.network

import okhttp3.OkHttpClient

/**
 * Production HTTP stack assembled once per process (FIX-M1-007-06):
 * - a BARE auth repository whose client has neither the bearer interceptor
 *   nor the authenticator - the only shape allowed to call `/v1/auth/refresh`
 *   so a rejected refresh can never recurse;
 * - an AUTHENTICATED client carrying [BearerAuthInterceptor] plus the
 *   serialized one-retry [RefreshingAuthenticator].
 *
 * The concrete client stays internal so consumers never depend on OkHttp
 * directly; later repositories are opened through this stack.
 */
class ProductionApiStack private constructor(
    baseUrl: ApiBaseUrl,
    tokens: AuthTokenHolder,
    rotationSink: SessionRotationSink,
) {

    /** Bare repository for register/login/refresh/logout round trips. */
    val bareAuthRepository: AuthRepository =
        AuthRepository.create(baseUrl)

    /** Fully wired client for every authenticated API surface. */
    val authenticatedClient: OkHttpClient =
        RefreshingAuthenticator.attach(
            OkHttpClient.Builder(),
            bareAuthRepository,
            tokens,
            rotationSink,
        ).build()

    companion object {
        fun create(
            baseUrl: ApiBaseUrl,
            tokens: AuthTokenHolder,
            rotationSink: SessionRotationSink,
        ): ProductionApiStack = ProductionApiStack(baseUrl, tokens, rotationSink)
    }
}
