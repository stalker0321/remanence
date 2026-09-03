package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.FakeStillCameraAdapter
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-STATE-08 (D) / FIX-STATE-04: on a 320dp-wide, 480dp-tall phone every
 * error message and its action stay reachable - the rejection panel replaces
 * the camera, and scrollable step content brings lower controls into view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CreateSmallViewportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
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

    private class RejectingThenAccepting : StillProcessor {
        private var calls = 0
        override fun process(jpegBytes: ByteArray): ProcessedStill {
            calls += 1
            return if (calls == 1) {
                ProcessedStill.Rejected(setOf(QualityReason.TOO_BLURRY))
            } else {
                ProcessedStill.Rejected(setOf(QualityReason.GLARE_EXCESSIVE))
            }
        }
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private class NoPersistence : SealedFingerprintPersistence {
        private var frontStored = false

        override suspend fun persist(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            frontStored = true
            return "fp"
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = frontStored

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    @Test
    fun rejectionPanelAndRetakeAreReachableByScrollingOnTinyScreens() {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "small-vp")))
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "small-vp")),
                retryStore,
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(
                File(context().filesDir, "small-vp-staging"),
            ),
            openPhotoSource = { error("unused") },
            frontProcessor = RejectingThenAccepting(),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
        vm.beginSession(1L)

        // Reach the FRONT capture step through legal recipient events first.
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()

        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        composeRule.setContent {
            MaterialTheme {
                CreateScreen(
                    viewModel = vm,
                    adapterFactory = { FakeStillCameraAdapter().also { live.set(it) } },
                    requestPermissionOnAttach = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        }
        composeRule.waitForIdle()
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()
        // The shutter exists only when the authoritative phase reached Ready.
        val dbg = "phase=" + vm.frontAttempt.phase
        assertTrue(
            dbg,
            composeRule.onAllNodesWithTag("capture_shutter_front").fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.scrollToTag("capture_shutter_front")
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("frame".toByteArray()) }
        composeRule.waitForIdle()

        // The reasons REPLACE the camera; on a 480dp-tall screen they sit
        // below the fold and MUST be reachable by scrolling.
        assertEquals(
            "delivery must reject on first frame",
            dev.hryshyn.remanence.capture.CaptureAttemptPhase.Rejected::class,
            vm.frontAttempt.phase?.let { it::class },
        )
        composeRule.scrollToTag("quality_reason_TOO_BLURRY")
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY").assertIsDisplayed()
        composeRule.scrollToTag("capture_retake_front")
        composeRule.onNodeWithTag("capture_retake_front")
            .assertIsDisplayed()
            .performClick()
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)

        // A second identical rejection is equally visible and actionable.
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.scrollToTag("capture_shutter_front")
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("frame-2".toByteArray()) }
        composeRule.waitForIdle()

        composeRule.scrollToTag("quality_reason_GLARE_EXCESSIVE")
        composeRule.onNodeWithTag("quality_reason_GLARE_EXCESSIVE").assertIsDisplayed()
        composeRule.scrollToTag("capture_retake_front")
        composeRule.onNodeWithTag("capture_retake_front").assertIsDisplayed()
    }

    @Test
    fun contentErrorsBelowTheFoldAreReachableByScrollingOnTinyScreens() {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "small-vp")))
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "small-vp")),
                retryStore,
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(
                File(context().filesDir, "small-vp-staging"),
            ),
            openPhotoSource = { error("unused") },
            frontProcessor = StillProcessor { ProcessedStill.Accepted("p", ByteArray(1)) },
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
        vm.beginSession(1L)

        // Land directly on CONTENT with three photos and an oversized note.
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        vm.onPhotosPicked(listOf("a", "b", "c"))
        vm.noteEditor.onChange("x".repeat(NoteEditorState.MAX_NOTE_BYTES + 1))

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        composeRule.setContent {
            MaterialTheme { CreateScreen(viewModel = vm) }
        }
        composeRule.waitForIdle()
        assertTrue(
            "limitReached=" + vm.noteEditor.limitReached,
            composeRule.onAllNodesWithTag("create_note_limit_error").fetchSemanticsNodes().size > 0 ||
                !vm.noteEditor.limitReached,
        )

        // The limit error sits below the fold; scrolling MUST reach it.
        composeRule.onNodeWithTag("create_screen_scroll")
            .performScrollToNode(hasTestTag("create_note_limit_error"))
        composeRule.onNodeWithTag("create_note_limit_error").assertIsDisplayed()
    }


    @Test
    fun capturePreviewHasDeterministicNonzeroHeightAndRecoveryStaysReachable() {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "small-vp")))
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "small-vp")),
                retryStore,
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(
                File(context().filesDir, "small-vp-staging"),
            ),
            openPhotoSource = { error("unused") },
            frontProcessor = RejectingThenAccepting(),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
        vm.beginSession(1L)
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()

        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        composeRule.setContent {
            MaterialTheme {
                CreateScreen(
                    viewModel = vm,
                    adapterFactory = { FakeStillCameraAdapter().also { live.set(it) } },
                    requestPermissionOnAttach = false,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()

        // FIX-STATE-09: the camera area is DETERMINISTICALLY nonzero even in
        // the tiny viewport.
        val dbgPhase = vm.frontAttempt.phase.toString()
        val previewBounds = composeRule.onNodeWithTag("capture_preview")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "phase=$dbgPhase preview height must be >= MIN(${dev.hryshyn.remanence.capture.CAPTURE_PREVIEW_MIN_HEIGHT}), was ${previewBounds.height} bounds=$previewBounds",
            previewBounds.height >= dev.hryshyn.remanence.capture.CAPTURE_PREVIEW_MIN_HEIGHT.value,
        )

        // Shutter sits directly BELOW the nonzero preview area after the
        // existing flow scroll brings it into view.
        composeRule.scrollToTag("capture_shutter_front")
        val scrolledPreviewBounds = composeRule.onNodeWithTag("capture_preview")
            .fetchSemanticsNode().boundsInRoot
        val shutterBounds = composeRule.onNodeWithTag("capture_shutter_front")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "shutter must be below the preview, shutter=$shutterBounds preview=$scrolledPreviewBounds",
            shutterBounds.top >= scrolledPreviewBounds.bottom - 1f,
        )

        // Rejection replaces the preview; reasons + Retake stay reachable and
        // inside the viewport.
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("frame".toByteArray()) }
        composeRule.waitForIdle()

        composeRule.scrollToTag("quality_reason_TOO_BLURRY")
        assertEquals(
            "delivery must reject on first frame",
            dev.hryshyn.remanence.capture.CaptureAttemptPhase.Rejected::class,
            vm.frontAttempt.phase?.let { it::class },
        )
        composeRule.scrollToTag("quality_reason_TOO_BLURRY")
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY").assertIsDisplayed()
        composeRule.scrollToTag("capture_retake_front")
        val viewportHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val retakeBounds = composeRule.onNodeWithTag("capture_retake_front")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "retake must be inside the viewport",
            retakeBounds.bottom <= viewportHeight + 1f,
        )
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.scrollToTag(tag: String) {
        onNodeWithTag("create_screen_scroll").performScrollToNode(hasTestTag(tag))
    }

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(UUID.fromString("9d111111-2222-4333-8444-555555555555")),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(UUID.fromString("9d333333-4444-4555-8666-777777777777")),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "enc",
        signingPublicKeysetB64Url = "sig",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
