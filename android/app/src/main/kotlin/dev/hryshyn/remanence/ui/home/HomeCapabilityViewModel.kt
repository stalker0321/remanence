package dev.hryshyn.remanence.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.session.IdentityAvailabilityPort
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FIX-M1-007-09: derives the REAL Home capability from the live auth state
 * plus the actual on-device identity availability - never a hardcoded
 * CryptoReady. Create/Scan enable only when the account is authenticated AND
 * both wrapped private keysets exist locally.
 */
class HomeCapabilityViewModel(
    private val identityAvailability: IdentityAvailabilityPort,
) : ViewModel() {

    private val _capability = MutableStateFlow<AccountCapabilityState>(AccountCapabilityState.NotAuthenticated)
    val capability: StateFlow<AccountCapabilityState> = _capability.asStateFlow()

    /** Called whenever the root auth state reaches a new terminal value. */
    fun onAuthStateChanged(authState: AuthUiState) {
        _capability.value = when (authState) {
            AuthUiState.SignedOut, AuthUiState.RequiresConnectivity ->
                AccountCapabilityState.NotAuthenticated

            AuthUiState.RecoveryRequired -> AccountCapabilityState.RecoveryRequired

            is AuthUiState.Authenticated -> if (cryptoReady()) {
                AccountCapabilityState.CryptoReady(authState.userId, authState.handle)
            } else {
                AccountCapabilityState.RecoveryRequired
            }
        }
    }

    private fun cryptoReady(): Boolean = try {
        identityAvailability.encryptionKeysetAvailable() && identityAvailability.signingKeysetAvailable()
    } catch (_: Exception) {
        false
    }
}
