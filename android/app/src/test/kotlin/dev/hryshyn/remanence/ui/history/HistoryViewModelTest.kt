package dev.hryshyn.remanence.ui.history

import androidx.lifecycle.ViewModelStore
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionBoundary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

internal const val HISTORY_OWNER = "10000000-0000-4000-8000-000000000001"
internal const val HISTORY_FRIEND = "30000000-0000-4000-8000-000000000001"
internal fun historyItem(id: String = "20000000-0000-4000-8000-000000000001") = OutgoingItem(
    id, 1, null, OutgoingStatus.READY, "2026-10-20T10:00:00Z", "2026-10-20T10:01:00Z",
    null, null, null, "friend_qa", null, false, LocatorStatus.ACTIVE, "AB12Z",
    HistoryCancelAction.REVOKE_V1, "2026-10-21T10:01:00Z",
)
internal class HistoryFakePort : OutgoingHistoryPort {
    var loads = 0
    var recentLoads = 0
    var cancels = 0
    val cursors = mutableListOf<String?>()
    var load: suspend (UserId, String?) -> HistoryResult<OutgoingPage> = { _, _ -> HistoryResult.Loaded(OutgoingPage(listOf(historyItem()), null)) }
    var suggestions: suspend () -> HistoryResult<List<RecentRecipient>> = {
        HistoryResult.Loaded(listOf(RecentRecipient(HISTORY_FRIEND, "friend_qa", "2026-10-20T10:01:00Z")))
    }
    var cancellation: suspend () -> HistoryCancelResult = { HistoryCancelResult.SUCCEEDED }
    override suspend fun outgoing(owner: UserId, cursor: String?): HistoryResult<OutgoingPage> {
        loads++; cursors += cursor; return load(owner, cursor)
    }
    override suspend fun recent(owner: UserId): HistoryResult<List<RecentRecipient>> { recentLoads++; return suggestions() }
    override suspend fun cancel(owner: UserId, item: OutgoingItem): HistoryCancelResult { cancels++; return cancellation() }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private class Fixture(scope: TestScope, val pending: Boolean = true) {
        val port = HistoryFakePort()
        val boundary = SessionBoundary()
        var lease: SessionRequestLease? = SessionRequestLease(UserId.parseRest(HISTORY_OWNER), 1)
        val vm = HistoryViewModel(port, { lease }, { it == lease }, boundary, pending, scope)
        init { vm.bind(HISTORY_OWNER) }
    }

