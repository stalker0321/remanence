package dev.hryshyn.remanence

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.await
import java.io.File
import dev.hryshyn.remanence.core.crypto.AndroidKeystoreKekBoundary
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.crypto.KekBoundary
import dev.hryshyn.remanence.core.crypto.KeysetKekWrapper
import dev.hryshyn.remanence.core.crypto.SessionRefreshRecord
import dev.hryshyn.remanence.core.crypto.SessionTokenStore
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.db.BlobCacheDao
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingAcceptanceCandidateSelector
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeDao
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadRepository
import dev.hryshyn.remanence.core.data.network.CapsuleDraftRepository
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeRepository
import dev.hryshyn.remanence.core.data.network.IncomingMaterialAckDrain
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRepository
import dev.hryshyn.remanence.core.data.network.RegisterRequestDto
import dev.hryshyn.remanence.core.data.network.RegisterResponseDto
import dev.hryshyn.remanence.core.data.network.AuthRepository
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.HealthRepository
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.data.prefetch.IncomingCiphertextPrefetchCoordinator
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdopter
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdopter
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceCommitter
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.index.SenderIndexBundleStager
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import dev.hryshyn.remanence.session.IdentityAvailabilityPort
import dev.hryshyn.remanence.session.SessionBootstrap
import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.sync.CapsuleUploadOrchestrator
import dev.hryshyn.remanence.sync.CapsuleUploadResumer
import dev.hryshyn.remanence.sync.CapsuleUploadWorker
import dev.hryshyn.remanence.sync.CurrentRecipientEncryptionIdentity
import dev.hryshyn.remanence.sync.IncomingCapsuleAcceptanceCoordinator
import dev.hryshyn.remanence.sync.IncomingAcceptanceDrain
import dev.hryshyn.remanence.sync.IncomingControlIndexAcceptanceCoordinator
import dev.hryshyn.remanence.sync.SenderIndexBundlePersistenceAdapter
import dev.hryshyn.remanence.ui.scan.IncomingSenderIndexCandidateProvider
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparation
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
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
 * The immutable A11d1 production composition. It contains only concrete,
 * already-owned dependencies; credentials, private handles, plaintext, and
 * caller-selected paths are intentionally absent.
 */
internal class IncomingCapsuleAcceptanceComposition(
    internal val incomingCapsuleDao: IncomingCapsuleDao,
    internal val incomingEnvelopeDao: IncomingEnvelopeDao,
    internal val blobCacheDao: BlobCacheDao,
    internal val roots: AccountScopedFileRoots,
    internal val recipientBlobDownloadRepository: RecipientBlobDownloadRepository,
    internal val controlIndexAcceptanceCoordinator: IncomingControlIndexAcceptanceCoordinator,
    internal val senderIndexBundleReader: SenderIndexBundleReader,
    internal val verifiedControlIndexPersistence: SenderIndexBundlePersistenceAdapter,
    internal val incomingRecognitionCiphertextAdopter: IncomingRecognitionCiphertextAdopter,
    internal val incomingIndexAcceptanceCommitter: IncomingIndexAcceptanceCommitter,
) {

    internal fun create(
        currentSession: suspend () -> IncomingSyncSession?,
    ): IncomingCapsuleAcceptanceCoordinator = IncomingCapsuleAcceptanceCoordinator(
        incomingCapsuleDao = incomingCapsuleDao,
        incomingEnvelopeDao = incomingEnvelopeDao,
        blobCacheDao = blobCacheDao,
        roots = roots,
        currentSession = currentSession,
        recipientBlobDownloadRepository = recipientBlobDownloadRepository,
        controlIndexAcceptanceCoordinator = controlIndexAcceptanceCoordinator,
        senderIndexBundleReader = senderIndexBundleReader,
        verifiedControlIndexPersistence = verifiedControlIndexPersistence,
        incomingRecognitionCiphertextAdopter = incomingRecognitionCiphertextAdopter,
        incomingIndexAcceptanceCommitter = incomingIndexAcceptanceCommitter,
    )

    override fun toString(): String =
        "IncomingCapsuleAcceptanceComposition(<redacted>)"
}

/**
 * The one place the object graph is assembled. Members are lazy individually;
 * Keystore-touching members stay lazy so plain unit contexts never need
 * hardware-backed keys.
 */
