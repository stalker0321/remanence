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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.recognition.ScanGrantManager

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

    private class NoopResolver : SessionStateResolver {
        override suspend fun bootstrap(): SessionState =
            SessionState.Active("u", "mykola", true, true)

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

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
        val vm = RootViewModel(NoopResolver(), grants = ScanGrantManager({ now }), clockMillis = { now })
        runCurrent() // terminal bootstrap result first, like production

        val revocations = mutableListOf<String>()
        backgroundScope.launch(main) { vm.capsuleRevocations.collect { revocations += it } }
        runCurrent()

        val grant = vm.scanGrants.issue(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        runCurrent() // arms the exact-expiry timer
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 1
        advanceTimeBy(ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 5)
        runCurrent()

        assertEquals("expired presentation must eject to Home", AppDestination.Home, vm.destination.value)
        assertEquals(CapsuleAccess.None, vm.liveCapsuleAccess)
        assertNull(vm.scanGrants.resolveCapsuleId(grant.grantId))
        assertEquals(listOf(grant.grantId.toString()), revocations)
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun expiredGrantDecryptsNothingOnTheNextPageLoadEvenBeforeAnyWakeUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var now = 1_000L
        val vm = RootViewModel(NoopResolver(), grants = ScanGrantManager({ now }), clockMillis = { now })
        runCurrent() // bootstrap terminal state first

        val grant = vm.scanGrants.issue(capsuleA)
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
        val vm = RootViewModel(NoopResolver(), grants = ScanGrantManager({ now }), clockMillis = { now })
        runCurrent() // bootstrap terminal state first

        val grant = vm.scanGrants.issue(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        runCurrent() // arms the timer
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        // Well before expiry the same grant keeps validating (recompositions
        // and re-watches replace only the timer, never the grant).
        advanceTimeBy(60_000)
        now += 60_000
        runCurrent()
        vm.requireLivePresentationGrant(grant.grantId.toString())
        assertTrue(vm.scanGrants.resolveCapsuleId(grant.grantId) != null)
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        // At expiry the SAME timer ejects the long-open presentation.
        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS - 60_000 + 1
        advanceTimeBy(ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS)
        runCurrent()
        assertEquals(AppDestination.Home, vm.destination.value)
        assertNull(vm.scanGrants.resolveCapsuleId(grant.grantId))
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun explicitCloseCancelsThePendingTimerAndConsumesEverything() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        var now = 1_000L
        val vm = RootViewModel(NoopResolver(), grants = ScanGrantManager({ now }), clockMillis = { now })
        runCurrent() // bootstrap terminal state first

        val revocations = mutableListOf<String>()
        backgroundScope.launch(main) { vm.capsuleRevocations.collect { revocations += it } }
        runCurrent()

        val grant = vm.scanGrants.issue(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())
        runCurrent()
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)

        vm.closeCapsule()
        assertEquals(AppDestination.Home, vm.destination.value)
        assertNull(vm.scanGrants.resolveCapsuleId(grant.grantId))

        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 1
        advanceTimeBy(ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 5)
        runCurrent()

        assertEquals("no revocation may fire after an explicit close", emptyList<String>(), revocations)
        assertEquals(AppDestination.Home, vm.destination.value)
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
