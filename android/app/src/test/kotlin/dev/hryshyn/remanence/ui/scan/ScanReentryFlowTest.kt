package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.CapsulePublishRequest
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AppNavigationController
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.session.SessionState
import dev.hryshyn.remanence.session.SessionStateResolver
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.recognition.ScanGrantManager

/**
 * FIX-REVIEW-02 regression for Scan: after a REAL scan reaches a verified
 * grant (real publisher/stager, real Room index, real crypto gate), leaving
 * and RE-ENTERING the flow starts over at FRONT and can never reopen the old
 * Granted - the terminal is cleared and the guarded route refuses the stale
 * grant string without a new scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanReentryFlowTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var roots: AccountScopedFileRoots

    private val identity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("6a111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("6a222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("6a333333-4444-4555-8666-777777777777")
    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, "scan-reentry.db")
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "scan-reentry").apply { mkdirs() }
        roots = AccountScopedFileRoots(filesRoot)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        context.getDatabasePath("scan-reentry.db").parentFile?.listFiles()
            ?.filter { it.name.startsWith("scan-reentry.db") }
            ?.forEach { it.delete() }
        filesRoot.deleteRecursively()
    }

    private fun store() = EncryptedFingerprintStore(
        roots,
        KekBoundSecretSealer(dev.hryshyn.remanence.auth.SoftwareKekBoundary(), KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS),
        database.recognitionFingerprintDao(),
        ownerUserIdProvider = { userUuid.toString() },
    )

    private fun syntheticFingerprint(seed: Int, side: FingerprintSide): ByteArray {
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
        return dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(
            dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = seed.toLong(),
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        )
    }

    /** Accepts every still with the SAME fingerprint the sender staged. */
    private class MatchingProcessor(private val bytes: ByteArray) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Accepted(profileId = RecognitionProfile.mvpOrbV1().profileId, serializedBytes = bytes)
    }

    private suspend fun stagePublishedCapsule() {
        store().persist(
            capsuleUuid.toString(), FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId,
            syntheticFingerprint(11, FingerprintSide.FRONT),
        )
        val prepared = CapsulePublisher(testWrapper, testAlias).publish(
            CapsulePublishRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(userUuid),
                recipientUserId = UserId(userUuid),
                senderKeyBundleId = KeyBundleId(bundleUuid),
                recipientKeyBundleId = KeyBundleId(bundleUuid),
                ownerUserId = userUuid.toString(),
                senderHandleSnapshot = "mykola",
                createdAtEpochSeconds = 1_700_000_000L,
                photoJpegs = (0 until 3).map { "photo-$it".toByteArray() },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = null,
                frontFingerprintBytes = syntheticFingerprint(11, FingerprintSide.FRONT),
                backFingerprintBytes = syntheticFingerprint(22, FingerprintSide.BACK),
                signingKeyset = identity.signingPrivateHandle,
                recipientEncryptionPublicKeyset =
                    TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset),
            ),
        )
        CapsuleOutboxStager(database, roots, SenderRetryMaterialStore(roots)).stage(prepared)
    }

    private fun scanViewModel(
        clock: Long = 0L,
        grants: ScanGrantManager = ScanGrantManager(clockMillis = { clock }),
        presentationGrants: dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority =
            dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(grants),
    ): ScanViewModel = ScanViewModel(
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
            // FIX-REVIEW2-04: trusted boundary wired to the provable self
            // account; corrupt rows below fail before this is ever consulted.
            trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
                directoryFetch = { error("self-send verification must not touch the network") },
                ownAccount = {
                    dev.hryshyn.remanence.identity.DirectorySenderKeyStore.OwnAccount(
                        userId = UserId(userUuid),
                        activeKeyBundleId = KeyBundleId(bundleUuid),
                        publicSigningExportB64Url =
                            com.google.crypto.tink.subtle.Base64.urlSafeEncode(identity.signingPublicKeyset),
                    )
                },
            ),
        presentationGrants = presentationGrants,
        candidateIndexProvider = { ScanCandidateIndex.EMPTY },
        incomingPresentationPreparation = null,
        frontProcessor = MatchingProcessor(syntheticFingerprint(11, FingerprintSide.FRONT)),
        backProcessor = MatchingProcessor(syntheticFingerprint(22, FingerprintSide.BACK)),
        cpuDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
    )

    /** FIX-STATE-01: production-shaped delivery through the authoritative controllers. */
    private fun capturePairThroughRealDelivery(vm: ScanViewModel) {
        listOf(vm.frontAttempt, vm.backAttempt).forEach {
            it.onPermissionResult(granted = true, canAskAgain = false)
            it.onPreviewBound()
        }
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        awaitCondition { vm.captureSession.state == dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_BACK }
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("back".toByteArray())
    }

    /** The Room-backed match+verify chain completes off-thread; await it. */
    private fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not reached in time")
            Thread.sleep(20)
        }
    }

    @Test
    fun verifiedGrantThenReentryAlwaysStartsFreshAtFrontAndNeverReopensOldGranted() = runBlocking {
        stagePublishedCapsule()
        val vm = scanViewModel()

        // First session: FRONT then BACK, real matching over the real index.
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        capturePairThroughRealDelivery(vm)
        awaitCondition { vm.terminal.value is ScanTerminalState.Granted }
        val granted = vm.terminal.value as ScanTerminalState.Granted
        assertTrue(granted.capsuleId == capsuleUuid.toString())

        // Leave the flow; re-enter (epoch bump) => everything fresh.
        vm.beginSession(epoch = 2L)

        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertNull(vm.captureSession.front)
        assertNull(vm.captureSession.back)

        // The old Granted can never reopen without a new scan: navigation to
        // its route lands on Home because no live access exists anymore.
        val controller = AppNavigationController(AuthUiState.Authenticated("u", "mykola"))
        controller.navigate(AppDestination.Capsule(granted.grantId))
        assertEquals(AppDestination.Home, controller.current)

        // And the same manager instance refuses the consumed/unknown grant.
        assertNull(vm.liveGrantCapsuleId(granted.grantId, UserId(userUuid)))

        database.close()
    }

    @Test
    fun sameEpochBeginIsANoOpSoRotationKeepsTheScanSession() = runBlocking {
        stagePublishedCapsule()
        val vm = scanViewModel()
        vm.beginSession(epoch = 1L)
        // FIX-STATE-01: a fresh session resets the authoritative controllers;
        // the surface re-resolves permission and rebinds before any attempt.
        vm.frontAttempt.onPermissionResult(true, canAskAgain = false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())

        vm.beginSession(epoch = 1L)

        assertEquals(dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_BACK, vm.captureSession.state)
        database.close()
    }

    /**
     * ANDROID-HOTFIX-A regression: model the production Scan -> guarded
     * Capsule -> close -> Scan sequence. The first scan's adapter callback is
     * delivered after the capsule closes and the next epoch has reset the
     * retained VM; it must be inert, while the new epoch remains FRONT-first.
     */
    @Test
    fun capsuleCloseThenScanDropsLateCallbackFromPreviousBinding() = runBlocking {
        stagePublishedCapsule()
        val grants = ScanGrantManager(clockMillis = { 0L })
        val presentationGrants =
            dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(grants)
        val vm = scanViewModel(grants = grants, presentationGrants = presentationGrants)
        val root = RootViewModel(
            sessionBootstrap = object : SessionStateResolver {
                override suspend fun bootstrap() =
                    SessionState.Active(userUuid.toString(), "mykola", true, true)

                override suspend fun logout() = SessionState.SignedOut
            },
            presentationGrants = presentationGrants,
            clockMillis = { 0L },
        )

        root.openScan()
        vm.beginSession(root.scanSessionEpoch.value)
        capturePairThroughRealDelivery(vm)
        awaitCondition { vm.terminal.value is ScanTerminalState.Granted }
        val granted = vm.terminal.value as ScanTerminalState.Granted
        root.openCapsuleWithGrant(granted.grantId)
        assertEquals(AppDestination.Capsule(granted.grantId), root.destination.value)

        // Model a camera bind that is still pending when the route is left.
        // ScanScreen.onDispose performs this reset while its old callback may
        // still be queued. Capture the old binding's ownership before reset.
        vm.frontAttempt.restartCapture()
        val oldBindingToken = vm.frontAttempt.currentBindingCallbackToken()
        vm.resetSession()
        assertEquals(dev.hryshyn.remanence.capture.CaptureAttemptPhase.Binding, vm.frontAttempt.phase)

        root.closeCapsule()
        assertEquals(AppDestination.Home, root.destination.value)
        root.openScan()
        vm.beginSession(root.scanSessionEpoch.value)

        assertEquals(dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertEquals(dev.hryshyn.remanence.capture.CapturePermissionStep.NotRequested, vm.frontAttempt.permission)
        assertFalse(vm.frontAttempt.onPreviewBound(oldBindingToken))
        assertEquals(null, vm.frontAttempt.phase)

        // The new epoch can bind normally after the stale callback is dropped.
        vm.frontAttempt.onPermissionResult(granted = true, canAskAgain = false)
        assertTrue(vm.frontAttempt.onPreviewBound())

        database.close()
    }
}
