package app.postmark.memory

import android.app.Application
import android.content.Context
import androidx.room.Room
import java.io.File
import postmark.core.crypto.AndroidKeystoreKekBoundary
import postmark.core.crypto.IdentityBundleRepository
import postmark.core.crypto.KekBoundary
import postmark.core.crypto.KeysetKekWrapper
import postmark.core.crypto.SessionTokenStore
import postmark.core.crypto.TinkPrimitives
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.EncryptedFingerprintStore
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.data.network.ApiBaseUrl
import postmark.core.data.network.RegisterRequestDto
import postmark.core.data.network.RegisterResponseDto
import postmark.core.data.network.AuthRepository
import postmark.core.data.network.AuthResult
import postmark.core.data.network.HealthRepository
import postmark.core.data.network.RegistrationUserDto
import app.postmark.memory.wiring.KekBoundSecretSealer
import app.postmark.memory.session.IdentityAvailabilityPort
import app.postmark.memory.session.SessionBootstrap
import app.postmark.memory.session.SessionTokenPort
import app.postmark.memory.wiring.TinkRegistrationIdentityAdapter

/**
 * I01 explicit application container: every long-lived dependency is built
 * once, here, in explicit order - Tink primitives, Keystore boundary, Room
 * database, sealed fingerprint persistence, identity bundle repository, and
 * session-token store. Activities read [PostmarkApplication.container];
 * nothing assembles ad-hoc singletons elsewhere.
 */
class PostmarkApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // OpenCV native runtime must be live before any capture/extraction
        // component runs (docs/recognition.md section 4).
        org.opencv.android.OpenCVLoader.initLocal()
        container = AppContainer(this)
    }
}

/**
 * The one place the object graph is assembled. Members are lazy individually;
 * Keystore-touching members stay lazy so plain unit contexts never need
 * hardware-backed keys.
 */
