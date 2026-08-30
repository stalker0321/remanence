package dev.hryshyn.remanence.session

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import java.util.UUID
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority

/**
 * FIX-REVIEW-03 regression: ONE authoritative memory-only grant lifecycle.
 * The root can only open a capsule when the shared [ScanGrantManager]
 * resolves the grant ID, unexpired, to its bound capsule ID. Valid, expired,
 * wrong-ID, close, logout, fresh-process, and re-entry behaviors are proven
 * here; no verified access can ever be minted out of arbitrary strings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanGrantAuthorityTest {

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
            if (signedOut) SessionState.SignedOut else
                SessionState.Active("7d111111-2222-4333-8444-555555555555", "mykola", true, true)

        override suspend fun logout(): SessionState {
            signedOut = true
            return SessionState.SignedOut
        }
    }

    private val capsuleA = UUID.fromString("7a111111-2222-4333-8444-555555555555")
    private val capsuleB = UUID.fromString("7b222222-3333-4444-8555-666666666666")
    private val owner = UserId(UUID.fromString("7d111111-2222-4333-8444-555555555555"))

    private fun newVm(now: () -> Long): Pair<RootViewModel, PresentationGrantAuthority> {
        val grants = ScanGrantManager(now)
        val authority = PresentationGrantAuthority(grants)
        return RootViewModel(
            NoopResolver(),
            presentationGrants = authority,
            clockMillis = now,
        ) to authority
    }

    private fun PresentationGrantAuthority.issueOutbox(
        capsuleId: UUID,
        expectedEpoch: Long = currentEpoch(),
    ) = issue(
        ownerUserId = owner,
        capsuleId = capsuleId,
        source = CapsulePresentationSource.OUTBOX,
        scanGeneration = 0,
        expectedEpoch = expectedEpoch,
    )

    @Test
    fun validGrantOpensOnlyItsOwnBoundCapsule() = runTest {
        var now = 1_000L
        val (vm, authority) = newVm { now }
        val grant = authority.issueOutbox(capsuleA)

        vm.openCapsuleWithGrant(grant.grantId.toString())

        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)
        assertEquals(capsuleA.toString(), vm.capsuleIdFor(grant.grantId.toString()))
        // A different live capsule ID is never reachable through this grant.
        assertNotEquals(capsuleB.toString(), vm.capsuleIdFor(grant.grantId.toString()))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun sameOwnerRefreshPreservesTheLivePresentationBinding() = runTest {
        val (vm, authority) = newVm { 1_000L }
        vm.resolveNow()
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        // A terminal refresh for the same authenticated owner is not an
        // account boundary and must not close the in-memory presentation.
        vm.resolveNow()
        assertEquals(
            AuthUiState.Authenticated(
                userId = owner.toRestString(),
                handle = "mykola",
                activeKeyBundleId = null,
            ),
            vm.authState.value,
        )
        val access = vm.liveCapsuleAccess as CapsuleAccess.Granted
        assertEquals(owner.toRestString(), access.ownerUserId)
        assertEquals(grant.grantId.toString(), access.grantId)

        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)
        assertEquals(capsuleA.toString(), vm.capsuleIdFor(grant.grantId.toString()))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun clearedContextRejectsAnOldScanEpoch() {
        val (_, authority) = newVm { 1_000L }
        val oldEpoch = authority.currentEpoch()
        authority.clearAll()

        assertThrows(IllegalStateException::class.java) {
            authority.issueOutbox(capsuleA, expectedEpoch = oldEpoch)
        }
    }

    @Test
    fun arbitraryStringsCanNeverCreateVerifiedAccess() = runTest {
        val (vm, _) = newVm { 0L }

        // No scan ever happened: nothing was issued, so no string opens anything.
        vm.openCapsuleWithGrant("forged-grant-id")
        vm.openCapsuleWithGrant(UUID.randomUUID().toString())
        vm.openCapsuleWithGrant(capsuleA.toString())

        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        assertNull(vm.capsuleIdFor("forged-grant-id"))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun wrongGrantIdIsRefusedEvenWhileAnotherGrantIsLive() = runTest {
        val (vm, authority) = newVm { 0L }
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())

        // A foreign/random grant string resolves to nothing and changes nothing.
        vm.openCapsuleWithGrant(UUID.randomUUID().toString())
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)
        assertNull(vm.capsuleIdFor(UUID.randomUUID().toString()))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun expiredGrantCannotOpenAndEjectsAnAlreadyOpenRouteOnNextResolve() = runTest {
        var now = 1_000L
        val (vm, authority) = newVm { now }
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 1L

        // Expiry invalidates the grant; the next resolve ejects the route...
        assertNull(vm.capsuleIdFor(grant.grantId.toString()))
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        // ...and the same expired string can never open anything again.
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun closeConsumesTheAuthoritativeGrantSoItCannotReopen() = runTest {
        val (vm, authority) = newVm { 0L }
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())

        vm.closeCapsule()

        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        assertNull(vm.capsuleIdFor(grant.grantId.toString()))
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun logoutClearsEveryGrantInTheManager() = runTest {
        val resolver = NoopResolver()
        val grants = ScanGrantManager({ 0L })
        val authority = PresentationGrantAuthority(grants)
        val vm = RootViewModel(resolver, presentationGrants = authority)
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())

        vm.logout()

        assertNull(vm.capsuleIdFor(grant.grantId.toString()))
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Authentication, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun clearingRootRevokesTheAppScopedAuthority() = runTest {
        val (vm, authority) = newVm { 0L }
        val grant = authority.issueOutbox(capsuleA)

        ViewModelStore().also { store ->
            store.put("root", vm)
            store.clear()
        }

        assertNull(authority.resolve(grant.grantId, owner))
    }

    @Test
    fun closingPresentedGrantRunsTheHandoffCaptureCleanupOnce() = runTest {
        val (vm, authority) = newVm { 0L }
        val grant = authority.issueOutbox(capsuleA)
        var cleanups = 0

        assertTrue(
            vm.openCapsuleWithGrant(grant.grantId.toString()) {
                cleanups += 1
            },
        )
        vm.closeCapsule()
        vm.closeCapsule()

        assertEquals(1, cleanups)
        assertNull(authority.resolve(grant.grantId, owner))
    }

    @Test
    fun logoutRunsTheHandoffCaptureCleanupBeforeAsyncTeardown() = runTest {
        val (vm, authority) = newVm { 0L }
        val grant = authority.issueOutbox(capsuleA)
        var cleanups = 0

        assertTrue(
            vm.openCapsuleWithGrant(grant.grantId.toString()) {
                cleanups += 1
            },
        )
        vm.logout()

        assertEquals(1, cleanups)
        assertNull(authority.resolve(grant.grantId, owner))
    }

    @Test
    fun newManagerAfterProcessDeathRejectsTheOldGrantString() = runTest {
        var now = 1_000L
        val (deadProcessVm, deadAuthority) = newVm { now }
        val grant = deadAuthority.issueOutbox(capsuleA)
        deadProcessVm.openCapsuleWithGrant(grant.grantId.toString())

        // Everything in-memory dies with the process; a fresh root+manager
        // pair knows nothing about the old grant string.
        val rebornVm = newVm { now }.first
        rebornVm.openCapsuleWithGrant(grant.grantId.toString())

        assertEquals(AppDestination.Home, rebornVm.destination.value)
        assertEquals(CapsuleAccess.None, rebornVm.liveCapsuleAccess)
        assertNull(rebornVm.capsuleIdFor(grant.grantId.toString()))
        rebornVm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun reEntryAfterCloseRequiresAFreshScanIssuedGrant() = runTest {
        val (vm, authority) = newVm { 0L }
        val first = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(first.grantId.toString())
        vm.closeCapsule()

        // Re-entry: a NEW scan issues a NEW grant for the next presentation;
        // issuing also replaces any previous grant by construction.
        val second = authority.issueOutbox(capsuleB)
        vm.openCapsuleWithGrant(second.grantId.toString())
        assertEquals(AppDestination.Capsule(second.grantId.toString()), vm.destination.value)
        assertEquals(capsuleB.toString(), vm.capsuleIdFor(second.grantId.toString()))
        // The first grant's string stays dead after it was consumed.
        vm.openCapsuleWithGrant(first.grantId.toString())
        assertEquals(AppDestination.Capsule(second.grantId.toString()), vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /**
     * FIX-REVIEW2-02 regression: the grant is issued while the user is still
     * on Scan, but the navigation effect never completes (Back wins the
     * race). Leaving Scan to Home must invalidate THE authoritative manager,
     * so the issued grant no longer resolves and can never open anything.
     */
    @Test
    fun returnToHomeFromScanInvalidatesAnIssuedButNeverNavigatedGrant() = runTest {
        val (vm, authority) = newVm { 0L }
        vm.openScan()
        assertEquals(AppDestination.Scan, vm.destination.value)

        // The scan flow verified crypto and issued its grant; the terminal ->
        // navigation effect has NOT run yet when the user leaves the flow.
        val grant = authority.issueOutbox(capsuleA)
        vm.returnToHome()

        assertEquals(AppDestination.Home, vm.destination.value)
        assertNull(authority.resolve(grant.grantId, owner))
        // Even the full navigation effect replaying afterwards opens nothing.
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun returnToHomeFromScanBeforeAnyIssueLeavesNothingBehindAndReentryStillWorks() = runTest {
        val (vm, authority) = newVm { 0L }

        // Exit before anything was ever issued: nothing breaks.
        vm.openScan()
        vm.returnToHome()
        assertEquals(AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)

        // A fresh re-entry scans, verifies, issues, and opens normally.
        vm.openScan()
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        // Close keeps its existing guarantee after the re-entry.
        vm.closeCapsule()
        assertNull(authority.resolve(grant.grantId, owner))
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