    @Test fun emptyIsDifferentFromUnsupportedAndExplicitRetryLoadsRealPage() = runTest {
        val f = Fixture(this)
        f.port.load = { _, _ -> HistoryResult.Failed(HistoryFailure.UNAVAILABLE) }
        f.vm.refresh(); runCurrent()
        assertFalse(f.vm.state.value.loaded)
        assertEquals(HistoryFailure.UNAVAILABLE, f.vm.state.value.error)
        f.port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(emptyList(), null)) }
        f.vm.refresh(); runCurrent()
        assertTrue(f.vm.state.value.loaded); assertNull(f.vm.state.value.error)
        assertEquals(2, f.port.loads)
    }

    @Test fun repeatedTapsSingleFlightAndPagingDeduplicatesStableIdentity() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<HistoryResult<OutgoingPage>>()
        f.port.load = { _, _ -> gate.await() }
        f.vm.refresh(); f.vm.refresh(); runCurrent()
        assertEquals(1, f.port.loads)
        gate.complete(HistoryResult.Loaded(OutgoingPage(listOf(historyItem()), "cursor")))
        runCurrent()
        f.port.load = { _, cursor ->
            assertEquals("cursor", cursor)
            HistoryResult.Loaded(OutgoingPage(listOf(historyItem(), historyItem("20000000-0000-4000-8000-000000000002")), null))
        }
        f.vm.loadMore(); f.vm.loadMore(); runCurrent()
        assertEquals(2, f.vm.state.value.items.size)
        assertEquals(listOf(null, "cursor"), f.port.cursors)
        f.vm.loadMore(); runCurrent(); assertEquals(2, f.port.loads)
    }

    @Test fun backDuringRefreshCannotBeUndoneByItsLatePage() = runTest {
        val f = Fixture(this)
        f.vm.refresh(); runCurrent()
        f.vm.select(historyItem().key)
        val gate = CompletableDeferred<HistoryResult<OutgoingPage>>()
        f.port.load = { _, _ -> gate.await() }
        f.vm.refresh(); runCurrent()
        f.vm.backToList()
        gate.complete(HistoryResult.Loaded(OutgoingPage(listOf(historyItem()), null)))
        runCurrent()
        assertNull(f.vm.state.value.selectedKey)
        assertEquals(1, f.vm.state.value.items.size)
    }

    @Test fun selectionDuringPagingWinsOverRequestTimeSelection() = runTest {
        val f = Fixture(this)
        val second = historyItem("20000000-0000-4000-8000-000000000002")
        f.port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(listOf(historyItem(), second), "next")) }
        f.vm.refresh(); runCurrent()
        val gate = CompletableDeferred<HistoryResult<OutgoingPage>>()
        f.port.load = { _, _ -> gate.await() }
        f.vm.loadMore(); runCurrent()
        f.vm.select(second.key)
        gate.complete(HistoryResult.Loaded(OutgoingPage(emptyList(), null)))
        runCurrent()
        assertEquals(second.key, f.vm.state.value.selectedKey)
    }

    @Test fun logoutClearsPrivateMetadataAndLateNonCooperativeResultsStayRetired() = runTest {
        val f = Fixture(this)
        val historyGate = CompletableDeferred<HistoryResult<OutgoingPage>>()
        val recentGate = CompletableDeferred<HistoryResult<List<RecentRecipient>>>()
        f.port.load = { _, _ -> withContext(NonCancellable) { historyGate.await() } }
        f.port.suggestions = { withContext(NonCancellable) { recentGate.await() } }
        f.vm.refresh(); f.vm.loadRecent(); runCurrent()
        f.lease = null
        f.boundary.invalidate()
        assertEquals(HistoryUiState(), f.vm.state.value)
        assertEquals(RecentUiState(), f.vm.recent.value)
        historyGate.complete(HistoryResult.Loaded(OutgoingPage(listOf(historyItem()), null)))
        recentGate.complete(HistoryResult.Loaded(listOf(RecentRecipient(HISTORY_FRIEND, "friend_qa", "2026-10-20T10:01:00Z"))))
        runCurrent()
        assertEquals(HistoryUiState(), f.vm.state.value)
        assertEquals(RecentUiState(), f.vm.recent.value)
        assertNull(f.vm.codeForCopy(historyItem().key))
        assertNull(f.vm.recentForLookup(HISTORY_FRIEND))
    }

    @Test fun sameOwnerNewSessionAndDifferentOwnerDoNotReuseOldMetadata() = runTest {
        for (newOwner in listOf(HISTORY_OWNER, HISTORY_FRIEND)) {
            val f = Fixture(this)
            val gate = CompletableDeferred<HistoryResult<OutgoingPage>>()
            f.port.load = { _, _ -> withContext(NonCancellable) { gate.await() } }
            f.vm.refresh(); runCurrent()
            f.lease = SessionRequestLease(UserId.parseRest(newOwner), 2)
            f.vm.bind(newOwner)
            assertTrue(f.vm.state.value.items.isEmpty())
            gate.complete(HistoryResult.Loaded(OutgoingPage(listOf(historyItem()), null)))
            runCurrent()
            assertTrue(f.vm.state.value.items.isEmpty())
            assertEquals(newOwner, f.vm.state.value.owner)
        }
    }

    @Test fun transportSessionLossClearsMetadataBeforeRootAuthStateCatchesUp() = runTest {
        val f = Fixture(this)
        f.port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(listOf(historyItem()), "next")) }
        f.vm.refresh(); f.vm.loadRecent(); runCurrent()
        assertEquals(1, f.vm.state.value.items.size)
        assertEquals(1, f.vm.recent.value.items.size)
        f.port.load = { _, _ ->
            // The authenticated transport retires its lease on rejection,
            // before Root has published SignedOut / invoked its UI boundary.
            f.lease = null
            HistoryResult.Failed(HistoryFailure.SIGN_IN)
        }
        f.vm.loadMore(); runCurrent()
        assertTrue(f.vm.state.value.items.isEmpty())
        assertTrue(f.vm.recent.value.items.isEmpty())
        assertEquals(HistoryFailure.SIGN_IN, f.vm.state.value.error)
        assertFalse(f.vm.state.value.loading)
    }

    @Test fun routeWithoutLiveLeaseShowsSignInInsteadOfBlankOrEmptySuccess() = runTest {
        val f = Fixture(this)
        f.lease = null
        f.vm.bind(HISTORY_OWNER)
        assertEquals(HISTORY_OWNER, f.vm.state.value.owner)
        assertEquals(HistoryFailure.SIGN_IN, f.vm.state.value.error)
        assertFalse(f.vm.state.value.loaded)
        f.vm.refresh(); f.vm.loadRecent(); runCurrent()
        assertEquals(0, f.port.loads); assertEquals(0, f.port.recentLoads)
        f.vm.bind(null)
        assertEquals(HistoryUiState(), f.vm.state.value)
        assertEquals(RecentUiState(), f.vm.recent.value)
    }

    @Test fun explicitAuthRejectionClearsBothSurfacesEvenBeforeLeaseObserverUpdates() = runTest {
        for (surface in listOf("history", "recent", "cancel")) {
            val f = Fixture(this)
            f.vm.refresh(); f.vm.loadRecent(); runCurrent()
            when (surface) {
                "history" -> {
                    f.port.load = { _, _ -> HistoryResult.Failed(HistoryFailure.SIGN_IN) }
                    f.vm.refresh()
                }
                "recent" -> {
                    f.port.suggestions = { HistoryResult.Failed(HistoryFailure.SIGN_IN) }
                    f.vm.loadRecent()
                }
                else -> {
                    f.port.cancellation = { HistoryCancelResult.SIGN_IN }
                    f.vm.requestCancel(historyItem().key); f.vm.confirmCancel()
                }
            }
            runCurrent()
            assertTrue(surface, f.vm.state.value.items.isEmpty())
            assertTrue(surface, f.vm.recent.value.items.isEmpty())
            assertEquals(HistoryFailure.SIGN_IN, f.vm.state.value.error)
            assertNull(f.vm.codeForCopy(historyItem().key))
            assertNull(f.vm.recentForLookup(HISTORY_FRIEND))
        }
    }

    @Test fun cancellationRequiresConfirmationIsSingleFlightAndRefetchesWithoutLocalRewrite() = runTest {
        val f = Fixture(this)
        f.vm.refresh(); runCurrent()
        f.vm.confirmCancel(); runCurrent(); assertEquals(0, f.port.cancels)
        val gate = CompletableDeferred<HistoryCancelResult>()
        f.port.cancellation = { gate.await() }
        f.vm.select(historyItem().key)
        f.vm.requestCancel(historyItem().key)
        f.vm.dismissCancel(); f.vm.confirmCancel(); runCurrent(); assertEquals(0, f.port.cancels)
        f.vm.requestCancel(historyItem().key)
        f.vm.confirmCancel(); f.vm.confirmCancel(); f.vm.refresh(); runCurrent()
        assertEquals(1, f.port.cancels); assertEquals(1, f.port.loads)
        gate.complete(HistoryCancelResult.SUCCEEDED); runCurrent()
        assertEquals(2, f.port.loads)
        assertEquals(HistoryCancelResult.SUCCEEDED, f.vm.state.value.outcome)
        // Deliberately unchanged GET: UI must not manufacture REVOKED.
        assertEquals(OutgoingStatus.READY, f.vm.state.value.items.single().status)
    }

    @Test fun conflictAndUnknownRefetchOnceWithoutAutomaticSecondPost() = runTest {
        for (outcome in listOf(HistoryCancelResult.UNKNOWN, HistoryCancelResult.CONFLICT, HistoryCancelResult.UNAVAILABLE)) {
            val f = Fixture(this)
            f.vm.refresh(); runCurrent()
            f.port.cancellation = { outcome }
            f.port.load = { _, _ -> HistoryResult.Failed(HistoryFailure.NETWORK) }
            f.vm.requestCancel(historyItem().key); f.vm.confirmCancel(); runCurrent()
            assertEquals(outcome, f.vm.state.value.outcome)
            assertEquals(HistoryFailure.NETWORK, f.vm.state.value.error)
            assertTrue(f.vm.state.value.items.isEmpty())
            assertEquals(1, f.port.cancels); assertEquals(2, f.port.loads)
        }
    }

    @Test fun lateCancellationAfterLogoutCannotRefetchForReplacementOwner() = runTest {
        val f = Fixture(this)
        f.vm.refresh(); runCurrent()
        val gate = CompletableDeferred<HistoryCancelResult>()
        f.port.cancellation = { withContext(NonCancellable) { gate.await() } }
        f.vm.requestCancel(historyItem().key); f.vm.confirmCancel(); runCurrent()
        f.boundary.invalidate()
        f.lease = SessionRequestLease(UserId.parseRest(HISTORY_FRIEND), 2)
        f.vm.bind(HISTORY_FRIEND)
        gate.complete(HistoryCancelResult.SUCCEEDED); runCurrent()
        assertEquals(1, f.port.loads)
        assertNull(f.vm.state.value.outcome)
    }

    @Test fun unavailableAndBuildDisabledPendingActionsCannotBeConfirmed() = runTest {
        val f = Fixture(this, pending = false)
        val pending = historyItem().copy(protocolVersion = 2, deliveryId = HISTORY_FRIEND,
            status = OutgoingStatus.PENDING, cancelAction = HistoryCancelAction.CANCEL_PENDING_V2)
        f.port.load = { _, _ -> HistoryResult.Loaded(OutgoingPage(listOf(pending, historyItem().copy(cancelAction = null)), null)) }
        f.vm.refresh(); runCurrent()
        f.vm.state.value.items.forEach {
            assertFalse(f.vm.canCancel(it)); f.vm.requestCancel(it.key); f.vm.confirmCancel()
        }
        runCurrent(); assertEquals(0, f.port.cancels)
    }

    @Test fun recentSelectionReturnsOnlyLiveSuggestionAndNeverConfirmsOrOpensAnything() = runTest {
        val f = Fixture(this)
        f.vm.loadRecent(); f.vm.loadRecent(); runCurrent()
        assertEquals(1, f.port.recentLoads)
        assertEquals("friend_qa", f.vm.recentForLookup(HISTORY_FRIEND)?.handle)
        assertNull(f.vm.recentForLookup(HISTORY_OWNER))
        val store = ViewModelStore()
        store.put("history", f.vm)
        store.clear()
        assertNull(f.vm.recentForLookup(HISTORY_FRIEND))
        assertEquals(RecentUiState(), f.vm.recent.value)
        f.boundary.invalidate() // detached listener is inert
    }
}
