package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import dev.hryshyn.remanence.core.crypto.AndroidKeystoreKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.capture.*
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.capsule.*
import dev.hryshyn.remanence.ui.collage.*
import dev.hryshyn.remanence.ui.create.PhotoSelectionState
import dev.hryshyn.remanence.ui.create.PickedPhotoTiles
import dev.hryshyn.remanence.ui.create.CreateScreen
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.ui.create.RecipientDirectoryPort
import dev.hryshyn.remanence.ui.create.RecipientLookupUiState
import dev.hryshyn.remanence.ui.create.SelfRecipientAccount
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.home.HomeScreen
import dev.hryshyn.remanence.ui.home.AccountCapabilityState
import dev.hryshyn.remanence.ui.home.BackendHealthUiState
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.session.RootScreen
import dev.hryshyn.remanence.ui.settings.BetaTermsPendingPage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native controls/measurement, synthetic owned media; no authentication/recognition bypass. */
@RunWith(AndroidJUnit4::class)
class CalmUiDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "t176-fixture-evidence").apply { mkdirs() }

    @Test fun recipientChangeHeaderAndHardwareBackStayOnLookupAndShowOr() {
        val owner = "0198f0a0-0000-7000-8000-00000000a176"
        val directory = object : RecipientDirectoryPort {
            override suspend fun lookup(rawHandle: String) = DirectoryLookupResult.Found(ResolvedHandleSnapshot(
                UserId.parseRest(owner), NormalizedHandle.parse("qa_fixture"),
                KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000b176"),
                "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519", 1, "unused", "unused", "ACTIVE", "fixture"))
        }
        val database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java).build()
        val roots = AccountScopedFileRoots(File(context.cacheDir, "t176-recipient-fixture"))
        val boundary = AndroidKeystoreKekBoundary()
        if (!boundary.hasKey("t176-recipient-fixture")) boundary.createAes256GcmKey("t176-recipient-fixture")
        val persistence = object : SealedFingerprintPersistence {
            override suspend fun persist(capsuleId: String, origin: FingerprintOrigin, profileId: String, plaintextBytes: ByteArray): String = error("capture not in this fixture")
            override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin) = false
            override suspend fun decrypt(fingerprintId: String) = error("decrypt not in this fixture")
            override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit
            override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
        }
        val vm = CreateViewModel(directory, { "fixture-token" }, { null }, persistence,
            CapsuleOutboxStager(database, roots, SenderRetryMaterialStore(roots)), RecognitionProfile.postcardSiftRootSiftV1(),
            roots, { error("no photo picker in this fixture") }, senderRetryKeysetWrapper = SenderRetryKeysetWrapper(boundary),
            senderRetryKekAlias = "t176-recipient-fixture", enqueueUpload = { _, _ -> },
            recipientLookupOwnerProvider = { owner }, selfRecipientAccountProvider = { SelfRecipientAccount(owner, "qa_fixture") })
        try {
            rule.runOnIdle { vm.beginSession(1L, owner) }
            var destination by mutableStateOf<AppDestination>(AppDestination.Home)
            rule.setContent { HoldTheme {
                RootScreen(AuthUiState.Authenticated(owner, "qa_fixture"), destination,
                    authenticationContent = {},
                    homeContent = { HomeScreen(BackendHealthUiState.AVAILABLE, AccountCapabilityState.CryptoReady(owner, "qa_fixture"),
                        onCreate = { destination = AppDestination.Create }) },
                    createContent = { CreateScreen(vm, requestPermissionOnAttach = false, onExit = { destination = AppDestination.Home }) },
                    onExitFlow = { destination = AppDestination.Home })
            } }
            capture("home-before-create")
            rule.onNodeWithTag("create_action").performClick()
            rule.waitUntil(10_000) { vm.selfRecipientAccount.value != null }
            rule.onNodeWithTag("create_recipient_or").assertIsDisplayed()
            capture("recipient-or")
            rule.onNodeWithTag("create_handle_input").performTextInput("qa_fixture")
            for (control in listOf("cancel_button", "flow_back_create", "hardware")) {
                rule.onNodeWithTag("create_lookup_button").performClick()
                rule.waitUntil(10_000) { vm.step.value == CreateViewModel.Step.RECIPIENT_CONFIRM }
                rule.onNodeWithTag("confirm_button").assertIsDisplayed()
                if (control == "hardware") rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
                else rule.onNodeWithTag(control).performClick()
                rule.waitForIdle()
                assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
                assertEquals(RecipientLookupUiState.Idle, vm.pickerVm.state.value)
                assertEquals("qa_fixture", vm.pickerVm.handle.value)
                assertNull(vm.confirmedRecipient.value)
                rule.onNodeWithTag("confirm_button").assertDoesNotExist()
                capture("recipient-back-$control")
            }
        } finally { rule.runOnIdle { vm.endSession() }; database.close() }
    }

    @Test fun pickedTilesToggleAndRemainReachableEvenWhenEverythingIsUnchecked() {
        val ids = listOf("p0", "p1", "p2")
        val selection = PhotoSelectionState().apply { ids.forEach(::toggle) }
        val images = ids.associateWith { Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(180, 95, 50))
        }.asImageBitmap() }
        rule.setContent { HoldTheme { Column(Modifier.fillMaxSize().padding(24.dp)) {
            PickedPhotoTiles(ids, images, selection.selectedIds.toSet(), { selection.toggle(it) })
        } } }
        capture("tiles-selected")
        rule.onNodeWithTag("create_photo_tile_0").performClick().assertIsOff()
        rule.onNodeWithTag("create_photo_tile_check_0", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(selection.canProceed)
        capture("tiles-unchecked")
        rule.onNodeWithTag("create_photo_tile_0").performClick().assertIsOn()
        assertTrue(selection.canProceed)
        repeat(3) { rule.onNodeWithTag("create_photo_tile_$it").performClick().assertIsOff() }
        repeat(3) { rule.onNodeWithTag("create_photo_tile_$it").assertIsDisplayed() }
        rule.onNodeWithTag("create_photo_tile_1").performClick().assertIsOn()
        assertEquals(listOf("p1"), selection.selectedIds)
    }

    @Test fun createAndReceiveProcessingHaveExactlyOneProgressIndicator() {
        val controller = CaptureAttemptController()
        controller.onPermissionResolved(CapturePermissionStep.Granted)
        controller.onPreviewBound(); controller.beginAttempt(); assertTrue(controller.markProcessing())
        var sender by mutableStateOf(false)
        rule.setContent { HoldTheme { CaptureAttemptSurface(controller, "shutter", "retake", { false }, {}, {},
            requestPermissionOnAttach = false, shutterLabel = if (sender) "Use postcard" else null) } }
        repeat(2) {
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
            rule.onNodeWithTag("capture_processing_still").assertDoesNotExist()
            rule.onNodeWithTag("capture_processing_spinner").assertIsDisplayed()
            rule.onNodeWithTag("shutter").assertIsNotEnabled()
            capture(if (sender) "create-one-loader" else "receive-one-loader")
            rule.runOnIdle { sender = true }
        }
    }

    @Test fun pendingTermsLookupHasBackButNeverFlashesAgreementForm() {
        var back = 0
        rule.setContent { HoldTheme { BetaTermsPendingPage { back++ } } }
        rule.onNodeWithTag("beta_terms_pending").assertIsDisplayed()
        rule.onNodeWithTag("beta_terms_agreement").assertDoesNotExist()
        rule.onNodeWithTag("beta_terms_continue").assertDoesNotExist()
        capture("terms-pending-neutral")
        rule.onNodeWithTag("access_back").performClick()
        assertEquals(1, back)
    }

    @Test fun originalPagerFitsAspectSwipesClosesAndDisappearsWithPresentation() {
        val ids = listOf("p0", "p1", "p2")
        val dimensions = listOf(320 to 240, 160 to 320, 300 to 300)
        val expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1, grammarId = "g-hold", grammarVersion = 3, branchId = "b-rows",
            input = GeneratorExpression.GeneratorInput("fixture-owner", 7L,
                ids.mapIndexed { i, id -> GeneratorExpression.PhotoRef(id, i, dimensions[i].first, dimensions[i].second, ('a' + i).toString().repeat(64)) }, null, null),
            placements = ids.mapIndexed { i, id -> GeneratorExpression.Placement(id, i * 120, 0, 120, 213,
                GeneratorExpression.CropWindow(0, 0, 10000, 10000), null, null) },
            noteTreatment = "regular-region", diagnostics = emptyList(), fontVersion = FontRegistry.VERSION,
            paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3, noteRegion = null,
            style = GeneratorExpression.StyleBinding("press", "style-study-1", requireNotNull(StyleRegistry.digest("press"))),
            noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18), musicRegion = null)
        var decodes = 0
        val state = CollagePresentationState(expression, { it.toByteArray() }, decoder = Ber1PhotoDecoder {
            val i = decodes++
            Bitmap.createBitmap(dimensions[i].first, dimensions[i].second, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(80 + i * 60, 100, 150))
            }.asImageBitmap()
        })
        runBlocking { state.load() }
        var validations = 0
        try {
            rule.setContent { HoldTheme { CollagePresentationHost(state, "Synthetic capsule", {}, validateExport = { validations++ }) } }
            capture("capsule-photo-actions")
            rule.onNodeWithTag("capsule_photo_region_0").performClick()
            rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("1 / 3")
            val landscape = rule.onNodeWithTag("capsule_photo_pager").fetchSemanticsNode().boundsInRoot
            assertEquals(4f / 3f, landscape.width / landscape.height, .02f)
            rule.onNodeWithTag("capsule_photo_pager").performTouchInput { swipeLeft() }
            rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("2 / 3")
            val portrait = rule.onNodeWithTag("capsule_photo_pager").fetchSemanticsNode().boundsInRoot
            assertTrue(portrait.height > landscape.height)
            assertTrue(portrait.height <= context.resources.displayMetrics.heightPixels * .71f)
            rule.onNodeWithTag("capsule_photo_pager").performTouchInput { swipeLeft() }
            rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("3 / 3")
            assertEquals(3, decodes)
            assertTrue(validations >= 3)
            rule.onNodeWithTag("capsule_photo_close").performClick()
            rule.onNodeWithTag("capsule_photo_dialog").assertDoesNotExist()
            rule.onNodeWithTag("capsule_photo_region_1").performClick()
            rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("2 / 3")
            rule.runOnIdle { state.close() }
            rule.onNodeWithTag("capsule_photo_dialog").assertDoesNotExist()
            // The actual modal is FLAG_SECURE; do not bypass it to obtain PNGs.
        } finally { rule.runOnIdle { state.close() } }
    }

    @Test fun actualBundledFontMetricsAreCapturedForMultilinePlanInsteadOfOneLineStub() {
        var result: Pair<Double, Double>? = null
        rule.setContent { HoldTheme {
            val real = rememberCollageTextMeasurer()
            LaunchedEffect(real) {
                val note = "A remembered afternoon beside the sea, a long walk home, and all the little things we wanted to keep."
                val input = AllFamilyInput("fixture", "qa_fixture", note, false, false,
                    List(3) { AllFamilyPhoto("p$it", 1000, 1000, false, null) })
                val captured = AllFamilyCandidateProducer.prepareTextMeasurer(input,
                    FrozenGrammarPlans.TextMeasurer { text, width, size, line, font ->
                        val layout = real.layout(CollageRenderer.TextMeasureRequest(text, font, size.toInt(), 400, false,
                            line.toInt(), width.toInt(), Int.MAX_VALUE))
                        FrozenGrammarPlans.TextMetric(layout.size.height.toDouble(), layout.lineCount)
                    })
                val d = FrozenGrammarPlans.Input("fixture", "qa_fixture", List(3) { FrozenGrammarPlans.Photo("p$it",1000.0,1000.0) }, note, null)
                val def = FrozenGrammarPlans.def(18)
                result = FrozenGrammarPlans.noteState(d, def, captured).height to FrozenGrammarPlans.noteState(d, def, FrozenGrammarPlans.LINE_HEIGHT_STUB).height
            }
        } }
        rule.waitUntil(10_000) { result != null }
        val (actual, stub) = requireNotNull(result)
        assertTrue("native multiline note $actual must exceed incorrect one-line $stub", actual > stub)
        File(evidence, "native-note-metrics.txt").writeText("bundledFontNoteHeight=$actual\noldStubNoteHeight=$stub\n")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
