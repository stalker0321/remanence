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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * S2b-sender regression: the DEBUG picker selection belongs to exactly one
 * create session. A new epoch/owner (beginSession) or leaving the surface
 * (endSession) clears it, so a later capsule can never inherit a stale
 * track — publish reads [CreateViewModel.musicSelection] live, hence a
 * cleared flow means no snapshot unless reselected. Same-epoch re-entry
 * (rotation) keeps it; photo/note edits intentionally do not clear it
 * (music is independent of photos/note — clearing on keystrokes would
 * destroy the selection with zero safety benefit).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateMusicSelectionSessionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
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
        stagingDir = File(context.filesDir, "music-selection-staging").apply { mkdirs() }
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

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class SelfDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private fun viewModel(): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir))
        return CreateViewModel(
            directory = SelfDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir), retryStore),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
            openPhotoSource = { error("photo picker not used in this test") },
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
    }

    private fun hit() = MusicTrackHit(
        id = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artists = listOf("Arctic Monkeys"),
        version = null,
        release = "Favourite Worst Nightmare",
        year = 2007,
        durationMs = 253000L,
        artworkAvailable = true,
    )

    @Test
    fun selectionClearedOnNewEpochBeginSoLaterPublishHasNone() = runTest {
        val vm = viewModel()
        vm.setMusicSelection(hit())
        assertEquals(hit(), vm.musicSelection.value)

        vm.beginSession(epoch = 2L)

        // The publish site reads musicSelection.value live: null here means
        // the later capsule carries no snapshot unless reselected.
        assertNull(vm.musicSelection.value)
    }

    @Test
    fun selectionClearedOnEndSession() = runTest {
        val vm = viewModel()
        vm.setMusicSelection(hit())
        vm.endSession()
        assertNull(vm.musicSelection.value)
    }

    @Test
    fun sameEpochBeginKeepsSelectionForRotation() = runTest {
        val vm = viewModel()
        vm.beginSession(epoch = 1L)
        vm.setMusicSelection(hit())
        vm.beginSession(epoch = 1L)
        assertEquals(hit(), vm.musicSelection.value)
    }

    @Test
    fun photoAndNoteEditsDoNotClearSelection() = runTest {
        val vm = viewModel()
        vm.setMusicSelection(hit())
        vm.photoSelection.toggle("photo-0")
        assertEquals(true, vm.noteEditor.onChange("dear mama"))
        assertEquals(hit(), vm.musicSelection.value)
    }

    @Test
    fun clearMusicSelectionDropsItImmediately() = runTest {
        val vm = viewModel()
        vm.setMusicSelection(hit())
        vm.clearMusicSelection()
        assertNull(vm.musicSelection.value)
    }
}
