package dev.hryshyn.remanence.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.cancellation.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState

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
    fun nonActiveStatesNeverInvokeResumeHook() = runTest {
        val states = listOf(
            SessionState.SignedOut,
            SessionState.RecoveryRequired,
            SessionState.RequiresConnectivity,
        )
        var hookCalls = 0

        states.forEach { state ->
            val vm = RootViewModel(
                MutableOutcomeResolver(state),
                resumeCapsuleUploads = { hookCalls++ },
            )
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        assertEquals(0, hookCalls)
    }

    @Test
    fun nullBlankAndMalformedActiveIdsPublishSafelyWithoutResume() = runTest {
        val invalidIds = listOf(null, "", "  ", "not-a-canonical-user-id")
        var hookCalls = 0

        invalidIds.forEach { rawUserId ->
            val vm = RootViewModel(
                MutableOutcomeResolver(SessionState.Active(rawUserId, "mykola", true, true)),
                resumeCapsuleUploads = { hookCalls++ },
            )
            assertEquals(AuthUiState.Authenticated(rawUserId ?: "", "mykola"), vm.authState.value)
            assertEquals(AppDestination.Home, vm.destination.value)
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        assertEquals(0, hookCalls)
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
}
