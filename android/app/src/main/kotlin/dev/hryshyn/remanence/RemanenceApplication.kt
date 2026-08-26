package dev.hryshyn.remanence

import android.app.Application
import android.content.Context
import androidx.room.Room
import java.io.File
import dev.hryshyn.remanence.core.crypto.AndroidKeystoreKekBoundary
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.crypto.KekBoundary
import dev.hryshyn.remanence.core.crypto.KeysetKekWrapper
import dev.hryshyn.remanence.core.crypto.SessionTokenStore
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.data.network.RegisterRequestDto
import dev.hryshyn.remanence.core.data.network.RegisterResponseDto
import dev.hryshyn.remanence.core.data.network.AuthRepository
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.HealthRepository
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import dev.hryshyn.remanence.session.IdentityAvailabilityPort
import dev.hryshyn.remanence.session.SessionBootstrap
import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.wiring.TinkRegistrationIdentityAdapter

/**
 * I01 explicit application container: every long-lived dependency is built
 * once, here, in explicit order - Tink primitives, Keystore boundary, Room
 * database, sealed fingerprint persistence, identity bundle repository, and
 * session-token store. Activities read [RemanenceApplication.container];
 * nothing assembles ad-hoc singletons elsewhere.
 */
class RemanenceApplication : Application() {

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

    val database: RemanenceLocalDatabase by lazy {
        Room.databaseBuilder(appContext, RemanenceLocalDatabase::class.java, DATABASE_NAME)
            // Versioned local schema evolution; no silent destructive resets.
            .addMigrations(
                RemanenceLocalDatabase.MIGRATION_1_2,
                RemanenceLocalDatabase.MIGRATION_2_3,
                RemanenceLocalDatabase.MIGRATION_3_4,
            )
            .build()
    }

    /** Non-exportable Android Keystore KEKs; overridable for JVM tests. */
    val kekBoundary: KekBoundary = kekBoundaryOverride ?: AndroidKeystoreKekBoundary()

