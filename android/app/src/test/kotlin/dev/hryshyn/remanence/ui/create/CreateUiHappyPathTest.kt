package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.FakeStillCameraAdapter
import dev.hryshyn.remanence.capture.PreparedBackItem
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillCameraAdapter
import dev.hryshyn.remanence.capture.StillProcessor
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-STATE-08 (A): the create happy path driven through the REAL production
 * UI - typed handle lookup, explicit confirmation, FRONT capture through the
 * camera adapter seam, checklist boxes + Continue, BACK capture, three photos
 * via the production picker sink, note input, publish - ending UPLOAD_PENDING with
 * the capsule staged in the durable outbox. No ViewModel method is called
 * that the UI itself would not call.
 */
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
                coarseHash64 = 5L,
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 13 + i * 7) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        ),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CreateUiHappyPathTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("9a111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("9a333333-4444-4555-8666-777777777777")

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

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(userUuid),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(bundleUuid),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url =
            com.google.crypto.tink.subtle.Base64.urlSafeEncode(identity.encryptionPublicKeyset),
        signingPublicKeysetB64Url =
            com.google.crypto.tink.subtle.Base64.urlSafeEncode(identity.signingPublicKeyset),
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private inner class SelfDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.Found(selfSnapshot())
    }

    private class AcceptingProcessor(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = synthetic(side)
    }


    private class RecordingPersistence : SealedFingerprintPersistence {
        val stored = mutableMapOf<String, ByteArray>()
        var counter = 0

        override suspend fun persist(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            val id = "fp-${++counter}"
            stored[id] = plaintextBytes
            return id
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = stored.isNotEmpty()

        override suspend fun decrypt(fingerprintId: String): ByteArray =
            requireNotNull(stored[fingerprintId])

        override suspend fun setPreferredPair(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    @Test
    fun fullCreateHappyPathThroughTheRealSurfaceEndsUploadPending() = runBlocking {
        val persistence = RecordingPersistence()
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir()))
        val vm = CreateViewModel(
            directory = SelfDirectory(),
            accessTokenProvider = { "test-token" },
            identityProvider = {
                SenderIdentitySnapshot(
                    userId = userUuid.toString(),
                    handle = "mykola",
                    activeKeyBundleId = bundleUuid.toString(),
                    encryptionPrivateHandle = identity.encryptionPrivateHandle,
                    signingPrivateHandle = identity.signingPrivateHandle,
                )
            },
            persistence = persistence,
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir()), retryStore),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir()),
            openPhotoSource = { id ->
                dev.hryshyn.remanence.create.PhotoSource {
                    java.io.ByteArrayInputStream("photo-$id".toByteArray())
                }
            },
            frontProcessor = AcceptingProcessor(FingerprintSide.FRONT),
            backProcessor = AcceptingProcessor(FingerprintSide.BACK),
            photoNormalizer = { input -> dev.hryshyn.remanence.create.NormalizedPhotoDto(input.copyOf(), 800, 600) },
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
        vm.beginSession(1L, userUuid.toString())

        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        composeRule.setContent {
            MaterialTheme {
                CreateScreen(
                    viewModel = vm,
                    adapterFactory = {
                        FakeStillCameraAdapter().also { live.set(it) }
                    },
                    requestPermissionOnAttach = false,
                )
            }
        }

        fun readyCamera() {
            composeRule.waitForIdle()
            composeRule.runOnIdle { live.get()?.emitReady() }
            composeRule.waitForIdle()
        }

        fun scroll(tag: String) {
            composeRule.onNodeWithTag("create_screen_scroll").performScrollToNode(hasTestTag(tag))
        }

        // 1) Lookup the recipient handle.
        scroll("create_handle_input")
        composeRule.onNodeWithTag("create_handle_input").performTextInput("mykola")
        composeRule.onNodeWithTag("create_lookup_button").performClick()
        composeRule.waitForIdle()

        // 2) Explicit confirmation of the resolved snapshot.
        composeRule.onNodeWithTag("confirm_ack_checkbox").performClick()
        composeRule.onNodeWithTag("confirm_button").performClick()

        // 3) FRONT capture through the camera seam.
        composeRule.runOnIdle {
            vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        }
        readyCamera()
        scroll("capture_shutter_front")
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("front-frame".toByteArray()) }
        composeRule.waitForIdle()

        // 4) Checklist boxes + Continue REALLY reach BACK.
        PreparedBackItem.entries.forEach { item ->
            scroll("checklist_${item.name}")
            composeRule.onNodeWithTag("checklist_${item.name}").performClick()
        }
        scroll("create_back_ready")
        composeRule.onNodeWithTag("create_back_ready").performClick()

        // 5) BACK capture.
        composeRule.runOnIdle {
            vm.backAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        }
        readyCamera()
        scroll("capture_shutter_back")
        composeRule.onNodeWithTag("capture_shutter_back").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("back-frame".toByteArray()) }
        composeRule.waitForIdle()

        // 6) Content: three photos via THE production sink + note input.
        scroll("create_pick_photos")
        composeRule.runOnIdle { vm.onPhotosPicked(listOf("u1", "u2", "u3")) }
        composeRule.onNodeWithTag("create_note_input").performTextInput("dear mama")

        // 7) Publish.
        scroll("create_publish")
        composeRule.onNodeWithTag("create_publish")
            .assertIsDisplayed()
            .performClick()

        // Durable staging completes on Room's own executors; await terminal.
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) {
                error(
                    "publish never terminated; publishError=" + vm.publishError.value +
                        " flowError=" + vm.flowError.value,
                )
            } // bounded await: Room executors are not under the test clock
            Thread.sleep(20)
        }
        composeRule.waitForIdle()
        assertEquals(
            "publishError=" + vm.publishError.value + " flowError=" + vm.flowError.value,
            CreateViewModel.Step.UPLOAD_PENDING,
            vm.step.value,
        )
        composeRule.onNodeWithTag("create_upload_pending").assertIsDisplayed()

        val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(vm.capsuleId, userUuid.toString())
        assertTrue(row != null)
        assertEquals(OutboxCapsuleState.ENCRYPTED, row!!.state)
        assertEquals(2, persistence.stored.size)
        Unit
    }

    private fun stagingDir() =
        java.io.File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "ui-happy-staging",
        )
}
