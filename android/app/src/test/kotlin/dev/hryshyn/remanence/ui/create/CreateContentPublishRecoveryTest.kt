package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.network.CapsuleRevokePort
import dev.hryshyn.remanence.core.data.network.CapsuleRevokeResult
import dev.hryshyn.remanence.core.data.network.CapsuleRevokeState
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-STATE-06 regression: content input is observable (typed note echoes,
 * the byte-limit error is visible), the photo picker result flows through THE
 * production sink so the 3..5 gate recomposes, and publishing shows visible
 * progress whose failure returns to CONTENT with every input preserved and a
 * visible retry path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CreateContentPublishRecoveryTest {

    /** Internal test dispatcher that cancels after a block returns. */
    private class CancelAfterRunDispatcher : CoroutineDispatcher() {
        private val delegate = Dispatchers.Default
        @Volatile var armed = false
        @Volatile var cancellationRequested = false
        @Volatile var jobSeen = false
        val cancellationObserved = CompletableDeferred<Unit>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                block.run()
                if (armed) {
                    cancellationRequested = true
                    context[kotlinx.coroutines.Job]?.let {
                        jobSeen = true
                        it.cancel()
                    }
                    cancellationObserved.complete(Unit)
                }
            }
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private val viewModelStore = ViewModelStore()
    private var viewModelKey = 0

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("8c111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("8c333333-4444-4555-8666-777777777777")

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
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
    }

    @After
    fun tearDown() {
        val viewModelJobs = viewModelStore.keys().mapNotNull { key ->
            viewModelStore.get(key)?.viewModelScope?.coroutineContext?.get(Job)
        }
        viewModelStore.clear()
        runBlocking {
            viewModelJobs.forEach { it.join() }
        }
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
    }

    private class ScriptedProcessor(private val outcome: ProcessedStill) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = outcome
    }

    private class RecordingPersistence : SealedFingerprintPersistence {
        val stored = mutableMapOf<String, ByteArray>()
        val decryptIds = mutableListOf<String>()
        val decryptedBuffers = mutableListOf<ByteArray>()
        var counter = 0

        override suspend fun persist(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            val id = "fp-${++counter}"
            stored[id] = plaintextBytes.copyOf()
            return id
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = stored.isNotEmpty()

        override suspend fun decrypt(fingerprintId: String): ByteArray {
            decryptIds += fingerprintId
            return requireNotNull(stored[fingerprintId]).copyOf().also { decryptedBuffers += it }
        }

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private class RecordingRevokePort(
        private val result: CapsuleRevokeResult,
    ) : CapsuleRevokePort {
        val calls = mutableListOf<CapsuleId>()

        override suspend fun revoke(
            capsuleId: CapsuleId,
            accessToken: String,
        ): CapsuleRevokeResult {
            calls += capsuleId
            return result
        }
    }

    private fun b64Url(bytes: ByteArray): String =
        com.google.crypto.tink.subtle.Base64.urlSafeEncode(bytes)

    private fun senderIdentity() = SenderIdentitySnapshot(
        userId = userUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = bundleUuid.toString(),
        encryptionPrivateHandle = identity.encryptionPrivateHandle,
        signingPrivateHandle = identity.signingPrivateHandle,
    )

    private fun synthetic(side: FingerprintSide): ProcessedStill.Accepted {
        return ProcessedStill.Accepted(
            profileId = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.PROFILE_ID,
            serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(9),
        )
    }

    /** Builds the production surface at the CONTENT step with real crypto. */
    private fun contentStage(
        identityGate: CompletableDeferred<SenderIdentitySnapshot>? = null,
        enqueueUpload: suspend (UserId, CapsuleId) -> Unit = { _, _ -> },
        ioDispatcher: CoroutineDispatcher = testDispatcher,
        revoke: CapsuleRevokePort? = null,
        networkConnected: () -> Boolean = { true },
        accessToken: String? = null,
    ): Triple<CreateViewModel, RecordingPersistence, androidx.compose.ui.test.junit4.ComposeTestRule> {
        val persistence = RecordingPersistence()
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir()))
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { accessToken },
            identityProvider = {
                if (identityGate != null) identityGate.await() else null
            },
            persistence = persistence,
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir()), retryStore),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir()),
            openPhotoSource = { id ->
                dev.hryshyn.remanence.create.PhotoSource {
                    java.io.ByteArrayInputStream("photo-$id".toByteArray())
                }
            },
            frontProcessor = ScriptedProcessor(synthetic(FingerprintSide.FRONT)),
            photoNormalizer = { input -> dev.hryshyn.remanence.create.NormalizedPhotoDto(input.copyOf(), 800, 600) },
            cpuDispatcher = testDispatcher,
            ioDispatcher = ioDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = enqueueUpload,
            outboxCapsuleDao = database.outboxCapsuleDao(),
            capsuleRevoke = revoke,
            networkConnected = networkConnected,
        )
        viewModelStore.put("create-${viewModelKey++}", vm)
        vm.beginSession(1L, userUuid.toString())

        // Walk the production table to CONTENT.
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        awaitStep(vm, CreateViewModel.Step.CONTENT)
        return Triple(vm, persistence, composeRule)
    }

    private fun awaitStep(vm: CreateViewModel, expected: CreateViewModel.Step) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value != expected) {
            if (System.currentTimeMillis() > deadline) {
                error("expected $expected, got ${vm.step.value}")
            }
            Thread.sleep(5)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.scrollToTag(tag: String) {
        onNodeWithTag("create_screen_scroll").performScrollToNode(hasTestTag(tag))
    }

    private fun stagingDir() =
        java.io.File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "content-recovery-staging",
        )

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(userUuid),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(bundleUuid),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = b64Url(identity.encryptionPublicKeyset),
        signingPublicKeysetB64Url = b64Url(identity.signingPublicKeyset),
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun promoteToPublished(vm: CreateViewModel) {
        runBlocking {
            val dao = database.outboxCapsuleDao()
            assertEquals(1, dao.beginUploadForOwner(vm.capsuleId, userUuid.toString()))
            assertEquals(1, dao.beginFinalizeForOwner(vm.capsuleId, userUuid.toString()))
            assertEquals(1, dao.markPublishedForOwner(vm.capsuleId, userUuid.toString()))
        }
        awaitStep(vm, CreateViewModel.Step.PUBLISHED)
    }

    // ------------------------------------------------------------------
    // Note input visibility.
    // ------------------------------------------------------------------

    @Test
    fun noteTypingEchoesAndOversizedInputShowsVisibleLimitError() {
        val (vm, _, _) = contentStage()
        composeRule.setContent {
            MaterialTheme { CreateScreen(viewModel = vm) }
        }

        composeRule.onNodeWithTag("create_note_input")
            .performTextInput("dear mama")

        composeRule.runOnIdle {
            // The observable state received the exact typed value.
            assertEquals("dear mama", vm.noteEditor.text)
            assertFalse(vm.noteEditor.limitReached)
        }
        // Oversized candidate is REJECTED whole and the error becomes visible.
        composeRule.runOnIdle {
            assertFalse(vm.noteEditor.onChange("x".repeat(NoteEditorState.MAX_NOTE_BYTES + 1)))
        }
        composeRule.waitForIdle()
        val editable = composeRule.onNodeWithTag("create_note_input")
            .fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)
        val errNodes = composeRule.onAllNodesWithTag("create_note_limit_error")
            .fetchSemanticsNodes().size
        assertTrue(
            "editable=$editable errNodes=$errNodes limit=${vm.noteEditor.limitReached}",
            errNodes > 0,
        )
        composeRule.onNodeWithTag("create_note_limit_error").assertIsDisplayed()
        composeRule.onNodeWithTag("create_publish").assertIsNotEnabled()
        assertEquals("dear mama", vm.noteEditor.text)

        // Valid input clears the error again.
        composeRule.runOnIdle { assertTrue(vm.noteEditor.onChange("still dear")) }
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithTag("create_note_limit_error").fetchSemanticsNodes().isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Photo picker sink + 3..5 gate recomposition.
    // ------------------------------------------------------------------

    @Test
    fun pickerResultsFlowThroughTheProductionSinkAndRecomposeTheGate() {
        val (vm, _, _) = contentStage()
        composeRule.setContent {
            MaterialTheme { CreateScreen(viewModel = vm) }
        }

        composeRule.runOnIdle { vm.onPhotosPicked(listOf("a", "b")) }
        composeRule.onNodeWithTag("create_selection_count").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("Selected: 2 of 3-5", countText())
            assertFalse(vm.photoSelection.canProceed)
        }
        composeRule.scrollToTag("create_publish")
        composeRule.onNodeWithTag("create_publish").assertIsNotEnabled()

        composeRule.runOnIdle { vm.onPhotosPicked(listOf("a", "b", "c")) }
        composeRule.runOnIdle {
            assertEquals("Selected: 3 of 3-5", countText())
            assertTrue(vm.photoSelection.canProceed)
        }
        composeRule.onNodeWithTag("create_publish").assertIsEnabled()

        // Six picked ids cannot exceed five; the gate stays inside 3..5.
        composeRule.runOnIdle { vm.onPhotosPicked(listOf("1", "2", "3", "4", "5", "6")) }
        composeRule.runOnIdle {
            assertEquals(5, vm.photoSelection.selectedIds.size)
            assertEquals("Selected: 5 of 3-5", countText())
        }
    }

    private fun countText(): String {
        val node = composeRule.onNodeWithTag("create_selection_count").fetchSemanticsNode()
        return node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
            ?.joinToString("") { it.text } ?: ""
    }

    // ------------------------------------------------------------------
    // Publishing progress, failure recovery, single side effects.
    // ------------------------------------------------------------------

    @Test
    fun publishingShowsProgressAndFailureRecoversToContentPreservingInputs() = runBlocking {
        val gate = CompletableDeferred<SenderIdentitySnapshot>()
        val (vm, _, _) = contentStage(identityGate = gate)
        composeRule.setContent {
            MaterialTheme { CreateScreen(viewModel = vm) }
        }

        composeRule.runOnIdle {
            vm.onPhotosPicked(listOf("p1", "p2", "p3"))
            assertTrue(vm.noteEditor.onChange("recover me"))
        }
        composeRule.waitForIdle()
        composeRule.scrollToTag("create_publish")
        composeRule.onNodeWithTag("create_publish")
            .assertIsEnabled()
            .performClick()

        composeRule.waitForIdle()
        assertEquals(
            "step=" + vm.step.value +
                " flowError=" + vm.flowError.value +
                " publishError=" + vm.publishError.value,
            CreateViewModel.Step.PUBLISHING,
            vm.step.value,
        )
        composeRule.onNodeWithTag("create_publishing_spinner").assertIsDisplayed()
        composeRule.onNodeWithTag("create_publishing").assertIsDisplayed()

        // A second start during PUBLISHING is refused visibly (no duplicates).
        composeRule.runOnIdle { vm.startPublishing() }
        composeRule.runOnIdle { assertNotNull(vm.flowError.value) }

        // The gated identity fails: recovery returns to CONTENT with inputs.
        composeRule.runOnIdle { gate.completeExceptionally(java.io.IOException("identity unavailable")) }
        composeRule.waitForIdle()
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        composeRule.scrollToTag("create_error")
        composeRule.onNodeWithTag("create_error").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(vm.photoSelection.canProceed)
            assertEquals("recover me", vm.noteEditor.text)
            assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        }
        // No outbox row was staged by the failed attempt.
        runBlocking {
            assertTrue(database.outboxCapsuleDao().getByCapsuleIdAndOwner(vm.capsuleId, userUuid.toString()) == null)
        }
        Unit
    }

    @Test
    fun cancellationAtFrontDecryptReturnWipesOwnedBuffer() = runBlocking {
        val gate = CompletableDeferred<SenderIdentitySnapshot>().apply { complete(senderIdentity()) }
        val ioDispatcher = CancelAfterRunDispatcher()
        val (vm, persistence, _) = contentStage(
            identityGate = gate,
            ioDispatcher = ioDispatcher,
        )
        vm.onPhotosPicked(listOf("p1", "p2", "p3"))

        ioDispatcher.armed = true
        vm.startPublishing()
        val deadline = System.currentTimeMillis() + 10_000
        while (persistence.decryptedBuffers.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("decrypt must return before cancellation", persistence.decryptedBuffers.isNotEmpty())
        runBlocking { withTimeout(10_000) { ioDispatcher.cancellationObserved.await() } }
        assertTrue(ioDispatcher.cancellationRequested)
        assertTrue(ioDispatcher.jobSeen)
        while (persistence.decryptedBuffers.any { bytes -> bytes.any { it != 0.toByte() } } &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(persistence.decryptedBuffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun publishedScreenConfirmsAndRendersHonestRevokeSuccess() = runBlocking {
        val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")
        val revoke = RecordingRevokePort(
            CapsuleRevokeResult.Success(
                dev.hryshyn.remanence.core.data.network.CapsuleRevoke(
                    capsuleId,
                    CapsuleRevokeState.REVOKED,
                    isReplay = true,
                ),
                httpStatus = 200,
            ),
        )
        val (vm, _, _) = contentStage(
            identityGate = CompletableDeferred<SenderIdentitySnapshot>().apply { complete(senderIdentity()) },
            revoke = revoke,
            accessToken = "test-token",
        )
        vm.onPhotosPicked(listOf("p1", "p2", "p3"))
        assertTrue(vm.noteEditor.onChange("honest copy"))
        vm.startPublishing()
        awaitStep(vm, CreateViewModel.Step.UPLOAD_PENDING)
        promoteToPublished(vm)

        composeRule.setContent { MaterialTheme { CreateScreen(viewModel = vm) } }
        composeRule.onNodeWithTag("create_revoke_disclaimer").assertIsDisplayed()
        composeRule.onNodeWithTag("create_revoke_button").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("create_revoke_confirm").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, revoke.calls.size)
        assertEquals(vm.capsuleId, revoke.calls.single().toRestString())
        composeRule.onNodeWithTag("create_revoke_success").assertIsDisplayed()
        composeRule.onNodeWithTag("create_revoke_success")
            .assertTextContains("already received or decrypted", substring = true)
        composeRule.onNodeWithTag("create_revoke_button").assertIsNotEnabled()
        assertEquals(CreateViewModel.Step.PUBLISHED, vm.step.value)
        assertEquals(OutboxCapsuleState.PUBLISHED, database.outboxCapsuleDao().getByCapsuleIdAndOwner(vm.capsuleId, userUuid.toString())!!.state)
    }

    private fun awaitTerminal(vm: CreateViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) error("publish did not terminate")
            Thread.sleep(10)
        }
    }

}
