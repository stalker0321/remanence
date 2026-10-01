package dev.hryshyn.remanence.ui.create

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
import org.robolectric.annotation.GraphicsMode
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.MusicArtworkReason
import dev.hryshyn.remanence.core.data.network.MusicArtworkResolution
import dev.hryshyn.remanence.core.data.network.MusicArtworkResolver
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

private fun artworkJpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(0xFFCC6633.toInt())
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * T144: artwork resolution outcome state. A successful selection publishes the
 * descriptor, the decoded preview bitmap and the success reason; null MBID or
 * a failed fetch publishes the exact bounded reason with no bitmap; stale or
 * cleared selections publish nothing from the old epoch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateArtworkResolutionStateTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-artwork-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    private val jpeg = artworkJpeg()
    private val descriptor = GeneratorExpression.ArtworkRef(
        provider = "coverartarchive",
        url = "https://archive.org/download/x/y-500.jpg",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/jpeg",
        width = 8,
        height = 8,
    )

    private var calls = 0
    private var scripted: MusicArtworkResolution =
        MusicArtworkResolution.Resolved(descriptor, jpeg)
    private lateinit var handler: suspend (String?) -> MusicArtworkResolution

    private val fakeResolver = MusicArtworkResolver { mbid: String? ->
        calls++
        handler(mbid)
    }

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "artwork-resolution-staging").apply { mkdirs() }
        calls = 0
        scripted = MusicArtworkResolution.Resolved(descriptor, jpeg)
        handler = { scripted }
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
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
    }

    private class SelfDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private fun viewModel(): CreateViewModel {
        val roots = AccountScopedFileRoots(stagingDir)
        val retryStore = SenderRetryMaterialStore(roots)
        return CreateViewModel(
            directory = SelfDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = CapsuleOutboxStager(database, roots, retryStore),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = roots,
            openPhotoSource = { error("photo picker not used in this test") },
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            musicArtworkResolver = fakeResolver,
        )
    }

    private fun hit(
        recordingMbid: String? = LIVE_MBID,
        id: String = "be30e36b-1111-4111-8111-000000000001",
    ) = MusicTrackHit(
        id = id,
        title = "Is This Love (live)",
        artists = listOf("Whitesnake"),
        version = null,
        release = "1987",
        year = 1987,
        durationMs = 283000L,
        artworkAvailable = true,
        recordingMbid = recordingMbid,
    )

    @Test
    fun successfulSelectionPublishesDescriptorBitmapAndSuccessReason() {
        val vm = viewModel()

        vm.setMusicSelection(hit())

        assertEquals(descriptor, vm.musicArtwork.value)
        assertEquals(MusicArtworkReason.RESOLVED, vm.musicArtworkReason.value)
        val bitmap = vm.musicArtworkBitmap.value
        assertNotNull(bitmap)
        assertEquals(8, bitmap!!.width)
        assertEquals(8, bitmap.height)
        assertEquals(1, calls)
    }

    @Test
    fun nullMbidPublishesNoRecordingIdWithoutCallingResolver() {
        val vm = viewModel()

        vm.setMusicSelection(hit(recordingMbid = null))

        assertEquals(MusicArtworkReason.NO_RECORDING_ID, vm.musicArtworkReason.value)
        assertNull(vm.musicArtwork.value)
        assertNull(vm.musicArtworkBitmap.value)
        assertEquals(0, calls)
    }

    @Test
    fun failedResolutionPublishesMappedReasonAndNoBitmap() {
        scripted = MusicArtworkResolution.Unavailable(MusicArtworkReason.HTTP_ERROR)
        val vm = viewModel()

        vm.setMusicSelection(hit())

        assertEquals(MusicArtworkReason.HTTP_ERROR, vm.musicArtworkReason.value)
        assertNull(vm.musicArtwork.value)
        assertNull(vm.musicArtworkBitmap.value)
    }

    @Test
    fun throwingResolverMapsToNetworkReason() {
        val throwing = MusicArtworkResolver { _: String? -> throw IOException("boom") }
        val roots = AccountScopedFileRoots(stagingDir)
        val vm = CreateViewModel(
            directory = SelfDirectory(),
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = CapsuleOutboxStager(database, roots, SenderRetryMaterialStore(roots)),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = roots,
            openPhotoSource = { error("photo picker not used in this test") },
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            musicArtworkResolver = throwing,
        )

        vm.setMusicSelection(hit())

        assertEquals(MusicArtworkReason.NETWORK, vm.musicArtworkReason.value)
        assertNull(vm.musicArtwork.value)
        assertNull(vm.musicArtworkBitmap.value)
    }

    @Test
    fun staleSelectionCannotPublishAfterClear() {
        val gate = CompletableDeferred<MusicArtworkResolution>()
        handler = { gate.await() }
        val vm = viewModel()

        vm.setMusicSelection(hit())
        vm.clearMusicSelection()
        gate.complete(MusicArtworkResolution.Resolved(descriptor, jpeg))

        assertNull(vm.musicArtwork.value)
        assertNull(vm.musicArtworkReason.value)
        assertNull(vm.musicArtworkBitmap.value)
        assertNull(vm.musicSelection.value)
    }

    @Test
    fun supersededSelectionCannotOverwriteNewerOutcome() {
        val first = CompletableDeferred<MusicArtworkResolution>()
        var n = 0
        handler = {
            if (n++ == 0) {
                first.await()
            } else {
                MusicArtworkResolution.Unavailable(MusicArtworkReason.NO_COVER)
            }
        }
        val vm = viewModel()

        vm.setMusicSelection(hit(id = "be30e36b-1111-4111-8111-000000000001"))
        vm.setMusicSelection(hit(id = "be30e36b-2222-4222-8222-000000000002"))
        first.complete(MusicArtworkResolution.Resolved(descriptor, jpeg))

        assertEquals(MusicArtworkReason.NO_COVER, vm.musicArtworkReason.value)
        assertNull(vm.musicArtwork.value)
        assertNull(vm.musicArtworkBitmap.value)
    }

    @Test
    fun reasonTokensStayWithinTheAllowlist() {
        for (reason in MusicArtworkReason.values()) {
            val line = dev.hryshyn.remanence.core.data.network.artworkResolveDiagnostic(reason)
            assertTrue(line.matches(Regex("^reason=[A-Z_]+$")))
        }
    }

    private companion object {
        const val LIVE_MBID = "300f0db4-c980-4040-bf54-1f5ff81f975c"
    }
}
