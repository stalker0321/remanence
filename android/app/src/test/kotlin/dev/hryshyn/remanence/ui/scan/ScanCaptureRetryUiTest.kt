package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.FakeStillCameraAdapter
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillCameraAdapter
import dev.hryshyn.remanence.capture.StillProcessor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * FIX-STATE-04/05/08 regression: on the REAL ScanScreen, a quality rejection
 * renders its reasons INSTEAD of the camera with a real Retake button; the
 * SAME rejection can be repeated and every Retake starts a genuinely new
 * attempt; a processor exception surfaces as a visible failure. The fake
 * camera adapter excites exactly the production delivery callbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScanCaptureRetryUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
    }

    private class ScriptedProcessor(vararg script: Any) : StillProcessor {
        private val queue = ArrayDeque(script.toList())
        override fun process(jpegBytes: ByteArray): ProcessedStill {
            val next = queue.removeFirstOrNull() ?: error("unexpected delivery")
            return when (next) {
                is ProcessedStill -> next
                is String -> throw IllegalStateException(next)
                else -> error("bad script")
            }
        }
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    /** Real serialized fingerprint so an accepted still advances the session. */
    private fun synthetic(side: FingerprintSide): ProcessedStill.Accepted {
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
        return ProcessedStill.Accepted(
            profileId = profile.profileId,
            serializedBytes = dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(
                dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                    profileId = profile.profileId,
                    canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                    canonicalHeightPx = 1000,
                    coarseHash64 = 7L,
                    keypoints = keypoints,
                    descriptors = List(64) { i ->
                        ByteArray(32) { ((it * 7 + i * 13) and 0xFF).toByte() }
                    },
                    quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
                ),
            ),
        )
    }

    @Test
    fun rejectionRendersPanelRetakeWorksTwiceAndExceptionShowsFailure() {
        val front = ScriptedProcessor(
            ProcessedStill.Rejected(setOf(QualityReason.TOO_BLURRY)),
            ProcessedStill.Rejected(setOf(QualityReason.TOO_BLURRY)),
            "orb exploded",
            synthetic(FingerprintSide.FRONT),
        )
        val back = ScriptedProcessor(synthetic(FingerprintSide.BACK))
        val vm = ScanViewModel(
            persistence = NoPersistence(),
            database = database,
            profile = RecognitionProfile.mvpOrbV1(),
            identityProvider = { null },
            trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
                directoryFetch = { error("unreachable") },
                ownAccount = { null },
            ),
            presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
                dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
            ),
            frontProcessor = front,
            backProcessor = back,
            candidateIndexProvider = { ScanCandidateIndex.EMPTY },
            incomingPresentationPreparation = null,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

        // One fake adapter per binding cycle; keep the live one reachable.
        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        var bindCount = 0

        composeRule.setContent {
            MaterialTheme {
                ScanScreen(
                    viewModel = vm,
                    requestPermissionOnAttach = false,
                    adapterFactory = {
                        bindCount += 1
                        FakeStillCameraAdapter().also { live.set(it) }
                    },
                )
            }
        }

        /** Lets the surface finish its binding cycle, then completes it. */
        fun readyCamera() {
            composeRule.waitForIdle()
            composeRule.runOnIdle { live.get()?.emitReady() }
            composeRule.waitForIdle()
        }

        fun shutter() {
            composeRule.onNodeWithTag("capture_shutter_front").assertIsDisplayed()
            composeRule.onNodeWithTag("capture_shutter_front").performClick()
        }

        fun deliver(frame: String) {
            composeRule.runOnIdle {
                live.get()!!.deliverFrame(frame.toByteArray())
            }
            composeRule.waitForIdle()
        }

        // Resolve permission exactly like a remembered system grant.
        composeRule.runOnIdle {
            listOf(vm.frontAttempt).forEach { attempt ->
                attempt.onPermissionResolved(CapturePermissionStep.Granted)
            }
        }
        readyCamera()

        // First TOO_BLURRY: reasons REPLACE the camera; Retake is real.
        shutter()
        deliver("frame-1")

        composeRule.onNodeWithTag("capture_terminal_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY").assertIsDisplayed()
        composeRule.onNodeWithTag("quality_failure_header").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("capture_shutter_front").fetchSemanticsNodes().isEmpty(),
        )

        val firstRejection = (vm.frontAttempt.phase as CaptureAttemptPhase.Rejected)

        // Retake -> fresh binding -> identical rejection is a NEW attempt.
        composeRule.onNodeWithTag("capture_retake_front").performClick()
        composeRule.waitForIdle()
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        readyCamera()

        shutter()
        deliver("frame-2")
        val secondRejection = (vm.frontAttempt.phase as CaptureAttemptPhase.Rejected)
        assertNotEquals(firstRejection.attemptId, secondRejection.attemptId)
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY").assertIsDisplayed()

        // Processor exception -> visible Failed message + working Retake.
        composeRule.onNodeWithTag("capture_retake_front").performClick()
        composeRule.waitForIdle()
        readyCamera()
        shutter()
        deliver("frame-3")

        composeRule.onNodeWithTag("capture_failed_message").assertIsDisplayed()
        assertTrue(vm.frontAttempt.phase is CaptureAttemptPhase.Failed)

        // Retry after failure is accepted: the BACK surface mounts.
        composeRule.onNodeWithTag("capture_retake_front").performClick()
        composeRule.waitForIdle()
        readyCamera()
        shutter()
        deliver("frame-4")

        composeRule.waitForIdle()
        val dbg = "phase=" + vm.frontAttempt.phase +
            " session=" + vm.captureSession.state +
            " binds=" + bindCount
        assertEquals(dbg, dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_BACK, vm.captureSession.state)
        assertTrue(bindCount >= 4)
    }
}
