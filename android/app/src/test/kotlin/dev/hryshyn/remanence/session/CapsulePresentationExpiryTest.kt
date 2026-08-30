package dev.hryshyn.remanence.session

import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.ui.capsule.DecryptedPhoto
import dev.hryshyn.remanence.ui.capsule.GrantGuardedCapsuleContentSource
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority

/**
 * FIX-REVIEW2-03 regression: the ten-minute expiry is REAL during capsule
 * presentation. One lifecycle-bound timer wakes exactly at the deadline,
 * THE authoritative manager decides, the route ejects to Home, and every
 * on-demand page load revalidates the same grant so an expired/consumed/
 * wrong grant decrypts nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapsulePresentationExpiryTest {

    private val capsuleA = UUID.fromString("7c111111-2222-4333-8444-555555555555")
    private val owner = UserId(UUID.fromString("7d111111-2222-4333-8444-555555555555"))

    private class NoopResolver : SessionStateResolver {
        override suspend fun bootstrap(): SessionState =
            SessionState.Active("7d111111-2222-4333-8444-555555555555", "mykola", true, true)

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    private class SwitchingResolver(
        var current: UserId,
    ) : SessionStateResolver {
        override suspend fun bootstrap(): SessionState =
            SessionState.Active(current.toRestString(), "mykola", true, true)

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    private fun newVm(
        now: () -> Long,
        resolver: SessionStateResolver = NoopResolver(),
    ): Pair<RootViewModel, PresentationGrantAuthority> {
        val grants = ScanGrantManager(now)
        val authority = PresentationGrantAuthority(grants)
        return RootViewModel(
            resolver,
            presentationGrants = authority,
            clockMillis = now,
        ) to authority
    }

    private fun PresentationGrantAuthority.issueOutbox() = issue(
        ownerUserId = owner,
        capsuleId = capsuleA,
        source = CapsulePresentationSource.OUTBOX,
        scanGeneration = 0,
    )

    /** Counting fake proving the guarded reader refuses BEFORE delegating. */
    private class CountingReader : dev.hryshyn.remanence.ui.capsule.CapsuleContentReader {
        var touches = 0

        override suspend fun photoCount(capsuleId: String): Int {
            touches += 1
            return 3
        }

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
            touches += 1
            return DecryptedPhoto(ordinal, "jpeg".toByteArray())
        }

        override suspend fun noteText(capsuleId: String): String? {
            touches += 1
            return "note"
        }
    }

    private inline fun rejectsAsDeadGrant(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (expected: IllegalStateException) {
            true
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun expiryWhilePresentingAutoEjectsToHomeAndRevokesTheGrant() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        var now = 1_000L
        val (vm, authority) = newVm(now = { now })
        runCurrent() // terminal bootstrap result first, like production

        val revocations = mutableListOf<String>()
        backgroundScope.launch(main) { vm.capsuleRevocations.collect { revocations += it } }
        runCurrent()

        val grant = authority.issueOutbox()
        var handoffCleanups = 0
        vm.openCapsuleWithGrant(grant.grantId.toString()) { handoffCleanups += 1 }
        runCurrent() // arms the exact-expiry timer
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 1
        advanceTimeBy(ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 5)
        runCurrent()

        assertEquals("expired presentation must eject to Home", AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        assertNull(authority.resolve(grant.grantId, owner))
        assertEquals(1, handoffCleanups)
        assertEquals(listOf(grant.grantId.toString()), revocations)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun expiredGrantDecryptsNothingOnTheNextPageLoadEvenBeforeAnyWakeUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var now = 1_000L
        val (vm, authority) = newVm(now = { now })
        runCurrent() // bootstrap terminal state first

        val grant = authority.issueOutbox()
        vm.openCapsuleWithGrant(grant.grantId.toString())
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 1

        assertThrows(IllegalStateException::class.java) {
            vm.requireLivePresentationGrant(grant.grantId.toString())
        }

        val reader = CountingReader()
        val guarded = GrantGuardedCapsuleContentSource(reader) {
            vm.requireLivePresentationGrant(grant.grantId.toString())
        }
        assertTrue(rejectsAsDeadGrant { kotlinx.coroutines.runBlocking { guarded.loadPhoto(grant.grantId.toString(), 1) } })
        assertTrue(rejectsAsDeadGrant { kotlinx.coroutines.runBlocking { guarded.noteText(grant.grantId.toString()) } })
        assertTrue(rejectsAsDeadGrant { kotlinx.coroutines.runBlocking { guarded.photoCount(grant.grantId.toString()) } })
        assertEquals("a dead grant must never touch ciphertext", 0, reader.touches)
    }

    @Test
    fun rotationStyleReWatchKeepsTheSameGrantAndPresentationAliveUntilExpiry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var now = 1_000L
        val (vm, authority) = newVm(now = { now })
        runCurrent() // bootstrap terminal state first

        val grant = authority.issueOutbox()
        vm.openCapsuleWithGrant(grant.grantId.toString())
        runCurrent() // arms the timer
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        // Well before expiry the same grant keeps validating (recompositions
        // and re-watches replace only the timer, never the grant).
        advanceTimeBy(60_000)
        now += 60_000
        runCurrent()
        vm.requireLivePresentationGrant(grant.grantId.toString())
        assertNotNull(authority.resolve(grant.grantId, owner))
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        // At expiry the SAME timer ejects the long-open presentation.
        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS - 60_000 + 1
        advanceTimeBy(ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS)
        runCurrent()
        assertEquals(AppDestination.Home, vm.destination.value)
        assertNull(authority.resolve(grant.grantId, owner))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun explicitCloseCancelsThePendingTimerAndConsumesEverything() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        var now = 1_000L
        val (vm, authority) = newVm(now = { now })
        runCurrent() // bootstrap terminal state first

        val revocations = mutableListOf<String>()
        backgroundScope.launch(main) { vm.capsuleRevocations.collect { revocations += it } }
        runCurrent()

        val grant = authority.issueOutbox()
        vm.openCapsuleWithGrant(grant.grantId.toString())
        runCurrent()
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        vm.closeCapsule()
        assertEquals(AppDestination.Home, vm.destination.value)
        assertNull(authority.resolve(grant.grantId, owner))

        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 1
        advanceTimeBy(ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 5)
        runCurrent()

        assertEquals(
            "explicit close also closes the route-owned presentation state",
            listOf(grant.grantId.toString()),
            revocations,
        )
        assertEquals(AppDestination.Home, vm.destination.value)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun routeFailureCancelsOnlyOldWatchAndReplacementStillExpires() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        var now = 1_000L
        val (vm, authority) = newVm({ now })
        runCurrent()

        val revocations = mutableListOf<String>()
        backgroundScope.launch(main) { vm.capsuleRevocations.collect { revocations += it } }
        runCurrent()

        val first = authority.issueOutbox()
        var firstCleanup = 0
        vm.openCapsuleWithGrant(first.grantId.toString()) { firstCleanup += 1 }
        runCurrent()

        vm.revokePresentationForRouteFailure(first.grantId.toString())
        runCurrent()
        assertEquals(1, firstCleanup)
        assertEquals(listOf(first.grantId.toString()), revocations)

        // Leave the old timer's original expiry in the future, then give the
        // replacement a later expiry. A leaked first timer would fire here.
        advanceTimeBy(100_000)
        now += 100_000
        val second = authority.issueOutbox()
        vm.openCapsuleWithGrant(second.grantId.toString())
        runCurrent()

        advanceTimeBy(500_000)
        now += 500_000
        runCurrent()
        assertEquals(listOf(first.grantId.toString()), revocations)
        assertNotNull(authority.resolve(second.grantId, owner))

        now += 100_001
        advanceTimeBy(100_001)
        runCurrent()
        assertEquals(
            listOf(first.grantId.toString(), second.grantId.toString()),
            revocations,
        )
        assertEquals(1, firstCleanup)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun accountBoundaryCancelsOldWatchAndDoesNotSuppressReplacementExpiry() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        var now = 1_000L
        val otherOwner = UserId(UUID.fromString("7e222222-3333-4444-8555-666666666666"))
        val resolver = SwitchingResolver(owner)
        val (vm, authority) = newVm({ now }, resolver)
        runCurrent()

        val revocations = mutableListOf<String>()
        backgroundScope.launch(main) { vm.capsuleRevocations.collect { revocations += it } }
        runCurrent()

        val first = authority.issueOutbox()
        var firstCleanup = 0
        vm.openCapsuleWithGrant(first.grantId.toString()) { firstCleanup += 1 }
        runCurrent()

        resolver.current = otherOwner
        vm.resolveNow()
        runCurrent()
        assertEquals(1, firstCleanup)
        assertEquals(listOf(first.grantId.toString()), revocations)
        assertNull(authority.resolve(first.grantId, owner))

        // The new account gets a new grant/watch. A stale first timer must not
        // fire at its original deadline or cancel this replacement timer.
        val second = authority.issue(
            ownerUserId = otherOwner,
            capsuleId = UUID.fromString("7f333333-4444-4555-8666-777777777777"),
            source = CapsulePresentationSource.OUTBOX,
            scanGeneration = 0,
        )
        vm.openCapsuleWithGrant(second.grantId.toString())
        runCurrent()

        advanceTimeBy(500_000)
        now += 500_000
        runCurrent()
        assertEquals(listOf(first.grantId.toString()), revocations)
        assertNotNull(authority.resolve(second.grantId, otherOwner))

        now += 100_001
        advanceTimeBy(100_001)
        runCurrent()
        assertEquals(
            listOf(first.grantId.toString(), second.grantId.toString()),
            revocations,
        )
        assertEquals(1, firstCleanup)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun guardedReaderDelegatesEveryOperationWhileTheGrantIsLive() = kotlinx.coroutines.runBlocking {
        val reader = CountingReader()
        val guarded = GrantGuardedCapsuleContentSource(reader) { /* live */ }

        assertEquals(3, guarded.photoCount("capsule"))
        assertEquals("jpeg", String(guarded.loadPhoto("capsule", 0).jpegBytes))
        assertEquals("note", guarded.noteText("capsule"))
        assertEquals(3, reader.touches)
        Unit
    }
}
