package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.create.MemoryGeneratorBridge
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frozen CONTENT selected-photo tiles: the strip shows WHICH photos are
 * picked (actual URIs in selection order, each with its selection check),
 * count stays secondary, and thumbnail loading can never invalidate a valid
 * choice. No picker/selection-limit/permission change is under test here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentPhotoTilesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-tiles-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    private val bridge = MemoryGeneratorBridge()

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "tiles-staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
    }

    private class Accepting(private val result: ProcessedStill.Accepted) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = result
    }

    private fun goldenSynthetic(): ProcessedStill.Accepted = ProcessedStill.Accepted(
        profileId = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.PROFILE_ID,
        serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(3),
    )

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

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private val ownerUuid = UUID.fromString("9d111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("9d333333-4444-4555-8666-777777777777")
    private val identity = AccountIdentityGenerator().generate()

    private fun senderIdentity() = SenderIdentitySnapshot(
        userId = ownerUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = bundleUuid.toString(),
        encryptionPrivateHandle = identity.encryptionPrivateHandle,
        signingPrivateHandle = identity.signingPrivateHandle,
    )

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(ownerUuid),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(bundleUuid),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "enc",
        signingPublicKeysetB64Url = "sig",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun newViewModel(
        loader: (suspend (String) -> ImageBitmap?)? = { ImageBitmap(2, 2) },
    ): CreateViewModel {
        val roots = AccountScopedFileRoots(stagingDir)
        val retryStore = SenderRetryMaterialStore(roots)
        return CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { senderIdentity() },
            persistence = NoPersistence(),
            outboxStager = CapsuleOutboxStager(database, roots, retryStore),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = roots,
            openPhotoSource = { pickerId ->
                dev.hryshyn.remanence.create.PhotoSource {
                    java.io.ByteArrayInputStream(ByteArray(8) { pickerId.length.toByte() })
                }
            },
            frontProcessor = Accepting(goldenSynthetic()),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            pickedThumbnailLoader = loader,
        ).also { it.beginSession(1L, ownerUuid.toString()) }
    }

    private fun driveToContent(vm: CreateViewModel, photos: List<String>) {
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        vm.onPhotosPicked(photos)
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
    }

    @Test
    fun tilesExposeActualFourUrisInOrder() {
        val vm = newViewModel()
        val uris = listOf("content://a", "content://b", "content://c", "content://d")
        driveToContent(vm, uris)
        assertEquals(uris, vm.photoSelection.selectedIds)
        assertEquals(uris, vm.pickedThumbnails.value.keys.toList())
        assertTrue(vm.photoSelection.canProceed)
    }

    @Test
    fun repickReplacesStaleThumbnails() {
        val vm = newViewModel()
        driveToContent(vm, listOf("content://a", "content://b", "content://c"))
        vm.onPhotosPicked(listOf("content://d", "content://e", "content://f", "content://g"))
        assertEquals(
            listOf("content://d", "content://e", "content://f", "content://g"),
            vm.pickedThumbnails.value.keys.toList(),
        )
    }

    @Test
    fun loadFailureKeepsValidChoice() {
        val vm = newViewModel(loader = { uri ->
            if (uri == "content://b") throw java.io.IOException("revoked") else ImageBitmap(2, 2)
        })
        val uris = listOf("content://a", "content://b", "content://c", "content://d")
        driveToContent(vm, uris)
        // The failed URI keeps its selection (check tile, no image); the rest load.
        assertEquals(uris, vm.photoSelection.selectedIds)
        assertTrue(vm.photoSelection.canProceed)
        assertEquals(
            listOf("content://a", "content://c", "content://d"),
            vm.pickedThumbnails.value.keys.toList(),
        )
    }

    @Test
    fun readinessNoteMusicUnchanged() {
        val vm = newViewModel()
        driveToContent(vm, listOf("content://a", "content://b"))
        assertFalse(vm.photoSelection.canProceed)
        vm.onPhotosPicked(listOf("content://a", "content://b", "content://c", "content://d"))
        assertTrue(vm.photoSelection.canProceed)
        assertTrue(vm.noteEditor.text.isEmpty())
        assertTrue(vm.noteEditor.canIncludeInCapsule)
        assertNull(vm.musicSelection.value)
    }

    @Test
    fun nullLoaderKeepsSelectionWithoutImages() {
        val vm = newViewModel(loader = null)
        val uris = listOf("content://a", "content://b", "content://c")
        driveToContent(vm, uris)
        assertEquals(uris, vm.photoSelection.selectedIds)
        assertTrue(vm.pickedThumbnails.value.isEmpty())
        assertTrue(vm.photoSelection.canProceed)
    }

    @Test
    fun supersededLoadIsCancelled() {
        val gate = CompletableDeferred<ImageBitmap?>()
        val vm = newViewModel(loader = { uri ->
            if (uri.startsWith("content://old")) gate.await() else ImageBitmap(2, 2)
        })
        vm.beginSession(1L, ownerUuid.toString())
        vm.onPhotosPicked(listOf("content://old1", "content://old2"))
        vm.onPhotosPicked(listOf("content://new1", "content://new2", "content://new3"))
        // The first job is cancelled; only the second selection's keys land.
        assertEquals(
            listOf("content://new1", "content://new2", "content://new3"),
            vm.pickedThumbnails.value.keys.toList(),
        )
    }

    @Test
    fun contentShowsFourTilesWithChecksAndCount() {
        val vm = newViewModel()
        composeRule.runOnIdle {
            driveToContent(vm, listOf("content://a", "content://b", "content://c", "content://d"))
        }
        composeRule.setContent {
            MaterialTheme {
                CreateScreen(viewModel = vm, requestPermissionOnAttach = false)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_photo_tiles").assertIsDisplayed()
        // Plain scrollable strip composes every tile: scroll each into
        // view (throws if absent) to prove all four tiles + checks + images.
        repeat(4) { index ->
            composeRule.onNodeWithTag("create_photo_tile_$index").performScrollTo()
            composeRule.onNodeWithTag("create_photo_tile_check_$index").assertIsDisplayed()
            composeRule.onNodeWithTag("create_photo_tile_image_$index").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("create_photo_tile_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("create_selection_count")
            .assertIsDisplayed()
            .assertTextContains("choose 3–5 photographs · 4")
    }

    @Test
    fun contentShowsChecksWithoutImagesWhenLoadFails() {
        val vm = newViewModel(loader = { null })
        composeRule.runOnIdle {
            driveToContent(vm, listOf("content://a", "content://b", "content://c"))
        }
        composeRule.setContent {
            MaterialTheme {
                CreateScreen(viewModel = vm, requestPermissionOnAttach = false)
            }
        }
        composeRule.waitForIdle()
        repeat(3) { index ->
            composeRule.onNodeWithTag("create_photo_tile_$index").performScrollTo()
            composeRule.onNodeWithTag("create_photo_tile_check_$index").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("create_selection_count")
            .assertTextContains("choose 3–5 photographs · 3")
    }
}
