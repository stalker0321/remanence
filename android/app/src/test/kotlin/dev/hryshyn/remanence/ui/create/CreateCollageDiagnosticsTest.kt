package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.create.DefaultGeneratorPreviewLoader
import dev.hryshyn.remanence.create.GeneratorPreviewLoader
import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.NormalizedPhotoDto
import dev.hryshyn.remanence.create.PhotoNormalizerPort
import dev.hryshyn.remanence.create.PhotoSource
import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageAnalysis
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageImage
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageMaps
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRunner
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRuntime
import dev.hryshyn.remanence.ui.collage.analysis.ModelAssetSource
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetImage
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRunner
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.YuNetConfig
import dev.hryshyn.remanence.ui.collage.analysis.YuNetImage
import dev.hryshyn.remanence.ui.collage.analysis.YuNetPass
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRunner
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRuntime
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
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

/**
 * Focused tests for the production collage retain + mount in the
 * [CreateViewModel]: the loader retain callback carries live ORIGINAL bytes
 * (never preview bytes, never a reopened URI); generation-gated sinks make
 * stale async completions inert; retain rejection preserves ordinary preview
 * with no candidates; ownership resets clear/zeroize the holder and close
 * the source; without the factory there is no chooser and forced selection
 * keeps publishing fail-closed.
 *
 * Holder zeroization itself is pinned by `RetainedOriginalHolderTest`; here
 * the lifecycle calls are pinned (source closed/nulled, sink cleared).
 * `onCleared` delegates to `endSession`, so the end-session test covers both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateCollageDiagnosticsTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val senderUserUuid = UUID.fromString("ae111111-2222-4333-8444-555555555555")

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-collage-diag-${UUID.randomUUID()}"
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
        stagingDir = File(context.filesDir, "collage-diag-staging").apply { mkdirs() }
        outboxDir = File(context.filesDir, "collage-diag-outbox")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
        outboxDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun originalFor(pickerId: String, size: Int = 64): ByteArray =
        ByteArray(size) { ((it * 31 + pickerId.hashCode()) and 0xFF).toByte() }

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

    private class FakeAsset(val bytes: ByteArray) : ModelAssetSource {
        override val sha256: String = sha256Hex(bytes)
        override val byteSize: Long = bytes.size.toLong()
        override val maxBytes: Long = bytes.size.toLong() + 16
        override fun open(): InputStream = ByteArrayInputStream(bytes)

        companion object {
            fun sha256Hex(bytes: ByteArray): String =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    private class FakeFieldRuntime(var closeCount: Int = 0) : FieldStageRuntime {
        override fun analyze(image: FieldStageImage): FieldStageMaps {
            val cells = CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE
            return FieldStageMaps(
                sourceWidth = image.width,
                sourceHeight = image.height,
                saliency = DoubleArray(cells) { 1.0 },
                edgeEnergy = DoubleArray(cells) { 1.0 },
                contrast = DoubleArray(cells) { 1.0 },
                tips = DoubleArray(cells) { 1.0 },
                tipBoxes = emptyList(),
            )
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeNanoRuntime(var closeCount: Int = 0) : NanoDetRuntime {
        override fun load(modelPath: String) = Unit

        override fun forward(image: NanoDetImage): List<dev.hryshyn.remanence.ui.collage.NanoDetHead> =
            emptyList()

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeYuNetRuntime(var closeCount: Int = 0) : YuNetRuntime {
        override fun load(modelPath: String, config: YuNetConfig) = Unit

        override fun detect(image: YuNetImage, longEdge: Int): YuNetPass =
            YuNetPass(image.width, image.height, 15, emptyList())

        override fun close() {
            closeCount += 1
        }
    }

    /**
     * Real default loader over scripted ports. `parkDecodeCall` parks the
     * given decode call on [gate] (1-based) for stale-completion tests.
     */
    private fun previewLoader(
        originals: Map<String, ByteArray>,
        decoderDims: Pair<Int, Int> = 1000 to 1000,
        parkDecodeCall: Int? = null,
        gate: CompletableDeferred<Unit>? = null,
        reads: MutableList<String> = mutableListOf(),
    ): DefaultGeneratorPreviewLoader {
        var decodeCalls = 0
        return DefaultGeneratorPreviewLoader(
            openSource = { pickerId ->
                reads += pickerId
                PhotoSource { ByteArrayInputStream(originals.getValue(pickerId)) }
            },
            decoder = GeneratorSourceBinding.PhotoDecoderPort { _ ->
                decodeCalls += 1
                if (parkDecodeCall != null && decodeCalls == parkDecodeCall) {
                    gate?.await()
                }
                GeneratorSourceBinding.UprightPhoto(decoderDims.first, decoderDims.second)
            },
            normalizer = PhotoNormalizerPort { _ ->
                NormalizedPhotoDto(byteArrayOf(9, 8, 7), 100, 100)
            },
            workerDispatcher = testDispatcher,
        )
    }

    private fun viewModel(
        loader: GeneratorPreviewLoader?,
        factory: CollageCropSourceFactory? = null,
    ): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(AccountScopedFileRoots(outboxDir))
        return CreateViewModel(
            directory = RecipientDirectoryPort { DirectoryLookupResult.NotFound },
            accessTokenProvider = { null },
            identityProvider = { null },
            persistence = StubPersistence(),
            outboxStager = CapsuleOutboxStager(
                database,
                AccountScopedFileRoots(outboxDir),
                retryStore,
            ),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = AccountScopedFileRoots(stagingDir),
            openPhotoSource = { id ->
                dev.hryshyn.remanence.create.PhotoSource {
                    ByteArrayInputStream(ByteArray(8))
                }
            },
            frontProcessor = dev.hryshyn.remanence.capture.StillProcessor {
                dev.hryshyn.remanence.capture.ProcessedStill.Rejected(emptySet())
            },
            generatorPreviewLoader = loader,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            collageSourceFactory = factory,
        )
    }

    private fun drivePhotos(vm: CreateViewModel, ids: List<String>): GeneratorPreviewState.Ready {
        vm.onPhotosPicked(ids)
        val state = vm.generatorPreview.value
        assertTrue("expected Ready, got $state", state is GeneratorPreviewState.Ready)
        return state as GeneratorPreviewState.Ready
    }

    @Test fun ordinaryLoaderFailureIsRedactedAndExplicitRepickRecoversWithoutNewDraft() {
        val ids = listOf("u1", "u2", "u3")
        val delegate = previewLoader(ids.associateWith { originalFor(it) })
        var fail = true
        val vm = viewModel(GeneratorPreviewLoader { if (fail) error("private source details") else delegate.load(it) })
        vm.beginSession(1L, senderUserUuid.toString())
        val capsuleId = vm.capsuleId
        vm.onPhotosPicked(ids)
        assertEquals(GeneratorPreviewState.Unavailable("preview preparation failed"), vm.generatorPreview.value)
        assertEquals(ids, vm.photoSelection.selectedIds)
        assertEquals(capsuleId, vm.capsuleId)
        fail = false
        drivePhotos(vm, ids)
        assertEquals(capsuleId, vm.capsuleId)
        assertTrue(vm.uploadStatus.value is CreateViewModel.CreateUploadStatus.NotStarted)
        vm.endSession()
    }

    @Test fun factoryFailureDoesNotPoisonSamePhotoNoteRetry() {
        val ids = listOf("u1", "u2", "u3")
        var calls = 0
        val goodFactory = sourceFactory(mutableListOf())
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }), CollageCropSourceFactory { holder ->
            calls++
            if (calls == 1) error("private runtime details")
            goodFactory.create(holder)
        })
        vm.beginSession(1L, senderUserUuid.toString())
        val capsuleId = vm.capsuleId
        vm.onPhotosPicked(ids)
        assertEquals(GeneratorPreviewState.Unavailable("preview preparation failed"), vm.generatorPreview.value)
        assertNull(vm.collageCropSource)
        vm.noteEditor.onChange("explicit retry") // Same picker IDs, coordinator cache reused.
        assertTrue(vm.generatorPreview.value is GeneratorPreviewState.NotePending)
        assertEquals(2, calls)
        val source = vm.collageCropSource
        assertTrue(source != null)
        vm.noteEditor.onChange("another edit")
        assertTrue(vm.collageCropSource === source)
        assertEquals(2, calls)
        assertEquals(capsuleId, vm.capsuleId)
        vm.endSession()
    }

    @Test fun obsoleteNonCooperativeFailureCannotReplaceNewerReadyOrSource() {
        val old = listOf("u1", "u2", "u3")
        val fresh = listOf("d1", "d2", "d3")
        val gate = CompletableDeferred<Unit>()
        val delegate = previewLoader((old + fresh).associateWith { originalFor(it) })
        val vm = viewModel(GeneratorPreviewLoader { id ->
            if (id == old.first()) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                gate.await()
                error("obsolete private failure")
            } else delegate.load(id)
        }, sourceFactory(mutableListOf()))
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onPhotosPicked(old)
        val ready = drivePhotos(vm, fresh)
        val source = vm.collageCropSource
        gate.complete(Unit)
        assertTrue(vm.generatorPreview.value === ready)
        assertTrue(vm.collageCropSource === source)
        vm.endSession()
    }

    @Test fun preparationCancellationRetainsCancellationIdentityNotUnavailable() {
        val cancelled = kotlinx.coroutines.CancellationException("synthetic cancellation")
        var completion: Throwable? = null
        val vm = viewModel(GeneratorPreviewLoader {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.invokeOnCompletion { completion = it }
            throw cancelled
        })
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onPhotosPicked(listOf("u1", "u2", "u3"))
        org.junit.Assert.assertSame(cancelled, completion)
        assertFalse(vm.generatorPreview.value is GeneratorPreviewState.Unavailable)
        vm.endSession()
    }

    @Test
    fun retainCallbackUsesLiveOriginalBytesNotPreviewBytes() = runTest {
        val original = ByteArray(64) { it.toByte() }
        var seen: Pair<ByteArray, String>? = null
        val loader = DefaultGeneratorPreviewLoader(
            openSource = { PhotoSource { ByteArrayInputStream(original) } },
            decoder = GeneratorSourceBinding.PhotoDecoderPort {
                GeneratorSourceBinding.UprightPhoto(1000, 1000)
            },
            normalizer = PhotoNormalizerPort {
                NormalizedPhotoDto(byteArrayOf(9, 8, 7), 100, 100)
            },
        )
        loader.retainOriginal = { bytes, hash -> seen = bytes.copyOf() to hash }
        val loaded = loader.load("u1")
        assertTrue("expected loaded source", loaded != null)
        val (seenBytes, seenHash) = requireNotNull(seen) { "sink must fire" }
        // Live ORIGINAL bytes (non-zeroed at call time), never preview bytes.
        assertTrue(seenBytes.contentEquals(original))
        assertTrue(seenBytes.any { it != 0.toByte() })
        assertFalse(seenBytes.contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(sha256Hex(original), seenHash)
        assertEquals(sha256Hex(original), loaded!!.originalHash)
        // The holder-style copy survives the loader's own zeroization.
        assertTrue(seenBytes.contentEquals(original))
    }

    @Test
    fun lastPhotoOffOnReloadsOriginalsEvenWhenPreviewIdsMatch() {
        assertPhotoRevisionRetainsOriginals { vm, ids ->
            vm.photoSelection.toggle(ids.last())
            vm.photoSelection.toggle(ids.last())
        }
    }

    @Test
    fun identicalRepickReloadsOriginalsEvenWhenPreviewIdsMatch() {
        assertPhotoRevisionRetainsOriginals { vm, ids -> vm.onPhotosPicked(ids) }
    }

    @Test
    fun reorderedPhotosReloadButPickerCancelAndNoteOnlyReuseOriginals() {
        assertPhotoRevisionRetainsOriginals { vm, ids -> vm.onPhotosPicked(ids.reversed()) }
    }

    private fun assertPhotoRevisionRetainsOriginals(edit: (CreateViewModel, List<String>) -> Unit) {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { originalFor(it) }
        val reads = mutableListOf<String>()
        val loader = previewLoader(originals, reads = reads)
        lateinit var retained: RetainedOriginalHolder
        val vm = viewModel(loader, CollageCropSourceFactory { holder ->
            retained = holder
            null
        })
        vm.beginSession(1L, senderUserUuid.toString())
        drivePhotos(vm, ids)
        val staleSink = requireNotNull(loader.retainOriginal)
        val before = reads.size
        edit(vm, ids)
        val ready = vm.generatorPreview.value as GeneratorPreviewState.Ready
        assertEquals("a cleared holder requires a fresh original read per photo", before + 3, reads.size)
        ready.expression.input.photos.forEach { photo ->
            val expected = originals.values.single { sha256Hex(it) == photo.contentId.removePrefix("c") }
            assertTrue("retained original must match identity, never thumbnail",
                retained.bytesFor(photo.contentId)?.contentEquals(expected) == true)
        }
        val currentKeys = retained.retainedSizesForTests().keys.toSet()
        val late = originalFor("obsolete")
        staleSink(late, sha256Hex(late))
        assertEquals("old revision callback must be inert", currentKeys, retained.retainedSizesForTests().keys)
        vm.onPhotosPicked(emptyList())
        vm.noteEditor.onChange("A short note")
        vm.noteEditor.onChange("")
        assertEquals("cancel and note-only edits must reuse originals", before + 3, reads.size)
        assertEquals(currentKeys, retained.retainedSizesForTests().keys)
        vm.endSession()
        assertTrue(retained.retainedSizesForTests().isEmpty())
    }

    @Test
    fun rapidSamePhotoRevisionIgnoresParkedOriginalCompletion() {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { originalFor(it) }
        val gate = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val loader = previewLoader(originals, parkDecodeCall = 6, gate = gate, reads = reads)
        lateinit var retained: RetainedOriginalHolder
        val vm = viewModel(loader, CollageCropSourceFactory { retained = it; null })
        vm.beginSession(1L, senderUserUuid.toString())
        drivePhotos(vm, ids)
        vm.photoSelection.toggle(ids.last())
        vm.photoSelection.toggle(ids.last())
        assertTrue(vm.generatorPreview.value is GeneratorPreviewState.Loading)
        val staleSink = requireNotNull(loader.retainOriginal)
        // A second same-ID edit overtakes the parked original decode.
        vm.photoSelection.toggle(ids.last())
        vm.photoSelection.toggle(ids.last())
        val ready = vm.generatorPreview.value as GeneratorPreviewState.Ready
        gate.complete(Unit)
        val obsolete = originalFor("obsolete")
        staleSink(obsolete, sha256Hex(obsolete))
        assertEquals(9, reads.size)
        assertEquals(ready.expression.input.photos.map { it.contentId }.toSet(),
            retained.retainedSizesForTests().keys)
        originals.values.forEach { bytes ->
            val id = dev.hryshyn.remanence.core.model.CapsulePhotoIdentity.contentIdFor(sha256Hex(bytes))
            assertTrue(retained.bytesFor(id)!!.contentEquals(bytes))
        }
        vm.endSession()
    }

    @Test
    fun staleCompletionCannotRetain() {
        val ids1 = listOf("u1", "u2", "u3")
        val ids2 = listOf("d1", "d2", "d3")
        val originals = (ids1 + ids2).associateWith { originalFor(it) }
        val gate = CompletableDeferred<Unit>()
        // Park the third decode (u3 of round one); round two never parks.
        val loader = previewLoader(originals, parkDecodeCall = 3, gate = gate)
        val seenSizes = mutableListOf<Map<String, Int>>()
        val vm = viewModel(loader, CollageCropSourceFactory { holder ->
            seenSizes += holder.retainedSizesForTests()
            null
        })
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onPhotosPicked(ids1)
        // Round one is parked mid-load; a fresh selection supersedes it.
        vm.onPhotosPicked(ids2)
        gate.complete(Unit)
        val state = vm.generatorPreview.value
        assertTrue("expected Ready, got $state", state is GeneratorPreviewState.Ready)
        val ready = state as GeneratorPreviewState.Ready
        // Round-one partial retains were cleared as stale, parked u3 never
        // retained, and round two retained fully: exactly two builds.
        assertEquals("builds=$seenSizes", 2, seenSizes.size)
        assertEquals(3, seenSizes.first().size)
        assertEquals(3, seenSizes.last().size)
        val readyIds = ready.expression.input.photos.map { it.contentId }
        assertEquals(readyIds.toSet(), seenSizes.last().keys)
        assertTrue(vm.collageChoices.candidates.isEmpty())
    }

    @Test
    fun retainRejectionPreservesPreviewAndYieldsNoCandidate() {
        // Three 22 MiB originals: two retain (44 MiB), the third exceeds the
        // 64 MiB aggregate and is rejected — preview must stay Ready.
        val ids = listOf("w1", "w2", "w3")
        val big = 22L * 1024L * 1024L
        val originals = ids.associateWith { id ->
            ByteArray(big.toInt()) { ((it + id.hashCode()) and 0xFF).toByte() }
        }
        val loader = previewLoader(originals)
        val seenSizes = mutableListOf<Map<String, Int>>()
        val vm = viewModel(loader, CollageCropSourceFactory { holder ->
            seenSizes += holder.retainedSizesForTests()
            null
        })
        vm.beginSession(1L, senderUserUuid.toString())
        val ready = drivePhotos(vm, ids)
        assertEquals(3, ready.sources.size)
        assertEquals(2, seenSizes.single().size)
        assertTrue(vm.collageChoices.candidates.isEmpty())
        assertNull(vm.collageCropSource)
    }

    @Test
    fun sessionResetClearsHolderAndClosesSource() {
        val ids = listOf("u1", "u2", "u3")
        val next = listOf("d1", "d2", "d3")
        val originals = (ids + next).associateWith { originalFor(it) }
        val loader = previewLoader(originals)
        val field = FakeFieldRuntime()
        val nano = FakeNanoRuntime()
        val yunet = FakeYuNetRuntime()
        val builds = mutableListOf<Map<String, Int>>()
        val vm = viewModel(loader, CollageCropSourceFactory { holder ->
            builds += holder.retainedSizesForTests()
            AndroidCollageCropSource(
                bytesSource = holder.asUprightBytesSource(),
                imageDecoder = { null },
                fieldRunner = FieldStageRunner(field),
                nanoDetRunner = NanoDetRunner(
                    FakeAsset(ByteArray(32) { 1 }),
                    nano,
                    Files.createTempDirectory("diag-nano").toFile(),
                ),
                yuNetRunner = YuNetRunner(
                    FakeAsset(ByteArray(32) { 2 }),
                    yunet,
                    Files.createTempDirectory("diag-yunet").toFile(),
                ),
            )
        })
        vm.beginSession(1L, senderUserUuid.toString())
        drivePhotos(vm, ids)
        assertTrue("source must be built on Ready", vm.collageCropSource != null)
        assertEquals(3, builds.single().size)
        vm.beginSession(2L, senderUserUuid.toString())
        assertNull("reset must close and drop the source", vm.collageCropSource)
        assertEquals(1, field.closeCount)
        assertEquals(1, nano.closeCount)
        assertEquals(1, yunet.closeCount)
        // A fresh round after reset retains only the new set: stale bytes
        // were cleared, not carried over.
        drivePhotos(vm, next)
        assertEquals(2, builds.size)
        assertEquals(3, builds.last().size)
    }

    @Test
    fun endSessionClosesSourceAndClearsHolder() {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        vm.beginSession(1L, senderUserUuid.toString())
        drivePhotos(vm, ids)
        vm.endSession()
        assertNull(vm.collageCropSource)
        assertTrue(vm.generatorPreview.value is GeneratorPreviewState.Idle)
    }

    @Test
    fun withoutFactoryHasNoChooserAndPublishStaysFailClosed() {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        vm.beginSession(1L, senderUserUuid.toString())
        drivePhotos(vm, ids)
        assertNull("without factory must not build a source", vm.collageCropSource)
        assertTrue(vm.collageChoices.candidates.isEmpty())
        assertNull(vm.collageChoices.selected)
        assertFalse(vm.selectCollageCandidate(0))
        // No recipient/front/session steps here: composition is refused and
        // publishing stays fail-closed off the COMPOSE screen.
        assertFalse(vm.goToCompose())
        vm.startPublishing()
        assertTrue(
            "publish must stay fail-closed off the COMPOSE screen",
            vm.uploadStatus.value is CreateViewModel.CreateUploadStatus.NotStarted,
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

    private fun sourceFactory(
        builds: MutableList<Int>,
        field: FakeFieldRuntime = FakeFieldRuntime(),
        nano: FakeNanoRuntime = FakeNanoRuntime(),
        yunet: FakeYuNetRuntime = FakeYuNetRuntime(),
    ) = CollageCropSourceFactory { _ ->
        builds += 1
        AndroidCollageCropSource(
            bytesSource = { null },
            imageDecoder = { null },
            fieldRunner = FieldStageRunner(field),
            nanoDetRunner = NanoDetRunner(
                FakeAsset(ByteArray(32) { 1 }),
                nano,
                Files.createTempDirectory("b2-nano").toFile(),
            ),
            yuNetRunner = YuNetRunner(
                FakeAsset(ByteArray(32) { 2 }),
                yunet,
                Files.createTempDirectory("b2-yunet").toFile(),
            ),
        )
    }

    @Test
    fun notePendingBuildsSourceAndReadyReusesIt() {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(
            previewLoader(ids.associateWith { originalFor(it) }),
            factory = sourceFactory(mutableListOf()),
        )
        vm.beginSession(1L, senderUserUuid.toString())
        assertTrue(vm.noteEditor.onChange("dear mama"))
        vm.onPhotosPicked(ids)
        // First preview carries the note: NotePending, never Ready — yet the
        // production source must still be built exactly once.
        val pending = vm.generatorPreview.value
        assertTrue("expected NotePending, got $pending", pending is GeneratorPreviewState.NotePending)
        val first = vm.collageCropSource
        assertTrue("NotePending must build the source", first != null)
        // Clearing the note recomputes Ready for the same photo set: the open
        // source is reused, never duplicated.
        assertTrue(vm.noteEditor.onChange(""))
        val ready = vm.generatorPreview.value
        assertTrue("expected Ready, got $ready", ready is GeneratorPreviewState.Ready)
        assertTrue("Ready must reuse the open source", vm.collageCropSource === first)
        vm.endSession()
    }

    @Test
    fun trackEntersChooserInputFreezeAndInvalidates() {
        val ids = listOf("u1", "u2", "u3")
        val vm = viewModel(previewLoader(ids.associateWith { originalFor(it) }))
        vm.beginSession(1L, senderUserUuid.toString())
        drivePhotos(vm, ids)
        assertNull(vm.collageChooserTrack)
        assertNull(vm.collageChooserInput()!!.music)

        vm.setMusicSelection(hit())
        val track = vm.collageChooserTrack
        assertTrue("expected mapped track", track != null)
        assertEquals(hit().id, track!!.trackId.toString())
        val musicInput = vm.collageChooserInput()!!
        assertEquals(
            dev.hryshyn.remanence.core.model.GeneratorExpression.MusicRef(hit().id, null),
            musicInput.music,
        )

        // Candidates built from the track-carrying input seal a music region,
        // and freezing succeeds against that same input.
        val measurer = CollageRenderer.TextMeasurer { request ->
            CollageRenderer.TextMeasurement(
                width = (request.text.length * 8).coerceIn(1, request.maxWidth),
                height = request.line.coerceIn(1, request.maxHeight),
                hasVisualOverflow = false,
            )
        }
        val crops = dev.hryshyn.remanence.ui.collage.CollageCropSource { requests ->
            requests.associate { it.contentId to dev.hryshyn.remanence.core.model.GeneratorExpression.CropWindow(0, 0, 5000, 10000) }
        }
        val built = runBlocking {
            CollageCandidateProvider(crops, measurer).candidates(musicInput, null, track)
        }
        assertTrue("expected Candidates, got $built", built is CollageProviderResult.Candidates)
        val candidates = (built as CollageProviderResult.Candidates).candidates
        assertTrue(candidates.first().expression.musicRegion != null)
        vm.collageChoices.replaceCandidates(candidates)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue(vm.selectCollageCandidate(0))
        assertTrue("expected freeze with track", vm.freezeCollageSelection())
        assertEquals(
            dev.hryshyn.remanence.core.model.GeneratorExpression.MusicRef(hit().id, null),
            vm.frozenCollageSelection!!.expression.input.music,
        )

        // A track edit invalidates the frozen selection.
        vm.clearMusicSelection()
        assertNull(vm.frozenCollageSelection)
        assertNull(vm.collageChooserTrack)
        assertNull(vm.collageChooserInput()!!.music)
    }
}
