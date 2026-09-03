package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    private fun viewModel(directory: RecipientDirectoryPort): CreateViewModel {
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir))
        return CreateViewModel(
            directory = directory,
            accessTokenProvider = { "token" },
            identityProvider = { null },
            persistence = NoPersistence(),
            outboxStager = CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir), retryStore),
            profile = RecognitionProfile.mvpOrbV1(),
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

    /** Types the handle and resolves it through the REAL picker + lookup. */
    private fun resolveThroughUi(handle: String) {
        composeRule.onNodeWithTag("create_handle_input").performTextInput(handle)
        composeRule.onNodeWithTag("create_lookup_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("confirm_handle_text")
                .fetchSemanticsNodes().isNotEmpty()
        }
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
        composeRule.onNodeWithTag("confirm_handle_text").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_account_cue_text").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_ack_checkbox").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_button").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("cancel_button").assertIsDisplayed()

        // Nothing is bound until the user explicitly acknowledges.
        assertNull("no binding may exist before explicit confirmation", vm.confirmedRecipient.value)

        composeRule.onNodeWithTag("confirm_ack_checkbox").performClick()
        composeRule.onNodeWithTag("confirm_button").assertIsEnabled()
        composeRule.onNodeWithTag("confirm_button").performClick()

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

        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
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
        composeRule.onNodeWithTag("confirm_ack_checkbox").performClick()
        composeRule.onNodeWithTag("confirm_button").performClick()

        // Confirming another recipient binds THEIR snapshot - never silently
        // rewritten to self - so the publication will be addressed to that
        // distinct identity. M2-P07: the prior M1 self-only guard is gone;
        // cross-identity publication is the supported path.
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertSame(otherResolved, vm.confirmedRecipient.value)

        // Without captured sides nothing can be published: FIX-STATE-02
        // fails CLOSED with a visible recovery message instead of crashing.
        vm.startPublishing()
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertTrue(vm.flowError.value!!.contains("publishing requires step CONTENT"))
        runBlocking {
            assertTrue(database.outboxBlobDao().getAllByCapsuleIdAndOwner(vm.capsuleId, "0198f0a0-0000-7000-8000-00000000ow01").isEmpty())
        }
    }
}
