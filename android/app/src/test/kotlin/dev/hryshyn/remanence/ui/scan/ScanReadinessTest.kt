package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.identity.DirectorySenderKeyStore
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparationRejection
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparationResult
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.session.SessionBoundary
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Scan readiness checkpoint:
 *  - authenticated Scan entry schedules the existing owner-scoped KEEP chain
 *  - INDEX_CACHED recognition is not recapture; online/offline pending copy
 *  - MATERIAL_CACHED resumes the ordinary grant path
 *  - generation/account-switch fencing still drops stale grants
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScanReadinessTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val profile = RecognitionProfile.mvpOrbV1()
    private val identity = AccountIdentityGenerator().generate()
    private val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
    private val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")
    private val bundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000b001")
    private val capsuleUuid = UUID.fromString("0198f0a0-0000-7000-8000-00000000c001")

    private lateinit var database: RemanenceLocalDatabase
    private val liveOwner = AtomicReference(ownerA)
    private val connected = AtomicBoolean(true)
    private val scheduled = mutableListOf<UserId>()
    private val prepareCalls = AtomicInteger(0)
    private lateinit var sessionBoundary: SessionBoundary

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        sessionBoundary = SessionBoundary()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun authenticatedScanEntrySchedulesExistingOwnerScopedSyncOncePerEpoch() {
        val vm = viewModel()
        assertEquals(listOf(ownerA), scheduled.toList())

        vm.beginSession(1L)
        assertEquals(listOf(ownerA), scheduled.toList())

        vm.beginSession(2L)
        assertEquals(listOf(ownerA, ownerA), scheduled.toList())
    }

    @Test
    fun recognizedIndexCachedIncomingShowsPendingCopyNotRecapture() {
        seedIndexCached(ownerA)
        connected.set(true)
        val vm = viewModel()
        captureMatchingPair(vm)

        val pending = vm.matchState.value as ScanMatchUiState.MaterialPending
        assertEquals(capsuleUuid.toString(), pending.capsuleId)
        assertTrue(pending.connected)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        assertTrue(scheduled.count { it == ownerA } >= 2)

        composeRule.setContent {
            MaterialTheme { ScanScreen(viewModel = vm, requestPermissionOnAttach = false) }
        }
        composeRule.onNodeWithTag("scan_material_pending_online").assertIsDisplayed()
    }

    @Test
    fun recognizedIndexCachedOfflineShowsConnectivityCopy() {
        seedIndexCached(ownerA)
        connected.set(false)
        val vm = viewModel()
        captureMatchingPair(vm)

        val pending = vm.matchState.value as ScanMatchUiState.MaterialPending
        assertFalse(pending.connected)
        composeRule.setContent {
            MaterialTheme { ScanScreen(viewModel = vm, requestPermissionOnAttach = false) }
        }
        composeRule.onNodeWithTag("scan_material_pending_offline").assertIsDisplayed()
    }

    @Test
    fun unsupportedReadyOrTerminalRowsNeverClaimMaterialPending() {
        seedIndexCached(ownerA)
        val row = kotlinx.coroutines.runBlocking {
            requireNotNull(
                database.incomingCapsuleDao().getByCapsuleIdAndOwner(
                    capsuleUuid.toString(),
                    ownerA.toRestString(),
                ),
            )
        }
        assertFalse(
            dev.hryshyn.remanence.ui.capsule.isMaterialPendingEligible(
                row.copy(protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION + 1),
                ownerA,
                CapsuleId(capsuleUuid),
            ),
        )
        assertFalse(
            dev.hryshyn.remanence.ui.capsule.isMaterialPendingEligible(
                row.copy(serverStatus = "PENDING"),
                ownerA,
                CapsuleId(capsuleUuid),
            ),
        )
        assertFalse(
            dev.hryshyn.remanence.ui.capsule.isMaterialPendingEligible(
                row.copy(materialState = LocalMaterialState.CORRUPT),
                ownerA,
                CapsuleId(capsuleUuid),
            ),
        )
    }

    @Test
    fun pendingWatcherUsesOneCollectorAndOnePreparationPerMaterialTransition() {
        seedIndexCached(ownerA)
        val vm = viewModel()
        captureMatchingPair(vm)
        assertTrue(vm.matchState.value is ScanMatchUiState.MaterialPending)
        val beforeMaterialTransition = prepareCalls.get()

        kotlinx.coroutines.runBlocking {
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                ownerA.toRestString(),
                capsuleUuid.toString(),
                LocalMaterialState.MATERIAL_CACHED,
            )
        }
        awaitCondition("pending watcher preparation") {
            prepareCalls.get() == beforeMaterialTransition + 1
        }
        Thread.sleep(50)
        assertEquals(beforeMaterialTransition + 1, prepareCalls.get())
        assertTrue(vm.matchState.value is ScanMatchUiState.MaterialPending)
    }

    @Test
    fun logoutBoundaryCancelsSchedulerAndPendingWatcherBeforePublication() {
        seedIndexCached(ownerA)
        val scheduleGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = viewModel(scheduleGate = scheduleGate)
        captureMatchingPair(vm)
        assertTrue(vm.matchState.value is ScanMatchUiState.MaterialPending)
        val prepareCallsBeforeLogout = prepareCalls.get()
        boundaryInvalidate()
        scheduleGate.complete(Unit)
        Thread.sleep(50)
        assertTrue("logout must fence the old scheduler", scheduled.isEmpty())

        // The same boundary also cancels the pending collector, so a later
        // Room state change cannot prepare or issue an old-owner grant.
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        kotlinx.coroutines.runBlocking {
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                ownerA.toRestString(),
                capsuleUuid.toString(),
                LocalMaterialState.MATERIAL_CACHED,
            )
        }
        Thread.sleep(50)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        assertEquals(prepareCallsBeforeLogout, prepareCalls.get())
    }

    @Test
    fun accountSwitchDropsPendingGrantAndDoesNotFalseRecaptureTheOldOwner() {
        seedIndexCached(ownerA)
        val vm = viewModel()
        captureMatchingPair(vm)
        assertTrue(vm.matchState.value is ScanMatchUiState.MaterialPending)

        liveOwner.set(ownerB)
        vm.beginSession(2L)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)

        kotlinx.coroutines.runBlocking {
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                ownerA.toRestString(),
                capsuleUuid.toString(),
                LocalMaterialState.MATERIAL_CACHED,
            )
        }
        Thread.sleep(50)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
    }

    @Test
    fun emptyIndexStillReportsRecaptureGuidance() {
        val vm = viewModel(includeCandidate = false)
        captureMatchingPair(vm)
        assertEquals(ScanMatchUiState.RecaptureGuidance(failedAttempts = 1), vm.matchState.value)
    }

    private fun viewModel(
        includeCandidate: Boolean = true,
        scheduleGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ): ScanViewModel {
        val frontBytes = serializedSynthetic(FingerprintSide.FRONT)
        val backBytes = serializedSynthetic(FingerprintSide.BACK)
        val vm = ScanViewModel(
            persistence = NoPersistence(),
            database = database,
            profile = profile,
            identityProvider = {
                val owner = liveOwner.get()
                SenderIdentitySnapshot(
                    userId = owner.toRestString(),
                    handle = "mykola",
                    activeKeyBundleId = bundleId.toRestString(),
                    encryptionPrivateHandle = identity.encryptionPrivateHandle,
                    signingPrivateHandle = identity.signingPrivateHandle,
                )
            },
            trustedSenderKeys = DirectorySenderKeyStore(
                directoryFetch = { error("scan readiness does not consult the directory") },
                ownAccount = { null },
            ),
            presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
                ScanGrantManager(clockMillis = { 1_000L }),
            ),
            frontProcessor = FixedProcessor(frontBytes),
            backProcessor = FixedProcessor(backBytes),
            candidateIndexProvider = { owner ->
                if (!includeCandidate || owner != ownerA) return@ScanViewModel ScanCandidateIndex.EMPTY
                ScanCandidateIndex(
                    candidates = listOf(
                        IndexedCandidate(
                            capsuleId = capsuleUuid,
                            front = FingerprintCodec.parse(frontBytes),
                            recipientPreferred = false,
                        ),
                    ),
                    presentationSources = mapOf(capsuleUuid to CapsulePresentationSource.INCOMING),
                )
            },
            incomingPresentationPreparation = null,
            incomingPrepareOverride = { owner, capsule ->
                prepareCalls.incrementAndGet()
                IncomingPresentationPreparationResult.Rejected(
                    IncomingPresentationPreparationRejection.CAPSULE_STATE_INVALID,
                )
            },
            scheduleIncomingSync = { owner ->
                scheduleGate?.await()
                scheduled += owner
            },
            networkConnected = { connected.get() },
            sessionBoundary = sessionBoundary,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        vm.beginSession(1L)
        return vm
    }

    private fun boundaryInvalidate() = sessionBoundary.invalidate()

    private fun seedIndexCached(owner: UserId) {
        kotlinx.coroutines.runBlocking {
            val dao = database.incomingCapsuleDao()
            dao.upsertAllForOwner(
                owner.toRestString(),
                listOf(
                    IncomingCapsuleEntity(
                        capsuleId = capsuleUuid.toString(),
                        ownerUserId = owner.toRestString(),
                        senderUserId = ownerB.toRestString(),
                        recipientUserId = owner.toRestString(),
                        senderSigningKeyBundleId = bundleId.toRestString(),
                        recipientEncryptionKeyBundleId = bundleId.toRestString(),
                        protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
                        serverStatus = "READY",
                        readyAtEpochMs = 1_700_000_000_000,
                        signedStatementBytes = byteArrayOf(1, 2, 3),
                        signedStatementSha256 = ByteArray(32),
                        publishSignatureBytes = byteArrayOf(4),
                        materialState = LocalMaterialState.DISCOVERED,
                    ),
                ),
            )
            val moved = dao.transitionMaterialStateForOwner(
                owner.toRestString(),
                capsuleUuid.toString(),
                LocalMaterialState.INDEX_CACHED,
            )
            check(moved is LocalMaterialTransitionResult.Accepted)
        }
    }

    private fun captureMatchingPair(vm: ScanViewModel) {
        listOf(vm.frontAttempt, vm.backAttempt).forEach { controller: CaptureAttemptController ->
            controller.onPermissionResult(granted = true, canAskAgain = false)
            assertEquals(CaptureAttemptPhase.Binding, controller.phase)
            controller.onPreviewBound()
            assertEquals(CapturePermissionStep.Granted, controller.permission)
        }
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("back".toByteArray())
        awaitCondition("match left Matching") { vm.matchState.value !is ScanMatchUiState.Matching }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private class FixedProcessor(private val serializedBytes: ByteArray) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Accepted(RecognitionProfile.mvpOrbV1().profileId, serializedBytes)
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredPair(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private fun serializedSynthetic(side: FingerprintSide): ByteArray {
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
        return FingerprintCodec.serialize(
            dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = if (side == FingerprintSide.FRONT) 11L else 22L,
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((i * 13 + it + side.ordinal) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        )
    }
}
