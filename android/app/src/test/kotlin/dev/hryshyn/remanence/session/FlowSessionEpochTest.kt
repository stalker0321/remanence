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
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority

/**
 * FIX-REVIEW-02 navigation half: entering Create/Scan bumps that flow's
 * session epoch (so re-entry always starts a fresh session), and leaving
 * Scan - or any account-context loss - never keeps a live capsule access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowSessionEpochTest {

    private val owner = UserId(java.util.UUID.fromString("7d111111-2222-4333-8444-555555555555"))

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
            SessionState.Active("7d111111-2222-4333-8444-555555555555", "mykola", true, true)

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
        val grants = ScanGrantManager({ 0L })
        val authority = PresentationGrantAuthority(grants)
        val vm = RootViewModel(NoopResolver(), presentationGrants = authority)
        // A verified grant was issued by the scan flow while inside Scan
        // (navigation to the capsule route is pending) and the user exits.
        val grant = authority.issue(
            ownerUserId = owner,
            capsuleId = java.util.UUID.randomUUID(),
            source = CapsulePresentationSource.OUTBOX,
            scanGeneration = 0,
        )
        vm.openCapsuleWithGrant(grant.grantId.toString())
        vm.openScan()
        vm.returnToHome()

        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        assertNull(vm.capsuleIdFor(grant.grantId.toString()))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
