package app.postmark.memory.ui.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.postmark.memory.capture.ProcessedStill
import app.postmark.memory.capture.SingleStillCaptureShell
import app.postmark.memory.capture.StillProcessor
import app.postmark.memory.ui.create.SenderIdentitySnapshot
import app.postmark.memory.create.SameAccountCapsulePublisher
import app.postmark.memory.create.SameAccountCapsuleRequest
import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AppNavigationController
import app.postmark.memory.ui.navigation.AuthUiState
import app.postmark.memory.wiring.KekBoundSecretSealer
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
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
import postmark.core.crypto.AccountIdentityGenerator
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide as DbFingerprintSide
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.EncryptedFingerprintStore
import postmark.core.data.outbox.CapsuleOutboxStager
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.UserId
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.RecognitionProfile

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
    private lateinit var database: PostmarkLocalDatabase
    private lateinit var filesRoot: File

    private val identity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("6a111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("6a222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("6a333333-4444-4555-8666-777777777777")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(context, PostmarkLocalDatabase::class.java, "scan-reentry.db")
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "scan-reentry").apply { mkdirs() }
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
        File(filesRoot, "fingerprints"),
        KekBoundSecretSealer(app.postmark.memory.auth.SoftwareKekBoundary(), KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS),
        database.recognitionFingerprintDao(),
    )

    private fun syntheticFingerprint(seed: Int, side: FingerprintSide): ByteArray {
        val profile = RecognitionProfile.mvpOrbV1()
        val keypoints = List(64) {
            postmark.core.recognition.FingerprintKeypoint(
                xNormalized = (it % 8) / 8.0,
                yNormalized = (it / 8) / 8.0,
                scaleNormalized = 1.0,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        return postmark.core.recognition.FingerprintCodec.serialize(
            postmark.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                side = side,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = seed.toLong(),
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() }
                },
                quality = postmark.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
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
        signingPublicExports = { identity.signingPublicKeyset },
        grantsClockMillis = { clock },
        frontProcessor = MatchingProcessor(syntheticFingerprint(11, FingerprintSide.FRONT)),
        backProcessor = MatchingProcessor(syntheticFingerprint(22, FingerprintSide.BACK)),
    )

    private fun capturingShell() = SingleStillCaptureShell().apply {
        onPermissionResult(granted = true, canAskAgain = false)
        onPreviewBound()
        onCaptureStarted()
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
        vm.onFrontJpeg("front".toByteArray(), capturingShell())
        vm.onBackJpeg("back".toByteArray(), capturingShell())
        awaitCondition { vm.terminal.value is ScanTerminalState.Granted }
        val granted = vm.terminal.value as ScanTerminalState.Granted
        assertTrue(granted.capsuleId == capsuleUuid.toString())

        // Leave the flow; re-enter (epoch bump) => everything fresh.
        vm.beginSession(epoch = 2L)

        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(app.postmark.memory.scan.ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
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
        vm.onFrontJpeg("front".toByteArray(), capturingShell())

        vm.beginSession(epoch = 1L)

        assertEquals(app.postmark.memory.scan.ScanSessionState.AWAITING_BACK, vm.captureSession.state)
        database.close()
    }
}
