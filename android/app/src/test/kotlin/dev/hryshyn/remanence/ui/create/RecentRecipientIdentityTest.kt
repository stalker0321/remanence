package dev.hryshyn.remanence.ui.create

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.*
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.*
import dev.hryshyn.remanence.core.model.*
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.session.SessionBoundary
import dev.hryshyn.remanence.ui.history.*
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Activity entrypoint, History/RecentRecipients and CreateScreen/VM. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecentRecipientIdentityTest {
    @get:Rule val rule = createComposeRule()
    private val dispatcher = UnconfinedTestDispatcher()
    private val owned = CreateTestViewModels(dispatcher.scheduler)
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var root: android.view.View
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val expected = "30000000-0000-4000-8000-000000000001"
    private val foreign = "30000000-0000-4000-8000-000000000002"
    private val calls = mutableListOf<String>()
    private var liveOwner: String? = owner
    private val boundary = SessionBoundary()
    private val recent = RecentRecipient(expected, "friend_qa", "2026-10-20T10:01:00Z")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            RemanenceLocalDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() {
        runBlocking { withTimeout(10_000) { owned.clearAndJoin() } }
        rule.waitForIdle()
        database.close()
        Dispatchers.resetMain()
    }

    private fun resolved(id: String, handle: String = "friend_qa") = ResolvedHandleSnapshot(
        UserId.parseRest(id), NormalizedHandle.parse(handle),
        KeyBundleId.parseRest("40000000-0000-4000-8000-000000000001"),
        "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519", 1, "fresh-encryption", "fresh-signing", "ACTIVE", "fresh-v2",
    )

    private fun viewModel(lookup: suspend (String) -> DirectoryLookupResult): CreateViewModel {
        val kek = SoftwareKekBoundary()
        val alias = "synthetic-t382-${java.util.UUID.randomUUID()}"
        kek.createAes256GcmKey(alias)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roots = AccountScopedFileRoots(File(context.filesDir, "t382"))
        val retries = SenderRetryMaterialStore(roots)
        val persistence = object : SealedFingerprintPersistence {
            override suspend fun persist(capsuleId: String, origin: FingerprintOrigin, profileId: String, plaintextBytes: ByteArray) = error("No persistence before recipient admission")
            override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin) = false
            override suspend fun decrypt(fingerprintId: String) = error("No decrypt before recipient admission")
            override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit
            override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
        }
        return owned.own(CreateViewModel(
            directory = RecipientDirectoryPort { handle -> calls += handle; lookup(handle) },
            recipientLookupOwnerProvider = { liveOwner },
            recipientLookupBoundaryEpoch = boundary::currentEpoch,
            registerRecipientLookupBoundary = { action -> boundary.register(action) },
            accessTokenProvider = { if (liveOwner == null) null else "synthetic-token" },
            identityProvider = { error("No signing identity before recipient admission") }, persistence = persistence,
            outboxStager = CapsuleOutboxStager(database, roots, retries),
            profile = RecognitionProfile.postcardSiftRootSiftV1(), accountScopedFileRoots = roots,
            openPhotoSource = { error("No photo intake") },
            senderRetryKeysetWrapper = SenderRetryKeysetWrapper(kek), senderRetryKekAlias = alias,
            enqueueUpload = { _, _ -> error("No publication before recipient admission") },
        )).also { it.beginSession(1L, owner) }
    }

    private fun mount(vm: CreateViewModel) {
        val lease = SessionRequestLease(UserId.parseRest(owner), 1)
        val history = owned.own(HistoryViewModel(object : OutgoingHistoryPort {
            override suspend fun outgoing(owner: UserId, cursor: String?) = HistoryResult.Loaded(OutgoingPage(emptyList(), null))
            override suspend fun recent(owner: UserId) = HistoryResult.Loaded(listOf(recent))
            override suspend fun cancel(owner: UserId, item: OutgoingItem) = error("No cancellation")
        }, { lease }, { it == lease && liveOwner == owner }, boundary, true))
        history.bind(owner); history.loadRecent()
        rule.setContent {
            root = LocalView.current.rootView
            HoldTheme { Surface(Modifier.fillMaxSize()) {
                CreateScreen(vm, recentRecipientsContent = {
                    val state by history.recent.collectAsState()
                    RecentRecipients(state, true, history::loadRecent, { id ->
                        history.recentForLookup(id)?.let { suggestion ->
                            // This is the shared production Activity entrypoint,
                            // not a copy of its handle/lookup implementation.
                            vm.lookupRecentRecipient(suggestion, owner, 1L)
                        }
                    })
                })
            } }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val output = File("build/t382-review").apply { mkdirs() }
        output.resolve("$name-semantics.txt").writeText(rule.onAllNodes(isRoot()).printToString())
        rule.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(android.graphics.Canvas(bitmap))
                output.resolve("$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun sameIdentityUsesFreshKeysAndStillRequiresExplicitConfirmation() {
        val fresh = resolved(expected)
        val vm = viewModel { DirectoryLookupResult.Found(fresh) }
        mount(vm)
        rule.onNodeWithTag("recent_$expected").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("friend_qa"), calls)
        assertSame(fresh, vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_CONFIRM, vm.step.value)
        capture("recent-same-en")
        rule.onNodeWithTag("confirm_button").performClick()
        assertSame(fresh, vm.confirmedRecipient.value)
        assertEquals(CreateViewModel.Step.FRONT, vm.step.value)
    }

    @Test fun sameHandleDifferentIdentityCannotReachPendingConfirmationOrFront() {
        val vm = viewModel { DirectoryLookupResult.Found(resolved(foreign)) }
        mount(vm)
        rule.onNodeWithTag("recent_$expected").performScrollTo().performClick()
        rule.waitForIdle()
        capture("recent-mismatch-en")
        assertEquals(listOf("friend_qa"), calls)
        assertNull("Selected recent UUID must not silently become another account", vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertTrue(vm.pickerVm.state.value is RecipientLookupUiState.Failed)
        assertEquals(RecipientLookupUiState.Failure.RECENT_IDENTITY,
            (vm.pickerVm.state.value as RecipientLookupUiState.Failed).reason)
        rule.onNodeWithTag("confirm_button").assertDoesNotExist()
        vm.confirmRecipient(); vm.startPublishing()
        assertNull(vm.confirmedRecipient.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
    }

    private fun assertRejected(vm: CreateViewModel) {
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertEquals(RecipientLookupUiState.Failure.RECENT_IDENTITY,
            (vm.pickerVm.state.value as RecipientLookupUiState.Failed).reason)
    }

    private fun admitCurrent(vm: CreateViewModel) {
        vm.onPickerResolved(vm.pickerVm.state.value as RecipientLookupUiState.Resolved)
    }

    private class HeldReply(private val snapshot: ResolvedHandleSnapshot) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val returned = CompletableDeferred<Unit>()
        private lateinit var requestJob: Job
        suspend fun answer(): DirectoryLookupResult {
            requestJob = currentCoroutineContext().job
            return try {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    DirectoryLookupResult.Found(snapshot)
                }
            } finally { returned.complete(Unit) }
        }
        fun finish() = runBlocking {
            release.complete(Unit)
            // Directory return is not the end of picker owner/admission work.
            // Assert only after the entire retired request has completed.
            withTimeout(10_000) { returned.await(); requestJob.join() }
        }
    }

    @Test fun explicitManualRestartCanResolveTheSameHandleToAnotherIdentity() {
        val fresh = resolved(foreign)
        val vm = viewModel { DirectoryLookupResult.Found(fresh) }
        vm.lookupRecentRecipient(recent, owner, 1L)
        assertRejected(vm)
        vm.restartLookup() // explicit user intent; no automatic fallback
        vm.lookupRecipient()
        admitCurrent(vm)
        assertSame(fresh, vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        vm.confirmRecipient()
        assertSame(fresh, vm.confirmedRecipient.value)
        assertEquals(listOf("friend_qa", "friend_qa"), calls)
    }

    @Test fun manualEditWhileRecentRequestIsHeldIgnoresNonCooperativeReply() {
        val held = HeldReply(resolved(expected))
        val manual = resolved(foreign, "manual_qa")
        val vm = viewModel { if (it == "friend_qa") held.answer() else DirectoryLookupResult.Found(manual) }
        mount(vm)
        rule.onNodeWithTag("recent_$expected").performScrollTo().performClick()
        try {
            assertTrue(held.entered.isCompleted)
            rule.onNodeWithTag("create_handle_input").performTextReplacement("manual_qa")
            rule.onNodeWithTag("create_lookup_button").performClick()
            rule.waitForIdle()
            assertSame(manual, vm.pendingRecipient.value)
        } finally { held.finish() }
        rule.waitForIdle()
        assertSame(manual, vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals(listOf("friend_qa", "manual_qa"), calls)
        rule.onNodeWithTag("confirm_button").performClick()
        assertSame(manual, vm.confirmedRecipient.value)
    }

    @Test fun replacingRecentKeepsNewExpectationWhenOldRequestReturns() {
        val held = HeldReply(resolved(expected))
        var first = true
        val vm = viewModel {
            if (first) { first = false; held.answer() }
            else DirectoryLookupResult.Found(resolved(expected)) // wrong for the new recent
        }
        vm.lookupRecentRecipient(recent, owner, 1L)
        try {
            assertTrue(held.entered.isCompleted)
            vm.lookupRecentRecipient(recent.copy(userId = foreign), owner, 1L)
            assertRejected(vm)
        } finally { held.finish() }
        assertRejected(vm)
        assertEquals(2, calls.size)
    }

    @Test fun retiredComposeResolutionCannotDropNewRecentExpectation() {
        val held = HeldReply(resolved(expected))
        var first = true
        val vm = viewModel {
            if (first) { first = false; DirectoryLookupResult.Found(resolved(expected)) }
            else held.answer() // new recent expects foreign, so this must be refused
        }
        vm.lookupRecentRecipient(recent, owner, 1L)
        val oldResolution = vm.pickerVm.state.value as RecipientLookupUiState.Resolved
        vm.lookupRecentRecipient(recent.copy(userId = foreign), owner, 1L)
        try {
            assertTrue(held.entered.isCompleted)
            vm.onPickerResolved(oldResolution)
            assertNull(vm.pendingRecipient.value)
            assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        } finally { held.finish() }
        assertRejected(vm)
        vm.onPickerResolved(oldResolution)
        assertRejected(vm)
    }

    @Test fun ownerOrEpochReplacementRejectsOldSelectionAndOldResponse() {
        for (nextOwner in listOf(owner, "10000000-0000-4000-8000-000000000002")) {
            liveOwner = owner
            val held = HeldReply(resolved(expected))
            var first = true
            val fresh = resolved(foreign)
            val vm = viewModel {
                if (first) { first = false; held.answer() } else DirectoryLookupResult.Found(fresh)
            }
            vm.lookupRecentRecipient(recent, owner, 1L)
            try {
                assertTrue(held.entered.isCompleted)
                liveOwner = nextOwner
                boundary.invalidate()
                vm.beginSession(2L, nextOwner)
                vm.lookupRecentRecipient(recent.copy(userId = foreign), nextOwner, 2L)
                val current = vm.pickerVm.state.value
                val count = calls.size
                vm.lookupRecentRecipient(recent, owner, 1L) // obsolete Activity callback
                assertSame(current, vm.pickerVm.state.value)
                assertEquals(count, calls.size)
            } finally { held.finish() }
            admitCurrent(vm)
            assertSame(fresh, vm.pendingRecipient.value)
            assertNull(vm.confirmedRecipient.value)
            vm.confirmRecipient()
            assertSame(fresh, vm.confirmedRecipient.value)
            vm.endSession()
        }
    }

    @Test fun logoutBeforeReplyCannotLeaveRecipientMaterial() {
        val held = HeldReply(resolved(expected))
        val vm = viewModel { held.answer() }
        vm.lookupRecentRecipient(recent, owner, 1L)
        try {
            assertTrue(held.entered.isCompleted)
            liveOwner = null
            boundary.invalidate()
            vm.endSession()
            vm.lookupRecentRecipient(recent, owner, 1L)
        } finally { held.finish() }
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertEquals(RecipientLookupUiState.Idle, vm.pickerVm.state.value)
        assertEquals(1, calls.size)
    }

    @Test fun sessionBoundaryBetweenResolutionAndConfirmationCannotBind() {
        val vm = viewModel { DirectoryLookupResult.Found(resolved(expected)) }
        vm.lookupRecentRecipient(recent, owner, 1L)
        admitCurrent(vm)
        assertNotNull(vm.pendingRecipient.value)
        liveOwner = null
        boundary.invalidate() // synchronous fence before Activity teardown/frame
        vm.confirmRecipient()
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
    }

    @Test fun malformedRecentUuidDoesNotFallBackToGenericLookup() {
        val vm = viewModel { error("Malformed identity must not reach directory") }
        vm.lookupRecentRecipient(recent.copy(userId = "not-a-uuid"), owner, 1L)
        assertRejected(vm)
        assertTrue(calls.isEmpty())
    }

    private fun localizedMismatch(label: String) {
        val vm = viewModel { DirectoryLookupResult.Found(resolved(foreign)) }
        mount(vm)
        rule.onNodeWithTag("recent_$expected").performScrollTo().performClick()
        rule.waitForIdle()
        assertRejected(vm)
        val copy = ApplicationProvider.getApplicationContext<Context>()
            .getString(dev.hryshyn.remanence.R.string.create_audit_recent_identity)
        rule.onNodeWithText(copy).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("create_lookup_button").assertIsEnabled()
        capture(label)
    }

    @Test @Config(qualifiers = "ru-rRU-w390dp-h844dp-mdpi")
    fun russianMismatchKeepsManualRecoveryVisible() = localizedMismatch("recent-mismatch-ru")

    @Test @Config(qualifiers = "uk-rUA-w390dp-h844dp-mdpi")
    fun ukrainianMismatchKeepsManualRecoveryVisible() = localizedMismatch("recent-mismatch-uk")
}
