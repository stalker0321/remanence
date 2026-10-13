package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.ui.home.AccountCapabilityState
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReceivePolicyTest {

    @Test
    fun knownReadyAccountSkipsNewSetup() {
        val auth = AuthUiState.Authenticated(userId = "u1", handle = "h", activeKeyBundleId = "b")
        val cap = AccountCapabilityState.CryptoReady("u1", "h")
        assertTrue(PendingReceivePolicy.isKnownReady(auth, cap))
    }

    @Test
    fun recoveryRequiredIsNotKnownReady() {
        val auth = AuthUiState.Authenticated(userId = "u1", handle = "h", activeKeyBundleId = null)
        assertFalse(
            PendingReceivePolicy.isKnownReady(auth, AccountCapabilityState.RecoveryRequired),
        )
        assertFalse(
            PendingReceivePolicy.isKnownReady(AuthUiState.SignedOut, AccountCapabilityState.RecoveryRequired),
        )
    }

    @Test
    fun handleDeferredUntilFirstSendNoPlaceholder() {
        val receiveOnly = PendingReceiveProfile(email = "a@example.org")
        assertEquals(null, receiveOnly.handle)
        assertTrue(PendingReceivePolicy.shouldPromptForHandleOnSend(receiveOnly))
        assertFalse(
            PendingReceivePolicy.shouldPromptForHandleOnSend(receiveOnly.copy(handle = "taken")),
        )
    }

    @Test
    fun handleBindingIsImmutableOnce() {
        val bound = PendingReceivePolicy.bindHandleOnce(PendingReceiveProfile(), "taken")
        assertEquals("taken", bound.handle)
        try {
            PendingReceivePolicy.bindHandleOnce(bound, "other")
            throw AssertionError("re-bind must fail")
        } catch (_: IllegalStateException) {
            // Immutable ownership: no auto-merge.
        }
    }
}
