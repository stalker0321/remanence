package dev.hryshyn.remanence.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.sync.IncomingSyncSchedulingOutcome
import dev.hryshyn.remanence.sync.ExistingIncomingWorkState
import dev.hryshyn.remanence.sync.IncomingAcceptanceDiagnostics

/**
 * Auth route-guard wiring proof (FIX-M1-007-05/08): the root is a lifecycle
 * ViewModel on [viewModelScope] and publishes ONLY terminal async auth
 * outcomes; it never leaves Home without a proven session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Bootstrap with no stored token at all: always resolves SignedOut. */
    private class SignedOutResolver : SessionStateResolver {
        override suspend fun bootstrap(): SessionState = SessionState.SignedOut

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    private class FixedOutcomeResolver(private val state: SessionState) : SessionStateResolver {
        var resolveCount: Int = 0
            private set

        override suspend fun bootstrap(): SessionState {
            resolveCount++
            return state
        }

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    private class MutableOutcomeResolver(var state: SessionState) : SessionStateResolver {
        override suspend fun bootstrap(): SessionState = state

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    private class GatedActiveResolver(
        private val owner: UserId,
    ) : SessionStateResolver {
        val firstEntered = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var calls = 0
            private set
        var maxConcurrent = 0
            private set
        private var concurrent = 0

        override suspend fun bootstrap(): SessionState {
            val call = ++calls
            concurrent += 1
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                when (call) {
                    1 -> {
                        firstEntered.complete(Unit)
                        firstGate.await()
                    }
                    2 -> {
                        secondEntered.complete(Unit)
                        secondGate.await()
                    }
                    else -> error("unexpected refresh")
                }
                return SessionState.Active(owner.toRestString(), "mykola", true, true)
            } finally {
                concurrent -= 1
            }
        }

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    @Test
    fun coldStartWithoutSessionLandsOnAuthenticationSurface() = runTest {
        val vm = RootViewModel(SignedOutResolver())

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun missingKeysOnColdStartSurfacesRecoveryRequired() = runTest {
        val vm = RootViewModel(FixedOutcomeResolver(SessionState.RecoveryRequired))

        assertEquals(AuthUiState.RecoveryRequired, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun unreachableColdStartSurfacesConnectivityInsteadOfAuthenticatedHome() = runTest {
        val vm = RootViewModel(FixedOutcomeResolver(SessionState.RequiresConnectivity))

        assertEquals(AuthUiState.RequiresConnectivity, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun establishedSessionReachesHomeAsAuthenticatedOnlyAfterTerminalResult() = runTest {
        val resolver = FixedOutcomeResolver(SessionState.Active("user-1", "mykola", true, true))
        val vm = RootViewModel(resolver)

        vm.onSessionEstablished()

        assertEquals(AuthUiState.Authenticated(userId = "user-1", handle = "mykola"), vm.authState.value)
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(2, resolver.resolveCount) // cold start + terminal success only
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun activeStatePublishesAuthenticatedHomeBeforeResumeHookAndEachResolveHandsOffOnce() = runTest {
        val owner = "0198f0a0-0000-7000-8000-00000000b501"
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val observed = mutableListOf<Pair<AuthUiState, AppDestination>>()
        val handedOff = mutableListOf<UserId>()
        lateinit var vm: RootViewModel
        vm = RootViewModel(
            resolver,
            resumeCapsuleUploads = { activeOwner ->
                observed += vm.authState.value to vm.destination.value
                handedOff += activeOwner
            },
        )

        resolver.state = SessionState.Active(owner, "mykola", true, true)
        vm.resolveNow()
        vm.resolveNow()

        assertEquals(
            listOf(
                AuthUiState.Authenticated(owner, "mykola") to AppDestination.Home,
                AuthUiState.Authenticated(owner, "mykola") to AppDestination.Home,
            ),
            observed,
        )
        assertEquals(listOf(UserId.parseRest(owner), UserId.parseRest(owner)), handedOff)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun foregroundResolutionRepeatsAuthenticatedSchedulingAfterColdStart() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b507")
        val scheduled = mutableListOf<UserId>()
        val vm = RootViewModel(
            MutableOutcomeResolver(SessionState.Active(owner.toRestString(), "mykola", true, true)),
            scheduleIncomingSync = {
                scheduled += it
                IncomingSyncSchedulingOutcome.Queued
            },
        )

        advanceUntilIdle()
        assertEquals(listOf(owner), scheduled)

        vm.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(listOf(owner, owner), scheduled)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun refreshSignalsSerializeCoalesceAndRunOneTrailingResolution() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b510")
        val resolver = GatedActiveResolver(owner)
        val scheduled = mutableListOf<UserId>()
        val vm = RootViewModel(
            resolver,
            scheduleIncomingSync = {
                scheduled += it
                IncomingSyncSchedulingOutcome.Queued
            },
        )

        advanceUntilIdle()
        assertTrue(resolver.firstEntered.isCompleted)
        assertEquals(1, resolver.calls)

        vm.onAppForegrounded()
        vm.onSessionEstablished()
        vm.onAppForegrounded()
        advanceUntilIdle()
        assertEquals(1, resolver.calls)

        resolver.firstGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(resolver.secondEntered.isCompleted)
        assertEquals(2, resolver.calls)
        assertEquals(1, resolver.maxConcurrent)
        assertEquals(AuthUiState.SignedOut, vm.authState.value)

        resolver.secondGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, resolver.calls)
        assertEquals(1, resolver.maxConcurrent)
        assertEquals(AuthUiState.Authenticated(owner.toRestString(), "mykola"), vm.authState.value)
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(listOf(owner), scheduled)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun freshRootReconstructionResolvesPersistedActiveSessionAgain() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b508")
        val scheduled = mutableListOf<UserId>()

        repeat(2) {
            val vm = RootViewModel(
                FixedOutcomeResolver(SessionState.Active(owner.toRestString(), "mykola", true, true)),
                scheduleIncomingSync = {
                    scheduled += it
                    IncomingSyncSchedulingOutcome.Queued
                },
            )
            advanceUntilIdle()
            assertEquals(AuthUiState.Authenticated(owner.toRestString(), "mykola"), vm.authState.value)
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        assertEquals(listOf(owner, owner), scheduled)
    }

    @Test
    fun authenticatedResolutionResumesUploadsBeforeSchedulingIncomingSync() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b505")
        val order = mutableListOf<String>()
        val vm = RootViewModel(
            MutableOutcomeResolver(SessionState.Active(owner.toRestString(), "mykola", true, true)),
            resumeCapsuleUploads = { activeOwner ->
                assertEquals(owner, activeOwner)
                order += "uploads"
            },
            scheduleIncomingSync = { activeOwner ->
                assertEquals(owner, activeOwner)
                order += "incoming"
                IncomingSyncSchedulingOutcome.Queued
            },
        )

        assertEquals(listOf("uploads", "incoming"), order)
        assertEquals(AuthUiState.Authenticated(owner.toRestString(), "mykola"), vm.authState.value)
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(IncomingSyncSchedulingState.Queued, vm.incomingSyncScheduling.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun foregroundSchedulingFailureKeepsAuthenticatedHomeAvailable() = runTest {
        val resolver = MutableOutcomeResolver(
            SessionState.Active("0198f0a0-0000-7000-8000-00000000b506", "mykola", true, true),
        )
        val vm = RootViewModel(
            resolver,
            scheduleIncomingSync = { error("WorkManager unavailable") },
        )

        advanceUntilIdle()
        assertEquals(
            AuthUiState.Authenticated(
                "0198f0a0-0000-7000-8000-00000000b506",
                "mykola",
            ),
            vm.authState.value,
        )
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(
            IncomingSyncSchedulingState.EnqueueFailed,
            vm.incomingSyncScheduling.value,
        )

        resolver.state = SessionState.Active(
            "0198f0a0-0000-7000-8000-00000000b506",
            "mykola",
            true,
            true,
        )
        vm.onAppForegrounded()
        advanceUntilIdle()
        assertEquals(
            AuthUiState.Authenticated(
                "0198f0a0-0000-7000-8000-00000000b506",
                "mykola",
            ),
            vm.authState.value,
        )
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(
            IncomingSyncSchedulingState.EnqueueFailed,
            vm.incomingSyncScheduling.value,
        )
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun foregroundMapsTypedIncomingSchedulingOutcomeWithoutAssumingQueued() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b521")
        val cases = listOf(
            IncomingSyncSchedulingOutcome.Queued to IncomingSyncSchedulingState.Queued,
            IncomingSyncSchedulingOutcome.AlreadyWaiting(
                ExistingIncomingWorkState.BLOCKED,
            ) to IncomingSyncSchedulingState.AlreadyWaiting,
            IncomingSyncSchedulingOutcome.SessionOwnerRejected to
                IncomingSyncSchedulingState.SessionOwnerRejected,
            IncomingSyncSchedulingOutcome.EnqueueFailed to IncomingSyncSchedulingState.EnqueueFailed,
        )

        cases.forEach { (outcome, expected) ->
            val vm = RootViewModel(
                FixedOutcomeResolver(SessionState.Active(owner.toRestString(), "mykola", true, true)),
                scheduleIncomingSync = { outcome },
            )
            advanceUntilIdle()
            assertEquals(expected, vm.incomingSyncScheduling.value)
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun staleSchedulingFailureCannotOverwriteTrailingTerminalResolution() = runTest {
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val scheduleEntered = CompletableDeferred<Unit>()
        val failSchedule = CompletableDeferred<Unit>()
        lateinit var vm: RootViewModel
        vm = RootViewModel(
            resolver,
            scheduleIncomingSync = {
                if (scheduleEntered.complete(Unit)) {
                    resolver.state = SessionState.SignedOut
                    // Request generation N+1 while generation N is still in
                    // its scheduling callback. The coordinator runs the
                    // trailing terminal resolution after N fails.
                    vm.onAppForegrounded()
                    failSchedule.await()
                    error("scheduled work rejected")
                }
                IncomingSyncSchedulingOutcome.Queued
            },
        )
        advanceUntilIdle()

        val states = mutableListOf<AuthUiState>()
        val observer = backgroundScope.launch {
            vm.authState.collect { states += it }
        }
        resolver.state = SessionState.Active(
            "0198f0a0-0000-7000-8000-00000000b520",
            "mykola",
            true,
            true,
        )
        vm.onAppForegrounded()
        advanceUntilIdle()
        assertTrue(scheduleEntered.isCompleted)

        failSchedule.complete(Unit)
        advanceUntilIdle()

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertFalse(states.contains(AuthUiState.RequiresConnectivity))
        assertEquals(IncomingSyncSchedulingState.NotAttempted, vm.incomingSyncScheduling.value)
        observer.cancel()
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun staleSchedulerSuccessCannotPublishSchedulingDiagnosticAfterNewSession() = runTest {
        IncomingAcceptanceDiagnostics.reset()
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val scheduleEntered = CompletableDeferred<Unit>()
        val releaseSchedule = CompletableDeferred<Unit>()
        lateinit var vm: RootViewModel
        vm = RootViewModel(
            resolver,
            scheduleIncomingSync = {
                scheduleEntered.complete(Unit)
                releaseSchedule.await()
                IncomingSyncSchedulingOutcome.Queued
            },
        )
        advanceUntilIdle()

        resolver.state = SessionState.Active(
            "0198f0a0-0000-7000-8000-00000000b522",
            "mykola",
            true,
            true,
        )
        vm.onAppForegrounded()
        advanceUntilIdle()
        assertTrue(scheduleEntered.isCompleted)

        resolver.state = SessionState.SignedOut
        vm.onAppForegrounded()
        releaseSchedule.complete(Unit)
        advanceUntilIdle()

        assertEquals(IncomingSyncSchedulingState.NotAttempted, vm.incomingSyncScheduling.value)
        assertEquals("not run", IncomingAcceptanceDiagnostics.schedulingState.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        IncomingAcceptanceDiagnostics.reset()
    }

    @Test
    fun logoutImmediatelyFencesInFlightSchedulerPublication() = runTest {
        IncomingAcceptanceDiagnostics.reset()
        val owner = "0198f0a0-0000-7000-8000-00000000b523"
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val scheduleEntered = CompletableDeferred<Unit>()
        val releaseSchedule = CompletableDeferred<Unit>()
        val vm = RootViewModel(
            resolver,
            scheduleIncomingSync = {
                scheduleEntered.complete(Unit)
                releaseSchedule.await()
                IncomingSyncSchedulingOutcome.Queued
            },
        )
        advanceUntilIdle()

        resolver.state = SessionState.Active(owner, "mykola", true, true)
        vm.onAppForegrounded()
        advanceUntilIdle()
        assertTrue(scheduleEntered.isCompleted)

        resolver.state = SessionState.SignedOut
        vm.logout()
        // The in-flight callback resumes only after the logout fence has
        // already invalidated its generation.
        releaseSchedule.complete(Unit)
        advanceUntilIdle()

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(IncomingSyncSchedulingState.NotAttempted, vm.incomingSyncScheduling.value)
        assertEquals("not run", IncomingAcceptanceDiagnostics.schedulingState.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        IncomingAcceptanceDiagnostics.reset()
    }

    @Test
    fun logoutBoundaryDoesNotAllowOldReporterEmissionAfterFenceBegins() = runTest {
        IncomingAcceptanceDiagnostics.reset()
        val owner = "0198f0a0-0000-7000-8000-00000000b523"
        val resolver = MutableOutcomeResolver(
            SessionState.Active(owner, "mykola", true, true),
        )
        val eligibility = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val logoutStarted = CountDownLatch(1)
        var boundaryStarted = false
        var oldReporterCurrent = true
        val oldReporter = IncomingAcceptanceDiagnostics.schedulingReporter(
            isCurrent = { oldReporterCurrent },
            beforePublishForTests = {
                eligibility.countDown()
                releasePublication.await()
            },
        )
        val vm = RootViewModel(
            resolver,
            invalidateSessionBoundary = {
                boundaryStarted = true
                oldReporterCurrent = false
            },
        )
        advanceUntilIdle()
        assertEquals(AuthUiState.Authenticated(owner, "mykola"), vm.authState.value)

        val oldEmission = async(Dispatchers.Default) {
            oldReporter.report("old A")
        }
        assertTrue(eligibility.await(5, TimeUnit.SECONDS))

        resolver.state = SessionState.SignedOut
        val logout = async(Dispatchers.Default) {
            logoutStarted.countDown()
            vm.logout()
        }
        assertTrue(logoutStarted.await(5, TimeUnit.SECONDS))
        // The reporter owns the publication fence, so invalidation cannot
        // begin midway through its eligibility-to-publication window.
        assertFalse(boundaryStarted)

        releasePublication.countDown()
        oldEmission.await()
        logout.await()
        advanceUntilIdle()
        assertTrue(boundaryStarted)

        // A late callback from the old owner/session is rejected after the
        // boundary and cannot recreate an A diagnostic.
        oldReporter.report("late old A")
        assertEquals("not run", IncomingAcceptanceDiagnostics.schedulingState.value)
        oldReporter.close()
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        IncomingAcceptanceDiagnostics.reset()
    }

    @Test
    fun pendingLogoutDropsSameOwnerRefreshUntilFreshSessionEstablished() = runTest {
        IncomingAcceptanceDiagnostics.reset()
        val owner = "0198f0a0-0000-7000-8000-00000000b526"
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val logoutEntered = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        val scheduleEntered = CompletableDeferred<Unit>()
        val releaseSchedule = CompletableDeferred<Unit>()
        val publishedStates = mutableListOf<AuthUiState>()
        var scheduleCalls = 0
        var boundaryInvalidations = 0
        val vm = RootViewModel(
            resolver,
            logoutAction = {
                logoutEntered.complete(Unit)
                releaseLogout.await()
            },
            invalidateSessionBoundary = { boundaryInvalidations += 1 },
            scheduleIncomingSync = {
                scheduleCalls += 1
                if (scheduleCalls == 1) {
                    scheduleEntered.complete(Unit)
                    releaseSchedule.await()
                }
                IncomingSyncSchedulingOutcome.Queued
            },
        )
        val observer = launch {
            vm.authState.collect { publishedStates += it }
        }
        advanceUntilIdle()

        resolver.state = SessionState.Active(owner, "mykola", true, true)
        vm.onSessionEstablished()
        advanceUntilIdle()
        assertTrue(scheduleEntered.isCompleted)
        assertEquals(AuthUiState.Authenticated(owner, "mykola"), vm.authState.value)
        val statesBeforeLogout = publishedStates.size

        vm.logout()
        assertTrue(logoutEntered.isCompleted)
        assertEquals(1, boundaryInvalidations)

        // A same-owner foreground/bootstrap result is intentionally dropped
        // while logoutAction is held. It cannot publish A, schedule again, or
        // create another boundary/diagnostic epoch.
        resolver.state = SessionState.Active(owner, "mykola", true, true)
        vm.onAppForegrounded()
        vm.resolveNow()
        advanceUntilIdle()
        assertEquals(statesBeforeLogout, publishedStates.size)
        assertEquals(1, scheduleCalls)
        assertEquals(1, boundaryInvalidations)
        assertEquals("not run", IncomingAcceptanceDiagnostics.schedulingState.value)

        // The pre-logout scheduler resumes only after the boundary and its
        // report remains stale; it cannot recreate the old diagnostic.
        releaseSchedule.complete(Unit)
        advanceUntilIdle()
        assertEquals("not run", IncomingAcceptanceDiagnostics.schedulingState.value)

        resolver.state = SessionState.SignedOut
        releaseLogout.complete(Unit)
        advanceUntilIdle()
        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(1, boundaryInvalidations)
        assertEquals("not run", IncomingAcceptanceDiagnostics.schedulingState.value)

        // Only a separate post-completion session-established callback may
        // reopen the owner and schedule once.
        resolver.state = SessionState.Active(owner, "mykola", true, true)
        vm.onSessionEstablished()
        advanceUntilIdle()
        assertEquals(AuthUiState.Authenticated(owner, "mykola"), vm.authState.value)
        assertEquals(2, scheduleCalls)
        assertEquals(1, boundaryInvalidations)
        assertEquals("queued", IncomingAcceptanceDiagnostics.schedulingState.value)

        observer.cancel()
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        IncomingAcceptanceDiagnostics.reset()
    }

    @Test
    fun pendingLogoutFencesCreateAndScanEntryBeforeTerminalSignedOut() = runTest {
        val owner = "0198f0a0-0000-7000-8000-00000000b527"
        val releaseLogout = CompletableDeferred<Unit>()
        val logoutEntered = CompletableDeferred<Unit>()
        var scheduleCalls = 0
        val vm = RootViewModel(
            MutableOutcomeResolver(SessionState.Active(owner, "mykola", true, true)),
            logoutAction = {
                logoutEntered.complete(Unit)
                releaseLogout.await()
            },
            scheduleIncomingSync = {
                scheduleCalls += 1
                IncomingSyncSchedulingOutcome.Queued
            },
        )
        advanceUntilIdle()

        assertEquals(AuthUiState.Authenticated(owner, "mykola"), vm.authState.value)
        assertEquals(AppDestination.Home, vm.destination.value)
        val createEpochBeforeLogout = vm.createSessionEpoch.value
        val scanEpochBeforeLogout = vm.scanSessionEpoch.value
        val schedulesBeforeLogout = scheduleCalls

        vm.logout()
        assertTrue(logoutEntered.isCompleted)

        // The auth state remains A until the ordered teardown completes, but
        // Root admission is already closed. Neither tap can navigate or mint
        // a new flow epoch while logoutAction is suspended.
        vm.openCreate()
        vm.openScan()
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(createEpochBeforeLogout, vm.createSessionEpoch.value)
        assertEquals(scanEpochBeforeLogout, vm.scanSessionEpoch.value)
        assertEquals(schedulesBeforeLogout, scheduleCalls)

        releaseLogout.complete(Unit)
        advanceUntilIdle()
        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun logoutRetiresSeededTrailingRefreshAndFreshSessionSchedulesOnce() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b528")
        val resolver = GatedActiveResolver(owner)
        val logoutEntered = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        var scheduleCalls = 0
        val vm = RootViewModel(
            resolver,
            logoutAction = {
                logoutEntered.complete(Unit)
                releaseLogout.await()
            },
            scheduleIncomingSync = {
                scheduleCalls += 1
                IncomingSyncSchedulingOutcome.Queued
            },
        )
        assertTrue(resolver.firstEntered.isCompleted)

        // Seed the one trailing request while the first resolver is held.
        vm.onAppForegrounded()
        vm.logout()
        assertTrue(logoutEntered.isCompleted)

        // Logout retires the pending request before the old resolver returns;
        // this release must not cause a second bootstrap or schedule.
        resolver.firstGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, resolver.calls)
        assertEquals(0, scheduleCalls)

        releaseLogout.complete(Unit)
        advanceUntilIdle()
        assertEquals(AuthUiState.SignedOut, vm.authState.value)

        // Only a distinct post-completion session-established signal may
        // reopen the resolver and schedule the fresh A session.
        vm.onSessionEstablished()
        assertTrue(resolver.secondEntered.isCompleted)
        assertEquals(2, resolver.calls)
        resolver.secondGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AuthUiState.Authenticated(owner.toRestString(), "mykola"), vm.authState.value)
        assertEquals(1, scheduleCalls)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun authenticatedOwnerBoundaryResetsSchedulingBeforeOnlyNewOwnerOutcome() = runTest {
        IncomingAcceptanceDiagnostics.reset()
        val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b524")
        val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b525")
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val aEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        var boundaryInvalidations = 0
        val vm = RootViewModel(
            resolver,
            invalidateSessionBoundary = { boundaryInvalidations += 1 },
            scheduleIncomingSync = { owner ->
                when (owner) {
                    ownerA -> {
                        aEntered.complete(Unit)
                        releaseA.await()
                        IncomingSyncSchedulingOutcome.Queued
                    }
                    ownerB -> {
                        bEntered.complete(Unit)
                        releaseB.await()
                        IncomingSyncSchedulingOutcome.AlreadyWaiting(
                            ExistingIncomingWorkState.BLOCKED,
                        )
                    }
                    else -> error("unexpected owner")
                }
            },
        )
        advanceUntilIdle()

        resolver.state = SessionState.Active(ownerA.toRestString(), "a", true, true)
        vm.onAppForegrounded()
        advanceUntilIdle()
        assertTrue(aEntered.isCompleted)

        // A is still inside its scheduler. A trailing authenticated B refresh
        // invalidates A's generation; its state is reset only when B is
        // atomically published, before B's scheduler is allowed to report.
        resolver.state = SessionState.Active(ownerB.toRestString(), "b", true, true)
        vm.onAppForegrounded()
        advanceUntilIdle()
        releaseA.complete(Unit)
        advanceUntilIdle()

        assertTrue(bEntered.isCompleted)
        assertEquals(1, boundaryInvalidations)
        assertEquals(
            IncomingSyncSchedulingState.NotAttempted,
            vm.incomingSyncScheduling.value,
        )
        assertEquals("not run", IncomingAcceptanceDiagnostics.schedulingState.value)

        releaseB.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            IncomingSyncSchedulingState.AlreadyWaiting,
            vm.incomingSyncScheduling.value,
        )
        assertEquals(
            "KEEP accepted (observed blocked)",
            IncomingAcceptanceDiagnostics.schedulingState.value,
        )
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        IncomingAcceptanceDiagnostics.reset()
    }

    @Test
    fun offlineActiveReachesHomeWithoutSchedulingNetworkWork() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b530")
        var resumeCalls = 0
        var incomingCalls = 0
        val vm = RootViewModel(
            FixedOutcomeResolver(
                SessionState.OfflineActive(
                    userId = owner.toRestString(),
                    handle = "mykola",
                    hasEncryptionKeyset = true,
                    hasSigningKeyset = true,
                    activeKeyBundleId = "00000000-0000-4000-8000-000000000001",
                ),
            ),
            resumeCapsuleUploads = { resumeCalls++ },
            scheduleIncomingSync = {
                incomingCalls++
                IncomingSyncSchedulingOutcome.Queued
            },
        )

        assertEquals(
            AuthUiState.Authenticated(
                userId = owner.toRestString(),
                handle = "mykola",
                activeKeyBundleId = "00000000-0000-4000-8000-000000000001",
            ),
            vm.authState.value,
        )
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(0, resumeCalls)
        assertEquals(0, incomingCalls)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun logoutInvalidatesInFlightRefreshesBeforeTeardown() = runTest {
        var invalidated = 0
        val vm = RootViewModel(
            SignedOutResolver(),
            invalidateSessionRefreshes = { invalidated++ },
        )

        vm.logout()
        advanceUntilIdle()

        assertEquals(1, invalidated)
        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun nonActiveStatesNeverInvokeResumeHook() = runTest {
        val states = listOf(
            SessionState.SignedOut,
            SessionState.RecoveryRequired,
            SessionState.RequiresConnectivity,
            SessionState.OfflineActive("user-1", "mykola", true, true),
        )
        var hookCalls = 0
        var incomingHookCalls = 0

        states.forEach { state ->
            val vm = RootViewModel(
                MutableOutcomeResolver(state),
                resumeCapsuleUploads = { hookCalls++ },
                scheduleIncomingSync = {
                    incomingHookCalls++
                    IncomingSyncSchedulingOutcome.Queued
                },
            )
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        assertEquals(0, hookCalls)
        assertEquals(0, incomingHookCalls)
    }

    @Test
    fun foregroundNonActiveStatesNeverScheduleIncoming() = runTest {
        val states = listOf(
            SessionState.SignedOut,
            SessionState.RecoveryRequired,
            SessionState.RequiresConnectivity,
            SessionState.OfflineActive("user-1", "mykola", true, true),
        )
        var incomingHookCalls = 0

        states.forEach { state ->
            val vm = RootViewModel(
                MutableOutcomeResolver(state),
                scheduleIncomingSync = {
                    incomingHookCalls++
                    IncomingSyncSchedulingOutcome.Queued
                },
            )
            vm.onAppForegrounded()
            advanceUntilIdle()
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        assertEquals(0, incomingHookCalls)
    }

    @Test
    fun foregroundCancellationCancelsRefreshChildNormally() = runTest {
        val callbackCancelled = CompletableDeferred<Unit>()
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val vm = RootViewModel(
            resolver,
            scheduleIncomingSync = {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { callbackCancelled.complete(Unit) }
                }
            },
        )

        resolver.state = SessionState.Active(
            "0198f0a0-0000-7000-8000-00000000b509",
            "mykola",
            true,
            true,
        )
        vm.onAppForegrounded()
        advanceUntilIdle()
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertTrue(callbackCancelled.isCompleted)
    }

    @Test
    fun nullBlankAndMalformedActiveIdsPublishSafelyWithoutResume() = runTest {
        val invalidIds = listOf(null, "", "  ", "not-a-canonical-user-id")
        var hookCalls = 0
        var incomingHookCalls = 0

        invalidIds.forEach { rawUserId ->
            val vm = RootViewModel(
                MutableOutcomeResolver(SessionState.Active(rawUserId, "mykola", true, true)),
                resumeCapsuleUploads = { hookCalls++ },
            scheduleIncomingSync = {
                incomingHookCalls++
                IncomingSyncSchedulingOutcome.Queued
            },
            )
            assertEquals(AuthUiState.Authenticated(rawUserId ?: "", "mykola"), vm.authState.value)
            assertEquals(AppDestination.Home, vm.destination.value)
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        assertEquals(0, hookCalls)
        assertEquals(0, incomingHookCalls)
    }

    @Test
    fun resumeOperationalFailureDoesNotDowngradeAuthenticatedHome() = runTest {
        val vm = RootViewModel(
            MutableOutcomeResolver(
                SessionState.Active("0198f0a0-0000-7000-8000-00000000b502", "mykola", true, true),
            ),
            resumeCapsuleUploads = { error("discovery unavailable") },
        )

        assertEquals(
            AuthUiState.Authenticated("0198f0a0-0000-7000-8000-00000000b502", "mykola"),
            vm.authState.value,
        )
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun resumeCancellationPropagatesFromExplicitResolveWithoutDowngradingState() = runTest {
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val vm = RootViewModel(
            resolver,
            resumeCapsuleUploads = { throw CancellationException("cancelled") },
        )
        resolver.state = SessionState.Active(
            "0198f0a0-0000-7000-8000-00000000b503",
            "mykola",
            true,
            true,
        )

        var propagated = false
        try {
            vm.resolveNow()
        } catch (_: CancellationException) {
            propagated = true
        }

        assertEquals(true, propagated)
        assertEquals(
            AuthUiState.Authenticated("0198f0a0-0000-7000-8000-00000000b503", "mykola"),
            vm.authState.value,
        )
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun sessionEstablishedResolutionUsesTheSameResumeHook() = runTest {
        val owner = "0198f0a0-0000-7000-8000-00000000b504"
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val handedOff = mutableListOf<UserId>()
        val vm = RootViewModel(
            resolver,
            resumeCapsuleUploads = { handedOff += it },
        )

        resolver.state = SessionState.Active(owner, "mykola", true, true)
        vm.onSessionEstablished()
        advanceUntilIdle()
        vm.onSessionEstablished()
        advanceUntilIdle()

        assertEquals(listOf(UserId.parseRest(owner), UserId.parseRest(owner)), handedOff)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun logoutReturnsToAuthenticationEvenIfTokenClearingFailsSilently() = runTest {
        val vm = RootViewModel(SignedOutResolver()) // nothing persisted; logout is still safe

        vm.logout()

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun recoverCreateStagingRunsBeforeAuthenticatedPublicationAndResume() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c601")
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        val order = mutableListOf<String>()
        val recovered = mutableListOf<UserId>()
        lateinit var vm: RootViewModel
        vm = RootViewModel(
            resolver,
            recoverCreateStaging = { activeOwner ->
                recovered += activeOwner
                order += "recover"
                assertEquals(AuthUiState.SignedOut, vm.authState.value)
            },
            resumeCapsuleUploads = {
                order += "resume"
                assertEquals(
                    AuthUiState.Authenticated(owner.toRestString(), "mykola"),
                    vm.authState.value,
                )
            },
        )

        resolver.state = SessionState.Active(owner.toRestString(), "mykola", true, true)
        vm.resolveNow()

        assertEquals(listOf("recover", "resume"), order)
        assertEquals(listOf(owner), recovered)
        assertEquals(AuthUiState.Authenticated(owner.toRestString(), "mykola"), vm.authState.value)
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun recoverCreateStagingFailurePublishesConnectivityWithoutExposingTheAccount() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c602")
        val resolver = MutableOutcomeResolver(SessionState.SignedOut)
        var resumeCalls = 0
        val vm = RootViewModel(
            resolver,
            recoverCreateStaging = { error("create staging sweep failed") },
            resumeCapsuleUploads = { resumeCalls++ },
            scheduleIncomingSync = {
                resumeCalls++
                IncomingSyncSchedulingOutcome.Queued
            },
        )

        resolver.state = SessionState.Active(owner.toRestString(), "mykola", true, true)
        vm.resolveNow()

        assertEquals(AuthUiState.RequiresConnectivity, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        assertEquals(0, resumeCalls)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun nonActiveStatesNeverRecoverCreateStaging() = runTest {
        val states = listOf(
            SessionState.SignedOut,
            SessionState.RecoveryRequired,
            SessionState.RequiresConnectivity,
        )
        var recoverCalls = 0
        states.forEach { state ->
            val vm = RootViewModel(
                MutableOutcomeResolver(state),
                recoverCreateStaging = { recoverCalls++ },
            )
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        assertEquals(0, recoverCalls)
    }
}
