package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.pending.PendingSendRouteFlow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T186 M12e-gap1: a frozen PENDING_V2 route is an explicit recipient
 * decision — the flow advances RECIPIENT_LOOKUP→FRONT on freeze and the
 * frozen route carries composition (FRONT→CONTENT→COMPOSE) with no
 * fabricated account, handle or recipient keys. Frozen V1 never
 * advances steps (resolve+confirm path owns it); off-step freezes fail
 * closed without moving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatePendingRouteStepTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    private val userUuid = UUID.fromString("7d111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("7d333333-4444-4555-8666-777777777777")

    private fun pendingIdentity() = PendingSendRouteFlow.FrozenIdentity.Pending(
        routeId = "0198f0a0-0000-7000-8000-00000000b301",
        deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        normalizedDestination = "a42@example.test",
        channel = "email",
        claimDeadline = 1814317432L,
        serviceKeyId = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b",
        expiresAt = 1798765432L,
    )

    private fun v1Identity() = PendingSendRouteFlow.FrozenIdentity.V1(
        routeId = "0198f0a0-0000-7000-8000-00000000b302",
        userId = "33333333-4444-5555-6666-777777777777",
        keyBundleId = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f",
        bindingRevision = 3L,
        expiresAt = 1798765432L,
    )

    private class AcceptProcessor(val bytes: ByteArray) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Accepted("postcard-sift-rootsift-v1", bytes)
    }

    private class RecordingPersistence : SealedFingerprintPersistence {
        val stored = mutableMapOf<String, ByteArray>()
        var counter = 0

        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            val id = "fp-${++counter}"
            stored[id] = plaintextBytes.copyOf()
            return id
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: FingerprintOrigin,
        ): Boolean = stored.isNotEmpty()

        override suspend fun decrypt(fingerprintId: String): ByteArray =
            requireNotNull(stored[fingerprintId]) { "unknown fingerprint" }.copyOf()

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: FingerprintOrigin,
        ) = Unit
    }

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        TinkPrimitives.ensureRegistered()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "pending-route-staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
    }

    private fun viewModel(): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(
            dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(
                File(stagingDir.parentFile, "pending-route-outbox"),
            ),
        )
        val vm = CreateViewModel(
            directory = RecipientDirectoryPort { DirectoryLookupResult.NotFound },
            accessTokenProvider = { "token" },
            identityProvider = { null },
            persistence = RecordingPersistence(),
            outboxStager = CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(
                    File(stagingDir.parentFile, "pending-route-outbox"),
                ),
                retryStore,
            ),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
            openPhotoSource = { error("photo picker not used in this test") },
            frontProcessor = AcceptProcessor(
                dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(6),
            ),
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            // FIX-STATE-01 (same as the lifetime harness): delivery
            // completes synchronously under the test dispatcher so
            // transition assertions are deterministic — never the real
            // CPU/IO pools under test.
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        vm.beginSession(1L, userUuid.toString())
        return vm
    }

    /** Deterministic step await (mirrors awaitTerminalPublish): the async
     * delivery path must land CONTENT before composition is attempted. */
    private fun awaitStep(vm: CreateViewModel, step: CreateViewModel.Step) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value != step) {
            if (System.currentTimeMillis() > deadline) {
                error("step never reached $step; at ${vm.step.value}")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun frozenPendingAdvancesLookupToFront() {
        val vm = viewModel()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        vm.onPendingRouteFrozen(pendingIdentity())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals(pendingIdentity(), vm.pendingRoute.value)
    }

    @Test
    fun frozenV1NeverAdvancesSteps() {
        val vm = viewModel()
        vm.onPendingRouteFrozen(v1Identity())
        // V1 stays for the resolve+confirm path: no step change, and the
        // route is stored for the boundary (not silently dropped).
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertEquals(v1Identity(), vm.pendingRoute.value)
    }

    @Test
    fun frozenPendingOffStepFailsClosed() {
        val vm = viewModel()
        vm.onPendingRouteFrozen(pendingIdentity())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        // A second freeze outside RECIPIENT_LOOKUP must not move steps.
        vm.onPendingRouteFrozen(pendingIdentity())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertTrue(vm.flowError.value != null)
    }

    private fun pendingIdentityAs(other: String) = dev.hryshyn.remanence.pending.PendingSendRouteFlow.FrozenIdentity.Pending(
        routeId = "0198f0a0-0000-7000-8000-00000000b999",
        deliveryTargetId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff",
        normalizedDestination = other,
        channel = "email",
        claimDeadline = 1814317432L,
        serviceKeyId = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b",
        expiresAt = 1798765432L,
    )

    /**
     * T186 M12e-gate (T188 REDO B1): an off-step callback for a
     * DIFFERENT target must not replace the chosen recipient — the
     * store keeps the ORIGINAL route, not merely the same identity
     * twice.
     */
    @Test
    fun frozenPendingOffStepDifferentTargetNoReplacement() {
        val vm = viewModel()
        val first = pendingIdentity()
        vm.onPendingRouteFrozen(first)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertEquals(first, vm.pendingRoute.value)
        vm.onPendingRouteFrozen(pendingIdentityAs("other@example.test"))
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertEquals(first, vm.pendingRoute.value)
        assertTrue(vm.flowError.value != null)
    }

    /**
     * T186 M12e-cap: backToFront regenerates the draft identity AND
     * clears the frozen route — a rescan/back can never publish the
     * old-UUID route; a new R1 freeze is required for the new capsule.
     */
    @Test
    fun backToFrontClearsFrozenRouteForNewCapsule() {
        val vm = viewModel()
        vm.onPendingRouteFrozen(pendingIdentity())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        val oldCapsule = vm.capsuleId
        bindReady(vm)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg(
            dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(6),
        )
        awaitStep(vm, CreateViewModel.Step.CONTENT)
        assertTrue(vm.backToFront())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertTrue(vm.capsuleId != oldCapsule)
        assertNull(vm.pendingRoute.value)
        // Without a frozen route (and no confirmed handle) the
        // boundary is unreachable for the old UUID.
        assertNull(vm.confirmedRecipient.value)
    }

    @Test
    fun frozenPendingCarriesCompositionToCompose() {
        val vm = viewModel()
        vm.onPendingRouteFrozen(pendingIdentity())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        bindReady(vm)
        assertTrue(vm.beginFrontCapture())
        // Canonical SIFT bytes (not a dummy): the FRONT outcome path
        // is the production delivery path, awaited deterministically.
        vm.deliverFrontJpeg(
            dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(6),
        )
        awaitStep(vm, CreateViewModel.Step.CONTENT)
        vm.photoSelection.toggle("a")
        vm.photoSelection.toggle("b")
        vm.photoSelection.toggle("c")
        assertTrue(vm.goToCompose())
        assertEquals(CreateViewModel.Step.COMPOSE, vm.step.value)
        // No handle was ever confirmed or fabricated.
        assertNull(vm.confirmedRecipient.value)
        assertEquals(pendingIdentity(), vm.pendingRoute.value)
    }

    @Test
    fun noBindingCompositionStillFails() {
        val vm = viewModel()
        vm.beginSession(1L, userUuid.toString())
        // Fresh session without any binding: FRONT cannot even be
        // reached through the pending path, and composition refuses.
        assertFalse(vm.goToCompose())
    }

    private fun bindReady(vm: CreateViewModel) {
        val attempt = vm.frontAttempt
        if (attempt.permission == dev.hryshyn.remanence.capture.CapturePermissionStep.NotRequested ||
            attempt.permission == dev.hryshyn.remanence.capture.CapturePermissionStep.DeniedRetryable
        ) {
            attempt.onPermissionResult(granted = true, canAskAgain = false)
        }
        attempt.onPreviewBound()
    }
}
