package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * The mounted Create session observes only its exact owner+capsule outbox
 * projection: pending, retryable, published, and terminal. A previous
 * session's row cannot paint a newer capsule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateUploadStatusObservationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val owner = "0198f0a0-0000-7000-8000-00000000a001"
    private val otherOwner = "0198f0a0-0000-7000-8000-00000000a002"

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "upload-status-staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
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

        override suspend fun setPreferredOrigin(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class ClosedDirectory : RecipientDirectoryPort {
        override suspend fun lookup(
            rawHandle: String,
        ): DirectoryLookupResult = DirectoryLookupResult.NotFound
    }

    private fun viewModel(): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(
            dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
        )
        return CreateViewModel(
            directory = ClosedDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(
                database,
                dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
                retryStore,
            ),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
            openPhotoSource = { error("photo picker not used in this test") },
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            outboxCapsuleDao = database.outboxCapsuleDao(),
        )
    }

    private fun capsule(
        capsuleId: String,
        ownerUserId: String = owner,
        state: OutboxCapsuleState,
        lastErrorCode: String? = null,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) = OutboxCapsuleEntity(
        capsuleId = capsuleId,
        idempotencyKey = idempotencyKey,
        ownerUserId = ownerUserId,
        senderUserId = ownerUserId,
        recipientUserId = "0198f0a0-0000-7000-8000-00000000re01",
        senderKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        senderSigningPublicKeysetB64 = null,
        state = state,
        recognitionManifestPath = null,
        contentManifestPath = null,
        envelopePath = null,
        publishStatementPath = null,
        publishStatementSignaturePath = null,
        senderRetryKeysetPath = null,
        lastErrorCode = lastErrorCode,
    )

    private fun insert(row: OutboxCapsuleEntity) {
        runBlocking { database.outboxCapsuleDao().insertOrAbort(row.ownerUserId, row) }
    }

    private fun awaitStatus(
        vm: CreateViewModel,
        expected: CreateViewModel.CreateUploadStatus,
        timeoutMs: Long = 10_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vm.uploadStatus.value != expected) {
            if (System.currentTimeMillis() > deadline) {
                error("timed out waiting for $expected; was ${vm.uploadStatus.value}")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun currentOwnerAndCapsuleMapsPendingRetryablePublishedAndTerminal() = runTest {
        val vm = viewModel()
        vm.beginSession(1L, owner)
        val capsuleId = vm.capsuleId
        assertEquals(CreateViewModel.CreateUploadStatus.NotStarted, vm.uploadStatus.value)

        insert(capsule(capsuleId, state = OutboxCapsuleState.ENCRYPTED))
        awaitStatus(vm, CreateViewModel.CreateUploadStatus.Pending(OutboxCapsuleState.ENCRYPTED))
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)

        runBlocking {
            database.outboxCapsuleDao().markRetryableFailureForOwner(capsuleId, owner, "NET")
        }
        awaitStatus(vm, CreateViewModel.CreateUploadStatus.RetryableFailure("NET"))

        runBlocking {
            database.outboxCapsuleDao().markTerminalFailureForOwner(capsuleId, owner, "REVOKED")
        }
        awaitStatus(vm, CreateViewModel.CreateUploadStatus.TerminalFailure("REVOKED"))
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
    }

    @Test
    fun publishedRowForTheMountedCapsuleIsObservedWithoutChangingLookupStep() = runTest {
        val vm = viewModel()
        vm.beginSession(1L, owner)
        insert(capsule(vm.capsuleId, state = OutboxCapsuleState.PUBLISHED))
        awaitStatus(vm, CreateViewModel.CreateUploadStatus.Published)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
    }

    @Test
    fun sameEpochRotationKeepsObservedStatusWhileANewEpochDropsIt() = runTest {
        val vm = viewModel()
        vm.beginSession(1L, owner)
        val firstCapsule = vm.capsuleId
        insert(capsule(firstCapsule, state = OutboxCapsuleState.UPLOADING))
        awaitStatus(vm, CreateViewModel.CreateUploadStatus.Pending(OutboxCapsuleState.UPLOADING))

        vm.beginSession(1L, owner)
        assertEquals(firstCapsule, vm.capsuleId)
        assertEquals(
            CreateViewModel.CreateUploadStatus.Pending(OutboxCapsuleState.UPLOADING),
            vm.uploadStatus.value,
        )

        vm.beginSession(2L, owner)
        assertNotEquals(firstCapsule, vm.capsuleId)
        assertEquals(CreateViewModel.CreateUploadStatus.NotStarted, vm.uploadStatus.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
    }

    @Test
    fun staleOwnerOrCapsuleEmissionsCannotPaintANewerSession() = runTest {
        val vm = viewModel()
        vm.beginSession(1L, owner)
        val firstCapsule = vm.capsuleId
        insert(capsule(firstCapsule, state = OutboxCapsuleState.ENCRYPTED))
        awaitStatus(vm, CreateViewModel.CreateUploadStatus.Pending(OutboxCapsuleState.ENCRYPTED))

        vm.beginSession(2L, owner)
        val secondCapsule = vm.capsuleId
        assertNotEquals(firstCapsule, secondCapsule)
        assertEquals(CreateViewModel.CreateUploadStatus.NotStarted, vm.uploadStatus.value)

        runBlocking {
            database.outboxCapsuleDao().markTerminalFailureForOwner(firstCapsule, owner, "STALE")
        }
        Thread.sleep(100)
        assertEquals(CreateViewModel.CreateUploadStatus.NotStarted, vm.uploadStatus.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)

        insert(capsule(UUID.randomUUID().toString(), ownerUserId = otherOwner, state = OutboxCapsuleState.PUBLISHED))
        Thread.sleep(100)
        assertEquals(CreateViewModel.CreateUploadStatus.NotStarted, vm.uploadStatus.value)

        insert(capsule(secondCapsule, state = OutboxCapsuleState.FINALIZING))
        awaitStatus(vm, CreateViewModel.CreateUploadStatus.Pending(OutboxCapsuleState.FINALIZING))
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
    }
}
