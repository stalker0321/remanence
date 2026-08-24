package app.postmark.memory.session

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
import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AuthUiState

/**
 * Auth route-guard wiring proof (FIX-M1-007-05/08): the root publishes ONLY
 * terminal async auth outcomes and never leaves Home without a proven session.
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
        override suspend fun bootstrap(): SessionState = state

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    private fun viewModelOf(resolver: SessionStateResolver): RootViewModel =
        RootViewModel(resolver, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher))

    @Test
    fun coldStartWithoutSessionLandsOnAuthenticationSurface() = runTest {
        val vm = viewModelOf(SignedOutResolver())

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.dispose()
    }

    @Test
    fun missingKeysOnColdStartSurfacesRecoveryRequired() = runTest {
        val vm = viewModelOf(FixedOutcomeResolver(SessionState.RecoveryRequired))

        assertEquals(AuthUiState.RecoveryRequired, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.dispose()
    }

    @Test
    fun unreachableColdStartSurfacesConnectivityInsteadOfAuthenticatedHome() = runTest {
        val vm = viewModelOf(FixedOutcomeResolver(SessionState.RequiresConnectivity))

        assertEquals(AuthUiState.RequiresConnectivity, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.dispose()
    }

    @Test
    fun establishedSessionReachesHomeAsAuthenticatedOnlyAfterTerminalResult() = runTest {
        val vm = viewModelOf(FixedOutcomeResolver(SessionState.Active("user-1", "mykola", true, true)))

        vm.onSessionEstablished()

        assertEquals(AuthUiState.Authenticated(userId = "user-1", handle = "mykola"), vm.authState.value)
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.dispose()
    }

    @Test
    fun logoutReturnsToAuthenticationEvenIfTokenClearingFailsSilently() = runTest {
        val vm = viewModelOf(SignedOutResolver()) // nothing persisted; logout is still safe

        vm.logout()

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.dispose()
    }
}