class AppContainer private constructor(
    context: Context,
    kekBoundaryOverride: KekBoundary?,
    private val identityLoadOverride: (() -> IdentityBundleRepository.LoadResult)?,
    @Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {

    constructor(
        context: Context,
        kekBoundaryOverride: KekBoundary? = null,
    ) : this(context, kekBoundaryOverride, null, Unit)

    internal constructor(
        context: Context,
        kekBoundaryOverride: KekBoundary?,
        identityLoadOverride: () -> IdentityBundleRepository.LoadResult,
    ) : this(context, kekBoundaryOverride, identityLoadOverride, Unit)

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
                RemanenceLocalDatabase.MIGRATION_4_5,
                RemanenceLocalDatabase.MIGRATION_5_6,
                RemanenceLocalDatabase.MIGRATION_6_7,
            )
            .build()
    }

    /** Non-exportable Android Keystore KEKs; overridable for JVM tests. */
    val kekBoundary: KekBoundary = kekBoundaryOverride ?: AndroidKeystoreKekBoundary()

    /** One lazy fingerprint/index sealer shared by all local index boundaries. */
    internal val fingerprintSealer: KekBoundSecretSealer by lazy {
        KekBoundSecretSealer(kekBoundary, KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS)
    }

    /**
     * M2-P02/P04: every sealed fingerprint row is attributed to the
     * authenticated local account at write time, and its ciphertext file
     * lives beneath THAT account's `accounts/<owner>/fingerprints/` root -
     * there is no shared fingerprints directory. All persistence flows run
     * post-auth, so the row always exists when this resolves.
     */
    val fingerprintPersistence: SealedFingerprintPersistence by lazy {
        EncryptedFingerprintStore(
            roots = accountScopedFileRoots,
            sealer = fingerprintSealer,
            dao = database.recognitionFingerprintDao(),
            ownerUserIdProvider = {
                val row = currentAccountStore.loadEntity()
                    ?: error("fingerprint persistence requires an authenticated local account")
                row.userId
            },
        )
    }

    /** Pure resolver for the fixed per-account storage roots. */
    val accountScopedFileRoots: dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots by lazy {
        dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(appContext.filesDir)
    }

    /**
     * M2-P04 retention boundary over the account-scoped roots: normal logout
     * purges only the owner's temp directory; durable encrypted material
     * stays for that same account's next login.
     */
    val accountStorageRetention: dev.hryshyn.remanence.core.data.storage.AccountStorageRetention by lazy {
        dev.hryshyn.remanence.core.data.storage.AccountStorageRetention(accountScopedFileRoots)
    }

    /**
     * M2-P05 cancellation boundary over WorkManager: cancelForAccount acts
     * ONLY on one canonical `remanence.account.<owner>` tag.
     */
    val accountWorkCancellation: dev.hryshyn.remanence.sync.AccountWorkCancellation by lazy {
        dev.hryshyn.remanence.sync.AccountWorkCancellation(
            androidx.work.WorkManager.getInstance(appContext),
        )
    }

    val identityRepository: IdentityBundleRepository by lazy {
        IdentityBundleRepository(
            baseDirectory = File(appContext.filesDir, "identity"),
            wrapper = KeysetKekWrapper(kekBoundary),
        )
    }

    private val incomingAcceptanceIdentityLoader:
        () -> IdentityBundleRepository.LoadResult =
        identityLoadOverride ?: { identityRepository.load() }

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
            override fun rotate(
                accessToken: String,
                refreshToken: String,
                ownerUserId: UserId,
            ) {
                sessionTokenStore.save(SessionRefreshRecord(ownerUserId, refreshToken))
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
            refreshTokenReader = dev.hryshyn.remanence.core.data.network.RefreshTokenReader {
                sessionTokenStore.load()?.let { record ->
                    dev.hryshyn.remanence.core.data.network.BoundRefreshCredential(
                        record.ownerUserId,
                        record.refreshToken,
                    )
                }
            },
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

    /** Ordinary requests: bearer only while the refresh domain is open. */
    private fun ordinaryAccessToken(): String? =
        apiStack.sessionRefreshCoordinator.openDomainAccessToken()?.takeIf { it.isNotBlank() }

    /** Identity KEK alias used for wrapping the HPKE/Ed25519 private keysets. */
    val identityKekAlias: String = IDENTITY_KEK_ALIAS

    /**
     * M2-P08: dedicated KEK alias for wrapping the sender-owned retry
     * capsule keyset. The KEK is lazily ensured on first use so a cold
     * start never blocks on key generation; the alias is fixed and
     * immutable.
     */
    val senderRetryKekAlias: String = SENDER_RETRY_KEK_ALIAS

    /**
     * M2-P08: the sender-retry keyset wrapper bound to the
     * [senderRetryKekAlias] KEK. The KEK is lazily created if it does
     * not yet exist; once created it is never replaced.
     */
    val senderRetryKeysetWrapper: dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper by lazy {
        if (!kekBoundary.hasKey(senderRetryKekAlias)) {
            kekBoundary.createAes256GcmKey(senderRetryKekAlias)
        }
        dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper(kekBoundary)
    }

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

    /** A04's three existing authenticated capsule boundaries. */
    val capsuleDraftRepository: CapsuleDraftRepository by lazy {
        apiStack.capsuleDraftRepository
    }

    val capsuleBlobUploadRepository: CapsuleBlobUploadRepository by lazy {
        apiStack.capsuleBlobUploadRepository
    }

    val capsuleFinalizeRepository: CapsuleFinalizeRepository by lazy {
        apiStack.capsuleFinalizeRepository
    }

    /** Immutable recipient lookup through the authenticated refreshing stack. */
    val recipientUserLookupRepository: dev.hryshyn.remanence.core.data.network.RecipientUserLookupRepository by lazy {
        apiStack.recipientUserLookupRepository
    }

    /** A11a opaque recipient ciphertext GET; adoption and acceptance remain A11b. */
    val recipientBlobDownloadRepository: dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRepository by lazy {
        apiStack.recipientBlobDownloadRepository
    }

    /** A09 account-scoped incoming page fetch and atomic Room commit seam. */
    val incomingCapsuleSyncRepository: dev.hryshyn.remanence.core.data.db.IncomingCapsuleSyncRepository by lazy {
        dev.hryshyn.remanence.core.data.db.IncomingCapsuleSyncRepository(
            remote = apiStack.incomingCapsuleRepository,
            database = database,
            roots = accountScopedFileRoots,
            currentSession = {
                val account = currentAccountStore.load() ?: return@IncomingCapsuleSyncRepository null
                val token = ordinaryAccessToken() ?: return@IncomingCapsuleSyncRepository null
                val owner = runCatching {
                    dev.hryshyn.remanence.core.model.UserId.parseRest(account.userId)
                }.getOrNull() ?: return@IncomingCapsuleSyncRepository null
                dev.hryshyn.remanence.core.data.db.IncomingSyncSession(owner, token)
            },
        )
    }

    /**
     * FIX-REVIEW2-04: THE trusted sender-key boundary for capsule
     * verification. Other senders resolve only through the authenticated
     * directory; the own export is returned solely for an exact match of the
     * authenticated account and its active bundle. The accepted sender-index
     * bundle separately retains the verified public key for offline
     * presentation; its revocation boundary is documented in docs/security.md.
     */
    val trustedSenderKeys: dev.hryshyn.remanence.identity.TrustedSenderKeyStore by lazy {
        dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
            directoryFetch = { bundleId ->
                val token = ordinaryAccessToken() ?: return@DirectorySenderKeyStore null
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

    /** A12a sender-index primitives; all share the one fingerprint sealer. */
    internal val senderIndexBundleStager: SenderIndexBundleStager by lazy {
        SenderIndexBundleStager(accountScopedFileRoots, fingerprintSealer)
    }

    internal val senderIndexBundleReader: SenderIndexBundleReader by lazy {
        SenderIndexBundleReader(accountScopedFileRoots, fingerprintSealer)
    }

    /** Real lazy offline presentation preparation; no network/session fallback. */
    internal val incomingPresentationPreparation: IncomingPresentationPreparation by lazy {
        IncomingPresentationPreparation(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            incomingEnvelopeDao = database.incomingEnvelopeDao(),
            blobCacheDao = database.blobCacheDao(),
            roots = accountScopedFileRoots,
            senderIndexBundleReader = senderIndexBundleReader,
            currentRecipientIdentity = { currentLocalRecipientEncryptionIdentity() },
        )
    }

    /** Real account-scoped bridge from accepted incoming bundles into Scan. */
    internal val incomingSenderIndexCandidateProvider: IncomingSenderIndexCandidateProvider by lazy {
        IncomingSenderIndexCandidateProvider(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            senderIndexBundleReader = senderIndexBundleReader,
            currentOwner = {
                currentAccountStore.load()?.userId?.let { raw ->
                    runCatching { UserId.parseRest(raw) }.getOrNull()
                }
            },
        )
    }

    internal val senderIndexBundlePersistenceAdapter: SenderIndexBundlePersistenceAdapter by lazy {
        SenderIndexBundlePersistenceAdapter(senderIndexBundleStager)
    }

    /** A11b acceptance uses the live recipient identity and trusted sender boundary. */
    internal val incomingControlIndexAcceptanceCoordinator: IncomingControlIndexAcceptanceCoordinator by lazy {
        IncomingControlIndexAcceptanceCoordinator(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            incomingEnvelopeDao = database.incomingEnvelopeDao(),
            blobCacheDao = database.blobCacheDao(),
            currentRecipientIdentity = { currentRecipientEncryptionIdentity() },
            trustedSenderKeys = trustedSenderKeys,
        )
    }

    internal val incomingRecognitionCiphertextAdopter: IncomingRecognitionCiphertextAdopter by lazy {
        IncomingRecognitionCiphertextAdopter(accountScopedFileRoots)
    }

    internal val incomingIndexAcceptanceCommitter: IncomingIndexAcceptanceCommitter by lazy {
        IncomingIndexAcceptanceCommitter(database, accountScopedFileRoots)
    }

    /** A11d1 concrete composition; no fallback or alternate constructor path. */
    internal val incomingCapsuleAcceptanceComposition: IncomingCapsuleAcceptanceComposition by lazy {
        IncomingCapsuleAcceptanceComposition(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            incomingEnvelopeDao = database.incomingEnvelopeDao(),
            blobCacheDao = database.blobCacheDao(),
            roots = accountScopedFileRoots,
            recipientBlobDownloadRepository = recipientBlobDownloadRepository,
            controlIndexAcceptanceCoordinator = incomingControlIndexAcceptanceCoordinator,
            senderIndexBundleReader = senderIndexBundleReader,
            verifiedControlIndexPersistence = senderIndexBundlePersistenceAdapter,
            incomingRecognitionCiphertextAdopter = incomingRecognitionCiphertextAdopter,
            incomingIndexAcceptanceCommitter = incomingIndexAcceptanceCommitter,
        )
    }

    /** A11d1 composition; construction is lazy and no worker/scheduler is invoked here. */
    val incomingCapsuleAcceptanceCoordinator: IncomingCapsuleAcceptanceCoordinator by lazy {
        incomingCapsuleAcceptanceComposition.create {
            currentIncomingAcceptanceSession()
        }
    }

    /** One real bounded acceptance drain owned by the incoming worker boundary. */
    val incomingAcceptanceDrain: IncomingAcceptanceDrain by lazy {
        val incomingCapsuleDao = database.incomingCapsuleDao()
        IncomingAcceptanceDrain(
            selector = IncomingAcceptanceCandidateSelector(incomingCapsuleDao),
            currentOwner = {
                currentAccountStore.load()?.userId?.let { raw ->
                    runCatching { UserId.parseRest(raw) }.getOrNull()
                }
            },
            acceptanceCoordinator = incomingCapsuleAcceptanceCoordinator,
            quarantineDao = incomingCapsuleDao,
        )
    }

    /** A12 prefetch adapter: real filesystem adoption under the account roots. */
    internal val incomingCiphertextAdopter: IncomingCiphertextAdopter by lazy {
        IncomingCiphertextAdopter(accountScopedFileRoots)
    }

    /** One lazy, account-rechecking bounded ciphertext prefetch coordinator. */
    val incomingCiphertextPrefetchCoordinator: IncomingCiphertextPrefetchCoordinator by lazy {
        IncomingCiphertextPrefetchCoordinator(
            prefetchDao = database.incomingPrefetchDao(),
            blobCacheDao = database.blobCacheDao(),
            roots = accountScopedFileRoots,
            currentSession = { currentIncomingAcceptanceSession() },
            repository = recipientBlobDownloadRepository,
            adopter = incomingCiphertextAdopter,
        )
    }

    /** One lazy material-ack drain bound to the authenticated production API. */
    val incomingMaterialAckDrain: IncomingMaterialAckDrain by lazy {
        apiStack.createIncomingMaterialAckDrain(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            currentSession = { currentIncomingAcceptanceSession() },
        )
    }

    /**
     * FIX-REVIEW-03: THE single memory-only scan-grant authority
     * (docs/architecture.md section 5). Issued by the scan flow after real
     * crypto verification; resolved/consumed/cleared by root navigation,
     * close, logout, and process death. Nothing else may create access.
     */
    /** Shared app-scoped source/owner/scan-generation grant binding authority. */
    internal val presentationGrants: PresentationGrantAuthority by lazy {
        PresentationGrantAuthority()
    }

    /** FIX-M1-007-07: the current account lives in the real local_account table. */
    val currentAccountStore: dev.hryshyn.remanence.session.RoomCurrentAccountStore by lazy {
        dev.hryshyn.remanence.session.RoomCurrentAccountStore(database.localAccountDao())
    }

    /** One account/capsule upload boundary used by [CapsuleUploadWorker]. */
    val capsuleUploadOrchestrator: CapsuleUploadOrchestrator by lazy {
        val retryStore = dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore(
            accountScopedFileRoots,
        )
        val retryLifecycle = dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialLifecycle(
            retryStore = retryStore,
            capsuleDao = database.outboxCapsuleDao(),
        )
        CapsuleUploadOrchestrator(
            capsuleDao = database.outboxCapsuleDao(),
            blobDao = database.outboxBlobDao(),
            currentAccountUserId = { currentAccountStore.load()?.userId },
            accessToken = { ordinaryAccessToken() },
            createDraft = { request, token -> capsuleDraftRepository.createDraft(request, token) },
            uploadBlob = { request, token -> capsuleBlobUploadRepository.uploadBlob(request, token) },
            finalizeCapsule = { request, token -> capsuleFinalizeRepository.finalize(request, token) },
            cleanupRetryMaterial = { owner, capsule ->
                retryLifecycle.cleanupForTerminalState(owner, capsule)
            },
            recipientUserLookup = { recipient, token ->
                recipientUserLookupRepository.lookup(recipient, token)
            },
            retryMaterialStore = retryStore,
            senderRetryKeysetWrapper = senderRetryKeysetWrapper,
            loadSenderSigningKeyset = { owner, senderBundle ->
                val account = currentAccountStore.loadEntity()
                if (account?.userId != owner.toRestString() ||
                    account.activeKeyBundleId != senderBundle.toRestString()
                ) {
                    null
                } else {
                    when (val loaded = identityRepository.load()) {
                        is IdentityBundleRepository.LoadResult.Available -> loaded.signingHandle
                        IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                    }
                }
            },
            accountScopedFileRoots = accountScopedFileRoots,
        )
    }

    /** A05b owner-scoped restart discovery boundary; lifecycle wiring stays at the root. */
    val capsuleUploadResumer: CapsuleUploadResumer by lazy {
        CapsuleUploadResumer(
            capsuleDao = database.outboxCapsuleDao(),
            currentAccountUserId = { currentAccountStore.load()?.userId },
            enqueue = { owner, capsule ->
                CapsuleUploadWorker.enqueue(
                    WorkManager.getInstance(appContext),
                    owner,
                    capsule,
                ).await()
            },
        )
    }

    /** A10b authenticated incoming scheduling boundary; invalid or stale owners are ignored. */
    suspend fun scheduleIncomingSync(owner: UserId) {
        val liveOwner = currentAccountStore.load()?.userId?.let { raw ->
            runCatching { UserId.parseRest(raw) }.getOrNull()
        } ?: return
        if (liveOwner != owner || ordinaryAccessToken() == null) return
        dev.hryshyn.remanence.sync.IncomingCapsuleSyncWorker.enqueue(
            WorkManager.getInstance(appContext),
            owner,
        ).await()
    }

    /** Returns a typed snapshot only while the durable current-account row is coherent. */
    private suspend fun currentAuthenticatedAccount(): AuthenticatedAccountSnapshot? {
        val row = currentAccountStore.loadEntity() ?: return null
        val summary = currentAccountStore.load() ?: return null
        if (row.userId != summary.userId || row.activeKeyBundleId != summary.activeKeyBundleId) {
            return null
        }
        val owner = runCatching { UserId.parseRest(row.userId) }.getOrNull() ?: return null
        val activeKeyBundleId = runCatching { KeyBundleId.parseRest(row.activeKeyBundleId) }.getOrNull()
            ?: return null
        if (owner.toRestString() != row.userId ||
            activeKeyBundleId.toRestString() != row.activeKeyBundleId
        ) {
            return null
        }
        return AuthenticatedAccountSnapshot(owner, activeKeyBundleId)
    }

    private suspend fun currentIncomingAcceptanceSession(): IncomingSyncSession? =
        currentIncomingAcceptanceSession { }

    private suspend fun currentIncomingAcceptanceSession(
        beforeCredentialRecheck: suspend () -> Unit,
    ): IncomingSyncSession? {
        val account = currentAuthenticatedAccount() ?: return null
        val token = ordinaryAccessToken() ?: return null
        beforeCredentialRecheck()
        if (currentAuthenticatedAccount() != account || ordinaryAccessToken() != token) return null
        return IncomingSyncSession(account.ownerUserId, token)
    }

    private suspend fun currentRecipientEncryptionIdentity(): CurrentRecipientEncryptionIdentity? =
        currentRecipientEncryptionIdentity { }

    private suspend fun currentRecipientEncryptionIdentity(
        beforeCredentialRecheck: suspend () -> Unit,
    ): CurrentRecipientEncryptionIdentity? {
        val account = currentAuthenticatedAccount() ?: return null
        val token = ordinaryAccessToken() ?: return null
        val loaded = incomingAcceptanceIdentityLoader()
        val encryptionHandle = when (loaded) {
            is IdentityBundleRepository.LoadResult.Available -> loaded.encryptionHandle
            IdentityBundleRepository.LoadResult.RecoveryRequired -> return null
        }
        val publicExport = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            encryptionHandle.publicKeysetHandle,
        )
        val exactBundle = try {
            deriveKeyBundleId(publicExport) == account.activeKeyBundleId.toRestString()
        } finally {
            publicExport.fill(0)
        }
        beforeCredentialRecheck()
        if (!exactBundle || currentAuthenticatedAccount() != account ||
            ordinaryAccessToken() != token
        ) return null
        return CurrentRecipientEncryptionIdentity(
            ownerUserId = account.ownerUserId,
            activeKeyBundleId = account.activeKeyBundleId,
            encryptionPrivateKeyset = encryptionHandle,
        )
    }

    /**
     * Offline presentation identity: the private recipient handle is loaded
     * locally and bound to the current account/key-bundle row, without an
     * access-token requirement or any network session.
     */
    private suspend fun currentLocalRecipientEncryptionIdentity(): CurrentRecipientEncryptionIdentity? {
        val account = currentAuthenticatedAccount() ?: return null
        val loaded = incomingAcceptanceIdentityLoader()
        val encryptionHandle = when (loaded) {
            is IdentityBundleRepository.LoadResult.Available -> loaded.encryptionHandle
            IdentityBundleRepository.LoadResult.RecoveryRequired -> return null
        }
        val publicExport = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            encryptionHandle.publicKeysetHandle,
        )
        val exactBundle = try {
            deriveKeyBundleId(publicExport) == account.activeKeyBundleId.toRestString()
        } finally {
            publicExport.fill(0)
        }
        if (!exactBundle || currentAuthenticatedAccount() != account) return null
        return CurrentRecipientEncryptionIdentity(
            ownerUserId = account.ownerUserId,
            activeKeyBundleId = account.activeKeyBundleId,
            encryptionPrivateKeyset = encryptionHandle,
        )
    }

    /** Test-only seam for proving the live session credential recheck. */
    internal suspend fun hasIncomingAcceptanceSessionForTesting(
        beforeCredentialRecheck: suspend () -> Unit,
    ): Boolean = currentIncomingAcceptanceSession(beforeCredentialRecheck) != null

    /** Test-only seam for proving the live recipient credential recheck. */
    internal suspend fun hasCurrentRecipientEncryptionIdentityForTesting(
        beforeCredentialRecheck: suspend () -> Unit,
    ): Boolean = currentRecipientEncryptionIdentity(beforeCredentialRecheck) != null

    private data class AuthenticatedAccountSnapshot(
        val ownerUserId: UserId,
        val activeKeyBundleId: KeyBundleId,
    )

    /**
     * FIX-M1-007-07 logout ordering: snapshot the owner, await its
     * account-scoped WorkManager cancellation, attempt server revocation,
     * clear in-memory credentials, clear the sealed refresh token, purge that
     * owner's TEMP root, clear the local_account row, then invalidate scan
     * grants. The rotation sink remains atomic for refresh failure handling;
     * logout uses only the credential-clear boundary so each store is cleared
     * exactly once.
     */
    val logoutUseCase: dev.hryshyn.remanence.auth.LogoutUseCase by lazy {
        dev.hryshyn.remanence.auth.LogoutUseCase(
            serverLogout = { accessToken -> bareAuthRepository.logout(accessToken) },
            accessToken = { apiStack.sessionRefreshCoordinator.rawAccessToken() },
            tokens = sessionTokenPort(),
            credentialSink = dev.hryshyn.remanence.auth.LogoutCredentialSink {
                authTokenHolder.clearSession()
            },
            accounts = { currentAccountStore.clear() },
            grants = {
                // App-level grant state dies with the account context.
                appLevelGrantCleanup.forEach { cleanup -> cleanup() }
            },
            // M2-P04: the owner is snapshotted from the still-live local_account
            // row BEFORE it is cleared, and cleanup targets ONLY that account's
            // temp root. A missing row means no attributable owner - nothing
            // may be deleted on a guess.
            logoutOwnerSnapshot = {
                val row = currentAccountStore.loadEntity()
                    ?: error("logout storage cleanup requires an authenticated local account")
                dev.hryshyn.remanence.core.model.UserId.parseRest(row.userId)
            },
            tempStorageCleanup = { owner ->
                accountStorageRetention.onLogout(owner)
            },
            // M2-P05: the SAME immutable snapshot is the only cancellation
            // target; exactly that account's chains are cancelled before any
            // server/network teardown or credential state clears.
            workCancellation = { owner ->
                accountWorkCancellation.cancelForAccount(owner)
            },
            invalidateSessionLease = { apiStack.sessionRefreshCoordinator.invalidate() },
        )
    }

    /** Hooks registered by the running UI to drop scan grants on logout. */
    private val appLevelGrantCleanup = mutableListOf<() -> Unit>()

    fun registerGrantCleanup(cleanup: () -> Unit) {
        appLevelGrantCleanup += cleanup
    }

    internal val sessionReplacement: dev.hryshyn.remanence.auth.SessionReplacementPort by lazy {
        dev.hryshyn.remanence.auth.BoundSessionReplacement(
            coordinator = apiStack.sessionRefreshCoordinator,
            currentAccountOwner = {
                currentAccountStore.load()?.userId?.let { raw ->
                    runCatching { UserId.parseRest(raw) }.getOrNull()
                }
            },
        )
    }

    private fun sessionTokenPort(): SessionTokenPort = object : SessionTokenPort {
        override fun readToken(): String? = sessionTokenStore.load()?.refreshToken

        override fun readRecord(): SessionRefreshRecord? = sessionTokenStore.load()

        override fun saveToken(refreshToken: String) {
            val existing = sessionTokenStore.load()
                ?: throw java.security.GeneralSecurityException("no bound session record")
            sessionTokenStore.save(SessionRefreshRecord(existing.ownerUserId, refreshToken))
        }

        override fun saveRecord(record: SessionRefreshRecord) {
            sessionTokenStore.save(record)
        }

        override fun clearToken() = sessionTokenStore.clear()

        override fun clearExactRecord(snapshot: SessionRefreshRecord) {
            apiStack.sessionRefreshCoordinator.clearExactBoundRecord(
                snapshot.ownerUserId,
                snapshot.refreshToken,
            )
        }
    }

    val loginUseCase: dev.hryshyn.remanence.auth.LoginUseCase by lazy {
        dev.hryshyn.remanence.auth.LoginUseCase(
            identity = { activeKeyBundleId ->
                val exports = identityRepository.loadPublicExports()
                exports is IdentityBundleRepository.PublicExportsResult.Available &&
                    deriveKeyBundleId(exports.encryptionPublicKeyset) == activeKeyBundleId
            },
            authApi = { request -> bareAuthRepository.login(request) },
            accounts = object : dev.hryshyn.remanence.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: dev.hryshyn.remanence.core.data.network.RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    currentAccountStore.record(user.userId, user.handle, activeKeyBundleId)
                }
            },
            sessionReplacement = sessionReplacement,
        )
    }

    val registrationUseCase: dev.hryshyn.remanence.auth.RegistrationUseCase by lazy {
        dev.hryshyn.remanence.auth.RegistrationUseCase(
            identity = registrationIdentityAdapter,
            authApi = object : dev.hryshyn.remanence.auth.RegistrationAuthApiPort {
                override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> =
                    bareAuthRepository.register(request)
            },
            accounts = object : dev.hryshyn.remanence.auth.CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: dev.hryshyn.remanence.core.data.network.RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    currentAccountStore.record(user.userId, user.handle, activeKeyBundleId)
                }
            },
            sessionReplacement = sessionReplacement,
        )
    }

    /**
     * Cold-start session resolution over the sealed refresh token, wrapped
     * keysets, and the real `local_account` row. The stored token is
     * proved against `/v1/auth/refresh` BEFORE any Active state exists.
     */
    val identityAvailability: IdentityAvailabilityPort by lazy {
        object : IdentityAvailabilityPort {
            override fun hasIdentityFor(activeKeyBundleId: String): Boolean {
                val exports = identityRepository.loadPublicExports()
                return exports is IdentityBundleRepository.PublicExportsResult.Available &&
                    deriveKeyBundleId(exports.encryptionPublicKeyset) == activeKeyBundleId
            }
        }
    }

    val sessionBootstrap: SessionBootstrap by lazy {
        val refreshCoordinator = apiStack.sessionRefreshCoordinator
        val identityAvailability = this.identityAvailability
        SessionBootstrap(
            tokens = sessionTokenPort(),
            identity = identityAvailability,
            account = { currentAccountStore.load() },
            refresher = object : dev.hryshyn.remanence.session.SessionRefresher {
                override suspend fun hasStoredToken(): Boolean =
                    refreshCoordinator.hasStoredToken()

                override suspend fun refresh(
                    expectedOwner: UserId,
                ): dev.hryshyn.remanence.session.SessionRefreshOutcome =
                    when (val result = refreshCoordinator.refreshForBootstrap(expectedOwner)) {
                        is dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.Rotated ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Rotated(
                                accessToken = result.accessToken,
                                refreshToken = result.refreshToken,
                            )
                        is dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.Reused ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Reused(result.accessToken)
                        dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.NoToken ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.NoToken
                        dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.Rejected ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Rejected
                        dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.Unreachable ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Unreachable
                        dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.Unavailable ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Unavailable
                        dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.Invalidated ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Invalidated
                    }
            },
        )
    }

    /** Process-wide refresh coordinator shared by bootstrap and OkHttp 401 retries. */
    val sessionRefreshCoordinator: dev.hryshyn.remanence.core.data.network.SessionRefreshCoordinator
        get() = apiStack.sessionRefreshCoordinator

    /** Deterministic client-generated bundle ID bound to this exact identity. */
    private fun deriveKeyBundleId(encryptionPublicKeyset: ByteArray): String =
        java.util.UUID.nameUUIDFromBytes(encryptionPublicKeyset).toString()

    companion object {
        const val DATABASE_NAME: String = "remanence.db"
        const val SESSION_TOKEN_KEK_ALIAS: String = "remanence.session.v1"
        const val IDENTITY_KEK_ALIAS: String = "remanence.identity.v1"
        const val SENDER_RETRY_KEK_ALIAS: String = "remanence.sender-retry.v1"
    }
}
