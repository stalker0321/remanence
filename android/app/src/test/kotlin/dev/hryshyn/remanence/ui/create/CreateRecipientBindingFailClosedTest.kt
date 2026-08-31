package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.subtle.Base64
import dev.hryshyn.remanence.capture.PreparedBackItem
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M2-P07 fail-closed lifecycle regression for the confirmed recipient
 * binding. The contract: a [CreateViewModel] publication request is
 * driven ONLY by the explicitly confirmed immutable
 * [ResolvedHandleSnapshot] captured into [PublishInputs] before any
 * suspend boundary. The five scenarios below pin that contract under
 * every kind of late or hostile change to the surrounding state:
 *
 * 1. an unconfirmed recipient cannot start publishing;
 * 2. a pending but unconfirmed replacement cannot redirect the bound
 *    snapshot;
 * 3. mutating directory / key data after confirmation cannot redirect
 *    an in-flight publish;
 * 4. malformed recipient key material returns the flow to a safe
 *    recoverable create state with no outbox row and no plaintext
 *    artifact on disk;
 * 5. a session restart drops the binding immediately.
 *
 * Every test drives the production [CreateViewModel] over the real
 * Room database, the real outbox stager, and the real Tink HPKE
 * keysets; only the lookup port and the photo pipeline are scripted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateRecipientBindingFailClosedTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val senderIdentity = AccountIdentityGenerator().generate()
    private val otherIdentity = AccountIdentityGenerator().generate()

    private val senderUserUuid = UUID.fromString("ae111111-2222-4333-8444-555555555555")
    private val senderBundleUuid = UUID.fromString("ae333333-4444-4555-8666-777777777777")
    private val otherUserUuid = UUID.fromString("ae999999-aaaa-4bbb-8ccc-dddddddddddd")
    private val otherBundleUuid = UUID.fromString("aebbbbbb-cccc-4ddd-8eee-ffffffffffff")

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

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
        stagingDir = File(context.filesDir, "recipient-fc-staging").apply { mkdirs() }
        outboxDir = File(context.filesDir, "recipient-fc-outbox")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
        outboxDir.deleteRecursively()
    }

    private fun b64Url(bytes: ByteArray): String = Base64.urlSafeEncode(bytes)

    private fun snapshotFor(
        userUuid: UUID,
        bundleUuid: UUID,
        handle: String,
        identity: AccountIdentityGenerator.AccountIdentity,
        encryptionB64: String = b64Url(identity.encryptionPublicKeyset),
        signingB64: String = b64Url(identity.signingPublicKeyset),
    ) = ResolvedHandleSnapshot(
        userId = UserId(userUuid),
        handle = NormalizedHandle.parse(handle),
        keyBundleId = KeyBundleId(bundleUuid),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = encryptionB64,
        signingPublicKeysetB64Url = signingB64,
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun selfSnapshot() = snapshotFor(
        userUuid = senderUserUuid,
        bundleUuid = senderBundleUuid,
        handle = "mykola",
        identity = senderIdentity,
    )

    private fun otherSnapshot() = snapshotFor(
        userUuid = otherUserUuid,
        bundleUuid = otherBundleUuid,
        handle = "friend",
        identity = otherIdentity,
    )

    private fun malformedKeySnapshot() = snapshotFor(
        userUuid = otherUserUuid,
        bundleUuid = otherBundleUuid,
        handle = "friend",
        identity = otherIdentity,
        encryptionB64 = "@@@not-a-valid-tink-keyset-base64@@@",
    )

    // ------------------------------------------------------------------
    // Test doubles.
    // ------------------------------------------------------------------

    /**
     * Directory whose response for a handle can be swapped at any time
     * (e.g. simulating a key rotation, a directory server compromise, or
     * a redirect attempt after the user already confirmed a recipient).
     */
    private class MutableDirectory : RecipientDirectoryPort {
        private val ref = AtomicReference<Map<String, ResolvedHandleSnapshot>>(emptyMap())
        fun set(byHandle: Map<String, ResolvedHandleSnapshot>) {
            ref.set(byHandle)
        }
        override suspend fun lookup(
            rawHandle: String,
            accessToken: String,
        ): DirectoryLookupResult =
            ref.get()[rawHandle]?.let { DirectoryLookupResult.Found(it) }
                ?: DirectoryLookupResult.NotFound
    }

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
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

    private fun viewModel(
        directory: RecipientDirectoryPort = MutableDirectory().also {
            it.set(mapOf("mykola" to selfSnapshot(), "friend" to otherSnapshot()))
        },
        identityGate: CompletableDeferred<SenderIdentitySnapshot>? = null,
        normalizerGate: CompletableDeferred<Unit>? = null,
    ): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir))
        return CreateViewModel(
            directory = directory,
            accessTokenProvider = { null },
            identityProvider = { identityGate?.await() ?: senderIdentitySnapshot() },
            persistence = RecordingPersistence(),
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir),
                retryStore,
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
        openPhotoSource = { id ->
            dev.hryshyn.remanence.create.PhotoSource {
                java.io.ByteArrayInputStream("photo-$id".toByteArray())
            }
        },
        frontProcessor = Accepting(FingerprintSide.FRONT),
        backProcessor = Accepting(FingerprintSide.BACK),
        photoNormalizer = { input ->
            normalizerGate?.await()
            dev.hryshyn.remanence.create.NormalizedPhotoDto(input.copyOf(), 800, 600)
        },
        cpuDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
        senderRetryKeysetWrapper = testWrapper,
        senderRetryKekAlias = testAlias,
        enqueueUpload = { _, _ -> },
        )
    }

    private fun senderIdentitySnapshot() = SenderIdentitySnapshot(
        userId = senderUserUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = senderBundleUuid.toString(),
        encryptionPrivateHandle = senderIdentity.encryptionPrivateHandle,
        signingPrivateHandle = senderIdentity.signingPrivateHandle,
    )

    /** Drives a create session through FRONT + BACK + CONTENT for the
     * given [snapshot], leaving the VM parked at CONTENT. */
    private fun driveToContent(vm: CreateViewModel, snapshot: ResolvedHandleSnapshot) {
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onResolved(snapshot)
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
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        vm.onPhotosPicked(listOf("p1", "p2", "p3"))
        assertTrue(vm.noteEditor.onChange("lifecycle note"))
    }

    private fun awaitTerminalPublish(vm: CreateViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) {
                error(
                    "publishing never reached a terminal step; publishError=" +
                        vm.publishError.value + " flowError=" + vm.flowError.value,
                )
            }
            Thread.sleep(20)
        }
    }

    private fun outboxRowCount(capsuleId: String): Int = runBlocking {
        val row = database.outboxCapsuleDao()
            .getByCapsuleIdAndOwner(capsuleId, senderUserUuid.toString())
        if (row == null) 0
        else database.outboxBlobDao()
            .getAllByCapsuleIdAndOwner(capsuleId, senderUserUuid.toString()).size
    }

    private fun assertNoPlaintextStaging(capsuleId: String) {
        val sessionDir = File(
            dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir)
                .createStagingRoot(UserId(senderUserUuid)),
            capsuleId,
        )
        val files = sessionDir.listFiles()?.toList().orEmpty()
        assertTrue(
            "session staging $capsuleId must not retain plaintext on disk; saw ${files.map { it.name }}",
            files.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 1. Unconfirmed recipient cannot start publishing.
    // ------------------------------------------------------------------

    @Test
    fun unconfirmedRecipientCannotStartPublishing() {
        val vm = viewModel()
        // Drive a normal session so the VM reaches CONTENT with a bound
        // snapshot, then simulate the surface teardown that wipes transient
        // in-memory state including step and the confirmed binding.
        driveToContent(vm, selfSnapshot())
        assertNotNull(vm.confirmedRecipient.value)
        vm.endSession()
        assertNull("endSession() must drop the confirmed binding", vm.confirmedRecipient.value)
        assertEquals(
            "endSession() must reset the step with the rest of the session",
            CreateViewModel.Step.RECIPIENT_LOOKUP,
            vm.step.value,
        )

        val capsuleId = vm.capsuleId
        vm.startPublishing()

        // Fail-closed: teardown left the flow off CONTENT with no recipient,
        // so no publish is launched and no outbox row or plaintext file lands.
        assertNotNull("a recovery message must be set", vm.flowError.value)
        assertTrue(
            "recovery message must explain the illegal publish",
            vm.flowError.value!!.contains("publishing", ignoreCase = true),
        )
        assertEquals(
            "step must stay on RECIPIENT_LOOKUP - no publication was attempted",
            CreateViewModel.Step.RECIPIENT_LOOKUP,
            vm.step.value,
        )
        assertNull(vm.publishError.value)
        assertEquals(0, outboxRowCount(capsuleId))
        assertNoPlaintextStaging(capsuleId)
    }

    // ------------------------------------------------------------------
    // 2. Pending but unconfirmed replacement cannot redirect the bound
    //    snapshot.
    // ------------------------------------------------------------------

    @Test
    fun pendingUnconfirmedReplacementCannotRedirectTheBoundSnapshot() = runBlocking {
        val vm = viewModel()
        driveToContent(vm, selfSnapshot())
        val selfCapsuleId = vm.capsuleId

        // A second resolution attempt for a different handle - this
        // fails closed at the step gate because we are already on
        // CONTENT (the lookup step is past). The confirmed snapshot is
        // therefore untouched.
        vm.onResolved(otherSnapshot())
        assertEquals(
            "a late onResolved() at CONTENT must fail closed without changing the bound snapshot",
            selfSnapshot(),
            vm.confirmedRecipient.value,
        )

        // The publication reads the captured snapshot, not the second
        // resolution attempt.
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        val row = requireNotNull(
            database.outboxCapsuleDao()
                .getByCapsuleIdAndOwner(selfCapsuleId, senderUserUuid.toString()),
        ) { "outbox row must be staged" }
        assertEquals(senderUserUuid.toString(), row.senderUserId)
        assertEquals(senderUserUuid.toString(), row.recipientUserId)
        assertEquals(senderBundleUuid.toString(), row.recipientKeyBundleId)
    }

    // ------------------------------------------------------------------
    // 3. Mutating directory / key data after confirmation cannot
    //    redirect an in-flight publish.
    // ------------------------------------------------------------------

    @Test
    fun mutatingDirectoryDataAfterConfirmationCannotRedirectInFlightPublish() = runBlocking {
        val directory = MutableDirectory().also {
            it.set(mapOf("mykola" to selfSnapshot(), "friend" to otherSnapshot()))
        }
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val normalizerGate = CompletableDeferred<Unit>()
        val vm = viewModel(directory = directory, identityGate = identityGate, normalizerGate = normalizerGate)
        driveToContent(vm, selfSnapshot())
        val selfCapsuleId = vm.capsuleId

        vm.startPublishing()
        // The publish is parked inside normalization (before any artifact
        // is written). The directory is now mutated: a re-lookup of the
        // same handle resolves to a different user / key bundle.
        directory.set(
            mapOf(
                "mykola" to otherSnapshot(),
                "friend" to otherSnapshot(),
            ),
        )

        // Resume the publish. The captured snapshot stays the original
        // self snapshot; the redirect attempt is irrelevant.
        normalizerGate.complete(Unit)
        identityGate.complete(senderIdentitySnapshot())
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        val row = requireNotNull(
            database.outboxCapsuleDao()
                .getByCapsuleIdAndOwner(selfCapsuleId, senderUserUuid.toString()),
        )
        assertEquals(senderUserUuid.toString(), row.senderUserId)
        assertEquals(senderUserUuid.toString(), row.recipientUserId)
        assertEquals(senderBundleUuid.toString(), row.recipientKeyBundleId)
        // In particular, the recipient is NOT the post-confirmation
        // directory entry.
        assertEquals(senderUserUuid.toString(), row.recipientUserId)
    }

    // ------------------------------------------------------------------
    // 4. Malformed recipient key material returns to a safe recoverable
    //    create state with no outbox row and no plaintext artifact.
    // ------------------------------------------------------------------

    @Test
    fun malformedRecipientKeyMaterialFailsClosedAndCleansUpStaging() = runBlocking {
        val vm = viewModel()
        driveToContent(vm, malformedKeySnapshot())
        val capsuleId = vm.capsuleId
        assertNotNull(vm.confirmedRecipient.value)

        vm.startPublishing()
        awaitTerminalPublish(vm)

        // M2-P07 fail-closed: the malformed HPKE public keyset bubbles
        // out of [parsePublicHandle] inside [publishSealed], the publish
        // is reported on the recovery surface, the step returns to
        // CONTENT, and no outbox row or staged plaintext survives.
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull("a publish error must be set", vm.publishError.value)
        assertNull(vm.flowError.value)
        assertEquals(0, outboxRowCount(capsuleId))
        assertNoPlaintextStaging(capsuleId)

        // The malformed-key binding is still in the session store; a
        // corrected re-resolution by the user is the recovery path, not
        // a silent rewrite to the local account.
        assertNotNull(vm.confirmedRecipient.value)
        assertEquals(
            otherUserUuid.toString(),
            vm.confirmedRecipient.value!!.userId.value.toString(),
        )
    }

    // ------------------------------------------------------------------
    // 5. Session restart drops the binding immediately.
    // ------------------------------------------------------------------

    @Test
    fun sessionRestartDropsTheBinding() = runBlocking {
        val vm = viewModel()
        driveToContent(vm, selfSnapshot())
        val firstCapsuleId = vm.capsuleId
        assertNotNull(vm.confirmedRecipient.value)

        // FIX-REVIEW-02: a new epoch restarts the create surface.
        vm.beginSession(epoch = 2L)

        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull("a new epoch must drop the bound snapshot", vm.confirmedRecipient.value)
        assertTrue(vm.capsuleId != firstCapsuleId)

        // Publishing from the new session is impossible: there is no
        // confirmed recipient and the step table blocks CONTENT access.
        vm.startPublishing()
        assertNotNull(vm.flowError.value)
        assertTrue(
            "the recovery message must explain the missing CONTENT prerequisites",
            vm.flowError.value!!.contains("publishing", ignoreCase = true),
        )
        assertEquals(
            "no outbox row from the dropped session is left behind",
            0,
            outboxRowCount(firstCapsuleId),
        )
        // The first session's outbox was never written either.
        assertNull(
            database.outboxCapsuleDao()
                .getByCapsuleIdAndOwner(firstCapsuleId, senderUserUuid.toString()),
        )
    }

    private companion object {
        private fun synthetic(side: FingerprintSide): ProcessedStill.Accepted {
            val profile = RecognitionProfile.mvpOrbV1()
            val keypoints = List(64) {
                FingerprintKeypoint(
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
                serializedBytes = FingerprintCodec.serialize(
                    PostcardFingerprint(
                        profileId = profile.profileId,
                        side = side,
                        canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                        canonicalHeightPx = 1000,
                        coarseHash64 = 6L,
                        keypoints = keypoints,
                        descriptors = List(64) { i ->
                            ByteArray(32) { ((it * 17 + i * 5) and 0xFF).toByte() }
                        },
                        quality = ExtractionQuality(200.0, 90.0, 0.01, 0.85),
                    ),
                ),
            )
        }
    }
}
