package dev.hryshyn.remanence.ui.settings

import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionBoundary
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SendAccessViewModelTest {
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val other = "10000000-0000-4000-8000-000000000002"
    private fun lease(id: String = owner, incarnation: Long = 1) = SessionRequestLease(UserId.parseRest(id), incarnation)
    private val value = SendAccess(SendAccessMode.ENFORCE, false,
        SendAccessBucket(1, 1), SendAccessBucket(2, 0), true, false)

    @Test fun refreshClearsCountsAndIsSingleFlightMetadataOnly() = runTest {
        var live = lease()
        val answers = java.util.ArrayDeque<CompletableDeferred<SendAccessResult>>()
        var calls = 0
        val vm = SendAccessViewModel(SendAccessPort { calls++; answers.removeFirst().await() },
            { live }, { it == live }, SessionBoundary(), this)
        answers.add(CompletableDeferred(SendAccessResult.Loaded(value)))
        vm.enter(owner); runCurrent()
        assertEquals(value, vm.state.value.value)
        assertEquals(value, vm.visibleFor(owner, live).value)
        assertEquals(SendAccessUiState(), vm.visibleFor(other, live))
        assertEquals(SendAccessUiState(), vm.visibleFor(owner, lease(owner, 2)))
        val second = CompletableDeferred<SendAccessResult>(); answers.add(second)
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.loading); assertNull(vm.state.value.value)
        vm.refresh(); vm.enter(owner); runCurrent()
        assertEquals(2, calls)
        second.complete(SendAccessResult.Failed(SendAccessFailure.UNAVAILABLE)); runCurrent()
        assertNull(vm.state.value.value); assertEquals(SendAccessFailure.UNAVAILABLE, vm.state.value.error)
        answers.add(CompletableDeferred(SendAccessResult.Hidden))
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.hidden); assertNull(vm.state.value.value)
        assertEquals(3, calls)
        vm.leave()
    }

    @Test fun exitAndSynchronousBoundaryClearMetadataWithoutRequest() = runTest {
        var calls = 0
        val live = lease()
        val boundary = SessionBoundary()
        val vm = SendAccessViewModel(SendAccessPort { calls++; SendAccessResult.Loaded(value) },
            { live }, { it == live }, boundary, this)
        vm.enter(owner); runCurrent()
        assertNotNull(vm.state.value.value)
        boundary.invalidate()
        assertEquals(SendAccessUiState(), vm.state.value)
        vm.refresh(); runCurrent(); assertEquals(1, calls)
        vm.enter(owner); runCurrent(); assertEquals(2, calls)
        vm.leave(); assertEquals(SendAccessUiState(), vm.state.value)
    }

    @Test fun oldNonCooperativeResponseCannotOverwriteNewOwnerOrSameOwnerSession() = runTest {
        for (next in listOf(lease(owner, 2), lease(other, 2))) {
            var live = lease()
            val boundary = SessionBoundary()
            val released = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var oldJob: Job? = null
            var calls = 0
            val vm = SendAccessViewModel(SendAccessPort {
                calls++
                if (calls == 1) {
                    oldJob = currentCoroutineContext().job
                    withContext(NonCancellable) { entered.complete(Unit); released.await() }
                    SendAccessResult.Loaded(value)
                } else SendAccessResult.Hidden
            }, { live }, { it == live }, boundary, this)
            vm.enter(owner); runCurrent(); assertTrue(entered.isCompleted)
            live = next; boundary.invalidate(); vm.enter(next.ownerUserId.toRestString()); runCurrent()
            assertTrue(vm.state.value.hidden); assertEquals(next.ownerUserId.toRestString(), vm.state.value.owner)
            released.complete(Unit); runCurrent(); oldJob!!.join()
            assertTrue(vm.state.value.hidden); assertNull(vm.state.value.value)
            assertEquals(2, calls); vm.leave()
        }
    }

    @Test fun lateResponseAfterExitNeverResurrectsSection() = runTest {
        val live = lease()
        val release = CompletableDeferred<Unit>()
        var request: Job? = null
        val vm = SendAccessViewModel(SendAccessPort {
            request = currentCoroutineContext().job
            withContext(NonCancellable) { release.await() }
            SendAccessResult.Loaded(value)
        }, { live }, { it == live }, SessionBoundary(), this)
        vm.enter(owner); runCurrent(); vm.leave()
        release.complete(Unit); runCurrent(); request!!.join()
        assertEquals(SendAccessUiState(), vm.state.value)
    }

    @Test fun expiredLeaseAndAuthResponseNeverExposePreviousBalanceOrRetryRead() = runTest {
        var live: SessionRequestLease? = lease()
        var answer: SendAccessResult = SendAccessResult.Loaded(value)
        var calls = 0
        val vm = SendAccessViewModel(SendAccessPort { calls++; answer }, { live }, { it == live },
            SessionBoundary(), this)
        vm.enter(owner); runCurrent()
        live = null
        assertEquals(SendAccessUiState(), vm.visibleFor(owner, live))
        vm.refresh(); runCurrent()
        assertNull(vm.state.value.value); assertEquals(SendAccessFailure.SIGN_IN, vm.state.value.error)
        vm.refresh(); assertEquals(1, calls)
        live = lease(owner, 2); answer = SendAccessResult.Failed(SendAccessFailure.SIGN_IN)
        vm.enter(owner); runCurrent()
        assertEquals(SendAccessFailure.SIGN_IN, vm.state.value.error)
        vm.refresh(); runCurrent(); assertEquals(2, calls)
        vm.enter(null); assertEquals(SendAccessUiState(), vm.state.value)
    }

    @Test fun mismatchedOwnerNeverReadsAndThrownErrorIsUnavailable() = runTest {
        val live = lease()
        var calls = 0
        val vm = SendAccessViewModel(SendAccessPort { calls++; error("private error must not be shown") },
            { live }, { it == live }, SessionBoundary(), this)
        vm.enter(other); runCurrent()
        assertEquals(0, calls); assertEquals(SendAccessFailure.SIGN_IN, vm.state.value.error)
        vm.enter(owner); runCurrent()
        assertEquals(1, calls); assertEquals(SendAccessFailure.UNAVAILABLE, vm.state.value.error)
        assertNull(vm.state.value.value); vm.leave()
    }
}
