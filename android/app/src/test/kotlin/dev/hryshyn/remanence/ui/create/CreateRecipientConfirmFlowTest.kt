package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-M1-ONDEVICE-01 regression, production-shaped: the REAL [CreateScreen]
 * over the REAL [CreateViewModel]. On device, resolving own handle reached
 * RECIPIENT_CONFIRM but the screen read `confirmedRecipient`, which stays
 * null until explicit confirmation - an eternal blank confirmation screen.
 * These tests drive the real lookup -> confirm -> FRONT flow through the
 * actual composition and tags.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CreateRecipientConfirmFlowTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    private val selfSnapshot = ResolvedHandleSnapshot(
        userId = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001"),
        handle = NormalizedHandle.parse("vodkolyan"),
        keyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000b001"),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "unused",
        signingPublicKeysetB64Url = "unused",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private val otherSnapshot = ResolvedHandleSnapshot(
        userId = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c003"),
        handle = NormalizedHandle.parse("friend"),
        keyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000d004"),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "unused",
        signingPublicKeysetB64Url = "unused",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private class ScriptedDirectory(
        private val byHandle: Map<String, ResolvedHandleSnapshot>,
    ) : RecipientDirectoryPort {
        override suspend fun lookup(
            rawHandle: String,
        ): DirectoryLookupResult =
            byHandle[rawHandle]?.let { DirectoryLookupResult.Found(it) }
                ?: DirectoryLookupResult.NotFound
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: FingerprintOrigin,
        ) = Unit
    }

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "confirm-flow-staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
    }

    private fun viewModel(directory: RecipientDirectoryPort,
                          selfAccount: suspend () -> SelfRecipientAccount? = { null },
                          ownerProvider: suspend () -> String? = { "token" }): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir))
        return CreateViewModel(
            directory = directory,
            selfRecipientAccountProvider = selfAccount,
            recipientLookupOwnerProvider = ownerProvider,
            accessTokenProvider = { "token" },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir), retryStore),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir),
            openPhotoSource = { error("photo picker not used in this test") },
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
        )
    }

    private fun setContent(viewModel: CreateViewModel) {
        composeRule.setContent {
            MaterialTheme { CreateScreen(viewModel = viewModel) }
        }
    }

    @Test
    fun recentSuggestionRunsFreshLookupThenExistingExplicitConfirmation() {
        val calls = mutableListOf<String>()
        val owner = selfSnapshot.userId.toRestString()
        val vm = viewModel(RecipientDirectoryPort { raw ->
            calls += raw
            DirectoryLookupResult.Found(otherSnapshot)
        }, ownerProvider = { owner })
        vm.beginSession(1L, owner)
        val recent = dev.hryshyn.remanence.ui.history.RecentUiState(owner = owner, loaded = true,
            items = listOf(dev.hryshyn.remanence.core.data.network.RecentRecipient(
                otherSnapshot.userId.toRestString(), "friend", "2026-10-20T10:01:00Z")))
        composeRule.setContent {
            MaterialTheme {
                CreateScreen(vm, recentRecipientsContent = {
                    dev.hryshyn.remanence.ui.history.RecentRecipients(recent, true, {}, { id ->
                        val suggestion = recent.items.single { it.userId == id }
                        vm.onHandleChange(suggestion.handle)
                        vm.lookupRecipient()
                    })
                })
            }
        }
        composeRule.onNodeWithTag("recent_${otherSnapshot.userId.toRestString()}").performScrollTo().performClick()
        composeRule.waitUntil(10_000) { vm.pendingRecipient.value != null }
        assertEquals(listOf("friend"), calls)
        assertNull(vm.confirmedRecipient.value)
        composeRule.onNodeWithTag("confirm_handle_text").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_button").performClick()
        composeRule.runOnIdle {
            assertEquals(otherSnapshot, vm.confirmedRecipient.value)
            assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        }
    }

    /** Types the handle and resolves it through the REAL picker + lookup. */
    private fun resolveThroughUi(handle: String) {
        composeRule.onNodeWithTag("create_handle_input").performTextInput(handle)
        composeRule.onNodeWithTag("create_lookup_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("confirm_handle_text", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selfShortcutNativeVisualFixtureUsesActualCreateScreen() {
        val owner = selfSnapshot.userId.toRestString()
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)),
            { SelfRecipientAccount(owner, "vodkolyan") }, { owner })
        vm.beginSession(1L, owner)
        val root = java.util.concurrent.atomic.AtomicReference<android.view.View>()
        composeRule.setContent {
            root.set(LocalView.current.rootView)
            HoldTheme { Surface(Modifier.fillMaxSize()) { CreateScreen(viewModel = vm) } }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.selfRecipientAccount.value != null }
        composeRule.onNodeWithTag("create_handle_input").assertIsDisplayed()
        composeRule.onNodeWithTag("create_self_recipient").assertIsDisplayed().assertIsEnabled()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val view = checkNotNull(root.get())
            check(view.width > 0 && view.height > 0)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = File("build/hold-review/recipient-self-en.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        // Presentation fixture only: no directory request or recipient binding happened.
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
    }

    @Test
    fun selfActionUsesAccountMetadataAndDirectoryThenRequiresExplicitConfirmation() {
        val calls = mutableListOf<String>()
        val owner = selfSnapshot.userId.toRestString()
        val vm = viewModel(RecipientDirectoryPort { raw ->
            calls += raw
            DirectoryLookupResult.Found(selfSnapshot)
        }, { SelfRecipientAccount(owner, "vodkolyan") }, { owner })
        vm.beginSession(1L, owner)
        setContent(vm)
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.selfRecipientAccount.value != null }
        composeRule.onNodeWithTag("create_handle_input").performTextInput("friend")
        composeRule.onNodeWithTag("create_self_recipient").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.step.value == CreateViewModel.Step.RECIPIENT_CONFIRM }
        assertEquals(listOf("vodkolyan"), calls)
        assertSame(selfSnapshot, vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        composeRule.onNodeWithTag("confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.step.value == CreateViewModel.Step.FRONT }
        assertSame(selfSnapshot, vm.confirmedRecipient.value)
    }

    @Test
    fun noCurrentAccountMeansNoSelfShortcut() {
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)))
        vm.beginSession(1L, selfSnapshot.userId.toRestString())
        setContent(vm)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_self_recipient").assertDoesNotExist()
        assertNull(vm.selfRecipientAccount.value)
    }

    @Test
    fun foreignDirectorySnapshotCannotBecomeSelfPendingConfirmation() {
        val owner = selfSnapshot.userId.toRestString()
        val vm = viewModel(RecipientDirectoryPort { DirectoryLookupResult.Found(otherSnapshot) },
            { SelfRecipientAccount(owner,"vodkolyan") }, { owner })
        vm.beginSession(1L, owner)
        setContent(vm)
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.selfRecipientAccount.value != null }
        composeRule.onNodeWithTag("create_self_recipient").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.selfRecipientLookupRejected.value }
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        composeRule.onNodeWithTag("create_self_recipient_error").assertExists()
    }

    @Test
    fun ownerChangeDuringSelfMetadataReadPreventsDirectoryLookup() {
        val originalOwner = selfSnapshot.userId.toRestString()
        var liveOwner = originalOwner
        val gate = kotlinx.coroutines.CompletableDeferred<SelfRecipientAccount?>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val vm = viewModel(RecipientDirectoryPort {
            calls.incrementAndGet()
            DirectoryLookupResult.Found(selfSnapshot)
        }, {
            if (reads.incrementAndGet() == 1) SelfRecipientAccount(originalOwner,"vodkolyan") else gate.await()
        }, { liveOwner })
        vm.beginSession(1L, originalOwner)
        setContent(vm)
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.selfRecipientAccount.value != null }
        composeRule.onNodeWithTag("create_self_recipient").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { reads.get() == 2 }
        liveOwner = otherSnapshot.userId.toRestString()
        gate.complete(SelfRecipientAccount(originalOwner,"vodkolyan"))
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.selfRecipientLookupRejected.value }
        assertEquals(0,calls.get())
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
    }

    @Test
    fun lookupFoundSelfShowsTheRealConfirmationScreenThenExplicitConfirmBinds() {
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)))
        vm.beginSession(1L)
        setContent(vm)

        resolveThroughUi("vodkolyan")

        // The resolved snapshot is observable for the confirmation screen
        // BEFORE any confirmation happened.
        assertEquals(CreateViewModel.Step.RECIPIENT_CONFIRM, vm.step.value)
        val resolvedBeforeConfirm = vm.pendingRecipient.value
        assertNotNull(resolvedBeforeConfirm)

        // The confirmation controls are REALLY rendered (was: blank screen).
        composeRule.onNodeWithTag("confirm_handle_text", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_account_cue_text", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_ack_checkbox").assertDoesNotExist()
        composeRule.onNodeWithTag("confirm_button").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("cancel_button").assertIsDisplayed()

        // Nothing is bound until the user presses the whole confirmation object.
        assertNull("no binding may exist before explicit confirmation", vm.confirmedRecipient.value)

        composeRule.onNodeWithTag("confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { vm.step.value == CreateViewModel.Step.FRONT }

        // The SAME immutable snapshot instance moved into the session store;
        // the pending copy is gone with it.
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertSame(resolvedBeforeConfirm, vm.confirmedRecipient.value)
        assertNull(vm.pendingRecipient.value)
    }

    @Test
    fun cancelDropsThePendingResolveAndReturnsToLookup() {
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)))
        vm.beginSession(1L)
        setContent(vm)
        resolveThroughUi("vodkolyan")
        assertNotNull(vm.pendingRecipient.value)

        composeRule.onNodeWithTag("cancel_button").performClick()
        composeRule.waitForIdle()

        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals("vodkolyan", vm.pickerVm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.pickerVm.state.value)
        composeRule.onNodeWithTag("create_handle_input").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_button").assertDoesNotExist()
    }

    @Test
    fun headerBackClearsResolvedStateInsteadOfImmediatelyReenteringConfirmation() {
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)))
        vm.beginSession(1L)
        setContent(vm)
        resolveThroughUi("vodkolyan")
        composeRule.onNodeWithTag("flow_back_create").performClick()
        composeRule.waitForIdle()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertEquals("vodkolyan", vm.pickerVm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.pickerVm.state.value)
        assertNull(vm.pendingRecipient.value)
        composeRule.onNodeWithTag("confirm_button").assertDoesNotExist()
        composeRule.onNodeWithTag("create_lookup_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_button").assertIsDisplayed()
    }

    @Test
    fun endSessionFromConfirmReturnsToLookupInsteadOfABlankScreen() {
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)))
        vm.beginSession(1L)
        setContent(vm)
        resolveThroughUi("vodkolyan")

        // endSession tears pending material AND the confirm step down, so the
        // surface returns to lookup instead of a blank confirmation screen.
        vm.endSession()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("create_handle_input")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_handle_input").assertIsDisplayed()

        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
    }

    @Test
    fun sameEpochKeepsPendingNewEpochAndEndSessionDropEverything() {
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)))
        vm.beginSession(1L)
        setContent(vm)
        resolveThroughUi("vodkolyan")
        val pending = vm.pendingRecipient.value
        assertNotNull(pending)

        // Rotation (same epoch) must NOT discard the pending resolve.
        vm.beginSession(1L)
        assertSame(pending, vm.pendingRecipient.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_CONFIRM, vm.step.value)

        // A new session (new epoch) drops pending AND confirmed material.
        vm.beginSession(2L)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)

        // And leaving mid-confirm tears both down immediately.
        vm.onResolved(selfSnapshot)
        vm.endSession()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
    }

    @Test
    fun anotherRecipientConfirmsButPublishCannotTurnCrossUser() {
        val vm = viewModel(ScriptedDirectory(mapOf("friend" to otherSnapshot)))
        vm.beginSession(1L)
        setContent(vm)

        resolveThroughUi("friend")
        val otherResolved = vm.pendingRecipient.value
        assertNotNull(otherResolved)
        composeRule.onNodeWithTag("confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { vm.step.value == CreateViewModel.Step.FRONT }

        // Confirming another recipient binds THEIR snapshot - never silently
        // rewritten to self - so the publication will be addressed to that
        // distinct identity. M2-P07: the prior M1 self-only guard is gone;
        // cross-identity publication is the supported path.
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertSame(otherResolved, vm.confirmedRecipient.value)

        // Without captured sides nothing can be published: FIX-STATE-02
        // fails CLOSED with a visible recovery message instead of crashing.
        // Still at FRONT with no photos: composition is refused, and
        // publishing still requires the COMPOSE screen.
        assertFalse(vm.goToCompose())
        vm.startPublishing()
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertTrue(vm.flowError.value!!.contains("publishing requires step COMPOSE"))
        runBlocking {
            assertTrue(database.outboxBlobDao().getAllByCapsuleIdAndOwner(vm.capsuleId, "0198f0a0-0000-7000-8000-00000000ow01").isEmpty())
        }
    }
}
