package dev.hryshyn.remanence.session

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.FakeStillCameraAdapter
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.ui.create.CreateScreen
import dev.hryshyn.remanence.ui.create.RecipientDirectoryPort
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import java.io.File
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * FIX-STATE-12 regression: THE production root hosts the REAL create surface.
 * The header (Back to Home) stays visible while the flow body receives only
 * the remaining height, and the body's bottom recovery actions stay reachable
 * by scrolling inside that bounded region on a small screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class RootFlowLayoutTest {

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

    private class RejectingProcessor : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Rejected(setOf(QualityReason.TOO_BLURRY))
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String, accessToken: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private class NoPersistence : SealedFingerprintPersistence {
        private var frontStored = false

        override suspend fun persist(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            if (side == dev.hryshyn.remanence.core.data.db.FingerprintSide.FRONT) frontStored = true
            return "fp"
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = side == dev.hryshyn.remanence.core.data.db.FingerprintSide.FRONT && frontStored

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredPair(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private fun newCreateViewModel(): CreateViewModel = CreateViewModel(
        directory = StaticDirectory(),
        accessTokenProvider = { null },
        identityProvider = { null },
        persistence = NoPersistence(),
        outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
            database,
            dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "root-flow")),
            SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(context().filesDir, "root-flow"))),
        ),
        profile = RecognitionProfile.mvpOrbV1(),
        accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(
            File(context().filesDir, "root-flow-staging"),
        ),
        openPhotoSource = { error("unused") },
        frontProcessor = RejectingProcessor(),
        backProcessor = RejectingProcessor(),
        cpuDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
        senderRetryKeysetWrapper = testWrapper,
        senderRetryKekAlias = testAlias,
        enqueueUpload = { _, _ -> },
    ).also { it.beginSession(1L) }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun rootKeepsHeaderVisibleWhileFlowBodyScrollsItsRecoveryIntoView() {
        val vm = newCreateViewModel()
        // Straight to FRONT through legal recipient events.
        vm.onResolved(SelfSnapshots.self())
        vm.confirmRecipient()

        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        var exited = false
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = AppDestination.Create,
                    authenticationContent = {},
                    homeContent = {},
                    createContent = {
                        CreateScreen(
                            viewModel = vm,
                            adapterFactory = { FakeStillCameraAdapter().also { live.set(it) } },
                            requestPermissionOnAttach = false,
                        )
                    },
                    scanContent = {},
                    onExitFlow = { exited = true },
                )
            }
        }

        composeRule.waitForIdle()

        // 1) The header is present and VISIBLE above everything else.
        composeRule.onNodeWithTag("flow_exit_create")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .let { headerBounds ->
                val shutterTop = composeRule.runCatching {
                    composeRule.onAllNodesWithTag("capture_shutter_front")
                        .fetchSemanticsNodes()
                        .firstOrNull()
                        ?.boundsInRoot?.top
                }
                // The flow body starts BELOW the header, never over it.
                assertTrue(
                    "body must start below the header; headerBottom=" +
                        headerBounds.boundsInRoot.bottom + " shutterTop=" + shutterTop.getOrNull(),
                    shutterTop.getOrNull() == null ||
                        shutterTop.getOrNull()!! >= headerBounds.boundsInRoot.bottom - 1f,
                )
            }

        // 2) Drive one capture attempt into rejection through the camera seam.
        composeRule.runOnIdle {
            vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()

        composeRule.scrollToTag("capture_shutter_front")
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("frame".toByteArray()) }
        composeRule.waitForIdle()

        // 3) The bottom recovery action is reachable by scrolling INSIDE the
        // flow body, while the header stays visible at the same time.
        composeRule.onNodeWithTag("create_screen_scroll")
            .performScrollToNode(hasTestTag("capture_retake_front"))

        val viewportHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val retakeBounds = composeRule.onNodeWithTag("capture_retake_front")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "retake must be fully inside the viewport",
            retakeBounds.top >= 0f && retakeBounds.bottom <= viewportHeight + 1f,
        )

        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY").assertIsDisplayed()

        // Header STILL displayed after all scrolling - it owns its own space,
        // and its action actually exits the flow.
        composeRule.onNodeWithText("Back to Home")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue("exit must fire", exited) }

    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.scrollToTag(tag: String) {
        onNodeWithTag("create_screen_scroll").performScrollToNode(hasTestTag(tag))
    }
}

private object SelfSnapshots {
    fun self(): dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot =
        dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot(
            userId = dev.hryshyn.remanence.core.model.UserId.parseRest("9f111111-2222-4333-8444-555555555555"),
            handle = dev.hryshyn.remanence.core.model.NormalizedHandle.parse("mykola"),
            keyBundleId = dev.hryshyn.remanence.core.model.KeyBundleId.parseRest("9f333333-4444-4555-8666-777777777777"),
            suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            protocolVersion = 1,
            encryptionPublicKeysetB64Url = "enc",
            signingPublicKeysetB64Url = "sig",
            keyBundleStatus = "ACTIVE",
            directoryVersion = "v1",
        )
}
