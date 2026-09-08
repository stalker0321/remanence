package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RecognitionFingerprintEntity
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadHeaderChecks
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.identity.DirectorySenderKeyStore
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparationRejection
import dev.hryshyn.remanence.ui.capsule.IncomingPresentationPreparationResult
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.sync.IncomingAcceptanceDiagnostics
import dev.hryshyn.remanence.sync.IncomingAcceptanceDownloadDiagnostic
import dev.hryshyn.remanence.session.SessionBoundary
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
    private val profile = RecognitionProfile.postcardSiftRootSiftV1()
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
    fun emptyIndexReportsIndexUnavailableInsteadOfVisualRecapture() {
        val vm = viewModel(includeCandidate = false)
        captureMatchingPair(vm)
        assertEquals(ScanMatchUiState.IndexUnavailable, vm.matchState.value)

        composeRule.setContent {
            MaterialTheme { ScanScreen(viewModel = vm, requestPermissionOnAttach = false) }
        }
        composeRule.onNodeWithTag("scan_index_unavailable").assertIsDisplayed()
        vm.retryIndexSync()
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertTrue(scheduled.count { it == ownerA } >= 2)
    }

    @Test
    fun debugScanScreenRendersSafeIncomingDownloadDiagnostic() {
        val vm = viewModel(includeCandidate = false)
        val diagnostic = IncomingAcceptanceDownloadDiagnostic.fromFailure(
            RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.INVALID_RESPONSE,
                httpStatus = 200,
                retryable = false,
                headerChecks = RecipientBlobDownloadHeaderChecks(
                    contentTypeExact = false,
                    contentLengthExact = true,
                    etagExact = true,
                    contentEncodingAbsent = true,
                    transferEncodingAbsent = true,
                    contentRangeAbsent = true,
                    trailerAbsent = true,
                ),
            ),
        )
        IncomingAcceptanceDiagnostics.report(diagnostic)
        try {
            composeRule.setContent {
                MaterialTheme { ScanScreen(viewModel = vm, requestPermissionOnAttach = false) }
            }
            if (BuildConfig.DEBUG) {
                composeRule.onNodeWithText("Sync: ${diagnostic.safeSummary()}").assertIsDisplayed()
            }
        } finally {
            IncomingAcceptanceDiagnostics.report("not run")
        }
    }

    @Test
    fun localPersistedFingerprintWithWrongRoomProfileIsSkippedBeforeDecrypt() = runBlocking {
        val fingerprintId = "fp-wrong-profile"
        database.recognitionFingerprintDao().insertAll(
            ownerA.toRestString(),
            listOf(
                RecognitionFingerprintEntity(
                    fingerprintId = fingerprintId,
                    ownerUserId = ownerA.toRestString(),
                    capsuleId = capsuleUuid.toString(),
                    origin = FingerprintOrigin.SENDER,
                    fingerprintProfileId = "retired-profile-v0",
                    encryptedPath = "unused",
                    createdAtEpochMs = 1_700_000_000_000,
                    preferred = false,
                ),
            ),
        )
        val persistence = NoPersistence(
            decrypted = mapOf(fingerprintId to serializedSynthetic()),
        )

        val vm = viewModel(includeCandidate = false, persistence = persistence)
        captureMatchingPair(vm)

        assertEquals(ScanMatchUiState.IndexUnavailable, vm.matchState.value)
        assertEquals(0, persistence.decryptCalls)
    }

    private fun viewModel(
        includeCandidate: Boolean = true,
        scheduleGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
        persistence: SealedFingerprintPersistence = NoPersistence(),
    ): ScanViewModel {
        val frontBytes = serializedSynthetic()
        val vm = ScanViewModel(
            persistence = persistence,
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
            matcher = deterministicMatcher(),
            candidateIndexProvider = { owner ->
                if (!includeCandidate || owner != ownerA) return@ScanViewModel ScanCandidateIndex.EMPTY
                ScanCandidateIndex(
                    candidates = listOf(
                        IndexedCandidate(
                            capsuleId = capsuleUuid,
                            front = SiftRootSiftFingerprintCodec.parse(frontBytes),
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

    /** Keeps readiness/routing assertions independent of native OpenCV. */
    private fun deterministicMatcher() = dev.hryshyn.remanence.core.recognition.SiftRootSiftMatcherPort {
            query, reference ->
        val queryUsable = query.quantizedSiftDescriptors.count { row -> row.any { it.toInt() != 0 } }
        val referenceUsable = reference.quantizedSiftDescriptors.count { row -> row.any { it.toInt() != 0 } }
        val count = if (query.coarseHash64 == reference.coarseHash64) minOf(queryUsable, referenceUsable) else 0
        val pairs = (0 until count).map { index ->
            dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchPair(index, index, 0.1)
        }
        dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchResult(
            matches = pairs,
            inlierMatchIndices = pairs.indices.toList(),
            homographyRowMajor = if (count >= 6) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) else null,
            diagnostics = dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchDiagnostics(
                rawQueryRows = query.quantizedSiftDescriptors.size,
                rawReferenceRows = reference.quantizedSiftDescriptors.size,
                usableQueryRows = queryUsable,
                usableReferenceRows = referenceUsable,
                forwardRatioMatches = count,
                reverseRatioMatches = count,
                reciprocalMatches = count,
                uniqueMatches = count,
                geometryAttempted = count >= 6,
                geometryFound = count >= 6,
                geometryAccepted = count >= 6,
                inliers = count,
                inlierRatio = if (count == 0) 0.0 else 1.0,
                medianInlierReprojectionErrorPx = if (count == 0) -1.0 else 1.0,
                referenceConvexHullCoverage = if (count == 0) -1.0 else 1.0,
                supportAreaPx2 = if (count == 0) 0.0 else 400.0,
                supportEdgeRatio = if (count == 0) 0.0 else 1.0,
                failure = if (count >= 6) dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.NONE
                else if (count == 0) dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.NO_RATIO_MATCHES
                else dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.INSUFFICIENT_UNIQUE_PAIRS,
            ),
        )
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
        vm.frontAttempt.onPermissionResult(granted = true, canAskAgain = false)
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        vm.frontAttempt.onPreviewBound()
        assertEquals(CapturePermissionStep.Granted, vm.frontAttempt.permission)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
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
            ProcessedStill.Accepted(RecognitionProfile.postcardSiftRootSiftV1().profileId, serializedBytes)
    }

    private class NoPersistence(
        private val decrypted: Map<String, ByteArray> = emptyMap(),
    ) : SealedFingerprintPersistence {
        var decryptCalls: Int = 0

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

        override suspend fun decrypt(fingerprintId: String): ByteArray {
            decryptCalls += 1
            return decrypted[fingerprintId]?.copyOf() ?: ByteArray(0)
        }

        override suspend fun setPreferredOrigin(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private fun serializedSynthetic(): ByteArray {
        val keypoints = List(64) {
            dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint(
                xMicro = Math.rint((it % 8) / 8.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                yMicro = Math.rint((it / 8) / 8.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                scaleMicro = SiftRootSiftFingerprintCodec.MICRO_UNITS,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        val fingerprint = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint(
                profileId = profile.profileId,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = 11L,
                keypoints = keypoints,
                quantizedSiftDescriptors = List(64) { i ->
                    ByteArray(SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) {
                        ((i * 13 + it).coerceIn(1, 255)).toByte()
                    }
                },
            )
        return try {
            SiftRootSiftFingerprintCodec.serialize(fingerprint)
        } finally {
            fingerprint.wipe()
        }
    }
}
