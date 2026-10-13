package dev.hryshyn.remanence

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.WorkInfo
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
import dev.hryshyn.remanence.core.data.db.RecipientTombstonePresentationBoundary
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
import dev.hryshyn.remanence.session.SessionOwnerCoordinator
import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.sync.CapsuleUploadOrchestrator
import dev.hryshyn.remanence.sync.CapsuleUploadResumer
import dev.hryshyn.remanence.sync.CapsuleUploadWorker
import dev.hryshyn.remanence.sync.CurrentRecipientEncryptionIdentity
import dev.hryshyn.remanence.sync.IncomingCapsuleAcceptanceCoordinator
import dev.hryshyn.remanence.sync.IncomingAcceptanceDrain
import dev.hryshyn.remanence.sync.IncomingAcceptanceDiagnostics
import dev.hryshyn.remanence.sync.IncomingControlIndexAcceptanceCoordinator
import dev.hryshyn.remanence.sync.IncomingSyncSchedulingOutcome
import dev.hryshyn.remanence.sync.ExistingIncomingWorkState
import dev.hryshyn.remanence.sync.IncomingKeepScheduleOperation
import dev.hryshyn.remanence.sync.IncomingScheduleLeaseRejected
import dev.hryshyn.remanence.sync.SenderIndexBundlePersistenceAdapter
import dev.hryshyn.remanence.ui.scan.IncomingSenderIndexCandidateProvider
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparation
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.wiring.TinkRegistrationIdentityAdapter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

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
        // No locale bootstrap here: AppCompat owns the store (platform
        // LocaleManager on 33+, backport below) and the attached activity
        // delegates apply/restore it. Nothing to mirror or wrap.
        // OpenCV native runtime must be live before any capture/extraction
        // component runs (docs/recognition.md section 4).
        org.opencv.android.OpenCVLoader.initLocal()
        container = AppContainer(this)
        // A prior process may have died without disposing its share route.
        runCatching { dev.hryshyn.remanence.ui.capsule.CapsuleExportFiles.sweep(this) }
        // T186: a prior process may have died with a queued retirement outbox;
        // re-arm its background drain on every startup (deduped, nonblocking).
        runCatching { container.rearmPendingRetirement() }
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
) : dev.hryshyn.remanence.pending.PendingRetirementHost {

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

    val apiBaseUrl: ApiBaseUrl = ApiBaseUrl.parse(BuildConfig.API_BASE_URL).also {
        // T188-B2 GLOBAL fail-closed: a pending-enabled build must never be
        // constructed against the live beta backend. This fires in the
        // container constructor before ANY client (auth/sync/upload/health/
        // telemetry/retire) is created. Default/beta/release builds (pending
        // OFF) are unaffected.
        if (BuildConfig.REMANENCE_PENDING_ENABLED &&
            !dev.hryshyn.remanence.pending.PendingFeatureGate.isIsolatedApiAccepted(BuildConfig.API_BASE_URL)
        ) {
            throw IllegalStateException("pending-enabled build requires an isolated API base (live beta forbidden)")
        }
    }

    val healthRepository: HealthRepository by lazy { HealthRepository.create(apiBaseUrl) }

    val appContext: Context = context.applicationContext

    internal val telemetry by lazy {
        dev.hryshyn.remanence.telemetry.TelemetryCoordinator(
            dev.hryshyn.remanence.telemetry.DeviceTelemetryConsentStorage(appContext),
            dev.hryshyn.remanence.telemetry.TelemetryHttpTransport(apiBaseUrl))
    }

    val recoveryReadiness by lazy { dev.hryshyn.remanence.recovery.RecoveryReadinessStore(appContext) }

    /**
     * In-app language choice (EN/RU/UK + system default). Handle only: the
     * store is AppCompat's (single source of truth), so switching recreates
     * at most the Activity while auth state, ViewModels and capsule grants
     * are preserved; the choice survives restart via the AppCompat/platform
     * record.
     */
    val appLocaleRepository: dev.hryshyn.remanence.ui.locale.AppLocaleRepository by lazy {
        dev.hryshyn.remanence.ui.locale.AppLocaleRepository()
    }

    /** Immediate account-boundary fence for live Scan scheduling/watchers. */
    internal val sessionBoundary: dev.hryshyn.remanence.session.SessionBoundary =
        dev.hryshyn.remanence.session.SessionBoundary { invalidate ->
            // Root owns the account-boundary publication transaction.  Keep
            // this low-level boundary callback limited to the epoch/listener
            // invalidation so Root does not perform a second reset after the
            // fence has already been released.
            invalidate()
        }

    val database: RemanenceLocalDatabase by lazy {
        Room.databaseBuilder(appContext, RemanenceLocalDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                dev.hryshyn.remanence.core.data.db.MIGRATION_8_9_RECIPIENT_TOMBSTONES,
                dev.hryshyn.remanence.core.data.db.MIGRATION_9_10_LOCAL_SEND_DUPLICATES,
                dev.hryshyn.remanence.core.data.db.MIGRATION_10_11_FIRST_OPEN_CLAIMS,
                dev.hryshyn.remanence.core.data.db.MIGRATION_11_12_NULLABLE_HANDLE,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    /** Shared owner boundary ordering tombstone commit and offline handoff. */
    internal val recipientTombstonePresentationBoundary =
        RecipientTombstonePresentationBoundary()

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
     * C2 generator wiring (container lazies ONLY): production dependencies
     * for the frozen C1 bridge (`GeneratorCreateBridge.Bridge` over the G3
     * `GeneratorStaging.Manager` and the G4B `SourceBinder`), using the
     * HB.md production choices — G4A owner-scoped filesystem store,
     * `elapsedRealtime` monotonic clock, real EXIF decoder. No ViewModel,
     * factory, publisher, protocol, renderer, UI or legacy-path changes
     * here; nothing here calls into the bridge.
     *
     * Owner scoping: the G4A store is bound to exactly one owner, so the
     * Manager/Bridge pair is cached per canonical owner. The binder
     * (normalizer + decoder) is owner-agnostic and shared. Every member
     * stays lazy so plain unit contexts never need Android clocks,
     * Bitmaps, or filesystem roots until accessed.
     */
    val generatorPhotoNormalizer: dev.hryshyn.remanence.create.PhotoNormalizerPort by lazy {
        val normalizer = dev.hryshyn.remanence.core.recognition.PhotoNormalizer()
        dev.hryshyn.remanence.create.PhotoNormalizerPort { jpeg ->
            val normalized = normalizer.normalize(jpeg)
            dev.hryshyn.remanence.create.NormalizedPhotoDto(
                normalized.jpegBytes,
                normalized.width,
                normalized.height,
            )
        }
    }

    /** Real EXIF-upright decoder (bounds-only, never allocates pixels). */
    val generatorPhotoDecoder: dev.hryshyn.remanence.create.GeneratorSourceBinding.PhotoDecoderPort by lazy {
        dev.hryshyn.remanence.create.GeneratorExifDecoder
    }

    /** Owner-agnostic G4B binder over the production normalizer + decoder. */
    val generatorSourceBinder: dev.hryshyn.remanence.create.GeneratorSourceBinding.SourceBinder by lazy {
        dev.hryshyn.remanence.create.GeneratorSourceBinding.SourceBinder(
            generatorPhotoNormalizer,
            generatorPhotoDecoder,
        )
    }

    /** Per-owner G3 staging managers (sessions live in the Manager). */
    private val generatorStagingManagers =
        ConcurrentHashMap<String, dev.hryshyn.remanence.core.model.GeneratorStaging.Manager>()

    /** Per-owner G4-C1 bridges (each owns its monotonic content revision). */
    private val generatorCreateBridges =
        ConcurrentHashMap<String, dev.hryshyn.remanence.create.GeneratorCreateBridge.Bridge>()

    /**
     * Production G3 staging manager for [owner]: G4A owner-scoped
     * filesystem store driven by the `elapsedRealtime` monotonic clock
     * (G3 fail-closed on rollback: sessions are kept, never wrongly
     * expired). Cached per canonical owner; construction runs the one
     * G4 restart-recovery sweep of this owner's orphan `gen-staging`
     * entries before the Manager is published (empty live set is
     * truthful only here — no session can exist yet), then returns the
     * Manager that owns the namespace.
     */
    fun generatorStagingManager(
        owner: UserId,
    ): dev.hryshyn.remanence.core.model.GeneratorStaging.Manager =
        generatorStagingManagers.computeIfAbsent(owner.toRestString()) {
            dev.hryshyn.remanence.core.data.storage.GeneratorStagingRecovery.managerForOwner(
                accountScopedFileRoots,
                accountStorageRetention,
                owner,
                nowMillis = { android.os.SystemClock.elapsedRealtime() },
            )
        }

    /**
     * Production C1 bridge for [owner] over that owner's staging manager
     * and the shared production binder. Cached per canonical owner.
     */
    fun generatorCreateBridge(
        owner: UserId,
    ): dev.hryshyn.remanence.create.GeneratorCreateBridge.Bridge =
        generatorCreateBridges.computeIfAbsent(owner.toRestString()) {
            dev.hryshyn.remanence.create.GeneratorCreateBridge.Bridge(
                generatorStagingManager(owner),
                generatorSourceBinder,
            )
        }

    /** Per-process, per-owner Create plaintext recovery completion ledger. */
    private val createStagingSweepOwners = ConcurrentHashMap.newKeySet<String>()
    private val createStagingSweepMutex = Mutex()
    private var startupStagingRecoveryComplete = false
    /** Keeps WorkInfo observation and KEEP enqueue one operation per owner. */
    private val incomingScheduleLocks =
        dev.hryshyn.remanence.sync.OwnerScopedIncomingScheduleLocks()

    /**
     * Runs before authenticated root publication. A failed sweep is not
     * recorded, so a later bootstrap may retry; concurrent bootstraps for the
     * same owner serialize and cannot expose the account mid-sweep.
     */
    suspend fun sweepCreateStagingOnce(owner: UserId) {
        withContext(Dispatchers.IO) {
            createStagingSweepMutex.withLock {
                val ownerText = owner.toRestString()
                if (ownerText in createStagingSweepOwners) return@withLock
                accountStorageRetention.sweepCreateStaging(owner)
                createStagingSweepOwners += ownerText
                startupStagingRecoveryComplete = true
            }
        }
    }

    /** A missing/rejected refresh token must not leave prior process plaintext
     * waiting for reauthentication. The canonical local account is attribution,
     * not authorization; only its temporary Create files are swept, not keys,
     * ciphertext or durable outbox. Once any live session began, route/publisher
     * cleanup owns its inputs and this startup-only sweep cannot run again. */
    suspend fun sweepSignedOutCreateStagingOnce() = withContext(Dispatchers.IO) {
        createStagingSweepMutex.withLock {
            if (startupStagingRecoveryComplete) return@withLock
            val row = currentAccountStore.loadEntity()
            val owner = row?.userId?.let { runCatching { UserId.parseRest(it) }.getOrNull() }
            if (owner != null) accountStorageRetention.sweepCreateStaging(owner)
            startupStagingRecoveryComplete = true
        }
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

    /** Owner/incarnation-bound online first-open claim; null is fail-closed. */
    internal suspend fun claimIncomingFirstOpen(
        ownerUserId: UserId,
        capsuleId: dev.hryshyn.remanence.core.model.CapsuleId,
        expectedLease: dev.hryshyn.remanence.core.data.network.SessionRequestLease?,
    ): Long? {
        val account = currentAccountStore.load() ?: return null
        val currentOwner = runCatching { UserId.parseRest(account.userId) }.getOrNull() ?: return null
        if (currentOwner != ownerUserId) return null
        val lease = expectedLease ?: return null
        val token = apiStack.accessTokenForSessionRequestLease(lease) ?: return null
        return when (
            val result = apiStack.capsuleFirstOpenRepository.claim(capsuleId, token, lease)
        ) {
            is dev.hryshyn.remanence.core.data.network.CapsuleFirstOpenResult.Success ->
                result.open.firstOpenedAtEpochMs
            is dev.hryshyn.remanence.core.data.network.CapsuleFirstOpenResult.Failure -> null
        }
    }

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
        apiStack.directoryRepository
    }

    /** FIX-REVIEW2-04: immutable public key-bundle lookup for verification. */
    val keyBundleByIdRepository: dev.hryshyn.remanence.core.data.network.KeyBundleByIdRepository by lazy {
        apiStack.keyBundleByIdRepository
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

    /** A11 account-scoped authenticated recipient tombstone feed. */
    val incomingTombstoneSyncRepository:
        dev.hryshyn.remanence.core.data.db.IncomingTombstoneSyncRepository by lazy {
        dev.hryshyn.remanence.core.data.db.IncomingTombstoneSyncRepository(
            remote = apiStack.incomingTombstoneRepository,
            database = database,
            roots = accountScopedFileRoots,
            revocationBoundary = recipientTombstonePresentationBoundary,
            currentSession = {
                val account = currentAccountStore.load() ?: return@IncomingTombstoneSyncRepository null
                val token = ordinaryAccessToken() ?: return@IncomingTombstoneSyncRepository null
                val owner = runCatching {
                    dev.hryshyn.remanence.core.model.UserId.parseRest(account.userId)
                }.getOrNull() ?: return@IncomingTombstoneSyncRepository null
                dev.hryshyn.remanence.core.data.db.IncomingSyncSession(owner, token)
            },
            onTombstonesCommitted = { owner, capsules ->
                capsules.forEach { capsule -> presentationGrants.revokeIncoming(owner, capsule) }
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
                keyBundleByIdRepository.fetch(bundleId)
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
            userHandleLookup = { senderUserId ->
                when (val result = recipientUserLookupRepository.lookup(senderUserId)) {
                    is dev.hryshyn.remanence.core.data.network.RecipientUserLookupResult.Found ->
                        dev.hryshyn.remanence.identity.DirectorySenderKeyStore.ResolvedDisplayIdentity(
                            userId = result.snapshot.userId,
                            handle = result.snapshot.handle,
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

    /** Local preparation plus a final online first-open admission claim. */
    internal val incomingPresentationPreparation: IncomingPresentationPreparation by lazy {
        IncomingPresentationPreparation(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            incomingEnvelopeDao = database.incomingEnvelopeDao(),
            blobCacheDao = database.blobCacheDao(),
            recipientTombstoneDao = database.recipientTombstoneDao(),
            roots = accountScopedFileRoots,
            senderIndexBundleReader = senderIndexBundleReader,
            currentRecipientIdentity = { currentLocalRecipientEncryptionIdentity() },
            firstOpenClaim = { owner, capsule, lease ->
                claimIncomingFirstOpen(owner, capsule, lease)
            },
            revocationBoundary = recipientTombstonePresentationBoundary,
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

    // -- T186 experimental pending-retirement host (isolated, default OFF) --
    override val pendingContext: Context get() = appContext

    /**
     * T188-B2: the actual constructed API base for the isolation guard.
     * The worker verifies THIS, not the BuildConfig constant.
     */
    override val pendingApiBaseUrl: String
        get() = try {
            apiBaseUrl.httpUrl.toString()
        } catch (_: Exception) {
            ""
        }

    override val pendingOwnerId: String?
        get() = try {
            // Synchronous live-lease owner: lock-guarded memory read, never
            // a Room call. Null when no live session (signed out/refreshing).
            apiStack.captureSessionRequestLease()
                ?.ownerUserId?.toRestString()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    /** Application-owned scope: outlives any screen; never a disposable UI scope. */
    override val pendingAppScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
    }

    /**
     * T186 M4: TYPED R15 completion port — EXACT frozen §8 inputs
     * (`publication_sha256_hex` + `operation_id`). Real routing: outbox
     * drain → this repository → `POST
     * v2/capsules/{id}/locator-open-completion`. Routes are still scaffold
     * server-side, so live calls fail into the retryable path and the queue
     * retains — never fake success, never claimed working. Gated by the
     * pending flag + isolated API (worker + global container fail-closed).
     */
    val pendingRetirementPort: dev.hryshyn.remanence.pending.PendingRetirementPort by lazy {
        val retire = dev.hryshyn.remanence.core.data.network.PendingLocatorRetireRepository.create(apiBaseUrl)
        dev.hryshyn.remanence.pending.PendingRetirementPort { _, capsule, op, pub, token ->
            if (token.isBlank()) {
                dev.hryshyn.remanence.pending.PendingRetireOutcome.RetryableFailure("no-token")
            } else {
                val id = runCatching {
                    dev.hryshyn.remanence.core.model.CapsuleId.parseRest(capsule)
                }.getOrNull() ?: return@PendingRetirementPort dev.hryshyn.remanence.pending.PendingRetireOutcome.PermanentFailure(
                    "malformed-capsule-id",
                )
                when (val result = retire.complete(id, pub, op, token)) {
                    is dev.hryshyn.remanence.core.data.network.LocatorRetireResult.Completed ->
                        dev.hryshyn.remanence.pending.PendingRetireOutcome.Success
                    is dev.hryshyn.remanence.core.data.network.LocatorRetireResult.Failure ->
                        if (result.retryable) {
                            dev.hryshyn.remanence.pending.PendingRetireOutcome.RetryableFailure("retire-retryable")
                        } else {
                            dev.hryshyn.remanence.pending.PendingRetireOutcome.PermanentFailure("retire-rejected")
                        }
                }
            }
        }
    }

    override val pendingOutbox: dev.hryshyn.remanence.pending.PendingRetirementOutbox by lazy {
        dev.hryshyn.remanence.pending.PendingRetirementOutbox(appContext.filesDir, pendingRetirementPort)
    }

    /** Real session-lease fence: capture before each send, recheck after. */
    override val pendingSessionGate: dev.hryshyn.remanence.pending.PendingRetirementSessionGate by lazy {
        object : dev.hryshyn.remanence.pending.PendingRetirementSessionGate {
            override fun capture() = apiStack.captureSessionRequestLease()
            override fun accessTokenFor(
                lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
            ) = apiStack.accessTokenForSessionRequestLease(lease)
            override fun isLive(
                lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
            ) = apiStack.isSessionRequestLeaseLive(lease)
        }
    }

    /**
     * T186: best-effort re-arm of the retirement drain after startup/login/
     * foreground resume (covers process death). Deduped unique work; safe to
     * call often; never blocks the caller. No-op unless pending is enabled.
     */
    fun rearmPendingRetirement() {
        try {
            dev.hryshyn.remanence.pending.PendingRetirementScheduler.scheduleDrain(appContext)
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    /**
     * Production presentation callback. Call ONLY after genuine supported
     * presentation (live grant + Ready supported content). Nonblocking: the
     * viewer returns immediately; the account fence (suspend Room read) and
     * persistence + drain run on [pendingAppScope] and WorkManager. Backend
     * failure never affects display/entitlement. Never blocks the viewer,
     * never reads Room on the caller thread.
     *
     * @param publicationHash R15 64-hex override when the caller already
     * holds the exact published bytes (H = `SHA256(S)` for v2). Null by
     * default: derived here from the trusted local incoming entity
     * (stored statement digest, else digest of the verified statement
     * bytes); capsules without statement material are not enqueued.
     */
    fun notifySupportedPresentation(
        ownerUserId: String,
        capsuleId: String,
        grantLive: Boolean,
        routeReady: Boolean,
        supported: Boolean,
        publicationHash: String? = null,
    ) {
        if (!dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()) return
        if (ownerUserId.isBlank() || capsuleId.isBlank()) return
        val kind = if (supported) {
            dev.hryshyn.remanence.pending.PendingPresentationGate.PresentationKind.SUPPORTED
        } else {
            dev.hryshyn.remanence.pending.PendingPresentationGate.PresentationKind.UNSUPPORTED
        }
        if (!dev.hryshyn.remanence.pending.PendingPresentationGate.shouldEnqueueRetirement(
                featureEnabled = true, grantLive = grantLive, routeReady = routeReady, kind = kind,
            )
        ) {
            return
        }
        // Fence against account switch between presentation and callback:
        // suspend Room reads inside the application-owned coroutine, never on
        // the caller (often Main) thread and never runBlocking.
        pendingAppScope.launch {
            val current = try {
                currentAccountStore.load()?.userId?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (current != ownerUserId) return@launch
            // H from trusted local material: prefer the stored statement
            // digest, else hash the verified statement bytes. Capsules
            // without locator metadata (no statement material) are NOT
            // enqueued at all.
            val derived = publicationHash ?: run {
                val entity = try {
                    database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleId, ownerUserId)
                } catch (_: Exception) {
                    null
                } ?: return@launch
                val stored = entity.signedStatementSha256
                if (stored.isNotEmpty()) {
                    stored.joinToString("") { "%02x".format(it) }
                } else if (entity.signedStatementBytes.isNotEmpty()) {
                    try {
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(entity.signedStatementBytes)
                            .joinToString("") { "%02x".format(it) }
                    } catch (_: Exception) {
                        return@launch
                    }
                } else {
                    return@launch
                }
            }
            // Application-owned scope inside; never the caller's screen scope.
            dev.hryshyn.remanence.pending.PendingRetirementScheduler.enqueueAfterPresentation(
                this@AppContainer, ownerUserId, capsuleId, derived,
            )
        }
    }

    /** FIX-M1-007-07: the current account lives in the real local_account table. */
    val currentAccountStore: dev.hryshyn.remanence.session.RoomCurrentAccountStore by lazy {
        dev.hryshyn.remanence.session.RoomCurrentAccountStore(database.localAccountDao())
    }

    /**
     * T186 M8: frozen R1 route-freeze port (routes scaffold server-side;
     * failures stay typed). Sender UI mount follows on navigation handoff.
     */
    val pendingSendRoutePort: dev.hryshyn.remanence.core.data.network.PendingSendRouteRepository by lazy {
        dev.hryshyn.remanence.core.data.network.PendingSendRouteRepository.create(apiBaseUrl)
    }

    /**
     * T186 M7a: frozen R17 handle-commit port (routes scaffold server-side;
     * calls fail generic-retryable until mounted — never fake success).
     */
    val pendingHandleCommitPort: dev.hryshyn.remanence.core.data.network.HandleCommitPort by lazy {
        dev.hryshyn.remanence.core.data.network.PendingHandleCommitRepository.create(apiBaseUrl)
    }

    /**
     * T186 M7c: frozen R6 gift-lookup port (anonymous, budgeted). Routes
     * scaffold server-side; failures stay generic (no existence oracle).
     */
    val pendingGiftLookupPort: dev.hryshyn.remanence.core.data.network.PendingGiftLookupRepository by lazy {
        dev.hryshyn.remanence.core.data.network.PendingGiftLookupRepository.create(apiBaseUrl)
    }

    /**
     * T186 M7c: proof-flow ViewModel with real frozen clients. The caller
     * supplies the coroutine scope that bounds the flow (no retained
     * ViewModelStore needed for this short secondary journey).
     */
    fun newPendingProofViewModel(
        scope: kotlinx.coroutines.CoroutineScope,
    ): dev.hryshyn.remanence.pending.PendingProofViewModel {
        val continueRepo = dev.hryshyn.remanence.core.data.network.PendingContinueRepository.create(apiBaseUrl)
        val verifyRepo = dev.hryshyn.remanence.core.data.network.PendingVerificationRepository.create(apiBaseUrl)
        val claimRepo = dev.hryshyn.remanence.core.data.network.PendingClaimRepository.create(apiBaseUrl)
        return dev.hryshyn.remanence.pending.PendingProofViewModel(
            continuePort = dev.hryshyn.remanence.pending.ProofContinuePort { ref, token ->
                continueRepo.continueFrom(ref, token)
            },
            verifyPort = object : dev.hryshyn.remanence.pending.ProofVerifyPort {
                override suspend fun request(
                    deliveryId: String, destination: String, smsConsent: Boolean,
                    nowSeconds: Long, idempotencyKey: String, token: String,
                ) = verifyRepo.requestVerification(
                    deliveryId, destination,
                    if (smsConsent) "v1" else null,
                    if (smsConsent) nowSeconds else null,
                    idempotencyKey, token,
                )

                override suspend fun check(
                    deliveryId: String, attemptId: String, code: String, token: String,
                ) = verifyRepo.checkCode(deliveryId, attemptId, code, token)
            },
            optionsPort = dev.hryshyn.remanence.pending.ProofOptionsPort { deliveryId, token ->
                claimRepo.options(deliveryId, token)
            },
            tokenProvider = {
                val lease = try {
                    apiStack.captureSessionRequestLease()
                } catch (_: Exception) {
                    null
                }
                if (lease == null) {
                    null
                } else {
                    try {
                        apiStack.accessTokenForSessionRequestLease(lease)
                    } catch (_: Exception) {
                        null
                    }
                }
            },
            accountProvider = {
                val summary = try {
                    currentAccountStore.load()
                } catch (_: Exception) {
                    null
                }
                if (summary == null) {
                    null
                } else {
                    dev.hryshyn.remanence.pending.ProofAccount(summary.userId, summary.activeKeyBundleId)
                }
            },
            signingKeyProvider = {
                when (val loaded = identityRepository.load()) {
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                        loaded.signingHandle
                    dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                }
            },
            hpkeKeyProvider = {
                when (val loaded = identityRepository.load()) {
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                        loaded.encryptionHandle
                    dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                }
            },
            // Explicit block: auth session_id not persisted client-side.
            sessionIdProvider = { null },
            scope = scope,
        )
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

    /** A10b owner-scoped incoming scheduling boundary with safe typed outcomes. */
    suspend fun scheduleIncomingSync(owner: UserId): IncomingSyncSchedulingOutcome =
        scheduleIncomingSyncWithOwnerRead(owner) {
            currentAccountStore.load()?.userId
        }

    /**
     * The owner-read seam keeps the lease invariant testable without changing
     * the production caller contract. The supplied read is used for both
     * admission reads; the lease is still captured before its first suspend.
     */
    internal suspend fun scheduleIncomingSyncWithOwnerRead(
        owner: UserId,
        accountRead: suspend () -> String?,
    ): IncomingSyncSchedulingOutcome {
        // Capture the lease before the first account/token read. It must never
        // be refreshed after a suspension: an old A attempt must remain stale
        // across logout -> login A rather than borrowing the new epoch.
        val scheduleEpoch = sessionBoundary.currentEpoch()
        val liveOwner = try {
            accountRead()?.let { raw ->
                runCatching { UserId.parseRest(raw) }.getOrNull()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return IncomingSyncSchedulingOutcome.EnqueueFailed
        }
        if (liveOwner != owner) {
            return IncomingSyncSchedulingOutcome.SessionOwnerRejected
        }

        val uniqueName = dev.hryshyn.remanence.sync.AccountWorkIdentity
            .incomingSync(owner)
            .uniqueName
        return try {
            // WorkManager initialization is part of this typed failure
            // boundary too; application startup must not leak its exception
            // into Root/Scan or turn it into an unconditional "enqueued".
            val workManager = WorkManager.getInstance(appContext)
            lateinit var enqueueOperation: androidx.work.Operation
            val keepSchedule = IncomingKeepScheduleOperation(
                observe = {
                    val existing = withContext(Dispatchers.IO) {
                        workManager.getWorkInfosForUniqueWork(uniqueName).get(5, TimeUnit.SECONDS)
                    }
                    existing.firstOrNull { info ->
                        when (info.state) {
                            WorkInfo.State.ENQUEUED,
                            WorkInfo.State.RUNNING,
                            WorkInfo.State.BLOCKED,
                            -> true
                            WorkInfo.State.SUCCEEDED,
                            WorkInfo.State.FAILED,
                            WorkInfo.State.CANCELLED,
                            -> false
                        }
                    }?.let { info ->
                        when (info.state) {
                            WorkInfo.State.ENQUEUED -> ExistingIncomingWorkState.ENQUEUED
                            WorkInfo.State.RUNNING -> ExistingIncomingWorkState.RUNNING
                            WorkInfo.State.BLOCKED -> ExistingIncomingWorkState.BLOCKED
                            else -> null
                        }
                    }
                },
                enqueue = {
                    // KEEP is mandatory even after an active snapshot. The
                    // snapshot is observability only. The second owner read
                    // is cancellation-transparent: lifecycle cancellation
                    // must not become a false owner rejection.
                    val currentOwner = try {
                        accountRead()?.let { raw -> UserId.parseRest(raw) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    enqueueOperation = sessionBoundary.withCurrentLease(scheduleEpoch) {
                        if (currentOwner != owner) {
                            throw IncomingScheduleLeaseRejected()
                        }
                        // This is the only WorkManager enqueue call. It is
                        // made while the short lifecycle lease is held;
                        // awaiting its result happens after the owner lock.
                        dev.hryshyn.remanence.sync.IncomingCapsuleSyncWorker.enqueue(
                            workManager,
                            owner,
                        )
                    } ?: throw IncomingScheduleLeaseRejected()
                },
                awaitEnqueue = { enqueueOperation.await() },
            )
            // Observation and the KEEP enqueue are the short owner-scoped
            // critical section. Operation.await() is deliberately outside it
            // so a hung WorkManager future cannot block the next lifecycle
            // operation for this owner.
            val prepared = incomingScheduleLocks.withOwner(owner) {
                keepSchedule.prepare()
            }
            keepSchedule.complete(prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IncomingSyncSchedulingOutcome.EnqueueFailed
        }
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
        val sessionLease = apiStack.captureSessionRequestLease() ?: return null
        val token = apiStack.accessTokenForSessionRequestLease(sessionLease) ?: return null
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
            apiStack.accessTokenForSessionRequestLease(sessionLease) != token ||
            !apiStack.isSessionRequestLeaseLive(sessionLease)
        ) return null
        return CurrentRecipientEncryptionIdentity(
            ownerUserId = account.ownerUserId,
            activeKeyBundleId = account.activeKeyBundleId,
            encryptionPrivateKeyset = encryptionHandle,
            sessionLease = sessionLease,
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
            sessionLease = apiStack.captureSessionRequestLease(),
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
            // C2: the snapshotted owner's generator bridge sessions die
            // with the account, exactly once at step 4.5.
            generatorBridgeLogout = dev.hryshyn.remanence.auth.GeneratorBridgeLogoutPort { owner ->
                generatorCreateBridge(owner).onLogout(owner)
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
            refreshForRecovery = BuildConfig.REMANENCE_RECOVERY_ENABLED,
            recoveryReady = { account -> !BuildConfig.REMANENCE_RECOVERY_ENABLED ||
                recoveryReadiness.isReady(account.userId, account.activeKeyBundleId) },
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
                                lease = result.lease,
                            )
                        is dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome.Reused ->
                            dev.hryshyn.remanence.session.SessionRefreshOutcome.Reused(
                                accessToken = result.accessToken,
                                lease = result.lease,
                            )
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

    /**
     * Process-death worker admission over the same bootstrap and refresh
     * coordinator as the root. Construction does not create an Activity or a
     * RootViewModel and remains lazy until a worker actually runs.
     */
    internal val sessionOwnerCoordinator: SessionOwnerCoordinator by lazy {
        SessionOwnerCoordinator(
            sessionResolver = sessionBootstrap,
            currentOwner = {
                currentAccountStore.load()?.userId?.let { raw ->
                    runCatching { UserId.parseRest(raw) }.getOrNull()
                }
            },
            liveAccessToken = { ordinaryAccessToken() },
            finalAdmission = { owner, lease -> apiStack.admitOwnerLease(owner, lease) },
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
