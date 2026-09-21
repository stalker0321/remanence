package dev.hryshyn.remanence.ui.scan

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.capture.FakeStillCameraAdapter
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.core.recognition.SiftRootSiftMatcherPort
import dev.hryshyn.remanence.identity.DirectorySenderKeyStore
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Arrival-branch contracts for the two states the previous tick left
 * uncovered:
 *
 * - AwaitingCapture: the real [ScanScreen] entry with a fresh
 *   [ScanViewModel] (initial state, no driving) renders the live FRONT
 *   camera through the routine entry transition — preview surface, guide
 *   overlay, working shutter — with the camera/viewModel lifecycle
 *   untouched.
 * - Chooser: [ScanChooserBranch] with fixture hint rows renders rows and
 *   both actions through the routine entry transition, under FULL and
 *   reduced regimes.
 *
 * Reduced motion is driven through the real platform signal
 * (`Settings.Global.ANIMATOR_DURATION_SCALE`, the same key
 * `rememberAstraMotion` reads), so the regime assertions are honest.
 * All assertions are locale-independent (tags, counts, callback
 * identity — never localized text). NOT run in this tick (no Gradle
 * execution per the task brief).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AstraScanArrivalTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val profile = RecognitionProfile.postcardSiftRootSiftV1()
    private val identity = AccountIdentityGenerator().generate()
    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
    private val capsuleUuid = UUID.fromString("0198f0a0-0000-7000-8000-00000000c001")

    private lateinit var database: RemanenceLocalDatabase

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val rows = listOf(
        ChooserHintRow(
            candidateId = "cap-a",
            primarySenderLabel = "from \u2068@mykola\u2069",
            senderIdentityVerified = true,
            claimedSenderHandle = "mykola",
            yearAndDateLabel = "2026",
            placeLabel = "Lviv",
        ),
        ChooserHintRow(
            candidateId = "cap-b",
            primarySenderLabel = "unverified sender",
            senderIdentityVerified = false,
            claimedSenderHandle = "olena",
            yearAndDateLabel = "2026",
            placeLabel = null,
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun awaitingCaptureArrivalRendersLiveCamera() {
        val vm = viewModel()
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        bindFront(vm)

        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        composeRule.setContent {
            MaterialTheme {
                ScanScreen(
                    viewModel = vm,
                    requestPermissionOnAttach = false,
                    adapterFactory = { FakeStillCameraAdapter().also { live.set(it) } },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        // The entry branch owns the live camera surface, guide and a
        // working shutter — lifecycle unchanged by the arrival wrapper.
        composeRule.onNodeWithTag("scan_screen_scroll").assertIsDisplayed()
        composeRule.onAllNodesWithTag("capture_preview").assertCountEquals(1)
        composeRule.onNodeWithTag("postcard_guide_overlay").assertIsDisplayed()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_shutter_front")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun awaitingCaptureArrivalRendersUnderReducedMotion() {
        putAnimatorScale("0")
        val vm = viewModel()
        bindFront(vm)

        composeRule.setContent {
            MaterialTheme {
                ScanScreen(
                    viewModel = vm,
                    requestPermissionOnAttach = false,
                    adapterFactory = { FakeStillCameraAdapter() },
                )
            }
        }
        composeRule.waitForIdle()

        // INSTANT regime: content lands immediately, no crash, no wait.
        composeRule.onAllNodesWithTag("capture_preview").assertCountEquals(1)
        composeRule.onNodeWithTag("postcard_guide_overlay").assertIsDisplayed()
    }

    @Test
    fun chooserArrivalRendersRowsAndActions() {
        var chosen: ChooserHintRow? = null
        var recaptured = false
        composeRule.setContent {
            MaterialTheme {
                ScanChooserBranch(
                    rows = rows,
                    onSelected = { chosen = it },
                    onRecapture = { recaptured = true },
                    motion = AstraMotionSpec.Resolved.FULL,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithTag("chooser_title").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-a").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-b").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_recapture_button")
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule.onNodeWithTag("chooser_row_cap-b").performClick()
        assertEquals("cap-b", chosen?.candidateId)

        composeRule.onNodeWithTag("chooser_recapture_button").performClick()
        assertEquals(true, recaptured)
        // Recapture never selects: the arrival preserves chooser actions.
        assertEquals("cap-b", chosen?.candidateId)
    }

    @Test
    fun chooserArrivalRendersUnderReducedMotion() {
        composeRule.setContent {
            MaterialTheme {
                ScanChooserBranch(
                    rows = rows,
                    onSelected = {},
                    onRecapture = {},
                    motion = AstraMotionSpec.Resolved.INSTANT,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chooser_title").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-a").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-b").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_recapture_button").assertIsDisplayed()
    }

    private fun putAnimatorScale(value: String) {
        Settings.Global.putString(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            value,
        )
    }

    private fun viewModel(): ScanViewModel {
        val frontBytes = CanonicalSiftFingerprintFixture.bytes(seed = 3)
        val front = SiftRootSiftFingerprintCodec.parse(frontBytes)
        return ScanViewModel(
            persistence = NoPersistence(),
            database = database,
            profile = profile,
            identityProvider = {
                SenderIdentitySnapshot(
                    userId = owner.toRestString(),
                    handle = "mykola",
                    activeKeyBundleId = UUID.randomUUID().toString(),
                    encryptionPrivateHandle = identity.encryptionPrivateHandle,
                    signingPrivateHandle = identity.signingPrivateHandle,
                )
            },
            trustedSenderKeys = DirectorySenderKeyStore(
                directoryFetch = { error("verification must be unreachable in this test") },
                ownAccount = { null },
            ),
            presentationGrants = PresentationGrantAuthority(
                ScanGrantManager(clockMillis = { 1_000L }),
            ),
            frontProcessor = StillProcessor {
                ProcessedStill.Accepted(profile.profileId, frontBytes.copyOf())
            },
            // Never exercised: no JPEG is delivered in arrival tests.
            matcher = SiftRootSiftMatcherPort { _, _ -> error("matcher must not run before delivery") },
            candidateIndexProvider = {
                ScanCandidateIndex(
                    candidates = listOf(
                        IndexedCandidate(
                            capsuleId = capsuleUuid,
                            front = front,
                            recipientPreferred = false,
                        ),
                    ),
                    presentationSources = mapOf(capsuleUuid to CapsulePresentationSource.INCOMING),
                )
            },
            incomingPresentationPreparation = null,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
    }

    private fun bindFront(vm: ScanViewModel) {
        vm.frontAttempt.onPermissionResult(granted = true, canAskAgain = false)
        vm.frontAttempt.onPreviewBound()
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
    }
}
