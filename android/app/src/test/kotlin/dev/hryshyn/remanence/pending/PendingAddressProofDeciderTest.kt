package dev.hryshyn.remanence.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAddressProofDeciderTest {

    @Test
    fun claimedV1SkipsProofRegardlessOfAuthority() {
        assertEquals(
            PendingAddressProofDecider.Decision.AlreadyOwnedNoProof,
            PendingAddressProofDecider.decide(
                isClaimedOrOwnedByCurrentAccount = true,
                isBoundToOtherAccount = false,
                authorityProofAvailable = false,
            ),
        )
    }

    @Test
    fun claimedV2SkipsProofRegardlessOfAuthority() {
        // Owned pending (claimed v2) uses ordinary sync/scan — no OTP even
        // without Google/phone authority, and never a pipeline conversion.
        assertEquals(
            PendingAddressProofDecider.Decision.AlreadyOwnedNoProof,
            PendingAddressProofDecider.decide(
                isClaimedOrOwnedByCurrentAccount = true,
                isBoundToOtherAccount = false,
                authorityProofAvailable = true,
            ),
        )
    }

    @Test
    fun unclaimedPendingStaysV2EvenWhenAuthoritySkipsOtp() {
        val decision = PendingAddressProofDecider.decide(
            isClaimedOrOwnedByCurrentAccount = false,
            isBoundToOtherAccount = false,
            authorityProofAvailable = true,
        ) as PendingAddressProofDecider.Decision.PendingProof
        assertTrue(decision.otpSkippableViaAuthority)
    }

    @Test
    fun unclaimedPendingWithoutAuthorityNeedsOtpDispatch() {
        val decision = PendingAddressProofDecider.decide(
            isClaimedOrOwnedByCurrentAccount = false,
            isBoundToOtherAccount = false,
            authorityProofAvailable = false,
        ) as PendingAddressProofDecider.Decision.PendingProof
        assertFalse(decision.otpSkippableViaAuthority)
    }

    @Test
    fun boundToOtherAccountRejectsNeverFallback() {
        // Even authority proof must not convert a foreign binding into a
        // pending claim for this account.
        assertEquals(
            PendingAddressProofDecider.Decision.RejectedBoundToOther,
            PendingAddressProofDecider.decide(
                isClaimedOrOwnedByCurrentAccount = false,
                isBoundToOtherAccount = true,
                authorityProofAvailable = true,
            ),
        )
        assertEquals(
            PendingAddressProofDecider.Decision.RejectedBoundToOther,
            PendingAddressProofDecider.decide(
                isClaimedOrOwnedByCurrentAccount = true,
                isBoundToOtherAccount = true,
                authorityProofAvailable = false,
            ),
        )
    }
}
