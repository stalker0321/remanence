package dev.hryshyn.remanence.ui.history

import android.graphics.Bitmap
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionBoundary
import dev.hryshyn.remanence.ui.hold.HoldTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val port = HistoryFakePort()
    private val boundary = SessionBoundary()
    private val lease = SessionRequestLease(UserId.parseRest(HISTORY_OWNER), 1)
    private val vm = HistoryViewModel(port, { lease }, { it == lease }, boundary, true, scope)
    private var exited = 0
    private lateinit var root: android.view.View
    @Before fun before() { Dispatchers.setMain(dispatcher); vm.bind(HISTORY_OWNER) }
    @After fun after() { scope.cancel(); Dispatchers.resetMain() }

    private fun mount(fontScale: Float = 1f, copy: (String) -> String? = vm::codeForCopy) {
        rule.setContent {
            root = LocalView.current.rootView
            val state by vm.state.collectAsState()
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, fontScale)) {
            HoldTheme { Surface(Modifier.fillMaxSize()) {
                HistoryScreen(state, { exited++ }, vm::select, vm::backToList, vm::refresh, vm::loadMore,
                    vm::canCancel, vm::requestCancel, vm::dismissCancel, vm::confirmCancel, copy)
            } }
            }
        }
    }
    private fun snapshot(name: String) {
        rule.waitForIdle()
        val semantics = rule.onAllNodes(isRoot()).printToString()
        rule.runOnIdle {
            val directory = File("build/t369-review").apply { mkdirs() }
            // A Compose dialog owns another window: capture that actual decor,
            // not the underlying activity while claiming to show confirmation.
            val view = if (name.contains("confirm")) requireNotNull(ShadowDialog.getLatestDialog().window).decorView else root
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                File(directory, "$name-semantics.txt").writeText(semantics)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun loadingUnavailableRetryAndGenuineEmptyAreDistinct() {
        val gate = CompletableDeferred<HistoryResult<OutgoingPage>>()
        port.load = { _, _ -> gate.await() }
        vm.refresh()
        mount()
        rule.onNodeWithTag("history_loading").assertIsDisplayed()
        rule.onNodeWithTag("history_empty").assertDoesNotExist()
        rule.runOnIdle { gate.complete(HistoryResult.Failed(HistoryFailure.UNAVAILABLE)) }
        rule.onNodeWithTag("history_error").assertIsDisplayed()
        rule.onNodeWithTag("history_empty").assertDoesNotExist()
        snapshot("history-unavailable-en")
        rule.runOnIdle { port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(emptyList(), null)) } }
        rule.onNodeWithTag("history_retry").performScrollTo().performClick()
        rule.onNodeWithTag("history_empty").assertIsDisplayed()
        rule.onNodeWithTag("history_error").assertDoesNotExist()
    }

    @Test fun listDetailCodeConfirmationAndSystemBackHaveNoOpeningPath() {
        vm.refresh()
        mount()
        rule.onNodeWithTag("history_row_${historyItem().key}").performClick()
        rule.onNodeWithTag("history_detail").assertIsDisplayed()
        rule.onNodeWithTag("history_copy").performScrollTo().performClick()
        assertEquals("AB12Z", rule.activity.getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithTag("history_cancel").performScrollTo().performClick()
        assertEquals(0, port.cancels)
        rule.onNodeWithTag("history_cancel_confirm").assertIsDisplayed()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("history_cancel_confirm").assertDoesNotExist()
        assertNotNull(vm.state.value.selected)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertNull(vm.state.value.selected)
        assertEquals(0, exited)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, exited)
        assertEquals(0, port.cancels)
    }

    @Test fun confirmedRevokeRefetchesRetiredCodeAndUnavailableAction() {
        vm.refresh(); mount()
        rule.onNodeWithTag("history_row_${historyItem().key}").performClick()
        rule.onNodeWithTag("history_cancel").performScrollTo().performClick()
        rule.runOnIdle {
            port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(listOf(historyItem().copy(
                status = OutgoingStatus.REVOKED, locatorStatus = LocatorStatus.RETIRED_TERMINAL,
                code = null, cancelAction = null,
            )), null)) }
        }
        rule.onNodeWithTag("history_cancel_confirm").performClick()
        assertEquals(1, port.cancels); assertEquals(2, port.loads)
        rule.onNodeWithTag("history_cancel_outcome").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("history_copy").assertDoesNotExist()
        rule.onNodeWithTag("history_cancel").assertDoesNotExist()
        rule.onNodeWithTag("history_no_cancel").performScrollTo().assertIsDisplayed()
    }

    @Test fun accountBoundaryRemovesOpenDetailAndConfirmationImmediately() {
        vm.refresh(); mount()
        rule.onNodeWithTag("history_row_${historyItem().key}").performClick()
        rule.onNodeWithTag("history_cancel").performScrollTo().performClick()
        rule.runOnIdle { boundary.invalidate() }
        rule.onNodeWithTag("history_detail").assertDoesNotExist()
        rule.onNodeWithTag("history_cancel_confirm").assertDoesNotExist()
        rule.onNodeWithTag("history_code").assertDoesNotExist()
        assertEquals(0, port.cancels)
    }

    @Test fun failedClipboardAdmissionNeverClaimsCopied() {
        vm.refresh(); mount(copy = { null })
        rule.onNodeWithTag("history_row_${historyItem().key}").performClick()
        rule.onNodeWithTag("history_copy").performScrollTo().performClick()
        rule.onNodeWithTag("history_copy_result").assertTextEquals("Could not copy the code.")
    }

    @Test fun expiredSessionOffersWorkingReturnHomeNotAnInertRetry() {
        port.load = { _, _ -> HistoryResult.Failed(HistoryFailure.SIGN_IN) }
        vm.refresh(); mount()
        rule.onNodeWithTag("history_retry").performScrollTo().performClick()
        assertEquals(1, exited)
        assertEquals(1, port.loads)
    }

    @Test fun recentSessionFailureOffersExitWithoutSelectingRecipient() {
        var exits = 0
        var selections = 0
        rule.setContent {
            HoldTheme { RecentRecipients(RecentUiState(HISTORY_OWNER, error = HistoryFailure.SIGN_IN),
                true, { exits++ }, { selections++ }) }
        }
        rule.onNodeWithTag("recent_error").assertIsDisplayed()
        rule.onNodeWithTag("recent_retry").performClick()
        assertEquals(1, exits); assertEquals(0, selections)
    }

    @Test fun deepListSelectionStartsDetailAtTopAndBackRestoresListPosition() {
        val rows = (1..20).map { historyItem("20000000-0000-4000-8000-" + it.toString().padStart(12, '0')) }
        port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(rows, null)) }
        vm.refresh(); mount()
        rule.onNodeWithTag("history_scroll").performScrollToNode(hasTestTag("history_row_${rows.last().key}"))
        rule.onNodeWithTag("history_row_${rows.last().key}").performClick()
        rule.onNodeWithTag("history_status").assertIsDisplayed()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("history_row_${rows.last().key}").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h568dp-mdpi")
    fun narrowLargeTextKeepsCodeAndCancelReachableByScroll() {
        vm.refresh(); mount(fontScale = 1.5f)
        rule.onNodeWithTag("history_row_${historyItem().key}").performScrollTo().performClick()
        rule.onNodeWithTag("history_copy").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("history_cancel").performScrollTo().assertIsDisplayed()
        snapshot("history-narrow-large-text-ru")
    }

    private fun visual(locale: String) {
        port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(listOf(
            historyItem(),
            historyItem("20000000-0000-4000-8000-000000000002").copy(
                protocolVersion = 2, recipientHandle = null, recipientHint = "r•••@•••",
                status = OutgoingStatus.PENDING, publishedAt = null, cancelAction = HistoryCancelAction.CANCEL_PENDING_V2),
            historyItem("20000000-0000-4000-8000-000000000003").copy(status = OutgoingStatus.OPENED,
                locatorStatus = LocatorStatus.RETIRED_OPEN, code = null, cancelAction = null),
        ), null)) }
        vm.refresh(); mount()
        snapshot("history-list-$locale")
        rule.onNodeWithTag("history_row_${historyItem().key}").performClick()
        snapshot("history-detail-$locale")
        rule.onNodeWithTag("history_cancel").performScrollTo().performClick()
        snapshot("history-confirm-$locale")
    }
    @Test fun visualEnglish() = visual("en")
    @Test @Config(qualifiers = "ru-w390dp-h844dp-mdpi") fun visualRussian() = visual("ru")
    @Test @Config(qualifiers = "uk-w390dp-h844dp-mdpi") fun visualUkrainian() = visual("uk")
}
