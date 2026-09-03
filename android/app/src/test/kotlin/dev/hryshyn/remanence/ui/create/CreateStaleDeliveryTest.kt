package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-STATE-08 (E): dispose/exit DURING processing. The pipeline result of an
 * abandoned attempt can never leak into the fresh session that replaced it -
 * the step stays put, no capture state appears, and nothing is staged.
 *
 * Fully deterministic: the CPU pipeline sits on a paused [StandardTestDispatcher]
 * shared with the test, so "processing still in flight" is represented by
 * QUEUED work instead of any real thread, latch, or sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateStaleDeliveryTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    /** Queues pipeline work; nothing runs until the test advances the scheduler. */
    private val cpuDispatcher = StandardTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "stale-staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
    }

    private class Accepting : StillProcessor {
        var bytes: ByteArray? = null

        override fun process(jpegBytes: ByteArray): ProcessedStill {
            val result = "late-orb".toByteArray()
            bytes = result
            return ProcessedStill.Accepted("mvp-orb-v1", result)
        }
    }

    private class NoPersistence : SealedFingerprintPersistence {
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

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    @Test
    fun exitDuringProcessingLeavesTheFreshSessionUntouched() {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir))
        val processor = Accepting()
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir), retryStore),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
            openPhotoSource = { error("unused") },
            frontProcessor = processor,
            cpuDispatcher = cpuDispatcher,
            ioDispatcher = cpuDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
        vm.beginSession(1L)

        // Walk to FRONT and begin a capture whose pipeline is parked in the queue.
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("in-flight".toByteArray())

        // Delivery marked the attempt Processing; the pipeline work is queued,
        // NOT executed (the scheduler was never advanced).
        assertEquals(CaptureAttemptPhase.Processing, vm.frontAttempt.phase)

        // The user exits; re-entry starts a brand-new session.
        vm.endSession()
        vm.beginSession(2L)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.frontAttempt.phase)

        // NOW the abandoned pipeline finishes with ACCEPTANCE...
        cpuDispatcher.scheduler.advanceUntilIdle()
        assertTrue(processor.bytes!!.all { it == 0.toByte() })

        // ...and changes NOTHING in the fresh session: the terminal publication
        // for the dead attempt is structurally inert.
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.frontAttempt.phase)

        // A late delivery for the dead attempt is silently inert as well.
        vm.deliverFrontJpeg("late-bytes".toByteArray())
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.flowError.value)

        // And nothing was staged for either session.
        kotlinx.coroutines.runBlocking {
            assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner(vm.capsuleId, "0198f0a0-0000-7000-8000-00000000ow01"))
        }
    }

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(UUID.fromString("9b111111-2222-4333-8444-555555555555")),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(UUID.fromString("9b333333-4444-4555-8666-777777777777")),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "enc",
        signingPublicKeysetB64Url = "sig",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )
}
