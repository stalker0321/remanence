package dev.hryshyn.remanence.ui.create

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.create.DefaultGeneratorPreviewLoader
import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.NormalizedPhotoDto
import dev.hryshyn.remanence.create.PhotoNormalizerPort
import dev.hryshyn.remanence.create.PhotoSource
import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageAnalysis
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T002 production cutover at the screen level: the normal content step mounts
 * the real collage chooser (never `GeneratorBer1PreviewHost`, never
 * `generator_preview_*` tags), an explicit Select tap freezes the displayed
 * candidate, and a missing source renders the typed notice with no canvas.
 * Fake runners stand in for natives; no device execution is claimed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateScreenCollageChooserTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val senderUserUuid = UUID.fromString("ae111111-2222-4333-8444-555555555555")
    private val senderBundleUuid = UUID.fromString("ae333333-4444-4555-8666-777777777777")
    private val senderIdentity = AccountIdentityGenerator().generate()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-screen-chooser-${UUID.randomUUID()}"
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
        stagingDir = File(context.filesDir, "screen-chooser-staging").apply { mkdirs() }
        outboxDir = File(context.filesDir, "screen-chooser-outbox")
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

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = ProcessedStill.Accepted(
            profileId = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.PROFILE_ID,
            serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(6),
        )
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

    private fun viewModel(
        originals: Map<String, ByteArray>,
        withFactory: Boolean,
    ): CreateViewModel {
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
            directory = RecipientDirectoryPort { DirectoryLookupResult.Found(selfSnapshot()) },
            accessTokenProvider = { null },
            identityProvider = {
                SenderIdentitySnapshot(
                    userId = senderUserUuid.toString(),
                    handle = "mykola",
                    activeKeyBundleId = senderBundleUuid.toString(),
                    encryptionPrivateHandle = senderIdentity.encryptionPrivateHandle,
                    signingPrivateHandle = senderIdentity.signingPrivateHandle,
                )
            },
            persistence = StubPersistence(),
            outboxStager = CapsuleOutboxStager(
                database,
                AccountScopedFileRoots(outboxDir),
                retryStore,
            ),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = AccountScopedFileRoots(stagingDir),
            openPhotoSource = { PhotoSource { ByteArrayInputStream(ByteArray(8)) } },
            frontProcessor = Accepting(FingerprintSide.FRONT),
            generatorPreviewLoader = loader,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            collageSourceFactory = if (withFactory) {
                CollageCropSourceFactory { holder ->
                    AndroidCollageCropSource(
                        bytesSource = holder.asUprightBytesSource(),
                        imageDecoder = { _ ->
                            FieldStageImage(1000, 1000, IntArray(1_000_000) { 0xFF336699.toInt() })
                        },
                        fieldRunner = FieldStageRunner(FakeFieldRuntime()),
                        nanoDetRunner = NanoDetRunner(
                            FakeAsset(ByteArray(32) { 1 }),
                            FakeNanoRuntime(),
                            Files.createTempDirectory("screen-nano").toFile(),
                        ),
                        yuNetRunner = YuNetRunner(
                            FakeAsset(ByteArray(32) { 2 }),
                            FakeYuNetRuntime(),
                            Files.createTempDirectory("screen-yunet").toFile(),
                        ),
                    )
                }
            } else {
                null
            },
        )
    }

    private fun driveToContent(vm: CreateViewModel, ids: List<String>) {
        vm.beginSession(1L, senderUserUuid.toString())
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        vm.onPhotosPicked(ids)
    }

    @Test
    fun contentStepMountsChooserAndSelectFreezesDisplayedCandidate() {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals, withFactory = true)
        driveToContent(vm, ids)
        composeRule.setContent {
            CreateScreen(
                viewModel = vm,
                adapterFactory = null,
                requestPermissionOnAttach = false,
            )
        }
        composeRule.waitForIdle()

        // The chooser is mounted; no BER1 preview semantics remain.
        composeRule.onNodeWithTag("collage_select_hint").assertExists()
        composeRule.onAllNodesWithTag("collage_select").assertCountEquals(1)
        composeRule.onAllNodesWithTag("generator_preview_notice").assertCountEquals(0)
        composeRule.onAllNodesWithTag("generator_preview_canvas").assertCountEquals(0)

        // Highlight the first variant, then one explicit tap selects and
        // freezes exactly the displayed candidate.
        composeRule.onNodeWithTag("create_screen_scroll")
            .performScrollToNode(hasTestTag("collage_next"))
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_screen_scroll")
            .performScrollToNode(hasTestTag("collage_select"))
        composeRule.onNodeWithTag("collage_select").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        val frozen = vm.frozenCollageSelection
        assertTrue("expected frozen selection", frozen != null)
        assertEquals(vm.collageChoices.selected?.candidateId, frozen!!.candidateId)
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_frozen").assertExists()
        // Navigation moves the highlight and invalidates the older freeze.
        composeRule.onNodeWithTag("create_screen_scroll")
            .performScrollToNode(hasTestTag("collage_next"))
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.collageChoices.selectedIndex)
        assertNull("navigation must invalidate the frozen selection", vm.frozenCollageSelection)
        vm.endSession()
    }

    @Test
    fun missingSourceRendersTypedNoticeWithoutCanvas() {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals, withFactory = false)
        driveToContent(vm, ids)
        assertNull(vm.collageCropSource)
        composeRule.setContent {
            CreateScreen(
                viewModel = vm,
                adapterFactory = null,
                requestPermissionOnAttach = false,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collage_notice").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        composeRule.onAllNodesWithTag("collage_select").assertCountEquals(0)
        vm.endSession()
    }
}
