package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.PendingBlobResult
import dev.hryshyn.remanence.core.data.network.PendingDraftResult
import dev.hryshyn.remanence.core.data.network.PendingFinalizeResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.pending.PendingBoundaryPorts
import dev.hryshyn.remanence.pending.PendingPublishFlow
import dev.hryshyn.remanence.pending.PendingRetirementSessionGate
import dev.hryshyn.remanence.pending.PendingSendRouteFlow
import dev.hryshyn.remanence.pending.PendingServicePin
import dev.hryshyn.remanence.pending.ProofAccount
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T186 M12e B2: the ACTUAL CreateVM publish boundary (not helpers
 * only) — a frozen pending route reaches a handwritten code through
 * the real authoring + staging path, and entry-lease/owner/incarnation
 * regressions fail closed with no code and no draft side effects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatePendingBoundaryPublishTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("8f111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("8f333333-4444-4555-8666-777777777777")
    private val serviceKeyUuid = UUID.fromString("9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b")

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    private val bridge = dev.hryshyn.remanence.create.MemoryGeneratorBridge()

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Accepted(
                profileId = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.PROFILE_ID,
                serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(6),
            )
    }

    private class RecordingPersistence : SealedFingerprintPersistence {
        val stored = mutableMapOf<String, ByteArray>()
        var counter = 0

        override suspend fun persist(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            val id = "fp-${++counter}"
            stored[id] = plaintextBytes.copyOf()
            return id
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = stored.isNotEmpty()

        override suspend fun decrypt(fingerprintId: String): ByteArray =
            requireNotNull(stored[fingerprintId]).copyOf()

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private inner class SwitchingGate(
        var owner: String = userUuid.toString(),
        var epoch: Long = 1L,
        var captureNull: Boolean = false,
    ) : PendingRetirementSessionGate {
        override fun capture(): SessionRequestLease? =
            if (captureNull) {
                null
            } else {
                SessionRequestLease(UserId.parseRest(owner), epoch)
            }

        override fun accessTokenFor(lease: SessionRequestLease): String? = "token"

        override fun isLive(lease: SessionRequestLease): Boolean =
            lease.ownerUserId == UserId.parseRest(owner) && lease.incarnation == epoch
    }

    private fun testPin(): PendingServicePin {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return PendingServicePin(
            serviceKeyUuid.toString(),
            java.util.Base64.getEncoder().encodeToString(
                TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signing.publicKeysetHandle),
            ),
        )
    }

    private fun frozenPending() = PendingSendRouteFlow.FrozenIdentity.Pending(
        routeId = "0198f0a0-0000-7000-8000-00000000b301",
        deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        normalizedDestination = "a42@example.test",
        channel = "email",
        claimDeadline = 1814317432L,
        serviceKeyId = serviceKeyUuid.toString(),
        expiresAt = 1798765432L,
    )

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
        stagingDir = File(context.filesDir, "pending-boundary").apply { mkdirs() }
        outboxDir = File(context.filesDir, "pending-boundary-outbox")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
        outboxDir.deleteRecursively()
    }

    private fun senderIdentity() = SenderIdentitySnapshot(
        userId = userUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = bundleUuid.toString(),
        encryptionPrivateHandle = identity.encryptionPrivateHandle,
        signingPrivateHandle = identity.signingPrivateHandle,
    )

    private fun viewModel(
        gate: SwitchingGate,
        drafts: MutableList<String> = mutableListOf(),
        onBlob: (() -> Unit)? = null,
    ): CreateViewModel {
        val persistence = RecordingPersistence()
        val retryStore = SenderRetryMaterialStore(
            dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir),
        )
        return CreateViewModel(
            directory = RecipientDirectoryPort { DirectoryLookupResult.NotFound },
            accessTokenProvider = { "token" },
            identityProvider = { senderIdentity() },
            persistence = persistence,
            outboxStager = CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir),
                retryStore,
            ),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
            openPhotoSource = { id ->
                dev.hryshyn.remanence.create.PhotoSource {
                    java.io.ByteArrayInputStream(
                        dev.hryshyn.remanence.create.memoryTestJpegForPhotoId(id),
                    )
                }
            },
            frontProcessor = Accepting(FingerprintSide.FRONT),
            generatorBridgeProvider = bridge.provider,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            generatorPreviewLoader = CollageFreezeFixture.previewLoader { id ->
                dev.hryshyn.remanence.create.memoryTestJpegForPhotoId(id)
            },
            enqueueUpload = { _, _ -> },
            pendingBoundary = PendingBoundaryPorts(
                draft = PendingPublishFlow.DraftPort { _, capsule, _, _, _, _, _, _, _, _ ->
                    drafts += capsule
                    PendingDraftResult.Draft(
                        "0198f0a0-0000-7000-8000-00000000d401",
                        "0f".repeat(32),
                        1798765432L,
                    )
                },
                blob = PendingPublishFlow.BlobPort { _, blobId, _, _, _, _ ->
                    onBlob?.invoke()
                    PendingBlobResult.Uploaded(blobId, 11L)
                },
                finalize = PendingPublishFlow.FinalizePort { deliveryId, _, _, _, _, _ ->
                    PendingFinalizeResult.Finalized(
                        deliveryId, "AB3DE", 1814317432L, "a•••@•••",
                    )
                },
                gate = gate,
                account = { ProofAccount(gate.owner, bundleUuid.toString()) },
                pin = { testPin() },
            ),
        )
    }

    /** Drives a frozen route to COMPOSE through the real capture + picks. */
    private fun authorToCompose(vm: CreateViewModel) {
        vm.beginSession(1L, userUuid.toString())
        vm.onPendingRouteFrozen(frozenPending())
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        vm.onPhotosPicked(listOf("p1", "p2", "p3"))
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())
        assertEquals(CreateViewModel.Step.COMPOSE, vm.step.value)
    }
    }

    private fun awaitTerminalPublish(vm: CreateViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) {
                error("publishing never reached a terminal step; publishError=" + vm.publishError.value)
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun pendingBoundaryPublishesCodeAtNormalBoundary() = runBlocking {
        val gate = SwitchingGate()
        val drafts = mutableListOf<String>()
        val vm = viewModel(gate, drafts)
        authorToCompose(vm)
        val capsule = vm.capsuleId
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.PUBLISHED, vm.step.value)
        // The SAME session capsule id at R3 that R1 froze for (via the
        // sheet path the route carries this session's capsule).
        assertEquals(listOf(capsule), drafts)
        val code = vm.pendingPublication.value
        assertEquals("AB3DE", code?.locatorCode)
        assertEquals("a•••@•••", code?.hint)
    }

    @Test
    fun nullEntryLeaseFailsClosedWithZeroDrafts() = runBlocking {
        val gate = SwitchingGate(captureNull = true)
        val drafts = mutableListOf<String>()
        val vm = viewModel(gate, drafts)
        authorToCompose(vm)
        vm.startPublishing()
        awaitTerminalPublish(vm)
        // Entry lease unavailable: synchronous fail-closed, the publish
        // job never starts and no draft ever leaves.
        assertTrue(drafts.isEmpty())
        assertNull(vm.pendingPublication.value)
        assertTrue(vm.step.value != CreateViewModel.Step.PUBLISHED)
    }

    @Test
    fun foreignEntryLeaseFailsClosedWithZeroDrafts() = runBlocking {
        val gate = SwitchingGate(owner = "99999999-2222-3333-4444-555555555555")
        val drafts = mutableListOf<String>()
        val vm = viewModel(gate, drafts)
        authorToCompose(vm)
        vm.startPublishing()
        awaitTerminalPublish(vm)
        // Lease owner is not the session owner: fail closed at entry.
        assertTrue(drafts.isEmpty())
        assertNull(vm.pendingPublication.value)
        assertTrue(vm.step.value != CreateViewModel.Step.PUBLISHED)
    }

    @Test
    fun incarnationAcrossStagingAwaitFailsWithNoCode() = runBlocking {
        val gate = SwitchingGate()
        val drafts = mutableListOf<String>()
        var firstBlob = true
        val vm = viewModel(
            gate, drafts,
            onBlob = {
                if (firstBlob) {
                    firstBlob = false
                    // Same-owner replacement across the staging awaits:
                    // the ORIGINAL entry lease no longer matches.
                    gate.epoch = 2L
                }
            },
        )
        authorToCompose(vm)
        vm.startPublishing()
        awaitTerminalPublish(vm)
        // A blob went out before the replacement, but the post-await
        // fence fails the publication: no code, no PUBLISHED step.
        assertNull(vm.pendingPublication.value)
        assertTrue(vm.step.value != CreateViewModel.Step.PUBLISHED)
    }
}
