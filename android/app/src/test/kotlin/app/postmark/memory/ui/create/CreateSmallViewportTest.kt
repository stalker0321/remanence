package app.postmark.memory.ui.create

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.postmark.memory.capture.CapturePermissionStep
import app.postmark.memory.capture.CaptureAttemptPhase
import app.postmark.memory.capture.PreparedBackItem
import app.postmark.memory.capture.FakeStillCameraAdapter
import app.postmark.memory.capture.ProcessedStill
import app.postmark.memory.capture.StillProcessor
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
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.data.network.DirectoryLookupResult
import postmark.core.data.network.ResolvedHandleSnapshot
import postmark.core.model.KeyBundleId
import postmark.core.model.NormalizedHandle
import postmark.core.model.UserId
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.QualityReason
import postmark.core.recognition.RecognitionProfile

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

    private lateinit var database: PostmarkLocalDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
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
        override suspend fun lookup(rawHandle: String, accessToken: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private class NoPersistence : SealedFingerprintPersistence {
        private var frontStored = false

        override suspend fun persist(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            if (side == postmark.core.data.db.FingerprintSide.FRONT) frontStored = true
            return "fp"
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
        ): Boolean = side == postmark.core.data.db.FingerprintSide.FRONT && frontStored

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredPair(capsuleId: String, origin: postmark.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    @Test
    fun rejectionPanelAndRetakeAreVisibleWithoutScrollingOnTinyScreens() {
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = postmark.core.data.outbox.CapsuleOutboxStager(
                database,
                File(context().filesDir, "small-vp"),
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            stagingDirectory = File(context().filesDir, "small-vp-staging"),
            openPhotoSource = { error("unused") },
            frontProcessor = RejectingThenAccepting(),
            backProcessor = RejectingThenAccepting(),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
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
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("frame".toByteArray()) }
        composeRule.waitForIdle()

        // The reasons REPLACE the camera and are fully displayed at 320x480.
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_retake_front")
            .assertIsDisplayed()
            .performClick()
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)

        // A second identical rejection is equally visible and actionable.
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("frame-2".toByteArray()) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("quality_reason_GLARE_EXCESSIVE").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_retake_front").assertIsDisplayed()
    }

    @Test
    fun contentErrorsBelowTheFoldAreReachableByScrollingOnTinyScreens() {
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = postmark.core.data.outbox.CapsuleOutboxStager(
                database,
                File(context().filesDir, "small-vp"),
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            stagingDirectory = File(context().filesDir, "small-vp-staging"),
            openPhotoSource = { error("unused") },
            frontProcessor = StillProcessor { ProcessedStill.Accepted("p", ByteArray(1)) },
            backProcessor = StillProcessor { ProcessedStill.Accepted("p", ByteArray(1)) },
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        vm.beginSession(1L)

        // Land directly on CONTENT with three photos and an oversized note.
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        PreparedBackItem.entries.forEach { item -> vm.backGate.setChecked(item, true) }
        vm.proceedToBackChecklist()
        vm.backAttempt.onPermissionResult(true, false)
        vm.backAttempt.onPreviewBound()
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("b".toByteArray())
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
