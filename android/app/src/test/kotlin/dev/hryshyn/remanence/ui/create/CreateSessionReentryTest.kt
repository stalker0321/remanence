package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * FIX-REVIEW-02 regression: the production CreateViewModel is Activity-scoped
 * and survives conditional navigation, so EVERY re-entry must begin a NEW
 * session - new capsule ID, RECIPIENT_LOOKUP, and empty recipient/photos/
 * note/checklist/errors/capture refs - while persisted sender fingerprints
 * and outbox rows stay untouched (nothing here deletes them).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateSessionReentryTest {

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
        stagingDir = File(context.filesDir, "reentry-staging").apply { mkdirs() }
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

        override suspend fun setPreferredPair(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    /** Directory that resolves every handle to a stable self snapshot. */
    private class SelfDirectory : RecipientDirectoryPort {
        override suspend fun lookup(
            rawHandle: String,
            accessToken: String,
        ): DirectoryLookupResult = DirectoryLookupResult.NotFound
    }

    private fun snapshot() = ResolvedHandleSnapshot(
        userId = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001"),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000b001"),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "unused",
        signingPublicKeysetB64Url = "unused",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun viewModel() = CreateViewModel(
        directory = SelfDirectory(),
        accessTokenProvider = { null },
        identityProvider = { null },
        persistence = NoPersistence(),
        outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, stagingDir),
        profile = RecognitionProfile.mvpOrbV1(),
        stagingDirectory = stagingDir,
        openPhotoSource = { error("photo picker not used in this test") },
    )

    /** Drives one session deep into the flow through public gates only. */
    private fun progressThroughFlow(vm: CreateViewModel): String {
        vm.onResolved(snapshot())
        vm.confirmRecipient()
        repeat(3) { vm.photoSelection.toggle("photo-$it") }
        assertTrue(vm.photoSelection.canProceed)
        assertTrue(vm.noteEditor.onChange("dear mama"))
        dev.hryshyn.remanence.capture.PreparedBackItem.entries.forEach { item ->
            vm.backGate.setChecked(item, true)
        }
        assertTrue(vm.backGate.ready)
        return vm.capsuleId
    }

    @Test
    fun reentryStartsANewSessionWithANewCapsuleIdAndEmptyState() = runTest {
        val vm = viewModel()
        val firstCapsuleId = progressThroughFlow(vm)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)

        // Exit happened; the next entry bumps the epoch.
        vm.beginSession(epoch = 2L)

        assertNotEquals(firstCapsuleId, vm.capsuleId)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.confirmedRecipient.value)
        assertFalse(vm.photoSelection.canProceed)
        assertTrue(vm.photoSelection.selectedIds.isEmpty())
        assertTrue(vm.noteEditor.isEmpty)
        assertFalse(vm.backGate.ready)
        assertNull(vm.publishError.value)
        // FIX-STATE-01: authoritative attempts are reset with the session.
        assertNull(vm.frontAttempt.phase)
        assertNull(vm.backAttempt.phase)
        assertEquals("", vm.pickerVm.handle.value)

        // Persisted material is untouched: no outbox rows were ever removed.
        kotlinx.coroutines.runBlocking {
            assertTrue(database.outboxBlobDao().getAllByCapsuleId(firstCapsuleId).isEmpty())
        }
    }

    @Test
    fun sameEpochBeginIsANoOpSoRotationKeepsTheInProgressSession() = runTest {
        val vm = viewModel()
        vm.beginSession(epoch = 1L)
        val capsuleId = progressThroughFlow(vm)

        // Configuration change recomposes with the SAME epoch.
        vm.beginSession(epoch = 1L)

        assertEquals(capsuleId, vm.capsuleId)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertTrue(vm.photoSelection.canProceed)
        assertTrue(vm.backGate.ready)
    }
}
