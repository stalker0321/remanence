package dev.hryshyn.remanence.session

import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * FIX-REVIEW-02 navigation half: entering Create/Scan bumps that flow's
 * session epoch (so re-entry always starts a fresh session), and leaving
 * Scan - or any account-context loss - never keeps a live capsule access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowSessionEpochTest {

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
        override suspend fun bootstrap(): SessionState =
            SessionState.Active("u", "mykola", true, true)

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    @Test
    fun everyEntryIntoCreateBumpsItsSessionEpoch() = runTest {
        val vm = RootViewModel(NoopResolver())
        val first = vm.createSessionEpoch.value
        vm.openCreate()
        val second = vm.createSessionEpoch.value
        vm.returnToHome()
        vm.openCreate()
        val third = vm.createSessionEpoch.value

        assertNotEquals(first, second)
        assertNotEquals(second, third)
        assertEquals(AppDestination.Create, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun everyEntryIntoScanBumpsItsSessionEpoch() = runTest {
        val vm = RootViewModel(NoopResolver())
        val first = vm.scanSessionEpoch.value
        vm.openScan()
        val second = vm.scanSessionEpoch.value
        vm.returnToHome()
        vm.openScan()
        val third = vm.scanSessionEpoch.value

        assertNotEquals(first, second)
        assertNotEquals(second, third)
        assertEquals(AppDestination.Scan, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun leavingScanDropsAnyLiveCapsuleAccessFromTheMidScanGrant() = runTest {
        val vm = RootViewModel(NoopResolver())
        // A verified grant was issued by the scan flow while inside Scan
        // (navigation to the capsule route is pending) and the user exits.
        val grant = vm.scanGrants.issue(java.util.UUID.randomUUID())
        vm.openCapsuleWithGrant(grant.grantId.toString())
        vm.openScan()
        vm.returnToHome()

        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        assertNull(vm.capsuleIdFor(grant.grantId.toString()))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
