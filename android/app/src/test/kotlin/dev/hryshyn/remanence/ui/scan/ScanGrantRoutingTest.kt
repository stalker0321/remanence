package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.create.SameAccountCapsulePublisher
import dev.hryshyn.remanence.create.SameAccountCapsuleRequest
import dev.hryshyn.remanence.identity.DirectorySenderKeyStore
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.session.SessionState
import dev.hryshyn.remanence.session.SessionStateResolver
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.core.recognition.FingerprintSide as RecognitionSide

/**
 * Physical-device regression for the verified-grant handoff: a REAL verified
 * scan (real publisher, real outbox, real identity, real acceptance gate)
 * must publish a terminal [ScanTerminalState.Granted] whose `grantId` is THE
 * exact UUID string issued into THE authoritative shared [ScanGrantManager] -
 * not an object rendering - so `RootViewModel.openCapsuleWithGrant` parses it,
 * resolves it through the SAME manager instance, and reaches
 * [AppDestination.Capsule]. Previously the data-class `toString()` leaked into
 * navigation and the root silently refused, stranding the flow on
 * "Verified. Opening the capsule...".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanGrantRoutingTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var outboxDir: File

    private val identity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("5f111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("5f222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("5f333333-4444-4555-8666-777777777777")

    private var now = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "grant-routing-artifacts").apply { mkdirs() }
        roots = AccountScopedFileRoots(filesRoot)
        outboxDir = File(filesRoot, "outbox").apply { mkdirs() }
        now = 1_000L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        filesRoot.deleteRecursively()
    }

    private class NoopResolver : SessionStateResolver {
        override suspend fun bootstrap(): SessionState =
            SessionState.Active("u", "mykola", true, true)

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    /** Accepts every still with EXACTLY the seeded side fingerprint bytes. */
    private class FixedProcessor(
        private val serializedBytes: ByteArray,
    ) : StillProcessor {
        private val profile = RecognitionProfile.mvpOrbV1()
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Accepted(profile.profileId, serializedBytes)
    }

    private fun store() = EncryptedFingerprintStore(
        roots,
        KekBoundSecretSealer(
            SoftwareKekBoundary(),
            KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS,
        ),
        database.recognitionFingerprintDao(),
        ownerUserIdProvider = { userUuid.toString() },
    )

    private fun syntheticFingerprint(seed: Int, side: RecognitionSide): ByteArray {
        val profile = RecognitionProfile.mvpOrbV1()
        val keypoints = List(64) {
            dev.hryshyn.remanence.core.recognition.FingerprintKeypoint(
                xNormalized = (it % 8) / 8.0,
                yNormalized = (it / 8) / 8.0,
                scaleNormalized = 1.0,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        val fp = dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
            profileId = profile.profileId,
            side = side,
            canonicalWidthPx = profile.capture.canonicalLongEdgePx,
            canonicalHeightPx = 1000,
            coarseHash64 = seed.toLong(),
            keypoints = keypoints,
            descriptors = List(64) { i -> ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() } },
            quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
        )
        return dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(fp)
    }

    /** Publishes one real self-send capsule whose fingerprints match the scan. */
    private suspend fun stagePublishedCapsule() {
        val front = syntheticFingerprint(11, RecognitionSide.FRONT)
        val back = syntheticFingerprint(22, RecognitionSide.BACK)
        store().persist(
            capsuleUuid.toString(), FingerprintSide.FRONT, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId, front,
        )
        store().persist(
            capsuleUuid.toString(), FingerprintSide.BACK, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId, back,
        )
        CapsuleOutboxStager(database, roots).stage(
            SameAccountCapsulePublisher().publish(
                SameAccountCapsuleRequest(
                    capsuleId = CapsuleId(capsuleUuid),
                    senderUserId = UserId(userUuid),
                    senderKeyBundleId = KeyBundleId(bundleUuid),
                    senderHandleSnapshot = "mykola",
                    createdAtEpochSeconds = 1_700_000_000L,
                    photoJpegs = (0 until 3).map { "grant-routing-photo-$it".toByteArray() },
                    photoWidthsPx = listOf(800, 800, 800),
                    photoHeightsPx = listOf(600, 600, 600),
                    noteUtf8 = null,
                    frontFingerprintBytes = front,
                    backFingerprintBytes = back,
                    signingKeyset = identity.signingPrivateHandle,
                    recipientEncryptionPublicKeyset =
                        TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset),
                ),
            ),
        )
    }

    /**
     * Production-shaped ScanViewModel: same construction surface as
     * PostmarkViewModelFactory, with fakes only at the camera/still seam and
     * THE given manager injected exactly once.
     */
    private fun scanViewModel(grantsManager: ScanGrantManager): ScanViewModel =
        ScanViewModel(
            persistence = store(),
            database = database,
            profile = RecognitionProfile.mvpOrbV1(),
            identityProvider = {
                SenderIdentitySnapshot(
                    userId = userUuid.toString(),
                    handle = "mykola",
                    activeKeyBundleId = bundleUuid.toString(),
                    encryptionPrivateHandle = identity.encryptionPrivateHandle,
                    signingPrivateHandle = identity.signingPrivateHandle,
                )
            },
            trustedSenderKeys = DirectorySenderKeyStore(
                directoryFetch = { error("self-send must resolve through the own account") },
                ownAccount = {
                    DirectorySenderKeyStore.OwnAccount(
                        userId = UserId(userUuid),
                        activeKeyBundleId = KeyBundleId(bundleUuid),
                        publicSigningExportB64Url = com.google.crypto.tink.subtle.Base64.urlSafeEncode(
                            identity.signingPublicKeyset,
                        ),
                    )
                },
            ),
            grantsClockMillis = { now },
            grants = grantsManager,
            frontProcessor = FixedProcessor(syntheticFingerprint(11, RecognitionSide.FRONT)),
            backProcessor = FixedProcessor(syntheticFingerprint(22, RecognitionSide.BACK)),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

    /** Drives THE authoritative capture controllers exactly as the surface does. */
    private fun readyCameras(vm: ScanViewModel) {
        listOf(vm.frontAttempt, vm.backAttempt).forEach { controller: CaptureAttemptController ->
            controller.onPermissionResult(granted = true, canAskAgain = false)
            assertEquals(CaptureAttemptPhase.Binding, controller.phase)
            controller.onPreviewBound()
            assertEquals(CapturePermissionStep.Granted, controller.permission)
        }
    }

    @Test
    fun verifiedTerminalGrantCarriesTheActiveManagerGrantAndNavigatesToCapsule() = runBlocking {
        // THE single manager instance both production VMs receive.
        val grantsManager = ScanGrantManager({ now })
        val scanVm = scanViewModel(grantsManager)
        val rootVm = RootViewModel(NoopResolver(), grants = grantsManager)

        try {
            stagePublishedCapsule()
            readyCameras(scanVm)
            assertTrue(scanVm.beginFrontCapture())
            scanVm.deliverFrontJpeg("jpeg-front".toByteArray())
            assertTrue(scanVm.beginBackCapture())
            scanVm.deliverBackJpeg("jpeg-back".toByteArray())

            // Matching hops through the real index/DAO executors; await THE
            // verified terminal outcome instead of racing it.
            val granted = withTimeout(10_000) {
                scanVm.terminal
                    .filterIsInstance<ScanTerminalState.Granted>()
                    .first()
            }
            assertEquals(capsuleUuid.toString(), granted.capsuleId)

            // THE defect regression: the terminal value must BE the manager's
            // active grant id - parseable and resolving to the scanned capsule.
            val parsed = runCatching { UUID.fromString(granted.grantId) }.getOrNull()
            assertNotNull("terminal grant id must parse as a UUID", parsed)
            assertEquals(capsuleUuid, grantsManager.resolveCapsuleId(parsed!!))

            // The production navigation effect runs with EXACTLY this value.
            rootVm.openCapsuleWithGrant(granted.grantId)
            assertEquals(AppDestination.Capsule(granted.grantId), rootVm.destination.value)
            assertEquals(capsuleUuid.toString(), rootVm.capsuleIdFor(granted.grantId))

            // Fail-closed navigation is unchanged: after close, the same exact
            // string can never reopen anything.
            rootVm.closeCapsule()
            rootVm.openCapsuleWithGrant(granted.grantId)
            assertEquals(AppDestination.Home, rootVm.destination.value)
        } finally {
            scanVm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            rootVm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    /**
     * Wiring proof at the factory boundary: RootViewModel and ScanViewModel
     * built by THE production factory share AppContainer.scanGrants - a grant
     * issued through the root's manager is live for the scan VM.
     */
    @Test
    fun factoryWiringGivesBothViewModelsTheContainerGrantManager() = runBlocking {
        val container = AppContainer(context, kekBoundaryOverride = SoftwareKekBoundary())
        val factory = RemanenceViewModelFactory(container)
        val rootVm = factory.create(RootViewModel::class.java)
        val scanVm = factory.create(ScanViewModel::class.java)

        try {
            assertSame(container.scanGrants, rootVm.scanGrants)
            val capsule = UUID.randomUUID()
            val issued = rootVm.scanGrants.issue(capsule)
            assertEquals(capsule.toString(), scanVm.liveGrantCapsuleId(issued.grantId.toString()))
            assertNull(scanVm.liveGrantCapsuleId(UUID.randomUUID().toString()))
        } finally {
            rootVm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            scanVm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
