package dev.hryshyn.remanence.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
    fun logoutReturnsToAuthenticationEvenIfTokenClearingFailsSilently() = runTest {
        val vm = RootViewModel(SignedOutResolver()) // nothing persisted; logout is still safe

        vm.logout()

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
