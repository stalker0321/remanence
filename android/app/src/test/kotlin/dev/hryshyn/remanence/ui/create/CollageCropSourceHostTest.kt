package dev.hryshyn.remanence.ui.create

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host coverage for the production crop-source mount: with the factory
 * present, a Ready preview builds a real source (fake runners here), the
 * test mounts it into [CollageChoiceHost] with the VM's chooser session, and
 * the shared canvas renders after explicit selection. Without the factory
 * the chooser stays absent. No publish wiring; no capability change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageCropSourceHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val senderUserUuid = UUID.fromString("ae111111-2222-4333-8444-555555555555")

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-collage-host-${UUID.randomUUID()}"
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
        stagingDir = File(context.filesDir, "collage-host-staging").apply { mkdirs() }
        outboxDir = File(context.filesDir, "collage-host-outbox")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
        outboxDir.deleteRecursively()
    }

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
        override val sha256: String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        override val byteSize: Long = bytes.size.toLong()
        override val maxBytes: Long = bytes.size.toLong() + 16
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    private class FakeFieldRuntime : FieldStageRuntime {
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

        override fun close() = Unit
    }

    private class FakeNanoRuntime : NanoDetRuntime {
        override fun load(modelPath: String) = Unit

        override fun forward(image: NanoDetImage): List<dev.hryshyn.remanence.ui.collage.NanoDetHead> {
            val n = 36
            return listOf(
                dev.hryshyn.remanence.ui.collage.NanoDetHead(
                    "cls",
                    intArrayOf(n, dev.hryshyn.remanence.ui.collage.NanoDetDecoder.CLASS_COUNT),
                    FloatArray(n * dev.hryshyn.remanence.ui.collage.NanoDetDecoder.CLASS_COUNT),
                ),
                dev.hryshyn.remanence.ui.collage.NanoDetHead(
                    "reg",
                    intArrayOf(n, dev.hryshyn.remanence.ui.collage.NanoDetDecoder.REG_CHANNELS),
                    FloatArray(n * dev.hryshyn.remanence.ui.collage.NanoDetDecoder.REG_CHANNELS),
                ),
            )
        }

        override fun close() = Unit
    }

    private class FakeYuNetRuntime : YuNetRuntime {
        override fun load(modelPath: String, config: YuNetConfig) = Unit

        override fun detect(image: YuNetImage, longEdge: Int): YuNetPass {
            val values = FloatArray(15)
            values[0] = 64f
            values[1] = 64f
            values[2] = 128f
            values[3] = 128f
            values[14] = 0.9f
            return YuNetPass(image.width, image.height, 15, listOf(values))
        }

        override fun close() = Unit
    }

    private fun realJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        return ByteArrayOutputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private fun viewModel(originals: Map<String, ByteArray>): CreateViewModel {
        val loader = DefaultGeneratorPreviewLoader(
            openSource = { pickerId ->
                PhotoSource { ByteArrayInputStream(originals.getValue(pickerId)) }
            },
            decoder = GeneratorSourceBinding.PhotoDecoderPort {
                GeneratorSourceBinding.UprightPhoto(1000, 1000)
            },
            normalizer = PhotoNormalizerPort {
                NormalizedPhotoDto(realJpeg(), 8, 8)
            },
        )
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
            openPhotoSource = { PhotoSource { ByteArrayInputStream(ByteArray(8)) } },
            frontProcessor = dev.hryshyn.remanence.capture.StillProcessor {
                dev.hryshyn.remanence.capture.ProcessedStill.Rejected(emptySet())
            },
            generatorPreviewLoader = loader,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            collageSourceFactory = CollageCropSourceFactory { holder ->
                AndroidCollageCropSource(
                    bytesSource = holder.asUprightBytesSource(),
                    imageDecoder = { _ ->
                        FieldStageImage(1000, 1000, IntArray(1_000_000) { 0xFF336699.toInt() })
                    },
                    fieldRunner = FieldStageRunner(FakeFieldRuntime()),
                    nanoDetRunner = NanoDetRunner(
                        FakeAsset(ByteArray(32) { 1 }),
                        FakeNanoRuntime(),
                        Files.createTempDirectory("host-nano").toFile(),
                    ),
                    yuNetRunner = YuNetRunner(
                        FakeAsset(ByteArray(32) { 2 }),
                        FakeYuNetRuntime(),
                        Files.createTempDirectory("host-yunet").toFile(),
                    ),
                )
            },
        )
    }

    @Test
    fun productionMountRendersSharedCanvasAfterExplicitSelection() {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals)
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onPhotosPicked(ids)
        val ready = vm.generatorPreview.value
        assertTrue("expected Ready, got $ready", ready is GeneratorPreviewState.Ready)
        ready as GeneratorPreviewState.Ready

        val source = vm.collageCropSource
        assertTrue("factory must build the production source", source != null)
        // Note/music are absent in preview Ready, so measurement is never
        // invoked; the erroring measurer proves it.
        val provider = CollageCandidateProvider(source!!, CollageRenderer.TextMeasurer {
            error("must not measure without note/music")
        })
        val built = runBlocking { provider.candidates(ready.expression.input, null, null) }
        assertTrue("expected Candidates, got $built", built is CollageProviderResult.Candidates)
        vm.collageChoices.replaceCandidates((built as CollageProviderResult.Candidates).candidates)
        // T151: the frozen grammar-5 branch enumeration yields many candidates
        // (branch × style) rather than exactly one per style.
        assertTrue("expected enumerated candidates, got ${vm.collageChoices.candidates.size}", vm.collageChoices.candidates.size > 3)

        composeRule.setContent {
            CollageChoiceHost(vm.collageChoices, ready.expression.input, ready.sources, null, null, source)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_select_hint").assertExists()
        composeRule.runOnIdle { vm.collageChoices.select(0) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        vm.endSession()
    }
}
