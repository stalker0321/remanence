package dev.hryshyn.remanence.session

import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HoldHomeIntentTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private class Resolver(var state: SessionState = SessionState.SignedOut) : SessionStateResolver {
        override suspend fun bootstrap() = state
        override suspend fun logout(): SessionState = SessionState.SignedOut
    }
    private val active = SessionState.Active("9f111111-2222-4333-8444-555555555555", "sender", true, true)

    @Test fun publicIntentCannotEnterProtectedFlowBeforeRealAuthentication() = runTest {
        val resolver = Resolver()
        val vm = RootViewModel(resolver)
        vm.requestHomeIntent(HomeIntent.OPEN)
        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        assertEquals(0L, vm.scanSessionEpoch.value)
        resolver.state = active
        vm.onSessionEstablished()
        assertEquals(AppDestination.Scan, vm.destination.value)
        assertEquals(1L, vm.scanSessionEpoch.value)
        assertNull(vm.homeIntent.value)
        vm.onAppForegrounded()
        assertEquals(1L, vm.scanSessionEpoch.value)
        vm.viewModelScope.cancel()
    }

    @Test fun failedAuthAndMissingKeysDoNotConsumeOrAuthorizeMakeIntent() = runTest {
        val resolver = Resolver()
        val vm = RootViewModel(resolver)
        vm.requestHomeIntent(HomeIntent.MAKE)
        vm.onAppForegrounded()
        assertEquals(HomeIntent.MAKE, vm.homeIntent.value)
        resolver.state = SessionState.RecoveryRequired
        vm.onSessionEstablished()
        assertEquals(AppDestination.Authentication, vm.destination.value)
        assertEquals(0L, vm.createSessionEpoch.value)
        assertEquals(HomeIntent.MAKE, vm.homeIntent.value)
        resolver.state = active
        vm.onSessionEstablished()
        assertEquals(AppDestination.Create, vm.destination.value)
        assertEquals(1L, vm.createSessionEpoch.value)
        vm.viewModelScope.cancel()
    }

    @Test fun backLogoutAndNewProcessCannotRestoreAnAbandonedIntent() = runTest {
        val resolver = Resolver()
        val vm = RootViewModel(resolver)
        vm.requestHomeIntent(HomeIntent.OPEN)
        vm.cancelHomeIntent()
        resolver.state = active
        vm.onSessionEstablished()
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.logout()
        assertNull(vm.homeIntent.value)
        vm.requestHomeIntent(HomeIntent.MAKE)
        val restarted = RootViewModel(Resolver(active))
        assertNull(restarted.homeIntent.value)
        assertEquals(AppDestination.Home, restarted.destination.value)
        vm.viewModelScope.cancel()
        restarted.viewModelScope.cancel()
    }
}
