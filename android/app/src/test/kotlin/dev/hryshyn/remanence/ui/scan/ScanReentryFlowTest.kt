package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.create.SameAccountCapsulePublisher
import dev.hryshyn.remanence.create.SameAccountCapsuleRequest
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AppNavigationController
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide as DbFingerprintSide
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

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

    @Before
    fun setUp() {
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
                side = side,
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
            capsuleUuid.toString(), DbFingerprintSide.FRONT, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId,
            syntheticFingerprint(11, FingerprintSide.FRONT),
        )
        store().persist(
            capsuleUuid.toString(), DbFingerprintSide.BACK, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId,
            syntheticFingerprint(22, FingerprintSide.BACK),
        )
        val prepared = SameAccountCapsulePublisher().publish(
            SameAccountCapsuleRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(userUuid),
                senderKeyBundleId = KeyBundleId(bundleUuid),
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
        CapsuleOutboxStager(database, File(filesRoot, "outbox")).stage(prepared)
    }

    private fun scanViewModel(clock: Long = 0L): ScanViewModel = ScanViewModel(
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
        grantsClockMillis = { clock },
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
        assertNull(vm.liveGrantCapsuleId(granted.grantId))

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
}
