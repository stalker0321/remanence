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
import app.postmark.memory.session.AccountSummaryStore
import app.postmark.memory.session.IdentityAvailabilityPort
import app.postmark.memory.session.SessionBootstrap
import app.postmark.memory.session.PersistedAccountSummary
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

    private val appContext: Context = context.applicationContext

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

    val authRepository: AuthRepository by lazy {
        AuthRepository.create(apiBaseUrl)
    }

    /** Identity KEK alias used for wrapping the HPKE/Ed25519 private keysets. */
    val identityKekAlias: String = IDENTITY_KEK_ALIAS

    val registrationIdentityAdapter: TinkRegistrationIdentityAdapter by lazy {
        TinkRegistrationIdentityAdapter(identityRepository, kekBoundary, identityKekAlias)
    }

    /** Persists the access token plus account summary after auth success. */
    private fun recordAuthenticatedSession(userId: String, handle: String, accessToken: String) {
        sessionTokenStore.save(accessToken)
        AccountSummaryStore(File(appContext.filesDir, ACCOUNT_SUMMARY_FILE))
            .save(PersistedAccountSummary(userId, handle))
    }

    /** Saves the access token from a successful auth call, then delegates. */
    private fun <T> captureToken(result: AuthResult<T>, tokenOf: (T) -> String): AuthResult<T> {
        if (result is AuthResult.Success) {
            val user = tokenUser(result.value)
            if (user != null) {
                recordAuthenticatedSession(user.userId, user.handle, tokenOf(result.value))
            }
        }
        return result
    }

    private fun tokenUser(value: Any?): postmark.core.data.network.RegistrationUserDto? = when (value) {
        is postmark.core.data.network.LoginResponseDto -> value.user
        is postmark.core.data.network.RegisterResponseDto -> value.user
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
                captureToken(authRepository.login(request)) { it.accessToken }
            },
            accounts = object : app.postmark.memory.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: postmark.core.data.network.RegistrationUserDto,
                    activeKeyBundleId: String,
                ) = Unit // summary already captured with the token above
            },
        )
    }

    val registrationUseCase: app.postmark.memory.auth.RegistrationUseCase by lazy {
        app.postmark.memory.auth.RegistrationUseCase(
            identity = registrationIdentityAdapter,
            authApi = object : app.postmark.memory.auth.RegistrationAuthApiPort {
                override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> =
                    captureToken(authRepository.register(request)) { it.accessToken }
            },
            accounts = object : app.postmark.memory.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: postmark.core.data.network.RegistrationUserDto,
                    activeKeyBundleId: String,
                ) = Unit
            },
        )
    }

    /**
     * Cold-start session resolution over the persisted token, wrapped keysets,
     * and the locally recorded account summary.
     */
    val sessionBootstrap: SessionBootstrap by lazy {
        val summaryStore = AccountSummaryStore(File(appContext.filesDir, ACCOUNT_SUMMARY_FILE))
        val identityAvailability = object : IdentityAvailabilityPort {
            private fun bothAvailable(): Boolean =
                identityRepository.exists() &&
                    identityRepository.load() !is IdentityBundleRepository.LoadResult.RecoveryRequired
            override fun encryptionKeysetAvailable(): Boolean = bothAvailable()
            override fun signingKeysetAvailable(): Boolean = bothAvailable()
        }
        SessionBootstrap(
            tokens = object : SessionTokenPort {
                override fun readToken(): String? = sessionTokenStore.load()
                override fun clearToken() = sessionTokenStore.clear()
            },
            identity = identityAvailability,
            account = { summaryStore.load() },
        )
    }

    /** Deterministic client-generated bundle ID bound to this exact identity. */
    private fun deriveKeyBundleId(encryptionPublicKeyset: ByteArray): String =
        java.util.UUID.nameUUIDFromBytes(encryptionPublicKeyset).toString()

    companion object {
        const val DATABASE_NAME: String = "postmark.db"
        const val SESSION_TOKEN_KEK_ALIAS: String = "postmark.session.v1"
        const val IDENTITY_KEK_ALIAS: String = "postmark.identity.v1"
        const val ACCOUNT_SUMMARY_FILE: String = "account-summary.txt"
    }
}
