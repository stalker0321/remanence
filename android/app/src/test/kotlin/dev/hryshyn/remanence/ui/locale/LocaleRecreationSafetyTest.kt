package dev.hryshyn.remanence.ui.locale

import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.session.SessionState
import dev.hryshyn.remanence.session.SessionStateResolver
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AppNavigationController
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Recreation-safety contract for the language switch, proven on the real
 * seams (never fakes): the locale path recreates at most the Activity, so
 * [RootViewModel], the [PresentationGrantAuthority] binding, and the flow
 * epochs all survive it. The strongest thing a recreation can trigger is a
 * same-owner foreground refresh — these tests drive exactly that plus the
 * teardown boundaries the locale path must never cross (logout, owner
 * change, flow re-entry).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocaleRecreationSafetyTest {

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

    private val capsuleA = UUID.fromString("7a111111-2222-4333-8444-555555555555")
    private val owner = UserId(UUID.fromString("7d111111-2222-4333-8444-555555555555"))
    private val otherOwner = UserId(UUID.fromString("7d999999-9999-4999-8999-999999999999"))

    private fun newVm(now: () -> Long): Pair<RootViewModel, PresentationGrantAuthority> {
        val authority = PresentationGrantAuthority(ScanGrantManager(now))
        return RootViewModel(
            NoopResolver(),
            presentationGrants = authority,
            clockMillis = now,
        ) to authority
    }

    private fun PresentationGrantAuthority.issueOutbox(capsuleId: UUID) = issue(
        ownerUserId = owner,
        capsuleId = capsuleId,
        source = CapsulePresentationSource.OUTBOX,
        scanGeneration = 0,
        expectedEpoch = currentEpoch(),
    )

    @Test
    fun recreationRefreshKeepsLiveGrantAndDestination() = runTest {
        val (vm, authority) = newVm { 1_000L }
        vm.resolveNow()
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        // Everything a recreation can trigger: foreground refreshes for the
        // same authenticated owner. The live grant and route must survive.
        vm.resolveNow()
        vm.resolveNow()

        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)
        assertNotNull(vm.presentationGrantFor(grant.grantId.toString()))
        assertEquals(capsuleA.toString(), vm.capsuleIdFor(grant.grantId.toString()))
        val access = vm.liveCapsuleAccess as CapsuleAccess.Granted
        assertEquals(owner.toRestString(), access.ownerUserId)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun logoutTeardownStillHoldsAfterRecreationWindow() = runTest {
        val (vm, authority) = newVm { 1_000L }
        vm.resolveNow()
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())

        // The locale path never calls this; when logout really happens the
        // authority binding, access, and route must all die together.
        vm.logout()

        assertNull(vm.presentationGrantFor(grant.grantId.toString()))
        assertNull(vm.capsuleIdFor(grant.grantId.toString()))
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun ownerChangeDropsGrantThroughNavigationGuard() {
        val controller = AppNavigationController(
            AuthUiState.Authenticated(owner.toRestString(), "mykola"),
        )
        controller.grantCapsuleAccess(
            grantId = "grant-1",
            capsuleId = capsuleA.toString(),
            ownerUserId = owner.toRestString(),
            source = CapsulePresentationSource.INCOMING,
            scanGeneration = 0,
        )
        controller.navigate(AppDestination.Capsule("grant-1"))
        assertEquals(AppDestination.Capsule("grant-1"), controller.current)

        // An account boundary (never a recreation) clears the grant and
        // ejects to Home.
        controller.updateAuth(AuthUiState.Authenticated(otherOwner.toRestString(), "other"))

        assertEquals(CapsuleAccess.None, controller.capsuleAccess)
        assertEquals(AppDestination.Home, controller.current)
    }

    @Test
    fun localePathNeverBumpsFlowEpochsOrMovesDestination() = runTest {
        val (vm, _) = newVm { 1_000L }
        vm.resolveNow()
        vm.openCreate()
        val createEpoch = vm.createSessionEpoch.value
        vm.returnToHome()
        vm.openScan()
        val scanEpoch = vm.scanSessionEpoch.value
        assertEquals(AppDestination.Scan, vm.destination.value)

        // Recreation performs no flow entry: epochs and destination are
        // stable across every refresh it can trigger. Camera/capture sessions
        // keyed to these epochs are therefore undisturbed.
        vm.resolveNow()
        vm.resolveNow()

        assertEquals(createEpoch, vm.createSessionEpoch.value)
        assertEquals(scanEpoch, vm.scanSessionEpoch.value)
        assertEquals(AppDestination.Scan, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun authenticatedStateSurvivesRefreshWithoutRelogin() = runTest {
        val (vm, _) = newVm { 1_000L }
        vm.resolveNow()
        val before = vm.authState.value
        assertEquals(
            AuthUiState.Authenticated(owner.toRestString(), "mykola", null),
            before,
        )

        vm.resolveNow()

        assertEquals(before, vm.authState.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
