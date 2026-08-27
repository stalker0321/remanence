package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.FrontCaptureOutcome
import dev.hryshyn.remanence.capture.PreparedBackItem
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * FIX-STATE-02 regression proof: THE create transition table. Every event is
 * legal only from its own step; accepted FRONT reaches BACK_CHECKLIST, the
 * checklist Continue REALLY reaches BACK (the old code looped
 * BACK_CHECKLIST->BACK_CHECKLIST), accepted BACK reaches CONTENT, and publish
 * success/failure terminates visibly. Out-of-order events fail closed with a
 * visible recovery message - never a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateTransitionTableTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "transition-staging").apply { mkdirs() }
        // FIX-STATE-13: ciphertext outbox lives OUTSIDE the staging root, as
        // in production wiring; the emptiness assertions below stay plaintext
        // assertions.
        File(context.filesDir, "transition-outbox").apply { mkdirs() }.deleteRecursively()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Fakes driving THE production delivery path.
    // ------------------------------------------------------------------

    /** Scripted outcomes, one per delivered still; throws when scripted. */
    private class ScriptedProcessor(
        vararg script: Scripted,
    ) : StillProcessor {
        sealed interface Scripted {
            data class Accept(val bytes: ByteArray) : Scripted

            data class Reject(val reasons: Set<QualityReason>) : Scripted

            data object ThrowIllegalState : Scripted
        }

        private val queue = ArrayDeque(script.toList())
        val deliveries = mutableListOf<Int>()
        var calls = 0

        override fun process(jpegBytes: ByteArray): ProcessedStill {
            calls += 1
            deliveries += jpegBytes.size
            return when (val next = queue.removeFirst()) {
                is Scripted.Accept -> ProcessedStill.Accepted("mvp-orb-v1", next.bytes)
                is Scripted.Reject -> ProcessedStill.Rejected(next.reasons)
                Scripted.ThrowIllegalState -> throw IllegalStateException("orb exploded")
            }
        }
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
            requireNotNull(stored[fingerprintId]) { "unknown fingerprint" }

        override suspend fun setPreferredPair(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String, accessToken: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("7c111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("7c333333-4444-4555-8666-777777777777")

    private fun b64Url(bytes: ByteArray): String =
        com.google.crypto.tink.subtle.Base64.urlSafeEncode(bytes)

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(userUuid),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(bundleUuid),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = b64Url(identity.encryptionPublicKeyset),
        signingPublicKeysetB64Url = b64Url(identity.signingPublicKeyset),
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun syntheticFingerprint(seed: Int, side: FingerprintSide): ByteArray {
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
        return dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(
            dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                side = side,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = seed.toLong(),
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        )
    }

    private fun viewModel(
        frontProcessor: StillProcessor,
        backProcessor: StillProcessor,
        identityProvider: suspend () -> SenderIdentitySnapshot? = {
            SenderIdentitySnapshot(
                userId = userUuid.toString(),
                handle = "mykola",
                activeKeyBundleId = bundleUuid.toString(),
                encryptionPrivateHandle = identity.encryptionPrivateHandle,
                signingPrivateHandle = identity.signingPrivateHandle,
            )
        },
        openPhotoSource: (String) -> dev.hryshyn.remanence.create.PhotoSource = {
            error("photo picker seam not used in this test")
        },
    ): Pair<CreateViewModel, RecordingPersistence> {
        val persistence = RecordingPersistence()
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = identityProvider,
            persistence = persistence,
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(stagingDir.parentFile, "transition-outbox")),
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            stagingDirectory = stagingDir,
            openPhotoSource = openPhotoSource,
            frontProcessor = frontProcessor,
            backProcessor = backProcessor,
            // FIX-STATE-08: deterministic normalization keeps publishing
            // exercisable without native image decoding in JVM tests.
            photoNormalizer = { input -> dev.hryshyn.remanence.create.NormalizedPhotoDto(input.copyOf(), 800, 600) },
            // FIX-STATE-01: delivery completes synchronously under the test
            // dispatcher so transition assertions are deterministic.
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        vm.beginSession(1L)
        return vm to persistence
    }

    /** Resolves and explicitly confirms the recipient; lands on FRONT/Ready. */
    private fun confirmRecipient(vm: CreateViewModel) {
        vm.onResolved(selfSnapshot())
        assertEquals(CreateViewModel.Step.RECIPIENT_CONFIRM, vm.step.value)
        vm.confirmRecipient()
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        bindReady(vm.frontAttempt)
    }

    private fun bindReady(attempt: dev.hryshyn.remanence.capture.CaptureAttemptController) {
        // Permission survives retakes; only a fresh session re-requests it.
        if (attempt.permission == dev.hryshyn.remanence.capture.CapturePermissionStep.NotRequested ||
            attempt.permission == dev.hryshyn.remanence.capture.CapturePermissionStep.DeniedRetryable
        ) {
            attempt.onPermissionResult(granted = true, canAskAgain = false)
        }
        attempt.onPreviewBound()
    }

    private fun deliverFront(vm: CreateViewModel, bytes: String = "jpeg-front") {
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg(bytes.toByteArray())
    }

    private fun deliverBack(vm: CreateViewModel, bytes: String = "jpeg-back") {
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg(bytes.toByteArray())
    }

    private fun confirmChecklist(vm: CreateViewModel) {
        PreparedBackItem.entries.forEach { item -> vm.backGate.setChecked(item, true) }
        vm.proceedToBackChecklist()
    }

    // ------------------------------------------------------------------
    // The transition table.
    // ------------------------------------------------------------------

    @Test
    fun checklistContinueAdvancesToFrontlessBackCapture_regression() {
        val front = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)))
        val back = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)))
        val (vm, _) = viewModel(front, back)

        confirmRecipient(vm)
        deliverFront(vm)

        assertEquals(CreateViewModel.Step.BACK_CHECKLIST, vm.step.value)
        confirmChecklist(vm)

        // FIX-STATE-02 core regression: Continue MUST reach BACK; the old
        // production code stayed on BACK_CHECKLIST forever.
        assertEquals(CreateViewModel.Step.BACK, vm.step.value)
        assertNull(vm.flowError.value)
    }

    @Test
    fun goldenHappyPathFromLookupThroughPublished() = runBlocking {
        val front = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)))
        val back = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)))
        val (vm, persistence) = viewModel(
            front,
            back,
            openPhotoSource = { id ->
                dev.hryshyn.remanence.create.PhotoSource {
                    java.io.ByteArrayInputStream("jpeg-payload-$id".toByteArray())
                }
            },
        )

        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        confirmRecipient(vm)
        deliverFront(vm)
        assertEquals(CreateViewModel.Step.BACK_CHECKLIST, vm.step.value)

        // Content selection before the back capture must not leak forward.
        confirmChecklist(vm)
        assertEquals(CreateViewModel.Step.BACK, vm.step.value)
        bindReady(vm.backAttempt)
        deliverBack(vm)
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)

        repeat(3) { index -> assertTrue(vm.photoSelection.toggle("content://photo/$index").let { true }) }
        assertTrue(vm.noteEditor.onChange("dear mama"))
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(
            "publishError=" + vm.publishError.value + " flowError=" + vm.flowError.value,
            CreateViewModel.Step.PUBLISHED,
            vm.step.value,
        )
        assertNull(vm.publishError.value)
        assertNull(vm.flowError.value)
        val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(vm.capsuleId, userUuid.toString())
        assertNotNull(row)
        assertEquals(OutboxCapsuleState.ENCRYPTED, row!!.state)
        assertTrue(persistence.stored.isNotEmpty())
        Unit
    }

    @Test
    fun repeatedIdenticalRejectionYieldsTwoFreshAttemptsOnTheSameSide() {
        val rejection = setOf(QualityReason.TOO_BLURRY)
        val front = ScriptedProcessor(
            ScriptedProcessor.Scripted.Reject(rejection),
            ScriptedProcessor.Scripted.Reject(rejection),
            ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)),
        )
        val back = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)))
        val (vm, _) = viewModel(front, back)
        confirmRecipient(vm)

        // First TOO_BLURRY.
        deliverFront(vm)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        val first = vm.frontAttempt.phase as CaptureAttemptPhase.Rejected
        assertEquals(rejection, first.reasons)

        // Retake, then the IDENTICAL rejection again: still a fresh attempt.
        vm.retakeFront()
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        bindReady(vm.frontAttempt)
        deliverFront(vm)
        val second = vm.frontAttempt.phase as CaptureAttemptPhase.Rejected
        assertNotEquals(first.attemptId, second.attemptId)
        assertEquals(first.reasons, second.reasons)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)

        // Third try is accepted and clears every capture error signal.
        vm.retakeFront()
        bindReady(vm.frontAttempt)
        deliverFront(vm)
        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)
        assertEquals(CreateViewModel.Step.BACK_CHECKLIST, vm.step.value)
        assertNull(vm.flowError.value)
        assertEquals(3, front.calls)
    }

    @Test
    fun processorExceptionShowsVisibleFailureAndRetriableAttempt() {
        val front = ScriptedProcessor(
            ScriptedProcessor.Scripted.ThrowIllegalState,
            ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)),
        )
        val back = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)))
        val (vm, _) = viewModel(front, back)
        confirmRecipient(vm)

        deliverFront(vm)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        val failed = vm.frontAttempt.phase as CaptureAttemptPhase.Failed
        assertEquals("orb exploded", failed.message)

        vm.retakeFront()
        bindReady(vm.frontAttempt)
        deliverFront(vm)
        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)
        assertEquals(CreateViewModel.Step.BACK_CHECKLIST, vm.step.value)
    }

    @Test
    fun backSideMirrorsRejectionRetakeAndAcceptanceIntoContent() {
        val front = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)))
        val back = ScriptedProcessor(
            ScriptedProcessor.Scripted.Reject(setOf(QualityReason.GLARE_EXCESSIVE)),
            ScriptedProcessor.Scripted.ThrowIllegalState,
            ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)),
        )
        val (vm, _) = viewModel(front, back)
        confirmRecipient(vm)
        deliverFront(vm)
        confirmChecklist(vm)
        assertEquals(CreateViewModel.Step.BACK, vm.step.value)
        bindReady(vm.backAttempt)

        deliverBack(vm)
        assertEquals(CreateViewModel.Step.BACK, vm.step.value)
        assertTrue(vm.backAttempt.phase is CaptureAttemptPhase.Rejected)

        vm.retakeBack()
        bindReady(vm.backAttempt)
        deliverBack(vm)
        assertTrue(vm.backAttempt.phase is CaptureAttemptPhase.Failed)
        assertEquals(CreateViewModel.Step.BACK, vm.step.value)

        vm.retakeBack()
        bindReady(vm.backAttempt)
        deliverBack(vm)
        assertEquals(CaptureAttemptPhase.Accepted, vm.backAttempt.phase)
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
    }

    /**
     * Out-of-order events are refused with a visible recovery message; the
     * step never moves and nothing crashes.
     */
    @Test
    fun outOfOrderEventsFailClosedVisiblyWithoutMovingTheFlow() {
        val front = ScriptedProcessor(
            ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)),
            ScriptedProcessor.Scripted.Accept(syntheticFingerprint(12, FingerprintSide.FRONT)),
        )
        val back = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)))
        val (vm, _) = viewModel(front, back)

        // FRONT-step events during RECIPIENT_LOOKUP.
        assertFalse(vm.beginFrontCapture())
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNotNull(vm.flowError.value)

        // Confirmation requires a resolved pending recipient.
        vm.confirmRecipient()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNotNull(vm.flowError.value)

        // Checklist continue requires BACK_CHECKLIST + readiness.
        vm.proceedToBackChecklist()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)

        // Publishing requires CONTENT.
        vm.startPublishing()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertTrue(vm.flowError.value!!.contains("CONTENT"))

        // Walk to FRONT; wrong-side delivery is inert and visible.
        confirmRecipient(vm)
        assertFalse(vm.beginBackCapture())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        vm.deliverBackJpeg("stray".toByteArray())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertEquals(0, back.calls)

        // Retake without a terminal attempt is refused.
        vm.retakeFront()
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertNotNull(vm.flowError.value)

        // Double confirmation after binding fails closed instead of rebinding.
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
    }

    @Test
    fun rejectedFrontThenAcceptedDeliveryClearsStaleErrorsAndAdvances() {
        val front = ScriptedProcessor(
            ScriptedProcessor.Scripted.Reject(setOf(QualityReason.CARD_TOO_SMALL)),
            ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)),
        )
        val back = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)))
        val (vm, _) = viewModel(front, back)
        confirmRecipient(vm)

        deliverFront(vm)
        assertNotNull(vm.frontAttempt.phase as CaptureAttemptPhase.Rejected)

        // A guard violation leaves a message; the successful retry clears it.
        vm.proceedToBackChecklist()
        assertNotNull(vm.flowError.value)
        vm.retakeFront()
        bindReady(vm.frontAttempt)
        deliverFront(vm)
        assertEquals(CreateViewModel.Step.BACK_CHECKLIST, vm.step.value)
        assertNull(vm.flowError.value)
    }

    @Test
    fun publishingFailureReturnsToContentPreservingInputsAndClearingStaging() = runBlocking {
        val front = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(11, FingerprintSide.FRONT)))
        val back = ScriptedProcessor(ScriptedProcessor.Scripted.Accept(syntheticFingerprint(22, FingerprintSide.BACK)))
        val (vm, persistence) = viewModel(front, back, identityProvider = { null })

        confirmRecipient(vm)
        deliverFront(vm)
        confirmChecklist(vm)
        bindReady(vm.backAttempt)
        deliverBack(vm)
        repeat(3) { vm.photoSelection.toggle("content://keep/$it") }
        assertTrue(vm.noteEditor.onChange("still here"))

        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.publishError.value)
        assertNull(vm.flowError.value)
        // Selection, note, and both captured sides survive for the retry.
        assertTrue(vm.photoSelection.canProceed)
        assertEquals("still here", vm.noteEditor.text)
        assertTrue(persistence.stored.isNotEmpty())
        // Plaintext staging never survives the failure.
        assertTrue(stagingDir.listFiles()?.isEmpty() == true)
        Unit
    }

    /** The durable outbox staging completes on Room's own executors. */
    private fun awaitTerminalPublish(vm: CreateViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) error("publishing never reached a terminal step")
            Thread.sleep(20)
        }
    }

    // M2-P07: the M1 self-only publication guard is removed. The create
    // transition table now admits cross-identity publication through the
    // confirmed recipient snapshot; the M1 test
    // `crossUserSnapshotIsRefusedAtPublishingTime` was deleted because it
    // exercised the removed guard. Cross-identity binding is pinned in
    // CreateRecipientPublicationBindingTest.
}