class AppContainer(
    context: Context,
    kekBoundaryOverride: KekBoundary? = null,
) {

    init {
        // Tink primitive registration is process-global and must precede any
        // keyset work (docs/security.md section 4).
        TinkPrimitives.ensureRegistered()
    }

    val apiBaseUrl: ApiBaseUrl = ApiBaseUrl.parse(BuildConfig.API_BASE_URL)

    val healthRepository: HealthRepository by lazy { HealthRepository.create(apiBaseUrl) }

    val appContext: Context = context.applicationContext

    val database: PostmarkLocalDatabase by lazy {
        Room.databaseBuilder(appContext, PostmarkLocalDatabase::class.java, DATABASE_NAME).build()
    }

    /** Non-exportable Android Keystore KEKs; overridable for JVM tests. */
    val kekBoundary: KekBoundary = kekBoundaryOverride ?: AndroidKeystoreKekBoundary()

    val fingerprintPersistence: SealedFingerprintPersistence by lazy {
        EncryptedFingerprintStore(
            filesRoot = File(appContext.filesDir, "fingerprints"),
            sealer = KekBoundSecretSealer(kekBoundary, KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS),
            dao = database.recognitionFingerprintDao(),
        )
    }

    val identityRepository: IdentityBundleRepository by lazy {
        IdentityBundleRepository(
            baseDirectory = File(appContext.filesDir, "identity"),
            wrapper = KeysetKekWrapper(kekBoundary),
        )
    }

    val sessionTokenStore: SessionTokenStore by lazy {
        if (!kekBoundary.hasKey(SESSION_TOKEN_KEK_ALIAS)) {
            kekBoundary.createAes256GcmKey(SESSION_TOKEN_KEK_ALIAS)
        }
        SessionTokenStore(
            directory = File(appContext.filesDir, "session"),
            kekBoundary = kekBoundary,
            kekAlias = SESSION_TOKEN_KEK_ALIAS,
        )
    }

    /**
     * Bare auth repository (FIX-M1-007-06): no bearer interceptor, no
     * authenticator. Only this shape may carry the auth endpoint calls so a
     * rejected refresh can never recurse through the authenticator.
     */
    val bareAuthRepository: AuthRepository
        get() = apiStack.bareAuthRepository

    /**
     * Atomic rotation sink: publishes both credentials to the memory holder
     * and re-seals the rotating refresh token in one step. Runs inside the
     * authenticator's serialization mutex; a persistence failure fails the
     * whole refresh closed.
     */
    private val sessionRotationSink: postmark.core.data.network.SessionRotationSink by lazy {
        object : postmark.core.data.network.SessionRotationSink {
            override fun rotate(accessToken: String, refreshToken: String) {
                sessionTokenStore.save(refreshToken)
                authTokenHolder.updateTokens(accessToken, refreshToken)
            }

            override fun clear() {
                authTokenHolder.clearSession()
                sessionTokenStore.clear()
            }
        }
    }

    /**
     * Production HTTP stack (FIX-M1-007-06): bare auth repository for the
     * auth endpoints plus an authenticated client with the bearer interceptor
     * and serialized one-retry authenticator.
     */
    val apiStack: postmark.core.data.network.ProductionApiStack by lazy {
        postmark.core.data.network.ProductionApiStack.create(
            baseUrl = apiBaseUrl,
            tokens = authTokenHolder,
            rotationSink = sessionRotationSink,
        )
    }

    /**
     * Memory-only holder for the live session credentials (FIX-05): the
     * short-lived access token NEVER touches disk; only the rotating refresh
     * token is persisted, sealed under the Keystore KEK.
     */
    val authTokenHolder: postmark.core.data.network.AuthTokenHolder by lazy {
        postmark.core.data.network.AuthTokenHolder()
    }

    /** Identity KEK alias used for wrapping the HPKE/Ed25519 private keysets. */
    val identityKekAlias: String = IDENTITY_KEK_ALIAS

    val registrationIdentityAdapter: TinkRegistrationIdentityAdapter by lazy {
        TinkRegistrationIdentityAdapter(identityRepository, kekBoundary, identityKekAlias)
    }

    /** Handle resolution for the create flow (docs/security.md section 8). */
    val directoryRepository: postmark.core.data.network.DirectoryRepository by lazy {
        postmark.core.data.network.DirectoryRepository.create(apiBaseUrl)
    }

    /** App-private root for bounded staging directories. */
    val appFilesRoot: File get() = appContext.filesDir

    /** FIX-M1-007-07: the current account lives in the real local_account table. */
    val currentAccountStore: app.postmark.memory.session.RoomCurrentAccountStore by lazy {
        app.postmark.memory.session.RoomCurrentAccountStore(database.localAccountDao())
    }

    /**
     * FIX-M1-007-07 logout ordering: server revocation first (best effort),
     * then session credentials, the local_account row, and scan grants.
     */
    val logoutUseCase: app.postmark.memory.auth.LogoutUseCase by lazy {
        app.postmark.memory.auth.LogoutUseCase(
            serverLogout = { accessToken -> bareAuthRepository.logout(accessToken) },
            accessToken = { authTokenHolder.accessToken },
            tokens = object : app.postmark.memory.session.SessionTokenPort {
                override fun readToken(): String? = sessionTokenStore.load()
                override fun saveToken(refreshToken: String) = sessionTokenStore.save(refreshToken)
                override fun clearToken() = sessionTokenStore.clear()
            },
            credentialSink = sessionRotationSink,
            accounts = { currentAccountStore.clear() },
            grants = {
                // App-level grant state dies with the account context.
                appLevelGrantCleanup.forEach { cleanup -> cleanup() }
            },
        )
    }

    /** Hooks registered by the running UI to drop scan grants on logout. */
    private val appLevelGrantCleanup = mutableListOf<() -> Unit>()

    fun registerGrantCleanup(cleanup: () -> Unit) {
        appLevelGrantCleanup += cleanup
    }

    /**
     * Persists ONLY the rotating refresh token (sealed); the access token
     * lives exclusively in [authTokenHolder]. The `local_account` row is
     * written by each auth use case through its suspended port.
     */
    private fun recordAuthenticatedSession(
        userId: String,
        handle: String,
        accessToken: String,
        refreshToken: String,
    ) {
        sessionTokenStore.save(refreshToken)
        authTokenHolder.updateTokens(accessToken, refreshToken)
    }

    /** Seals the refresh token from a successful auth call, then delegates. */
    private fun <T> captureAuthSession(
        result: AuthResult<T>,
        accessTokenOf: (T) -> String,
        refreshTokenOf: (T) -> String,
    ): AuthResult<T> {
        if (result is AuthResult.Success) {
            val user = sessionUser(result.value)
            if (user != null) {
                recordAuthenticatedSession(
                    user.userId,
                    user.handle,
                    accessTokenOf(result.value),
                    refreshTokenOf(result.value),
                )
            }
        }
        return result
    }

    private fun sessionUser(value: Any?): RegistrationUserDto? = when (value) {
        is postmark.core.data.network.LoginResponseDto -> value.user
        is RegisterResponseDto -> value.user
        else -> null
    }

    val loginUseCase: app.postmark.memory.auth.LoginUseCase by lazy {
        app.postmark.memory.auth.LoginUseCase(
            identity = { activeKeyBundleId ->
                val exports = identityRepository.loadPublicExports()
                exports is IdentityBundleRepository.PublicExportsResult.Available &&
                    deriveKeyBundleId(exports.encryptionPublicKeyset) == activeKeyBundleId
            },
            authApi = { request ->
                captureAuthSession(
                    bareAuthRepository.login(request),
                    accessTokenOf = { it.accessToken },
                    refreshTokenOf = { it.refreshToken },
                )
            },
            accounts = object : app.postmark.memory.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: postmark.core.data.network.RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    currentAccountStore.record(user.userId, user.handle, activeKeyBundleId)
                }
            },
        )
    }

    val registrationUseCase: app.postmark.memory.auth.RegistrationUseCase by lazy {
        app.postmark.memory.auth.RegistrationUseCase(
            identity = registrationIdentityAdapter,
            authApi = object : app.postmark.memory.auth.RegistrationAuthApiPort {
                override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> =
                    captureAuthSession(
                        bareAuthRepository.register(request),
                        accessTokenOf = { it.accessToken },
                        refreshTokenOf = { it.refreshToken },
                    )
            },
            accounts = object : app.postmark.memory.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: postmark.core.data.network.RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    currentAccountStore.record(user.userId, user.handle, activeKeyBundleId)
                }
            },
        )
    }

    /**
     * Cold-start session resolution over the sealed refresh token, wrapped
     * keysets, and the real `local_account` row. The stored token is
     * proved against `/v1/auth/refresh` BEFORE any Active state exists.
     */
    val identityAvailability: IdentityAvailabilityPort by lazy {
        object : IdentityAvailabilityPort {
            private fun bothAvailable(): Boolean =
                identityRepository.exists() &&
                    identityRepository.load() !is IdentityBundleRepository.LoadResult.RecoveryRequired
            override fun encryptionKeysetAvailable(): Boolean = bothAvailable()
            override fun signingKeysetAvailable(): Boolean = bothAvailable()
        }
    }

    val sessionBootstrap: SessionBootstrap by lazy {
        val identityAvailability = this.identityAvailability
        SessionBootstrap(
            tokens = object : SessionTokenPort {
                override fun readToken(): String? = sessionTokenStore.load()
                override fun saveToken(refreshToken: String) = sessionTokenStore.save(refreshToken)
                override fun clearToken() = sessionTokenStore.clear()
            },
            identity = identityAvailability,
            account = { currentAccountStore.load() },
            refresher = { storedRefreshToken ->
                when (val result = bareAuthRepository.refresh(
                    postmark.core.data.network.RefreshRequestDto(storedRefreshToken),
                )) {
                    is AuthResult.Success -> {
                        authTokenHolder.updateTokens(
                            result.value.accessToken,
                            result.value.refreshToken,
                        )
                        app.postmark.memory.session.SessionRefreshOutcome.Rotated(
                            accessToken = result.value.accessToken,
                            refreshToken = result.value.refreshToken,
                        )
                    }
                    is AuthResult.Failure -> when {
                        result.reason == postmark.core.data.network.AuthFailure.NETWORK ->
                            app.postmark.memory.session.SessionRefreshOutcome.Unreachable
                        // 401/403/409: the server definitively refused the
                        // lineage; anything else is treated as transient.
                        result.httpStatus == 401 || result.httpStatus == 403 || result.httpStatus == 409 ->
                            app.postmark.memory.session.SessionRefreshOutcome.Rejected
                        else -> app.postmark.memory.session.SessionRefreshOutcome.Unreachable
                    }
                }
            },
        )
    }

    /** Deterministic client-generated bundle ID bound to this exact identity. */
    private fun deriveKeyBundleId(encryptionPublicKeyset: ByteArray): String =
        java.util.UUID.nameUUIDFromBytes(encryptionPublicKeyset).toString()

    companion object {
        const val DATABASE_NAME: String = "postmark.db"
        const val SESSION_TOKEN_KEK_ALIAS: String = "postmark.session.v1"
        const val IDENTITY_KEK_ALIAS: String = "postmark.identity.v1"
    }
}
