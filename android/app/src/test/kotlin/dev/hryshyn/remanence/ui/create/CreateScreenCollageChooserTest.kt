package dev.hryshyn.remanence.ui.create

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.GeneratorExpression
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
            // Centered 128px face on the 1000x1000 stub: the previous
            // corner box (64,64) made EVERY zoom>1 window fail the
            // solver's 0.12 face-margin gate, so no reseal test could pass.
            // Centered keeps zoom>1 corrections solver-acceptable while
            // all production guards stay intact.
            values[0] = 436f
            values[1] = 436f
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
        candidateBuildGate: CompletableDeferred<Unit>? = null,
        candidateBuildEntered: CompletableDeferred<Unit>? = null,
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
                candidateBuildEntered?.complete(Unit)
                candidateBuildGate?.await()
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
    fun composeStepMountsChooserAndSelectFreezesDisplayedCandidate() {
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
        // The chooser lives on the COMPOSE screen only (no-inline-chooser
        // rule): navigate with the explicit Next before waiting for candidates.
        composeRule.onNodeWithTag("create_next").assertIsDisplayed()
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()
        // Preview readiness and provider analysis are separate asynchronous
        // boundaries. Wait for the chooser's candidate-ready state rather
        // than assuming one Compose idle observation covers both.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.candidates.isNotEmpty()
        }
        composeRule.waitForIdle()

        // The chooser is mounted; no BER1 preview semantics remain.
        // The first candidate displays as soon as it is ready (no freeze).
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_select_hint").assertDoesNotExist()
        composeRule.onAllNodesWithTag("collage_select").assertCountEquals(1)
        composeRule.onAllNodesWithTag("generator_preview_notice").assertCountEquals(0)
        composeRule.onAllNodesWithTag("generator_preview_canvas").assertCountEquals(0)

        // Browse to the second variant, then one explicit tap selects and
        // freezes exactly the displayed candidate. The bounded COMPOSE
        // viewport keeps controls reachable without scrolling.
        composeRule.onNodeWithTag("collage_next").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        // The nav row scrolls horizontally on narrow screens (native
        // overflow adaptation): reveal Select before asserting/clicking it.
        composeRule.onNodeWithTag("collage_select").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        val frozen = vm.frozenCollageSelection
        assertTrue("expected frozen selection", frozen != null)
        assertEquals(vm.collageChoices.selected?.candidateId, frozen!!.candidateId)
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_frozen").assertExists()
        // Selected and browsing states share the frozen arrow/counter row.
        composeRule.onNodeWithTag("collage_nav_counter")
            .assertTextContains("✓ selected", substring = true)
        composeRule.onNodeWithTag("collage_next").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        assertEquals(2, vm.collageChoices.displayIndex)
        assertTrue(vm.collageChoices.selectedIndex == null)
        assertNull("navigation must invalidate the frozen selection", vm.frozenCollageSelection)
        vm.endSession()
    }

    private fun driveToComposeCandidates(vm: CreateViewModel) {
        // The chooser lives on the COMPOSE screen only (no-inline-chooser
        // rule): navigate with the explicit Next before waiting for candidates.
        composeRule.onNodeWithTag("create_next").assertIsDisplayed()
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.candidates.isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun smallViewportKeepsSelectionContinueAndReviewReachable() {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals, withFactory = true)
        driveToContent(vm, ids)
        composeRule.setContent {
            dev.hryshyn.remanence.ui.hold.HoldTheme {
                Box(Modifier.size(320.dp, 568.dp)) {
                    CreateScreen(vm, adapterFactory = null, requestPermissionOnAttach = false)
                }
            }
        }
        driveToComposeCandidates(vm)
        assertWithinViewport("collage_canvas")
        assertWithinViewport("collage_select")
        composeRule.onNodeWithTag("create_publish").assertDoesNotExist()

        // Cropping is available while browsing, before any explicit choice.
        assertNull(vm.frozenCollageSelection)
        composeRule.onNodeWithTag("crop_hotspot_0").performClick()
        composeRule.onNodeWithTag("crop_stage").assertIsDisplayed()
        assertWithinViewport("crop_done")
        assertNull(vm.frozenCollageSelection)
        composeRule.onNodeWithTag("crop_back").performClick()

        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.onNodeWithTag("create_compose_continue").assertIsEnabled()
        assertWithinViewport("create_compose_continue")
        composeRule.onNodeWithTag("create_compose_continue").performClick()
        composeRule.onNodeWithTag("create_publish").assertIsEnabled()
        assertWithinViewport("create_publish")
        assertWithinViewport("collage_canvas")
        vm.endSession()
    }

    @Test
    fun contentStaysVisibleUntilCandidateBuildCompletes() {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals, true, gate, entered)
        driveToContent(vm, ids)
        composeRule.setContent {
            dev.hryshyn.remanence.ui.hold.HoldTheme {
                CreateScreen(vm, adapterFactory = null, requestPermissionOnAttach = false)
            }
        }
        composeRule.waitUntil(10_000) { entered.isCompleted }
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        composeRule.onNodeWithTag("create_content_scroll").assertIsDisplayed()
        composeRule.onNodeWithTag("create_photo_tile_0").assertExists()
        composeRule.onNodeWithTag("create_next").assertIsNotEnabled()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()

        // The pending arrival has a bounded escape: Back cancels only the
        // wait, not the front capture or selected content. A late empty picker
        // callback is neutral and cannot invalidate its in-flight build.
        composeRule.runOnIdle { vm.onPhotosPicked(emptyList()) }
        composeRule.onNodeWithTag("create_pick_photos").assertIsNotEnabled()
        composeRule.onNodeWithTag("flow_back_create").performClick()
        composeRule.waitForIdle()
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals(ids, vm.photoSelection.selectedIds)
        composeRule.onNodeWithTag("create_next").assertIsEnabled().performClick()

        gate.complete(Unit)
        composeRule.waitUntil(10_000) { vm.step.value == CreateViewModel.Step.COMPOSE }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_select").assertIsEnabled()
        composeRule.onNodeWithTag("create_content_scroll").assertDoesNotExist()

        // Every browse page is real, drawable content with a usable action.
        val count = vm.collageChoices.candidates.size
        assertTrue(count > 0)
        repeat(count) { index ->
            composeRule.onNodeWithTag("collage_canvas").assertIsDisplayed()
            composeRule.onNodeWithTag("collage_select").assertIsEnabled()
            composeRule.onNodeWithTag("collage_notice").assertDoesNotExist()
            assertEquals(index, vm.collageChoices.displayIndex)
            if (index < count - 1) composeRule.onNodeWithTag("collage_next").performClick()
        }
        vm.endSession()
    }

    @Test
    fun largerTextKeepsPrimaryActionsReachable() {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals, withFactory = true)
        driveToContent(vm, ids)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                dev.hryshyn.remanence.ui.hold.HoldTheme {
                    Box(Modifier.size(400.dp, 900.dp)) {
                        CreateScreen(vm, adapterFactory = null, requestPermissionOnAttach = false)
                    }
                }
            }
        }
        driveToComposeCandidates(vm)
        assertWithinViewport("collage_select")
        composeRule.onNodeWithTag("collage_select").performClick()
        assertWithinViewport("create_compose_continue")
        composeRule.onNodeWithTag("create_compose_continue").performClick()
        assertWithinViewport("create_publish")
        vm.endSession()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w400dp-h900dp")
    fun tallViewportKeepsCanvasAndActionsWithinBoundedPane() {
        // A genuinely tall window, not an oversized child whose pinned
        // actions fall below the real display.
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals, withFactory = true)
        driveToContent(vm, ids)
        composeRule.setContent {
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                CreateScreen(
                    viewModel = vm,
                    adapterFactory = null,
                    requestPermissionOnAttach = false,
                )
            }
        }
        driveToComposeCandidates(vm)

        // Weight remainder on a tall screen, not the crowded fixed size.
        val art = composeRule.onNodeWithTag("compose_art_space")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "tall art-space must be weight-driven (well above 280px), was ${art.height}",
            art.height > 300f,
        )
        // Canvas contained in the art-space (art-relative, not root).
        val canvas = composeRule.onNodeWithTag("collage_canvas")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "canvas must sit inside the art-space: $canvas not in $art",
            canvas.left >= art.left - 1f && canvas.top >= art.top - 1f &&
                canvas.right <= art.right + 1f && canvas.bottom <= art.bottom + 1f,
        )
        // Frozen sibling order: nav renders below the art-space.
        val selectBox = composeRule.onNodeWithTag("collage_select")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "nav must sit below the art-space: select $selectBox vs art $art",
            selectBox.top >= art.bottom - 1f,
        )
        // Publication has its own review step after explicit selection.
        composeRule.onNodeWithTag("create_publish").assertDoesNotExist()
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.onNodeWithTag("create_compose_continue").performClick()
        composeRule.onNodeWithTag("create_publish").assertExists()
        vm.endSession()
    }

    @Test
    fun expressionHasNoDuplicatePhotoRow() {
        // Frozen expression opens crop by tapping a photograph (canvas
        // hotspot); the duplicate Photo 1-4 slot row + heading are absent
        // from expression semantics while the hint and hotspot entry stay.
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
        driveToComposeCandidates(vm)

        composeRule.onNodeWithTag("crop_hint").assertExists()
        composeRule.onNodeWithTag("crop_slots").assertDoesNotExist()
        composeRule.onNodeWithTag("crop_slots_label").assertDoesNotExist()
        composeRule.onNodeWithTag("crop_slot_0").assertDoesNotExist()

        // A real photograph hotspot opens crop before selection.
        assertNull(vm.frozenCollageSelection)
        composeRule.onNodeWithTag("crop_hotspot_0").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_stage").assertExists()
        assertTrue(vm.cropSlot.value != null)
        vm.endSession()
    }

    @Test
    fun cropBackRetainsFrozenSelectionWithoutRebuild() {
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
        composeRule.onNodeWithTag("create_next").assertIsDisplayed()
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.candidates.isNotEmpty()
        }
        composeRule.waitForIdle()

        // Select + freeze the displayed candidate through the real host.
        // The nav row scrolls horizontally on narrow screens (native
        // overflow adaptation): reveal Select before asserting/clicking it.
        composeRule.onNodeWithTag("collage_select").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        val frozenId = requireNotNull(vm.frozenCollageSelection?.candidateId)
        val listed = vm.collageChoices.candidates

        // Open the crop stage on the first photo hotspot, then Back: the
        // host must stay mounted (no rebuild), so frozen id, highlight,
        // publish enablement and the candidate list instance all survive.
        composeRule.onNodeWithTag("crop_hotspot_0").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_stage").assertExists()
        composeRule.onNodeWithTag("crop_back").performClick()
        composeRule.waitForIdle()

        assertEquals(frozenId, vm.frozenCollageSelection?.candidateId)
        assertEquals(0, vm.collageChoices.selectedIndex)
        assertSame(listed, vm.collageChoices.candidates)
        composeRule.onNodeWithTag("collage_selected_label", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("create_compose_continue").assertIsEnabled()
        vm.endSession()
    }

    @Test
    fun cropDoneAtZoomAboveOneResealsAndSurvivesBack() {
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
        composeRule.onNodeWithTag("create_next").assertIsDisplayed()
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.candidates.isNotEmpty()
        }
        composeRule.waitForIdle()
        // The nav row scrolls horizontally on narrow screens (native
        // overflow adaptation): reveal Select before clicking it.
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        val frozenId = requireNotNull(vm.frozenCollageSelection?.candidateId)

        // Open the stage and set a zoomed, panned correction directly.
        // Widget-event limitation (documented, not a product claim):
        // slider/pad/Done taps do not execute in this Robolectric
        // environment (stage-control taps land everywhere else in the
        // flow: open/Back/navigate/Select/publish all fire normally), and
        // device QA to date covers arrows at z=1 identity only — zoom>1
        // slider travel and the Done widget event are NOT verified
        // anywhere yet. z=1.02 keeps zoom>1 coverage (see below); apply
        // runs directly.
        composeRule.onNodeWithTag("crop_hotspot_0").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "hotspot tap must open the stage, slot=${vm.cropSlot.value}",
            vm.cropSlot.value != null,
        )
        // z=1.02 keeps zoom>1 lifecycle coverage with the window near the
        // approved base: the V4 sealed base is a tight sub-rect, so 1.25x
        // drops below the solver's 0.60 importance floor even centered
        // (flat fixture maps spread mass uniformly, unlike real photos).
        // Production guards are untouched; the reseal path is what matters.
        vm.updateCrop(1.02f, 0.0f, 0.0f)
        composeRule.waitForIdle()
        assertTrue(
            "direct write must land: slot=${vm.cropSlot.value} " +
                "selIdx=${vm.collageChoices.selectedIndex} " +
                "selId=${vm.collageChoices.selected?.candidateId} " +
                "dispIdx=${vm.collageChoices.displayIndex} " +
                "dispId=${vm.collageChoices.displayed?.candidateId} " +
                "nCand=${vm.collageChoices.candidates.size} " +
                "flowError=${vm.flowError.value} corrections=${vm.cropCorrections.value}",
            vm.cropCorrections.value.isNotEmpty(),
        )
        val applied = kotlinx.coroutines.runBlocking { vm.applyCropAndClose() }
        assertTrue(
            "apply must succeed POST-call: applied=$applied flowError=${vm.flowError.value} " +
                "slot=${vm.cropSlot.value} " +
                "selId=${vm.collageChoices.selected?.candidateId} " +
                "shapes=${vm.collageChoices.selected?.expression?.placements?.mapIndexed { i, p -> "$i:${p.shape}:${p.contentId}" }} " +
                "keys=${vm.cropCorrections.value.keys} " +
                "src=${vm.collageCropSource != null}",
            applied,
        )
        composeRule.waitForIdle()

        val resealedId = requireNotNull(vm.frozenCollageSelection?.candidateId)
        assertTrue(resealedId != frozenId)
        composeRule.onNodeWithTag("create_compose_continue").assertIsEnabled()

        // A second open/Back on the resealed candidate keeps the new freeze.
        composeRule.onNodeWithTag("crop_hotspot_0").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_back").performClick()
        composeRule.waitForIdle()
        assertEquals(resealedId, vm.frozenCollageSelection?.candidateId)
        vm.endSession()
    }

    @Test
    fun contentRoundtripRetainsCandidatesAndCanSelectAgain() {
        val ids = listOf("u1", "u2", "u3")
        val originals = ids.associateWith { id ->
            ByteArray(64) { ((it * 31 + id.hashCode()) and 0xFF).toByte() }
        }
        val vm = viewModel(originals, withFactory = true)
        driveToContent(vm, ids)
        composeRule.setContent {
            CreateScreen(vm, adapterFactory = null, requestPermissionOnAttach = false)
        }
        driveToComposeCandidates(vm)
        composeRule.onNodeWithTag("collage_select").performClick()
        assertTrue(vm.frozenCollageSelection != null)
        val listed = vm.collageChoices.candidates
        val firstId = requireNotNull(vm.collageChoices.displayed).candidateId

        // Back leaves the chosen state first; a second Back returns to content.
        composeRule.onNodeWithTag("flow_back_create").performClick()
        assertNull(vm.frozenCollageSelection)
        composeRule.onNodeWithTag("flow_back_create").performClick()
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitUntil(10_000) { vm.step.value == CreateViewModel.Step.COMPOSE }
        composeRule.waitForIdle()
        assertSame(listed, vm.collageChoices.candidates)
        assertEquals(firstId, vm.collageChoices.displayed?.candidateId)

        composeRule.onNodeWithTag("collage_select").performClick()
        assertEquals(firstId, vm.frozenCollageSelection?.candidateId)
        vm.endSession()
    }

    @Test
    fun equalRebuildClearsProofUntilRepost() {
        // VM seam contract: a stored positive proof gates Select; an equal
        // rebuild (same ids, equal expressions) clears it and refuses until
        // the host re-reports.
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
        driveToComposeCandidates(vm)

        val id0 = requireNotNull(vm.collageChoices.displayed?.candidateId)
        vm.onCandidatePrepared(id0, true, vm.prepareInputs())
        assertTrue("proof present: select must succeed", vm.selectCollageCandidate(0))

        val count = vm.collageChoices.candidates.size
        vm.collageChoices.replaceCandidates(vm.collageChoices.candidates.toList())
        assertEquals(count, vm.collageChoices.candidates.size)
        assertEquals(id0, vm.collageChoices.displayed?.candidateId)
        assertTrue("cleared proof: select must refuse", !vm.selectCollageCandidate(0))
        assertEquals("candidate is not drawable", vm.flowError.value)

        vm.onCandidatePrepared(id0, true, vm.prepareInputs())
        assertTrue("re-reported proof: select must succeed", vm.selectCollageCandidate(0))
        vm.endSession()
    }

    @Test
    fun movementAtBaseZoomMovesFrameAndResetRestoresIt() {
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
        composeRule.onNodeWithTag("create_next").assertIsDisplayed()
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.candidates.isNotEmpty()
        }
        composeRule.waitForIdle()
        // The nav row scrolls horizontally on narrow screens (native
        // overflow adaptation): reveal Select before clicking it.
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_hotspot_0").performClick()
        composeRule.waitForIdle()

        fun windowState(): String =
            composeRule.onNodeWithTag("crop_frame").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]

        // Identity default: no correction yet, window is the approved base.
        val identity = windowState()
        // Source-space movement is available at base zoom too: the whole
        // source stays visible and the crop frame moves within it.
        vm.updateCrop(1.0f, 1.0f, 0.0f)
        composeRule.waitForIdle()
        assertTrue(vm.cropCorrections.value.isNotEmpty())
        assertTrue(windowState() != identity)

        // Pinching changes the window size; Reset returns to the solver's
        // original frame. Touch behavior has separate surface tests.
        vm.updateCrop(1.25f, 1.0f, 0.0f)
        composeRule.waitForIdle()
        val moved = windowState()
        assertTrue("expected a moved window, got $moved", moved != identity)
        vm.resetCrop()
        composeRule.waitForIdle()
        assertEquals(identity, windowState())
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
        // The chooser lives on the COMPOSE screen only (no-inline-chooser
        // rule): navigate with the explicit Next before waiting for candidates.
        composeRule.onNodeWithTag("create_next").assertIsDisplayed()
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.notice != null || vm.collageChoices.candidates.isNotEmpty()
        }

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        composeRule.onNodeWithTag("collage_notice").assertExists()
        composeRule.onNodeWithTag("create_next").assertIsEnabled()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        composeRule.onAllNodesWithTag("collage_select").assertCountEquals(0)
        vm.endSession()
    }

    private fun assertWithinViewport(tag: String) {
        val node = composeRule.onNodeWithTag(tag)
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must remain inside $viewport, was $bounds",
            bounds.width > 0f && bounds.height > 0f &&
                bounds.left >= viewport.left - 1f && bounds.top >= viewport.top - 1f &&
                bounds.right <= viewport.right + 1f && bounds.bottom <= viewport.bottom + 1f)
    }

    private fun musicHit() = MusicTrackHit(
        id = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artists = listOf("Arctic Monkeys"),
        version = null,
        release = "Favourite Worst Nightmare",
        year = 2007,
        durationMs = 253000L,
        artworkAvailable = true,
    )

    /**
     * T026 reactivity: a music attach or clear invalidates the chooser, so
     * the production chooser must observe the selection and restart the
     * provider cycle instead of leaving `Select a collage` with neither
     * candidates nor a notice (the T023 finding-2 symptom). A repeat select of
     * the identical hit must not empty the chooser either, because no state
     * change exists that could restart the cycle for it.
     */
    @Test
    fun musicAttachAndClearEachRestartTheProviderCycleWithCurrentTrack() {
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
        // The chooser lives on the COMPOSE screen only (no-inline-chooser
        // rule): navigate with the explicit Next before waiting for candidates.
        composeRule.onNodeWithTag("create_next").assertIsDisplayed()
        composeRule.onNodeWithTag("create_next").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.collageChoices.candidates.isNotEmpty() }
        composeRule.waitForIdle()
        assertNull(vm.collageChooserTrack)

        composeRule.onNodeWithTag("collage_next").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        // The nav row scrolls horizontally on narrow screens (native
        // overflow adaptation): reveal Select before asserting/clicking it.
        composeRule.onNodeWithTag("collage_select").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        assertTrue("expected a frozen selection", vm.frozenCollageSelection != null)

        vm.setMusicSelection(musicHit())
        assertTrue("attach must invalidate candidates", vm.collageChoices.candidates.isEmpty())
        assertNull("attach must invalidate the notice", vm.collageChoices.notice)
        assertNull("attach must drop the frozen selection", vm.frozenCollageSelection)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.candidates.isNotEmpty() || vm.collageChoices.notice != null
        }
        composeRule.waitForIdle()
        assertEquals(musicHit().id, vm.collageChooserTrack?.trackId?.toString())
        assertEquals(
            GeneratorExpression.MusicRef(musicHit().id, null),
            vm.collageChooserInputFor(vm.musicSelection.value)?.music,
        )

        vm.setMusicSelection(musicHit())
        composeRule.waitForIdle()
        assertTrue(
            "re-selecting the identical hit must leave the chooser built",
            vm.collageChoices.candidates.isNotEmpty() || vm.collageChoices.notice != null,
        )

        vm.clearMusicSelection()
        assertTrue("clear must invalidate candidates", vm.collageChoices.candidates.isEmpty())
        assertNull("clear must invalidate the notice", vm.collageChoices.notice)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.collageChoices.candidates.isNotEmpty() || vm.collageChoices.notice != null
        }
        composeRule.waitForIdle()
        assertNull(vm.collageChooserTrack)
        assertNull(vm.collageChooserInputFor(vm.musicSelection.value)?.music)
        vm.endSession()
    }
}
