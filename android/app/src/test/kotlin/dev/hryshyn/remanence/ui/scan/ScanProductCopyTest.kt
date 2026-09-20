package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.capture.CapturePermissionStep
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
import dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchDiagnostics
import dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure
import dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchResult
import dev.hryshyn.remanence.core.recognition.SiftRootSiftMatcherPort
import dev.hryshyn.remanence.identity.DirectorySenderKeyStore
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
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
import org.robolectric.annotation.Config

/**
 * HOLD-06 scan product copy and the RecaptureGuidance action contract:
 * Recovery states render authored EN/RU/UK copy, guidance shows exactly one
 * honest message plus one recapture action, and that action is the regression
 * proof that removing the dead inline camera resets to AwaitingCapture and
 * restores the live camera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScanProductCopyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val profile = RecognitionProfile.postcardSiftRootSiftV1()
    private val identity = AccountIdentityGenerator().generate()
    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
    private val capsuleUuid = UUID.fromString("0198f0a0-0000-7000-8000-00000000c001")

    private lateinit var database: RemanenceLocalDatabase

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
    fun recaptureGuidanceShowsOneMessageOneActionAndResetsToCamera() {
        val vm = viewModel(candidateIndex = candidateIndex())
        bindFront(vm)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        awaitMatchState(vm) { it is ScanMatchUiState.RecaptureGuidance }
        assertTrue(
            "single non-matching candidate must guide recapture, got ${vm.matchState.value}",
            vm.matchState.value is ScanMatchUiState.RecaptureGuidance,
        )

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

        composeRule.onAllNodesWithTag("scan_recapture").assertCountEquals(1)
        composeRule.onNodeWithTag("scan_recapture").assertTextEquals(
            context.getString(R.string.hold_nomatch),
        )
        composeRule.onAllNodesWithTag("scan_recapture_action").assertCountEquals(1)
        composeRule.onNodeWithTag("scan_recapture_action").assertTextEquals(
            context.getString(R.string.hold_scan_again),
        )
        // The dead inline camera surface is gone: guidance alone owns the branch.
        composeRule.onAllNodesWithTag("capture_preview").assertCountEquals(0)

        composeRule.onNodeWithTag("scan_recapture_action").performClick()
        composeRule.waitForIdle()

        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        composeRule.onAllNodesWithTag("capture_preview").assertCountEquals(1)
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_shutter_front").assertIsDisplayed()
    }

    @Test
    fun indexUnavailableRendersLocalizedBodyAndRetry() {
        val vm = viewModel(candidateIndex = ScanCandidateIndex.EMPTY)
        bindFront(vm)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        awaitMatchState(vm) { it is ScanMatchUiState.IndexUnavailable }
        assertEquals(ScanMatchUiState.IndexUnavailable, vm.matchState.value)

        composeRule.setContent {
            MaterialTheme { ScanScreen(viewModel = vm, requestPermissionOnAttach = false) }
        }
        composeRule.onNodeWithTag("scan_index_unavailable").assertTextEquals(
            context.getString(R.string.hold_scan_index_body),
        )
        composeRule.onNodeWithTag("scan_index_retry").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianScanStateCopyIsAuthored() {
        assertEquals("открываем вашу открытку…", context.getString(R.string.hold_scan_opening))
        assertEquals("сканировать ещё раз", context.getString(R.string.hold_scan_again))
        assertEquals(
            "на устройстве ещё нет нужных данных. подключитесь к интернету и попробуйте снова.",
            context.getString(R.string.hold_scan_index_body),
        )
        assertEquals(
            "открытка найдена. загружаем вложение; после проверки оно откроется.",
            context.getString(R.string.hold_scan_material_online),
        )
        assertEquals(
            "открытка найдена, нужен интернет. вложения ещё нет на устройстве.",
            context.getString(R.string.hold_scan_material_offline),
        )
    }

    @Test
    @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun ukrainianScanStateCopyIsAuthored() {
        assertEquals("відкриваємо вашу листівку…", context.getString(R.string.hold_scan_opening))
        assertEquals("сканувати знову", context.getString(R.string.hold_scan_again))
        assertEquals(
            "на пристрої ще немає потрібних даних. під’єднайтеся до інтернету й спробуйте знову.",
            context.getString(R.string.hold_scan_index_body),
        )
        assertEquals(
            "листівку знайдено. завантажуємо вкладення; після перевірки воно відкриється.",
            context.getString(R.string.hold_scan_material_online),
        )
        assertEquals(
            "листівку знайдено, потрібен інтернет. вкладення ще немає на пристрої.",
            context.getString(R.string.hold_scan_material_offline),
        )
    }

    private fun viewModel(candidateIndex: ScanCandidateIndex): ScanViewModel {
        val frontBytes = CanonicalSiftFingerprintFixture.bytes(seed = 3)
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
            matcher = zeroMatchMatcher,
            candidateIndexProvider = { candidateIndex },
            incomingPresentationPreparation = null,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
    }

    private fun candidateIndex(): ScanCandidateIndex {
        val front = SiftRootSiftFingerprintCodec.parse(CanonicalSiftFingerprintFixture.bytes(seed = 3))
        return ScanCandidateIndex(
            candidates = listOf(
                IndexedCandidate(capsuleId = capsuleUuid, front = front, recipientPreferred = false),
            ),
            presentationSources = mapOf(capsuleUuid to CapsulePresentationSource.INCOMING),
        )
    }

    private fun bindFront(vm: ScanViewModel) {
        vm.frontAttempt.onPermissionResult(granted = true, canAskAgain = false)
        vm.frontAttempt.onPreviewBound()
        assertEquals(CapturePermissionStep.Granted, vm.frontAttempt.permission)
    }

    private fun awaitMatchState(
        vm: ScanViewModel,
        timeoutMs: Long = 5_000,
        predicate: (ScanMatchUiState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(vm.matchState.value)) {
            if (System.currentTimeMillis() > deadline) {
                error("timed out waiting for match state; was ${vm.matchState.value}")
            }
            Thread.sleep(10)
        }
    }

    /** Deterministic NO_RATIO_MATCHES for any candidate: forces RecaptureRequired. */
    private val zeroMatchMatcher = SiftRootSiftMatcherPort { query, reference ->
        SiftRootSiftMatchResult(
            matches = emptyList(),
            inlierMatchIndices = emptyList(),
            homographyRowMajor = null,
            diagnostics = SiftRootSiftMatchDiagnostics(
                rawQueryRows = query.quantizedSiftDescriptors.size,
                rawReferenceRows = reference.quantizedSiftDescriptors.size,
                usableQueryRows = 0,
                usableReferenceRows = 0,
                forwardRatioMatches = 0,
                reverseRatioMatches = 0,
                reciprocalMatches = 0,
                uniqueMatches = 0,
                geometryAttempted = false,
                geometryFound = false,
                geometryAccepted = false,
                inliers = 0,
                inlierRatio = 0.0,
                medianInlierReprojectionErrorPx = -1.0,
                referenceConvexHullCoverage = -1.0,
                supportAreaPx2 = 0.0,
                supportEdgeRatio = 0.0,
                failure = SiftRootSiftMatchFailure.NO_RATIO_MATCHES,
            ),
        )
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
