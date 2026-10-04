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
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
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
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            result.copy(serializedBytes = result.serializedBytes.copyOf())
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

    private fun newViewModel(
        withCropSource: Boolean = false,
        cornerFace: Boolean = false,
        persistence: SealedFingerprintPersistence = NoPersistence(),
    ): CreateViewModel {
        val roots = AccountScopedFileRoots(stagingDir)
        val retryStore = SenderRetryMaterialStore(roots)
        return CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = { senderIdentity() },
            persistence = persistence,
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
            collageSourceFactory = if (withCropSource) testCollageSourceFactory(cornerFace) else null,
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
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
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

        // Refusing before entering PUBLISHING leaves the COMPOSE step (never
        // entered publishing); the typed pre-publish guard names freezing,
        // like the photo/note/recipient guards above it (publishError stays
        // for async publish failures).
        assertEquals(CreateViewModel.Step.COMPOSE, vm.step.value)
        assertNotNull(vm.flowError.value)
        assertTrue(
            "flowError=" + vm.flowError.value,
            vm.flowError.value!!.contains("frozen"),
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

        CollageFreezeFixture.freezeFirstCandidate(vm)
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
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())
        assertTrue(vm.openCrop(ids.first()))

        vm.updateCrop(5f, 100f, -100f)

        val correction = requireNotNull(vm.cropCorrections.value[ids.first()])
        assertEquals(1.25f, correction.zoom)
        assertEquals(12.5f, correction.panX)
        assertEquals(-12.5f, correction.panY)
    }

    @Test
    fun cancelDiscardsDraftAndResetRestoresOriginalFrame() {
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())
        assertTrue(vm.openCrop(ids.first()))
        vm.updateCrop(1.1f, 2f, 3f)
        assertNotNull(vm.cropCorrections.value[ids.first()])

        assertTrue(vm.closeCrop())
        assertNull(vm.cropSlot.value)
        assertNull(vm.cropCorrections.value[ids.first()])

        assertTrue(vm.openCrop(ids.first()))
        vm.resetCrop()
        val reset = requireNotNull(vm.cropCorrections.value[ids.first()])
        assertEquals(1f, reset.zoom)
        assertEquals(0f, reset.panX)
        assertEquals(0f, reset.panY)
        assertEquals(ids.first(), vm.cropSlot.value)
    }

    @Test
    fun cropUpdateWithoutSlotIsNoop() {
        val vm = newViewModel()
        driveToContent(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())

        vm.updateCrop(1.2f, 5f, 5f)
        vm.resetCrop()

        assertTrue(vm.cropCorrections.value.isEmpty())
        assertFalse(vm.closeCrop())
    }

    @Test
    fun returningToPostcardRestartsCameraAndRetainsTheContent() {
        val capturedCapsules = mutableListOf<String>()
        val persistence = object : SealedFingerprintPersistence by NoPersistence() {
            override suspend fun persist(
                capsuleId: String,
                origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
                profileId: String,
                plaintextBytes: ByteArray,
            ): String {
                if (capsuleId in capturedCapsules) {
                    throw dev.hryshyn.remanence.core.data.fingerprints.DuplicateFingerprintException(capsuleId, origin)
                }
                capturedCapsules += capsuleId
                return "fp-${capturedCapsules.size}"
            }
        }
        val vm = newViewModel(persistence = persistence)
        driveToContent(vm)
        val originalCapsuleId = vm.capsuleId
        vm.noteEditor.onChange("keep this note")
        val track = hit("8fc55a01-ccd2-4b27-bcda-8d3a9aafc512")
        vm.setMusicSelection(track)
        val photos = vm.photoSelection.selectedIds.toList()
        assertTrue(vm.backToFront())
        assertNotEquals(originalCapsuleId, vm.capsuleId)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertEquals(dev.hryshyn.remanence.capture.CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        assertEquals(photos, vm.photoSelection.selectedIds)
        assertEquals("keep this note", vm.noteEditor.text)
        assertEquals(track, vm.musicSelection.value)
        assertFalse(vm.goToCompose())
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("replacement postcard".toByteArray())
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals(listOf(originalCapsuleId, vm.capsuleId), capturedCapsules)
        assertEquals(photos, vm.photoSelection.selectedIds)
        assertEquals("keep this note", vm.noteEditor.text)
        assertTrue(vm.goToCompose())
    }

    @Test
    fun returningToBrowsingClearsSelectionWithoutChangingDisplayedCandidate() {
        val vm = newViewModel()
        driveToContent(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())
        val displayed = vm.collageChoices.displayed
        assertNotNull(vm.collageChoices.selected)
        vm.clearFrozenCollageSelection()
        assertEquals(displayed, vm.collageChoices.displayed)
        assertNull(vm.collageChoices.selected)
        assertNull(vm.frozenCollageSelection)
        assertTrue(vm.openCrop(previewContentIds(vm).first()))
        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun openCropWorksOnDisplayedCandidateBeforeSelection() {
        val vm = newViewModel()
        driveToContent(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())

        // The displayed page is editable before an explicit selection.
        vm.collageChoices.next()
        vm.clearFrozenCollageSelection()
        assertTrue(vm.openCrop(previewContentIds(vm).first()))
        assertNotNull(vm.cropSlot.value)
        assertNull(vm.frozenCollageSelection)
        assertNull(vm.collageChoices.selected)
    }

    @Test
    fun openCropWorksAfterBrowsingToAnotherUnselectedCandidate() {
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())

        // Reference ordering works: select, then open the stage.
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue(vm.selectCollageCandidate(0))
        assertTrue(vm.freezeCollageSelection())
        assertTrue(vm.openCrop(ids.first()))
        assertEquals(ids.first(), vm.cropSlot.value)
        assertTrue(vm.closeCrop())

        // Browsing shows another candidate with its own editable geometry.
        vm.collageChoices.next()
        vm.clearFrozenCollageSelection()
        assertTrue(vm.openCrop(ids.first()))
        assertEquals(ids.first(), vm.cropSlot.value)
        assertNull(vm.frozenCollageSelection)
        assertNull(vm.collageChoices.selected)
    }

    @Test
    fun unknownCandidateCannotSelectOrFreeze() {
        val vm = newViewModel()
        driveToContent(vm)
        previewContentIds(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())
        // Browse to a candidate that was never reported drawable (mimics
        // the host path where its prepare never succeeded).
        vm.collageChoices.next()
        vm.clearFrozenCollageSelection()
        assertFalse(vm.selectCollageCandidate(1))
        assertFalse(vm.freezeCollageSelection())
        assertNull(vm.frozenCollageSelection)
        // A report for an unknown id approves nothing either.
        vm.onCandidatePrepared("ber1-unknown", true, vm.prepareInputs())
        assertFalse(vm.selectCollageCandidate(1))
    }

    @Test
    fun staleProofCannotSelect() {
        val vm = newViewModel()
        driveToContent(vm)
        previewContentIds(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
        val id = vm.collageChoices.candidates[0].candidateId
        // Tampered note content must not match the live key.
        val stale = vm.prepareInputs().copy(note = "stale-note")
        vm.onCandidatePrepared(id, true, stale)
        assertFalse(vm.selectCollageCandidate(0))
        // The exact current key recovers.
        vm.onCandidatePrepared(id, true, vm.prepareInputs())
        assertTrue(vm.selectCollageCandidate(0))
    }

    @Test
    fun readyToRejectedClearsFrozenAndBlocksPublish() {        val vm = newViewModel()
        driveToContent(vm)
        previewContentIds(vm)
        val frozenId = CollageFreezeFixture.freezeFirstCandidate(vm)
        assertTrue(vm.goToCompose())
        // The sheet flips Ready -> Rejected under identical inputs
        // (e.g. late artwork changing the measured band).
        vm.onCandidatePrepared(frozenId, false, vm.prepareInputs())
        assertNull(vm.frozenCollageSelection)
        vm.startPublishing()
        assertEquals(CreateViewModel.Step.COMPOSE, vm.step.value)
        assertTrue(vm.flowError.value!!.contains("frozen"))
    }

    @Test
    fun photoEditClearsCropState() {
        val vm = newViewModel()
        driveToContent(vm)
        val ids = previewContentIds(vm)
        CollageFreezeFixture.freezeFirstCandidate(vm)
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
    fun savedCropSurvivesBrowsingAndNeverSelectsTheCandidate() = runBlocking {
        val vm = newViewModel(withCropSource = true)
        driveToContent(vm)
        val ids = previewContentIds(vm)
        CollageFreezeFixture.freezeFirstCandidateWithProductionSource(vm)
        assertTrue(vm.goToCompose())
        // Explicitly return to browsing before opening the crop.
        vm.collageChoices.next()
        vm.collageChoices.previous()
        vm.clearFrozenCollageSelection()
        assertNull(vm.collageChoices.selected)
        assertTrue(vm.openCrop(ids.first()))
        vm.updateCrop(1.02f, 0f, 0f)
        assertTrue("crop refusal: " + vm.flowError.value, vm.applyCropAndClose())
        val corrected = requireNotNull(vm.collageChoices.displayed)
        val savedWindow = corrected.expression.placements.first { it.contentId == ids.first() }.crop
        assertNull(vm.frozenCollageSelection)
        assertNull(vm.collageChoices.selected)
        assertNull(vm.cropSlot.value)

        vm.collageChoices.next()
        vm.clearFrozenCollageSelection()
        assertTrue(vm.cropCorrections.value.isEmpty())
        vm.collageChoices.previous()
        vm.clearFrozenCollageSelection()
        assertEquals(corrected.candidateId, vm.collageChoices.displayed?.candidateId)
        assertEquals(savedWindow, vm.collageChoices.displayed!!.expression.placements
            .first { it.contentId == ids.first() }.crop)
        assertEquals(1.02f, vm.cropCorrections.value.getValue(ids.first()).zoom)
        assertTrue(vm.openCrop(ids.first()))
        vm.updateCrop(1.1f, 2f, 3f)
        assertTrue(vm.closeCrop())
        assertEquals(1.02f, vm.cropCorrections.value.getValue(ids.first()).zoom)
        assertEquals(savedWindow, vm.collageChoices.displayed!!.expression.placements
            .first { it.contentId == ids.first() }.crop)
        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun cropApplySealsNewWindowAndFrozenHash() = runBlocking {
        // Real safe crop: production crop source + its OWN cached analysis
        // (shared instance), T157 correct() gate, quantized window, new
        // authored expression and a new frozen candidate id/hash.
        val vm = newViewModel(withCropSource = true)
        driveToContent(vm)
        val ids = previewContentIds(vm)
        val beforeId = CollageFreezeFixture.freezeFirstCandidateWithProductionSource(vm)
        assertTrue(vm.goToCompose())
        assertTrue(vm.openCrop(ids.first()))
        val beforeCrop = requireNotNull(
            requireNotNull(vm.collageChoices.selected).expression.placements
                .first { it.contentId == ids.first() }.crop,
        )

        // A modest zoom: the corrected window must stay above the frozen
        // protection-field floor (PROTECTION_FIELD_LOW) for the narrow slot —
        // the classifier is never waived to make the test pass.
        vm.updateCrop(1.02f, 0f, 0f)
        // Evaluate first: the message must report the POST-apply state.
        val applied = vm.applyCropAndClose()
        assertTrue(
            "apply must succeed, applied=$applied slot=${vm.cropSlot.value} " +
                "selected=${vm.collageChoices.selected?.candidateId} " +
                "flowError=${vm.flowError.value}",
            applied,
        )

        assertNull(vm.cropSlot.value)
        assertEquals(1.02f, vm.cropCorrections.value.getValue(ids.first()).zoom)
        val frozen = requireNotNull(vm.frozenCollageSelection)
        assertNotEquals("the frozen identity must change with the corrected window", beforeId, frozen.candidateId)
        val expression = frozen.expression
        assertTrue(
            GeneratorExpression.validateResolved(expression) is GeneratorExpression.InputValidation.Valid,
        )
        val afterCrop = requireNotNull(
            expression.placements.first { it.contentId == ids.first() }.crop,
        )
        assertNotEquals("the sealed window must move with the zoom", beforeCrop, afterCrop)
        // The corrected window is strictly inside the approved one (zoom in).
        assertTrue(afterCrop.width < beforeCrop.width)
        assertTrue(afterCrop.height < beforeCrop.height)
        assertEquals(
            "candidate id is the canonical hash of the corrected expression",
            GeneratorBer1Provider.candidateIdFor(expression),
            frozen.candidateId,
        )
        assertTrue(ids.first() in expression.placements.map { it.contentId })
    }

    /**
     * F1 wiring regression (peer-crop-integration): `applyCropAndClose` must
     * re-classify the corrected window under the placement's OWN
     * [GeneratorExpression.PhotoShape]. With a face parked in the extreme
     * corner of the analysis:
     * - the identical RECT placement applies (the frozen rect mask keeps it);
     * - a ROUND V4 placement of the same window is refused with the frozen
     *   `FACE_CROSSES_CROP_OR_MASK` reason, and the frozen selection is left
     *   untouched — the round/ellipse mask safety is never waived.
     */
    @Test
    fun roundedApertureCorrectionIsRefusedByTheFrozenMask() = runBlocking {
        val rectVm = newViewModel(withCropSource = true, cornerFace = true)
        driveToContent(rectVm)
        val rectIds = previewContentIds(rectVm)
        CollageFreezeFixture.freezeFirstCandidate(rectVm, aspectMatchedSlots = true)
        assertTrue(rectVm.goToCompose())
        assertTrue(rectVm.openCrop(rectIds.first()))
        rectVm.updateCrop(1f, 0f, 0f)
        assertTrue(
            "RECT control must apply, flowError=${rectVm.flowError.value}",
            rectVm.applyCropAndClose(),
        )

        val roundVm = newViewModel(withCropSource = true, cornerFace = true)
        driveToContent(roundVm)
        val roundIds = previewContentIds(roundVm)
        val frozenBefore = CollageFreezeFixture.freezeRoundCandidate(roundVm, aspectMatchedSlots = true)
        assertTrue(roundVm.goToCompose())
        assertTrue(roundVm.openCrop(roundIds.first()))
        roundVm.updateCrop(1f, 0f, 0f)
        assertFalse(
            "a ROUND aperture must be re-checked under the round mask",
            roundVm.applyCropAndClose(),
        )
        val refusal = requireNotNull(roundVm.flowError.value)
        assertTrue(
            "the typed frozen mask reason must surface, got $refusal",
            refusal.contains("FACE_CROSSES_CROP_OR_MASK"),
        )
        assertEquals(
            "the frozen selection must stay untouched when the correction is refused",
            frozenBefore,
            roundVm.frozenCollageSelection?.candidateId,
        )
    }

    // ------------------------------------------------------------------
    // F1 source-dimension repair (preview vs authoritative analysis dims).
    // ------------------------------------------------------------------

    /**
     * Same VM wiring as [newViewModel], but the preview loader reports reduced
     * preview dims while the upright/G1 dims (and optionally the authoritative
     * analysis dims) are full resolution. Used to pin the caller fix:
     * `loadAllFamilyPhotos` must take descriptor dims from
     * `GeneratorInput.photos` (upright), not `previewWidthPx/HeightPx`.
     */
    private fun newViewModelWithDims(
        uprightWidth: Int,
        uprightHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        analysisWidth: Int,
        analysisHeight: Int,
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
            openPhotoSource = { pickerId -> PhotoSource { ByteArrayInputStream(originalFor(pickerId)) } },
            frontProcessor = Accepting(goldenSynthetic()),
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            generatorPreviewLoader = DefaultGeneratorPreviewLoader(
                openSource = { pickerId -> PhotoSource { ByteArrayInputStream(originalFor(pickerId)) } },
                decoder = GeneratorSourceBinding.PhotoDecoderPort {
                    GeneratorSourceBinding.UprightPhoto(uprightWidth, uprightHeight)
                },
                normalizer = PhotoNormalizerPort {
                    NormalizedPhotoDto(byteArrayOf(9, 8, 7), previewWidth, previewHeight)
                },
            ),
            generatorBridgeProvider = bridge.provider,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            collageSourceFactory = testCollageSourceFactoryWithAnalysisDims(analysisWidth, analysisHeight),
        ).also { it.beginSession(1L, ownerUuid.toString()) }
    }

    @Test
    fun allFamilySealsV4WhenPreviewDimsDifferFromUprightAndAnalysisMatchesUpright() = runBlocking {
        // preview 2560x1920 (normalized), upright + analysis 4000x3000: the
        // descriptor must carry the upright dims so the identity guard passes
        // and the real all-family V4 producer yields sealed candidates.
        val vm = newViewModelWithDims(
            uprightWidth = 4000,
            uprightHeight = 3000,
            previewWidth = 2560,
            previewHeight = 1920,
            analysisWidth = 4000,
            analysisHeight = 3000,
        )
        driveToContent(vm, photos = 3)
        awaitReadyPreview(vm)
        val sealed = requireNotNull(vm.buildAllFamilySealedCandidates()) {
            "all-family producer must seal V4 when descriptor dims come from the upright input"
        }
        assertTrue("must yield candidates", sealed.isNotEmpty())
        sealed.forEach { candidate ->
            assertEquals(
                GeneratorExpression.EXPRESSION_CONTRACT_V4,
                candidate.expression.expressionContractVersion,
            )
            assertTrue(
                "sealed expression must validate",
                GeneratorExpression.validateResolved(candidate.expression) is
                    GeneratorExpression.InputValidation.Valid,
            )
            assertEquals(
                "candidate identity must be the canonical expression hash",
                GeneratorBer1Provider.candidateIdFor(candidate.expression),
                candidate.candidateId,
            )
        }
    }

    @Test
    fun allFamilyFailsClosedWhenAnalysisDimsDoNotMatchUpright() = runBlocking {
        // Analysis 2000x1500 != upright descriptor 4000x3000: the strict
        // analysis-dimension guard must still fail closed (no spoofed resize).
        val vm = newViewModelWithDims(
            uprightWidth = 4000,
            uprightHeight = 3000,
            previewWidth = 2560,
            previewHeight = 1920,
            analysisWidth = 2000,
            analysisHeight = 1500,
        )
        driveToContent(vm, photos = 3)
        awaitReadyPreview(vm)
        assertNull(
            "a mismatched analysis must not be scored",
            vm.buildAllFamilySealedCandidates(),
        )
    }
}
