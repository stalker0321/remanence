package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import java.io.ByteArrayInputStream
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.create.DefaultGeneratorPreviewLoader
import dev.hryshyn.remanence.create.MemoryGeneratorBridge
import dev.hryshyn.remanence.create.memoryTestJpegForPhotoId
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.NormalizedPhotoDto
import dev.hryshyn.remanence.create.PhotoNormalizerPort
import dev.hryshyn.remanence.create.PhotoSource
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer

/**
 * T150 explicit CONTENT → COMPOSE flow: Next is refused until the form is
 * ready, Back retains every input including the frozen choice, any content
 * edit invalidates the frozen choice, and publishing stays fail-closed
 * without a frozen collage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CreateComposeFlowTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-compose-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    // Shared memory-backed bridge seam so the unfrozen publish test reaches
    // the frozen gate instead of stopping at bridge absence.
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
        stagingDir = File(context.filesDir, "compose-staging").apply { mkdirs() }
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
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private val ownerUuid = UUID.fromString("9c111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("9c333333-4444-4555-8666-777777777777")
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
        keyBundleId = KeyBundleId(UUID.fromString("9c333333-4444-4555-8666-777777777777")),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "enc",
        signingPublicKeysetB64Url = "sig",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun previewLoader() = DefaultGeneratorPreviewLoader(
        openSource = { pickerId ->
            PhotoSource { ByteArrayInputStream(originalFor(pickerId)) }
        },
        decoder = GeneratorSourceBinding.PhotoDecoderPort {
            GeneratorSourceBinding.UprightPhoto(1000, 1000)
        },
        normalizer = PhotoNormalizerPort {
            NormalizedPhotoDto(byteArrayOf(9, 8, 7), 100, 100)
        },
    )

    private fun originalFor(pickerId: String): ByteArray = memoryTestJpegForPhotoId(pickerId)

    private fun newViewModel(): CreateViewModel {
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
            openPhotoSource = { pickerId -> PhotoSource { ByteArrayInputStream(originalFor(pickerId)) } },
            frontProcessor = Accepting(goldenSynthetic()),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            generatorPreviewLoader = previewLoader(),
            generatorBridgeProvider = bridge.provider,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        ).also { it.beginSession(1L, ownerUuid.toString()) }
    }

    private fun driveToContent(vm: CreateViewModel, photos: Int = 3) {
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        vm.onPhotosPicked(List(photos) { "content://$it" })
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
    }

    private fun awaitReadyPreview(vm: CreateViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.generatorPreview.value !is GeneratorPreviewState.Ready) {
            if (System.currentTimeMillis() > deadline) {
                error("preview never Ready, got ${vm.generatorPreview.value}")
            }
            Thread.sleep(20)
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

    private fun hit(id: String) = MusicTrackHit(
        id = id,
        title = "Is This Love (live)",
        artists = listOf("Whitesnake"),
        version = null,
        release = "1987",
        year = 1987,
        durationMs = 283000L,
        artworkAvailable = true,
        recordingMbid = null,
    )

    // ------------------------------------------------------------------
    // Next / Back.
    // ------------------------------------------------------------------

    @Test
    fun nextMovesReadyFormToCompose() {
        val vm = newViewModel()
        driveToContent(vm)

        assertTrue(vm.goToCompose())
        assertEquals(CreateViewModel.Step.COMPOSE, vm.step.value)
    }

    @Test
    fun nextRefusesUnreadyFormWithVisibleError() {
        val vm = newViewModel()
        driveToContent(vm, photos = 2)

        assertFalse(vm.goToCompose())
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.flowError.value)
    }

    @Test
    fun overlongNoteIsRefusedAtKeystrokeAndFormStaysSubmittable() {
        val vm = newViewModel()
        driveToContent(vm)
        // The 1000-byte limit rejects whole input at keystroke: nothing is
        // truncated into the form, and the ready form still reaches COMPOSE.
        assertFalse(vm.noteEditor.onChange("x".repeat(1001)))
        assertTrue(vm.noteEditor.limitReached)
        assertEquals("", vm.noteEditor.text)

        assertTrue(vm.goToCompose())
        assertEquals(CreateViewModel.Step.COMPOSE, vm.step.value)
    }

    @Test
    fun backOffComposeIsSilentNoop() {
        val vm = newViewModel()
        driveToContent(vm)

        assertFalse(vm.backToContent())
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNull(vm.flowError.value)
    }

    // ------------------------------------------------------------------
    // Back retains inputs; edits invalidate the frozen choice.
    // ------------------------------------------------------------------

    private fun freezeFirstCandidate(vm: CreateViewModel) {
        awaitReadyPreview(vm)
        val input = vm.collageChooserInput()
            ?: error("expected chooser input, got ${vm.generatorPreview.value}")
        val provider = CollageCandidateProvider(
            CollageCropSource { requests ->
                requests.associate { request ->
                    request.contentId to GeneratorExpression.CropWindow(0, 0, 10_000, 10_000)
                }
            },
            CollageRenderer.TextMeasurer { request ->
                CollageRenderer.TextMeasurement(1, request.line.coerceAtLeast(1), false)
            },
        )
        val result = kotlinx.coroutines.runBlocking {
            provider.candidates(input, null, null)
        }
        val candidates = (result as? CollageProviderResult.Candidates)?.candidates
            ?: error("expected candidates, got $result")
        vm.collageChoices.replaceCandidates(candidates)
        assertTrue(vm.selectCollageCandidate(0))
        assertTrue(vm.freezeCollageSelection())
    }

    @Test
    fun backRetainsPhotosAndFrozenChoice() {
        val vm = newViewModel()
        driveToContent(vm)
        freezeFirstCandidate(vm)
        val frozenId = requireNotNull(vm.frozenCollageSelection).candidateId
        assertTrue(vm.goToCompose())

        assertTrue(vm.backToContent())

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals(3, vm.photoSelection.selectedIds.size)
        assertEquals(frozenId, requireNotNull(vm.frozenCollageSelection).candidateId)
    }

    @Test
    fun backRetainsNoteAndMusicWithoutFrozen() {
        val vm = newViewModel()
        driveToContent(vm)
        assertTrue(vm.noteEditor.onChange("dear mama"))
        vm.setMusicSelection(hit("be30e36b-1111-4111-8111-000000000001"))
        assertTrue(vm.goToCompose())

        assertTrue(vm.backToContent())

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals(3, vm.photoSelection.selectedIds.size)
        assertEquals("dear mama", vm.noteEditor.text)
        assertEquals(
            "be30e36b-1111-4111-8111-000000000001",
            requireNotNull(vm.musicSelection.value).id,
        )
        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun photoEditInvalidatesFrozenChoice() {
        val vm = newViewModel()
        driveToContent(vm)
        freezeFirstCandidate(vm)
        assertNotNull(vm.frozenCollageSelection)

        vm.onPhotosPicked(listOf("content://0", "content://1", "content://9"))

        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun noteEditInvalidatesFrozenChoice() {
        val vm = newViewModel()
        driveToContent(vm)
        freezeFirstCandidate(vm)
        assertNotNull(vm.frozenCollageSelection)

        assertTrue(vm.noteEditor.onChange("changed"))

        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun musicReselectInvalidatesFrozenChoice() {
        val vm = newViewModel()
        driveToContent(vm)
        vm.setMusicSelection(hit("be30e36b-1111-4111-8111-000000000001"))
        freezeFirstCandidate(vm)
        assertNotNull(vm.frozenCollageSelection)

        vm.setMusicSelection(hit("be30e36b-2222-4222-8222-000000000002"))

        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun publishRefusesUnfrozenCompose() {
        val vm = newViewModel()
        driveToContent(vm)
        assertTrue(vm.goToCompose())

        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.publishError.value)
        assertTrue(
            "publishError=" + vm.publishError.value,
            vm.publishError.value!!.contains("frozen"),
        )
    }

    // ------------------------------------------------------------------
    // Crop seam: VM-owned per-photo corrections, display only.
    // ------------------------------------------------------------------

    private fun previewContentIds(vm: CreateViewModel): List<String> {
        awaitReadyPreview(vm)
        return when (val preview = vm.generatorPreview.value) {
            is GeneratorPreviewState.Ready -> preview.sources.map { it.contentId }
            else -> error("expected Ready sources")
        }
    }

    @Test
    fun cropOpenRequiresComposeAndKnownPhoto() {
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)

        assertFalse(vm.openCrop(ids.first()))
        assertNull(vm.cropSlot.value)

        assertTrue(vm.goToCompose())
        assertFalse(vm.openCrop("unknown-content-id"))
        assertNull(vm.cropSlot.value)
        assertNotNull(vm.flowError.value)

        assertTrue(vm.openCrop(ids.first()))
        assertEquals(ids.first(), vm.cropSlot.value)
    }

    @Test
    fun cropUpdateClampsToPrototypeBounds() {
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)
        assertTrue(vm.goToCompose())
        assertTrue(vm.openCrop(ids.first()))

        vm.updateCrop(5f, 100f, -100f)

        val correction = requireNotNull(vm.cropCorrections.value[ids.first()])
        assertEquals(1.25f, correction.zoom)
        assertEquals(12.5f, correction.panX)
        assertEquals(-12.5f, correction.panY)
    }

    @Test
    fun cropResetRemovesEntryAndClosePreserves() {
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)
        assertTrue(vm.goToCompose())
        assertTrue(vm.openCrop(ids.first()))
        vm.updateCrop(1.1f, 2f, 3f)
        assertNotNull(vm.cropCorrections.value[ids.first()])

        assertTrue(vm.closeCrop())
        assertNull(vm.cropSlot.value)
        assertNotNull(vm.cropCorrections.value[ids.first()])

        assertTrue(vm.openCrop(ids.first()))
        vm.resetCrop()
        assertNull(vm.cropCorrections.value[ids.first()])
        assertEquals(ids.first(), vm.cropSlot.value)
    }

    @Test
    fun cropUpdateWithoutSlotIsNoop() {
        val vm = newViewModel()
        driveToContent(vm)
        assertTrue(vm.goToCompose())

        vm.updateCrop(1.2f, 5f, 5f)
        vm.resetCrop()

        assertTrue(vm.cropCorrections.value.isEmpty())
        assertFalse(vm.closeCrop())
    }

    @Test
    fun photoEditClearsCropState() {
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)
        assertTrue(vm.goToCompose())
        assertTrue(vm.openCrop(ids.first()))
        vm.updateCrop(1.2f, 5f, 5f)
        assertNotNull(vm.cropCorrections.value[ids.first()])

        vm.backToContent()
        vm.onPhotosPicked(listOf("content://0", "content://1", "content://9"))

        assertNull(vm.cropSlot.value)
        assertTrue(vm.cropCorrections.value.isEmpty())
    }

    @Test
    fun candidateNavigationClearsCropCorrections() {
        // T157 §6: the VM stores per-photo values only, so moving to another
        // sheet drops them fail-closed instead of reusing them cross-slot.
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)
        assertTrue(vm.goToCompose())
        assertTrue(vm.openCrop(ids.first()))
        vm.updateCrop(1.2f, 5f, 5f)
        assertNotNull(vm.cropCorrections.value[ids.first()])

        vm.clearFrozenCollageSelection()

        assertNull(vm.cropSlot.value)
        assertTrue(vm.cropCorrections.value.isEmpty())
    }
}
