package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.PreparedBackItem
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-STATE-11 regression: THE publication belongs to exactly ONE create
 * session. endSession()/beginSession(new epoch) cancel the owning job,
 * invalidate every queued continuation, remove staging plaintext, and the old
 * publish can neither stage an outbox row nor mutate step/error of any newer
 * session. Duplicate taps stay a single publish.
 */
private fun lifetimeSynthetic(side: FingerprintSide): ProcessedStill.Accepted {
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
                side = side,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = 6L,
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 17 + i * 5) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        ),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatePublishLifetimeTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("8e111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("8e333333-4444-4555-8666-777777777777")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "publish-lifetime").apply { mkdirs() }
        // FIX-STATE-13: ciphertext outbox lives OUTSIDE the staging root, as
        // in production wiring.
        outboxDir = File(context.filesDir, "publish-lifetime-outbox")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
        outboxDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = lifetimeSynthetic(side)
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

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String, accessToken: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

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

    private fun senderIdentity() = SenderIdentitySnapshot(
        userId = userUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = bundleUuid.toString(),
        encryptionPrivateHandle = identity.encryptionPrivateHandle,
        signingPrivateHandle = identity.signingPrivateHandle,
    )

    /** Builds a ViewModel parked at CONTENT with photos + note ready. */
    private fun contentStage(
        identityGate: CompletableDeferred<SenderIdentitySnapshot>,
        normalizerGate: CompletableDeferred<Unit>? = null,
    ): CreateViewModel {
        val persistence = RecordingPersistence()
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir))
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { identityGate.await() },
            persistence = persistence,
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir), retryStore),
            profile = RecognitionProfile.mvpOrbV1(),
            stagingDirectory = stagingDir,
            openPhotoSource = { id ->
                dev.hryshyn.remanence.create.PhotoSource {
                    java.io.ByteArrayInputStream("photo-$id".toByteArray())
                }
            },
            frontProcessor = Accepting(FingerprintSide.FRONT),
            backProcessor = Accepting(FingerprintSide.BACK),
            photoNormalizer = { input ->
                if (normalizerGate != null) normalizerGate.await()
                dev.hryshyn.remanence.create.NormalizedPhotoDto(input.copyOf(), 800, 600)
            },
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        vm.beginSession(1L)
        vm.onResolved(selfSnapshot())
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
        assertTrue(vm.noteEditor.onChange("lifetime note"))
        return vm
    }

    private suspend fun outboxRow(capsuleId: String) =
        database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId, userUuid.toString())

    // ------------------------------------------------------------------
    // Scenarios.
    // ------------------------------------------------------------------

    @Test
    fun exitDuringNormalizationLeavesNoOutboxRowAndNoLateMutation() {
        val normalizerGate = CompletableDeferred<Unit>()
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val vm = contentStage(identityGate, normalizerGate)
        val oldCapsuleId = vm.capsuleId

        vm.startPublishing()
        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value)
        // Parked INSIDE normalization before any file was written.
        assertTrue(stagingDir.listFiles()?.isEmpty() == true)

        // FIX-STATE-13: a plaintext artifact exists INSIDE the owning
        // session's own staging subdirectory when the exit happens.
        val sessionDir = File(stagingDir, vm.capsuleId).apply { mkdirs() }
        File(sessionDir, "mid-flight.jpg").writeBytes("plaintext".toByteArray())

        // Exit tears the publication down.
        vm.endSession()

        // Whatever the cancelled job does afterwards must not resurrect state.
        normalizerGate.complete(Unit)
        identityGate.complete(senderIdentity())

        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value) // dead surface
        assertNull(vm.publishError.value)
        assertTrue(stagingDir.listFiles()?.isEmpty() == true)
        runBlockingNullable { assertNull(outboxRow(oldCapsuleId)) }

        // Re-entry starts fresh and the old job cannot mark IT published.
        vm.beginSession(2L)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.confirmedRecipient.value)
        runBlockingNullable { assertNull(outboxRow(oldCapsuleId)) }
        assertTrue(vm.capsuleId != oldCapsuleId)
    }

    @Test
    fun beginNewEpochWhilePublishSuspendedCannotMarkNewSessionPublished() {
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val vm = contentStage(identityGate)
        val oldCapsuleId = vm.capsuleId

        vm.startPublishing()
        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value)

        // New epoch while parked at the identity boundary.
        vm.beginSession(2L)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.confirmedRecipient.value)

        // The old publish now completes successfully - into the void.
        identityGate.complete(senderIdentity())

        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.publishError.value)
        assertNull(vm.flowError.value)
        runBlockingNullable { assertNull(outboxRow(oldCapsuleId)) }
        assertTrue(stagingDir.listFiles()?.isEmpty() == true)
    }

    @Test
    fun duplicateTapsStaySinglePublishAndTerminalGuardsReplays() {
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val vm = contentStage(identityGate)
        val capsuleId = vm.capsuleId

        vm.startPublishing()
        // Second tap during PUBLISHING: refused visibly, no second job.
        vm.startPublishing()
        assertNotNull(vm.flowError.value)
        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value)

        identityGate.complete(senderIdentity())

        // Durable staging completes on Room's own executors: bounded await.
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) {
                error(
                    "publish never terminated; publishError=" + vm.publishError.value +
                        " flowError=" + vm.flowError.value,
                )
            }
            Thread.sleep(20)
        }
        assertEquals(CreateViewModel.Step.PUBLISHED, vm.step.value)
        runBlockingNullable {
            val row = outboxRow(capsuleId)
            assertNotNull(row)
            assertEquals(OutboxCapsuleState.ENCRYPTED, row!!.state)
        }
        val blobCount = kotlinx.coroutines.runBlocking {
            database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId, userUuid.toString()).size
        }
        assertTrue("expected full artifact set", blobCount >= 5)

        // Replay from PUBLISHED is impossible: still one row, still sealed.
        vm.startPublishing()
        assertEquals(CreateViewModel.Step.PUBLISHED, vm.step.value)
        runBlockingNullable {
            val row = outboxRow(capsuleId)
            assertNotNull(row)
            assertEquals(OutboxCapsuleState.ENCRYPTED, row!!.state)
        }
        assertTrue(stagingDir.listFiles()?.isEmpty() == true)
    }

    private fun runBlockingNullable(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