    /**
     * M2-P02: every sealed fingerprint row is attributed to the authenticated
     * local account at write time. All persistence flows run post-auth, so
     * the row always exists when this resolves.
     */
    val fingerprintPersistence: SealedFingerprintPersistence by lazy {
        EncryptedFingerprintStore(
            filesRoot = File(appContext.filesDir, "fingerprints"),
            sealer = KekBoundSecretSealer(kekBoundary, KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS),
            dao = database.recognitionFingerprintDao(),
            ownerUserIdProvider = { currentAccountStore.loadEntity()?.userId.orEmpty() },
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
    private val sessionRotationSink: dev.hryshyn.remanence.core.data.network.SessionRotationSink by lazy {
        object : dev.hryshyn.remanence.core.data.network.SessionRotationSink {
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
    val apiStack: dev.hryshyn.remanence.core.data.network.ProductionApiStack by lazy {
        dev.hryshyn.remanence.core.data.network.ProductionApiStack.create(
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
    val authTokenHolder: dev.hryshyn.remanence.core.data.network.AuthTokenHolder by lazy {
        dev.hryshyn.remanence.core.data.network.AuthTokenHolder()
    }

    /** Identity KEK alias used for wrapping the HPKE/Ed25519 private keysets. */
    val identityKekAlias: String = IDENTITY_KEK_ALIAS

    val registrationIdentityAdapter: TinkRegistrationIdentityAdapter by lazy {
        TinkRegistrationIdentityAdapter(identityRepository, kekBoundary, identityKekAlias)
    }

    /** Handle resolution for the create flow (docs/security.md section 8). */
    val directoryRepository: dev.hryshyn.remanence.core.data.network.DirectoryRepository by lazy {
        dev.hryshyn.remanence.core.data.network.DirectoryRepository.create(apiBaseUrl)
    }

    /** FIX-REVIEW2-04: immutable public key-bundle lookup for verification. */
    val keyBundleByIdRepository: dev.hryshyn.remanence.core.data.network.KeyBundleByIdRepository by lazy {
        dev.hryshyn.remanence.core.data.network.KeyBundleByIdRepository.create(apiBaseUrl)
    }

    /**
     * FIX-REVIEW2-04: THE trusted sender-key boundary for capsule
     * verification. Other senders resolve only through the authenticated
     * directory; the own export is returned solely for an exact match of the
     * authenticated account and its active bundle. No local cache, so a later
     * revocation can never be outrun by a stale copy.
     */
    val trustedSenderKeys: dev.hryshyn.remanence.identity.TrustedSenderKeyStore by lazy {
        dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
            directoryFetch = { bundleId ->
                val token = authTokenHolder.accessToken ?: return@DirectorySenderKeyStore null
                keyBundleByIdRepository.fetch(bundleId, token)
            },
            ownAccount = {
                val row = currentAccountStore.loadEntity()
                    ?: return@DirectorySenderKeyStore null
                when (val exports = identityRepository.loadPublicExports()) {
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.PublicExportsResult.Available ->
                        dev.hryshyn.remanence.identity.DirectorySenderKeyStore.OwnAccount(
                            userId = dev.hryshyn.remanence.core.model.UserId(java.util.UUID.fromString(row.userId)),
                            activeKeyBundleId = dev.hryshyn.remanence.core.model.KeyBundleId(
                                java.util.UUID.fromString(row.activeKeyBundleId),
                            ),
                            publicSigningExportB64Url = com.google.crypto.tink.subtle.Base64.urlSafeEncode(
                                exports.signingPublicKeyset,
                            ),
                        )
                    else -> null
                }
            },
        )
    }

    /** App-private root for bounded staging directories. */
    val appFilesRoot: File get() = appContext.filesDir

    /**
     * FIX-REVIEW-03: THE single memory-only scan-grant authority
     * (docs/architecture.md section 5). Issued by the scan flow after real
     * crypto verification; resolved/consumed/cleared by root navigation,
     * close, logout, and process death. Nothing else may create access.
     */
    val scanGrants: dev.hryshyn.remanence.core.recognition.ScanGrantManager by lazy {
        dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = System::currentTimeMillis)
    }

    /** FIX-M1-007-07: the current account lives in the real local_account table. */
    val currentAccountStore: dev.hryshyn.remanence.session.RoomCurrentAccountStore by lazy {
        dev.hryshyn.remanence.session.RoomCurrentAccountStore(database.localAccountDao())
    }

    /**
     * FIX-M1-007-07 logout ordering: server revocation first (best effort),
     * then session credentials, the local_account row, and scan grants.
     */
    val logoutUseCase: dev.hryshyn.remanence.auth.LogoutUseCase by lazy {
        dev.hryshyn.remanence.auth.LogoutUseCase(
            serverLogout = { accessToken -> bareAuthRepository.logout(accessToken) },
            accessToken = { authTokenHolder.accessToken },
            tokens = object : dev.hryshyn.remanence.session.SessionTokenPort {
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
        is dev.hryshyn.remanence.core.data.network.LoginResponseDto -> value.user
        is RegisterResponseDto -> value.user
        else -> null
    }

    val loginUseCase: dev.hryshyn.remanence.auth.LoginUseCase by lazy {
        dev.hryshyn.remanence.auth.LoginUseCase(
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
            accounts = object : dev.hryshyn.remanence.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: dev.hryshyn.remanence.core.data.network.RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    currentAccountStore.record(user.userId, user.handle, activeKeyBundleId)
                }
            },
        )
    }

    val registrationUseCase: dev.hryshyn.remanence.auth.RegistrationUseCase by lazy {
        dev.hryshyn.remanence.auth.RegistrationUseCase(
            identity = registrationIdentityAdapter,
            authApi = object : dev.hryshyn.remanence.auth.RegistrationAuthApiPort {
                override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> =
                    captureAuthSession(
                        bareAuthRepository.register(request),
                        accessTokenOf = { it.accessToken },
                        refreshTokenOf = { it.refreshToken },
                    )
            },
            accounts = object : dev.hryshyn.remanence.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: dev.hryshyn.remanence.core.data.network.RegistrationUserDto,
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
                    dev.hryshyn.remanence.core.data.network.RefreshRequestDto(storedRefreshToken),
                )) {
                    is AuthResult.Success -> {
                        authTokenHolder.updateTokens(
                            result.value.accessToken,
                            result.value.refreshToken,
                        )
                        dev.hryshyn.remanence.session.SessionRefreshOutcome.Rotated(
                            accessToken = result.value.accessToken,
                            refreshToken = result.value.refreshToken,
                        )
                    }
                    is AuthResult.Failure -> when {
                        result.reason == dev.hryshyn.remanence.core.data.network.AuthFailure.NETWORK ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Unreachable
                        // 401/403/409: the server definitively refused the
                        // lineage; anything else is treated as transient.
                        result.httpStatus == 401 || result.httpStatus == 403 || result.httpStatus == 409 ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Rejected
                        else -> dev.hryshyn.remanence.session.SessionRefreshOutcome.Unreachable
                    }
                }
            },
        )
    }

    /** Deterministic client-generated bundle ID bound to this exact identity. */
    private fun deriveKeyBundleId(encryptionPublicKeyset: ByteArray): String =
        java.util.UUID.nameUUIDFromBytes(encryptionPublicKeyset).toString()

    companion object {
        const val DATABASE_NAME: String = "remanence.db"
        const val SESSION_TOKEN_KEK_ALIAS: String = "remanence.session.v1"
        const val IDENTITY_KEK_ALIAS: String = "remanence.identity.v1"
    }
}
