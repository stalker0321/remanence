package dev.hryshyn.remanence.session

import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.ui.navigation.AppDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FIX-M1-007-10: Create/Scan are reachable authenticated destinations, and
 * leaving a flow (exit, logout) runs its registered transient cleanups.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootFlowLifecycleTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class NoopResolver : SessionStateResolver {
        var signedOut = false

        override suspend fun bootstrap(): SessionState =
            if (signedOut) SessionState.SignedOut else SessionState.Active("u", "mykola", true, true)

        override suspend fun logout(): SessionState {
            signedOut = true
            return SessionState.SignedOut
        }
    }

    private fun viewModel(resolver: SessionStateResolver = NoopResolver()) = RootViewModel(resolver)

    @Test
    fun createAndScanAreReachableAndExitRunsTransientCleanups() = runTest {
        val vm = viewModel()
        val cleaned = mutableListOf<String>()
        vm.registerTransientCleanup(AppDestination.Create) { cleaned += "create" }
        vm.registerTransientCleanup(AppDestination.Scan) { cleaned += "scan" }

        vm.openCreate()
        assertEquals(AppDestination.Create, vm.destination.value)
        vm.returnToHome()
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(listOf("create"), cleaned)

        vm.openScan()
        assertEquals(AppDestination.Scan, vm.destination.value)
        vm.returnToHome()
        assertEquals(listOf("create", "scan"), cleaned)

        // Returning from Home itself runs nothing.
        vm.returnToHome()
        assertEquals(listOf("create", "scan"), cleaned)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun logoutWhileInsideAFlowClearsEveryFlowState() = runTest {
        val resolver = NoopResolver()
        val vm = viewModel(resolver)
        val cleaned = mutableListOf<String>()
        vm.registerTransientCleanup(AppDestination.Create) { cleaned += "create" }
        vm.registerTransientCleanup(AppDestination.Scan) { cleaned += "scan" }

        vm.openScan()
        vm.logout()

        assertEquals(AppDestination.Authentication, vm.destination.value)
        assertTrue("create" in cleaned && "scan" in cleaned)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun authenticatedOwnerBoundaryExitsCreateAndRunsItsCleanupExactlyOnce() = runTest {
        val resolver = SwitchingResolver("1f0a1234-5678-4abc-9def-aabbccdd1001")
        val vm = viewModel(resolver)
        vm.resolveNow()
        vm.openCreate()
        var cleanups = 0
        vm.registerTransientCleanup(AppDestination.Create) { cleanups += 1 }

        resolver.userId = "2f0a1234-5678-4abc-9def-aabbccdd1002"
        vm.resolveNow()

        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(1, cleanups)

        resolver.userId = "3f0a1234-5678-4abc-9def-aabbccdd1003"
        vm.resolveNow()
        assertEquals(1, cleanups)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun authenticatedOwnerBoundaryExitsScanAndRunsItsCleanup() = runTest {
        val resolver = SwitchingResolver("1f0a1234-5678-4abc-9def-aabbccdd1001")
        val vm = viewModel(resolver)
        vm.resolveNow()
        vm.openScan()
        var cleanups = 0
        vm.registerTransientCleanup(AppDestination.Scan) { cleanups += 1 }

        resolver.userId = "2f0a1234-5678-4abc-9def-aabbccdd1002"
        vm.resolveNow()

        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(1, cleanups)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun cleanupsAreOneShotPerRegistrationAndFreshVisitsRegisterAgain() = runTest {
        val vm = viewModel()
        var runs = 0
        vm.registerTransientCleanup(AppDestination.Create) { runs++ }

        vm.openCreate()
        vm.returnToHome()
        assertEquals(1, runs)

        // A second visit without a new registration has nothing left to run.
        vm.openCreate()
        vm.returnToHome()
        assertEquals(1, runs)

        // Re-entering the flow registers its fresh transient cleanup.
        vm.openCreate()
        vm.registerTransientCleanup(AppDestination.Create) { runs++ }
        vm.returnToHome()
        assertEquals(2, runs)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private class SwitchingResolver(var userId: String) : SessionStateResolver {
        override suspend fun bootstrap(): SessionState =
            SessionState.Active(userId, "mykola", true, true)

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }
}
