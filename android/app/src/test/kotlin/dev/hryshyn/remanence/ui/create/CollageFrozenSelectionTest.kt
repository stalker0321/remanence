package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.create.DefaultGeneratorPreviewLoader
import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.MemoryGeneratorBridge
import dev.hryshyn.remanence.create.NormalizedPhotoDto
import dev.hryshyn.remanence.create.PhotoNormalizerPort
import dev.hryshyn.remanence.create.PhotoSource
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T001 explicit frozen selection: the pure [buildFrozenSelection] validator
 * recomputes (never trusts) identity, rejects stale/tampered input without
 * side effects, and the [CreateViewModel] freezes only on explicit action,
 * clearing on every photo/note/track edit, ownership reset, and candidate
 * rebuild — while collage publication stays fail-closed. Fake crop/source
 * data throughout; no production publish path is exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageFrozenSelectionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val capsuleId = "1f0a1234-5678-4abc-9def-aabbccdd2001"
    private val senderUserUuid = UUID.fromString("ae111111-2222-4333-8444-555555555555")

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-frozen-${UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    private val senderIdentity = AccountIdentityGenerator().generate()
    private val senderBundleUuid = UUID.fromString("ae333333-4444-4555-8666-777777777777")

    private val bridge = dev.hryshyn.remanence.create.MemoryGeneratorBridge()

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "frozen-staging").apply { mkdirs() }
        outboxDir = File(context.filesDir, "frozen-outbox")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
        outboxDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Candidate fixtures (fake crops, real provider).
    // ------------------------------------------------------------------

    private fun measurer() = CollageRenderer.TextMeasurer { request ->
        CollageRenderer.TextMeasurement(
            width = (request.text.length * 8).coerceIn(1, request.maxWidth),
            height = request.line.coerceIn(1, request.maxHeight),
            hasVisualOverflow = false,
        )
    }

    private fun cropSource() = dev.hryshyn.remanence.ui.collage.CollageCropSource { requests ->
        requests.mapIndexed { index, request ->
            request.contentId to GeneratorExpression.CropWindow(index * 100, 0, 5000, 10000)
        }.toMap()
    }

    private fun photoInput() = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = listOf("p0", "p1", "p2").mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
        },
        note = null,
        music = null,
    )

    private suspend fun builtCandidates(): List<CollageCandidate> {
        val result = CollageCandidateProvider(cropSource(), measurer())
            .candidates(photoInput(), null, null)
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)
        return (result as CollageProviderResult.Candidates).candidates
    }

    private suspend fun candidatesFor(input: GeneratorExpression.GeneratorInput): List<CollageCandidate> {
        val result = CollageCandidateProvider(cropSource(), measurer())
            .candidates(input, null, null)
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)
        return (result as CollageProviderResult.Candidates).candidates
    }

    // ------------------------------------------------------------------
    // Pure validator.
    // ------------------------------------------------------------------

    @Test
    fun freezeValidCandidateRecomputesIdentities() = runTest {
        val candidates = builtCandidates()
        val candidate = candidates.first()
        val input = candidate.expression.input
        val frozen = buildFrozenSelection(candidate, input, capsuleId)
        assertTrue("expected frozen selection", frozen != null)
        frozen!!
        assertEquals(GeneratorBer1Provider.candidateIdFor(candidate.expression), frozen.candidateId)
        assertEquals(candidate.candidateId, frozen.candidateId)
        assertEquals(GeneratorExpression.canonicalHash(input), frozen.inputHash)
        assertEquals(
            CapsulePhotoIdentity.projectedHash(
                candidate.expression,
                frozen.candidateId,
                CapsuleId(UUID.fromString(capsuleId)),
                input.photos.map { it.contentId },
            ),
            frozen.projectedHash,
        )
        assertEquals(candidate.expression, frozen.expression)
    }

    @Test
    fun missingSelectionOrInputFailsClosed() = runTest {
        val candidate = builtCandidates().first()
        assertNull(buildFrozenSelection(null, candidate.expression.input, capsuleId))
        assertNull(buildFrozenSelection(candidate, null, capsuleId))
    }

    @Test
    fun tamperedCandidateIdIsRejected() = runTest {
        val candidate = builtCandidates().first()
        val tampered = candidate.copy(candidateId = "ber1-tampered")
        assertNull(buildFrozenSelection(tampered, candidate.expression.input, capsuleId))
    }

    @Test
    fun swappedExpressionIsRejected() = runTest {
        val candidates = builtCandidates()
        val swapped = candidates[0].copy(expression = candidates[1].expression)
        assertNull(buildFrozenSelection(swapped, candidates[0].expression.input, capsuleId))
    }

    @Test
    fun staleInputIsRejected() = runTest {
        val candidate = builtCandidates().first()
        val staleInput = candidate.expression.input.copy(note = "later note")
        assertNull(buildFrozenSelection(candidate, staleInput, capsuleId))
    }

    @Test
    fun invalidExpressionIsRejected() = runTest {
        val candidate = builtCandidates().first()
        val invalid = candidate.copy(
            expression = candidate.expression.copy(expressionContractVersion = 999),
        )
        assertNull(buildFrozenSelection(invalid, candidate.expression.input, capsuleId))
    }

    @Test
    fun unparsableCapsuleIdIsRejected() = runTest {
        val candidate = builtCandidates().first()
        assertNull(buildFrozenSelection(candidate, candidate.expression.input, "not-a-uuid"))
    }

    @Test
    fun candidateRebuildFiresHookButClearAndSelectDoNot() {
        val session = CollageChoiceSession()
        var fires = 0
        session.onCandidatesReplaced = { fires += 1 }
        val first = listOf(candidateForHook("a"))
        session.replaceCandidates(first)
        assertEquals(1, fires)
        session.select(0)
        assertEquals(1, fires)
        session.clear()
        assertEquals(1, fires)
        session.replaceCandidates(first)
        assertEquals(2, fires)
    }

    private fun candidateForHook(id: String) = CollageCandidate(
        candidateId = id,
        styleId = id,
        branchId = "editorial-1+2-equal",
        expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "remanence-editorial-rows",
            grammarVersion = 1,
            branchId = "editorial-1+2-equal",
            input = photoInput(),
            placements = emptyList(),
            noteTreatment = "regular-region",
            diagnostics = emptyList(),
            fontVersion = 1,
            paletteVersion = 1,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        ),
    )

    // ------------------------------------------------------------------
    // ViewModel harness.
    // ------------------------------------------------------------------

    private class StubPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp-1"

        override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
    }

    private class SelfDirectory : RecipientDirectoryPort {
        private var byHandle: Map<String, ResolvedHandleSnapshot> = emptyMap()

        fun set(entries: Map<String, ResolvedHandleSnapshot>) {
            byHandle = entries
        }

        override suspend fun lookup(rawHandle: String): dev.hryshyn.remanence.core.data.network.DirectoryLookupResult =
            byHandle[rawHandle]?.let { dev.hryshyn.remanence.core.data.network.DirectoryLookupResult.Found(it) }
                ?: dev.hryshyn.remanence.core.data.network.DirectoryLookupResult.NotFound
    }

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = ProcessedStill.Accepted(
            profileId = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.PROFILE_ID,
            serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(6),
        )
    }

    private fun originalFor(pickerId: String): ByteArray =
        ByteArray(64) { ((it * 31 + pickerId.hashCode()) and 0xFF).toByte() }

    /**
     * Real decodable JPEG bytes (EXIF parse succeeds): preread validates
     * through the production EXIF decoder, so publish-prerequisite
     * fixtures must be real images, not opaque byte blobs. Distinct per
     * id, and distinct from [originalFor] bytes, so preread input
     * mismatch (the intended stale-frozen negative) is genuine.
     */
    private fun realJpegFor(pickerId: String): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(pickerId.hashCode() or 0xFF000000.toInt())
        return java.io.ByteArrayOutputStream().use { stream ->
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream))
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private fun previewLoader(originals: Map<String, ByteArray>) = DefaultGeneratorPreviewLoader(
        openSource = { pickerId ->
            PhotoSource { ByteArrayInputStream(originals.getValue(pickerId)) }
        },
        decoder = GeneratorSourceBinding.PhotoDecoderPort {
            GeneratorSourceBinding.UprightPhoto(1000, 1000)
        },
        normalizer = PhotoNormalizerPort {
            NormalizedPhotoDto(byteArrayOf(9, 8, 7), 100, 100)
        },
        // Test lane: run the loader hop on the shared test dispatcher so
        // preview completion stays synchronous under runTest (production
        // uses Dispatchers.IO; lane behavior itself is proven by the
        // loader's own hermetic tests, not re-asserted here).
        workerDispatcher = testDispatcher,
    )

    private fun viewModel(
        loader: DefaultGeneratorPreviewLoader,
        directory: RecipientDirectoryPort = SelfDirectory(),
        identity: (suspend () -> SenderIdentitySnapshot?)? = null,
        withBridge: Boolean = false,
        openPhotoSource: ((String) -> PhotoSource)? = null,
    ): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(AccountScopedFileRoots(outboxDir))
        return CreateViewModel(
            directory = directory,
            accessTokenProvider = { null },
            identityProvider = identity ?: { null },
            persistence = StubPersistence(),
            outboxStager = CapsuleOutboxStager(
                database,
                AccountScopedFileRoots(outboxDir),
                retryStore,
            ),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = AccountScopedFileRoots(stagingDir),
            openPhotoSource = openPhotoSource
                ?: { PhotoSource { ByteArrayInputStream(ByteArray(8)) } },
            frontProcessor = Accepting(FingerprintSide.FRONT),
            generatorBridgeProvider = if (withBridge) bridge.provider else null,
            generatorPreviewLoader = loader,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
    }

    private fun readyPreview(vm: CreateViewModel, ids: List<String>): GeneratorPreviewState.Ready {
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onPhotosPicked(ids)
        val state = vm.generatorPreview.value
        assertTrue("expected Ready, got $state", state is GeneratorPreviewState.Ready)
        return state as GeneratorPreviewState.Ready
    }

    private suspend fun frozenAfterSelect(
        vm: CreateViewModel,
        input: GeneratorExpression.GeneratorInput,
    ): CollageFrozenSelection {
        val candidates = candidatesFor(input)
        vm.collageChoices.replaceCandidates(candidates)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue(vm.selectCollageCandidate(0))
        assertTrue("expected freeze to succeed", vm.freezeCollageSelection())
        return requireNotNull(vm.frozenCollageSelection) { "expected frozen selection" }
    }

    // ------------------------------------------------------------------
    // ViewModel behavior.
    // ------------------------------------------------------------------

    @Test
    fun freezeRequiresExplicitSelection() = runTest {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        readyPreview(vm, ids)
        assertFalse(vm.freezeCollageSelection())
        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun selectFreezeAndEditsClear() = runTest {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        val ready = readyPreview(vm, ids)
        val frozen = frozenAfterSelect(vm, ready.expression.input)
        assertEquals(vm.collageChoices.selected?.candidateId, frozen.candidateId)

        vm.onPhotosPicked(listOf("d1", "d2", "d3"))
        assertNull("photo edit must clear frozen", vm.frozenCollageSelection)

        val retry = readyPreview(vm, ids)
        frozenAfterSelect(vm, retry.expression.input)
        assertTrue(vm.noteEditor.onChange("dear mama"))
        assertNull("note edit must clear frozen", vm.frozenCollageSelection)

        // A set note parks preview in NotePending; clear it to reach Ready.
        assertTrue(vm.noteEditor.onChange(""))
        val again = readyPreview(vm, ids)
        frozenAfterSelect(vm, again.expression.input)
        vm.clearMusicSelection()
        assertNull("track edit must clear frozen", vm.frozenCollageSelection)
    }

    @Test
    fun sessionResetClearsFrozen() = runTest {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        val ready = readyPreview(vm, ids)
        frozenAfterSelect(vm, ready.expression.input)
        vm.beginSession(2L, senderUserUuid.toString())
        assertNull(vm.frozenCollageSelection)
        vm.endSession()
        assertNull(vm.frozenCollageSelection)
    }

    @Test
    fun rebuildClearsFrozenEvenWithOverlappingIds() = runTest {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        val ready = readyPreview(vm, ids)
        val candidates = candidatesFor(ready.expression.input)
        vm.collageChoices.replaceCandidates(candidates)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue(vm.selectCollageCandidate(0))
        assertTrue(vm.freezeCollageSelection())
        vm.collageChoices.replaceCandidates(candidates)
        assertNull("rebuild must clear selected index", vm.collageChoices.selectedIndex)
        assertNull("rebuild must clear frozen even with overlapping IDs", vm.frozenCollageSelection)
    }

    @Test
    fun clearFrozenCollageSelectionDropsFrozenOnly() = runTest {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        val ready = readyPreview(vm, ids)
        frozenAfterSelect(vm, ready.expression.input)
        vm.clearFrozenCollageSelection()
        assertNull(vm.frozenCollageSelection)
        // Navigation clears the frozen id; the highlighted candidates stay.
        // T151: the provider enumerates every grammar-5 branch (12 for 3 photos)
        // per style, so more than one candidate per style remains.
        assertTrue(vm.collageChoices.candidates.size > 3)
        assertEquals(3, vm.collageChoices.candidates.map { it.styleId }.distinct().size)
        assertEquals(0, vm.collageChoices.selectedIndex)
    }

    @Test
    fun tamperedRebuildYieldsNoFrozenAndRejectsFreeze() = runTest {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        val ready = readyPreview(vm, ids)
        val candidates = candidatesFor(ready.expression.input)
        vm.collageChoices.replaceCandidates(candidates)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue(vm.selectCollageCandidate(0))
        assertTrue(vm.freezeCollageSelection())
        // A rebuild drops the previously valid frozen selection first (even
        // with overlapping IDs); freezing the tampered set then fails
        // validation without storing anything.
        vm.collageChoices.replaceCandidates(
            listOf(candidates[0].copy(candidateId = "ber1-tampered")),
        )
        assertNull(vm.frozenCollageSelection)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue(vm.selectCollageCandidate(0))
        assertFalse("tampered freeze must fail", vm.freezeCollageSelection())
        assertNull(vm.frozenCollageSelection)
    }

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(senderUserUuid),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(senderBundleUuid),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = com.google.crypto.tink.subtle.Base64.urlSafeEncode(
            senderIdentity.encryptionPublicKeyset,
        ),
        signingPublicKeysetB64Url = com.google.crypto.tink.subtle.Base64.urlSafeEncode(
            senderIdentity.signingPublicKeyset,
        ),
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun senderIdentitySnapshot() = SenderIdentitySnapshot(
        userId = senderUserUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = senderBundleUuid.toString(),
        encryptionPrivateHandle = senderIdentity.encryptionPrivateHandle,
        signingPrivateHandle = senderIdentity.signingPrivateHandle,
    )

    @Test
    fun frozenSelectionKeepsPublishFailClosed() = runTest {
        val ids = listOf("u1", "u2", "u3")
        val directory = SelfDirectory().also { it.set(mapOf("mykola" to selfSnapshot())) }
        val vm = viewModel(
            previewLoader(ids.associateWith { originalFor(it) }),
            directory = directory,
            identity = { senderIdentitySnapshot() },
            withBridge = true,
            // Publish prereads the ACTUAL selected originals through the
            // production EXIF decoder: supply valid per-id JPEGs (distinct
            // from the preview bytes) so preread passes and the test
            // reaches the intended stale-frozen collage guard instead of
            // the unreadable-photo prerequisite.
            openPhotoSource = { pickerId ->
                PhotoSource { ByteArrayInputStream(realJpegFor(pickerId)) }
            },
        )
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        vm.onPhotosPicked(ids)
        val preview = vm.generatorPreview.value
        assertTrue("expected Ready, got $preview", preview is GeneratorPreviewState.Ready)
        preview as GeneratorPreviewState.Ready

        val candidates = candidatesFor(preview.expression.input)
        vm.collageChoices.replaceCandidates(candidates)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue(vm.selectCollageCandidate(0))
        assertTrue(vm.freezeCollageSelection())

        assertTrue(vm.goToCompose())
        vm.startPublishing()
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) error("publishing never settled")
            kotlinx.coroutines.delay(20)
        }
        assertTrue(
            "collage selection must keep publishing fail-closed, error=" + vm.publishError.value,
            vm.uploadStatus.value !is CreateViewModel.CreateUploadStatus.Published,
        )
        assertTrue(
            "guard must name collage freezing, error=" + vm.publishError.value,
            (vm.publishError.value ?: "").contains("collage"),
        )
    }
}
