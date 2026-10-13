package dev.hryshyn.remanence.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingProfileCapabilityTest {

    private fun account(
        authenticated: Boolean = true,
        cryptoReady: Boolean = true,
        handle: String? = "taken",
        termsAccepted: Boolean = true,
    ) = PendingProfileCapability.AccountView(authenticated, cryptoReady, handle, termsAccepted)

    @Test
    fun receiveNeedsAuthAndCryptoOnly() {
        assertTrue(account().run(PendingProfileCapability.canReceive))
        assertTrue(account(handle = null).run(PendingProfileCapability.canReceive))
        assertFalse(account(authenticated = false).run(PendingProfileCapability.canReceive))
        assertFalse(account(cryptoReady = false).run(PendingProfileCapability.canReceive))
    }

    @Test
    fun sendNeedsHandleAndTerms() {
        assertTrue(PendingProfileCapability.canSend(account()))
        // Handleless receive-only account cannot send (prompts first).
        assertFalse(PendingProfileCapability.canSend(account(handle = null)))
        assertFalse(PendingProfileCapability.canSend(account(handle = "  ")))
        assertFalse(PendingProfileCapability.canSend(account(termsAccepted = false)))
        assertFalse(PendingProfileCapability.canSend(account(cryptoReady = false)))
        assertFalse(PendingProfileCapability.canSend(account(authenticated = false)))
    }

    @Test
    fun handleSetupOnceReplayConflict() {
        assertEquals(
            PendingHandleSetup.Result.Committed("taken"),
            PendingHandleSetup.resolve(null, "taken", null),
        )
        assertEquals(
            PendingHandleSetup.Result.ReplayOk("taken"),
            PendingHandleSetup.resolve("taken", "taken", null),
        )
        assertEquals(
            PendingHandleSetup.Result.ImmutableConflict,
            PendingHandleSetup.resolve("taken", "other", null),
        )
        assertEquals(
            PendingHandleSetup.Result.ImmutableConflict,
            PendingHandleSetup.resolve(null, "taken", "HANDLE_IMMUTABLE"),
        )
        assertTrue(PendingHandleSetup.resolve(null, "  ", null) is PendingHandleSetup.Result.Rejected)
    }
}
