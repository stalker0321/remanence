package app.postmark.memory.ui.home

import app.postmark.memory.session.IdentityAvailabilityPort
import app.postmark.memory.ui.navigation.AuthUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * FIX-M1-007-09: Home enables Create/Scan only for a REAL crypto-ready
 * account - authenticated on the server AND both wrapped keysets on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCapabilityViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeIdentity(
        var encryption: Boolean = true,
        var signing: Boolean = true,
        var explode: Boolean = false,
    ) : IdentityAvailabilityPort {
        override fun encryptionKeysetAvailable(): Boolean {
            if (explode) throw IllegalStateException("storage gone")
            return encryption
        }

        override fun signingKeysetAvailable(): Boolean {
            if (explode) throw IllegalStateException("storage gone")
            return signing
        }
    }

    private fun viewModel(identity: FakeIdentity) = HomeCapabilityViewModel(identity)

    @Test
    fun signedOutIsNeverCryptoReady() {
        val vm = viewModel(FakeIdentity())

        vm.onAuthStateChanged(AuthUiState.SignedOut)

        assertEquals(AccountCapabilityState.NotAuthenticated, vm.capability.value)
    }

    @Test
    fun requiresConnectivityStaysDisabledUntilTheSessionIsProven() {
        val vm = viewModel(FakeIdentity())

        vm.onAuthStateChanged(AuthUiState.RequiresConnectivity)

        assertEquals(AccountCapabilityState.NotAuthenticated, vm.capability.value)
    }

    @Test
    fun recoveryRequiredSurfacesTheRecoveryState() {
        val vm = viewModel(FakeIdentity())

        vm.onAuthStateChanged(AuthUiState.RecoveryRequired)

        assertEquals(AccountCapabilityState.RecoveryRequired, vm.capability.value)
    }

    @Test
    fun authenticatedWithBothKeysetsIsCryptoReady() {
        val vm = viewModel(FakeIdentity())

        vm.onAuthStateChanged(AuthUiState.Authenticated("user-1", "mykola"))

        assertEquals(
            AccountCapabilityState.CryptoReady(userId = "user-1", handle = "mykola"),
            vm.capability.value,
        )
    }

    @Test
    fun authenticatedButMissingSigningKeysetFallsBackToRecovery() {
        val vm = viewModel(FakeIdentity(signing = false))

        vm.onAuthStateChanged(AuthUiState.Authenticated("user-1", "mykola"))

        assertEquals(AccountCapabilityState.RecoveryRequired, vm.capability.value)
    }

    @Test
    fun identityAvailabilityFailureNeverYieldsCryptoReady() {
        val vm = viewModel(FakeIdentity(explode = true))

        vm.onAuthStateChanged(AuthUiState.Authenticated("user-1", "mykola"))

        assertEquals(AccountCapabilityState.RecoveryRequired, vm.capability.value)
    }
}
