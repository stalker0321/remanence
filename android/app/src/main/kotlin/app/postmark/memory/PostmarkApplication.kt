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
import postmark.core.data.network.HealthRepository
import app.postmark.memory.wiring.KekBoundSecretSealer

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

    companion object {
        const val DATABASE_NAME: String = "postmark.db"
        const val SESSION_TOKEN_KEK_ALIAS: String = "postmark.session.v1"
    }
}
