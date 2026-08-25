package app.postmark.memory.ui.create

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
import postmark.core.data.db.FingerprintSide
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.data.network.DirectoryLookupResult
import postmark.core.data.network.ResolvedHandleSnapshot
import postmark.core.data.outbox.CapsuleOutboxStager
import postmark.core.model.KeyBundleId
import postmark.core.model.NormalizedHandle
import postmark.core.model.UserId
import postmark.core.recognition.RecognitionProfile

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

    private lateinit var database: PostmarkLocalDatabase
    private lateinit var stagingDir: File

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
            accessToken: String,
        ): DirectoryLookupResult =
            byHandle[rawHandle]?.let { DirectoryLookupResult.Found(it) }
                ?: DirectoryLookupResult.NotFound
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            side: FingerprintSide,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(
            capsuleId: String,
            side: FingerprintSide,
            origin: FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredPair(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: FingerprintSide,
            origin: FingerprintOrigin,
        ) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
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

    private fun viewModel(directory: RecipientDirectoryPort) = CreateViewModel(
        directory = directory,
        accessTokenProvider = { "token" },
        identityProvider = { null },
        persistence = NoPersistence(),
        outboxStager = CapsuleOutboxStager(database, stagingDir),
        profile = RecognitionProfile.mvpOrbV1(),
        stagingDirectory = stagingDir,
        openPhotoSource = { error("photo picker not used in this test") },
    )

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
    fun recipientConfirmWithoutPendingFailsClosedInsteadOfABlankScreen() {
        val vm = viewModel(ScriptedDirectory(mapOf("vodkolyan" to selfSnapshot)))
        vm.beginSession(1L)
        setContent(vm)
        resolveThroughUi("vodkolyan")

        // endSession clears the transient material while navigation stays -
        // the impossible invariant must show an explicit error + way back.
        vm.endSession()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("create_confirm_missing_pending")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_confirm_missing_pending").assertIsDisplayed()
        composeRule.onNodeWithTag("create_confirm_back_to_lookup").performClick()

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
        // rewritten to self - so the M1 publisher's own-account guard decides.
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
        assertSame(otherResolved, vm.confirmedRecipient.value)

        // Without captured sides nothing can be published: fail closed.
        try {
            vm.startPublishing()
            fail("publishing must be gated behind real captures")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("captured"))
        }
        assertNull(vm.publishError.value)
        runBlocking {
            assertTrue(database.outboxBlobDao().getAllByCapsuleId(vm.capsuleId).isEmpty())
        }
    }
}
