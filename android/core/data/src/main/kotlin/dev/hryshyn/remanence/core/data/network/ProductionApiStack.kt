package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
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
    refreshTokenReader: RefreshTokenReader,
    rotationSink: SessionRotationSink,
) {

    /** Bare repository for register/login/refresh/logout round trips. */
    val bareAuthRepository: AuthRepository =
        AuthRepository.create(baseUrl)

    /** One refresh boundary shared by bootstrap and authenticated requests. */
    val sessionRefreshCoordinator: SessionRefreshCoordinator =
        SessionRefreshCoordinator(
            bareAuthRepository = bareAuthRepository,
            tokens = tokens,
            refreshTokenReader = refreshTokenReader,
            rotationSink = rotationSink,
        )

    /** Fully wired client for every authenticated API surface. */
    private val authenticatedClient: OkHttpClient =
        RefreshingAuthenticator.attach(
            OkHttpClient.Builder(),
            sessionRefreshCoordinator,
        ).build()

    /** Capsule clients share the serialized authenticated transport boundary. */
    val capsuleDraftRepository: CapsuleDraftRepository =
        CapsuleDraftRepository(authenticatedClient, baseUrl)

    val capsuleBlobUploadRepository: CapsuleBlobUploadRepository =
        CapsuleBlobUploadRepository(authenticatedClient, baseUrl)

    val capsuleFinalizeRepository: CapsuleFinalizeRepository =
        CapsuleFinalizeRepository(authenticatedClient, baseUrl)

    /** Immutable recipient lookup shares the authenticated refreshing client. */
    val recipientUserLookupRepository: RecipientUserLookupRepository =
        RecipientUserLookupRepository(authenticatedClient, baseUrl)

    /** Recipient ciphertext transport shares the authenticated refreshing client. */
    val recipientBlobDownloadRepository: RecipientBlobDownloadRepository =
        RecipientBlobDownloadRepository(authenticatedClient, baseUrl)

    /** Material acknowledgement transport shares the authenticated refreshing client. */
    internal val recipientMaterialSyncedRepository: RecipientMaterialSyncedRepository =
        RecipientMaterialSyncedRepository(authenticatedClient, baseUrl)

    /** Creates the public drain surface while retaining the repository internally. */
    fun createIncomingMaterialAckDrain(
        incomingCapsuleDao: IncomingCapsuleDao,
        currentSession: suspend () -> IncomingSyncSession?,
    ): IncomingMaterialAckDrain = IncomingMaterialAckDrain(
        incomingCapsuleDao = incomingCapsuleDao,
        currentSession = currentSession,
        recipientMaterialSyncedRepository = recipientMaterialSyncedRepository,
    )

    /** Account-scoped incoming cursor pages use the same authenticated stack. */
    val incomingCapsuleRepository: IncomingCapsuleRepository =
        IncomingCapsuleRepository(authenticatedClient, baseUrl)

    companion object {
        fun create(
            baseUrl: ApiBaseUrl,
            tokens: AuthTokenHolder,
            refreshTokenReader: RefreshTokenReader,
            rotationSink: SessionRotationSink,
        ): ProductionApiStack = ProductionApiStack(
            baseUrl = baseUrl,
            tokens = tokens,
            refreshTokenReader = refreshTokenReader,
            rotationSink = rotationSink,
        )
    }
}
