package dev.hryshyn.remanence.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Android request builder seam (pinned credentials 1.6.0):
 * ordinary gets use discovery, only the fresh-create verification
 * get requests immediately-available credentials. No allow-list
 * guessing anywhere on this path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImmediateRequestModeTest {

    @Test
    fun ordinaryGetUsesDiscoveryByDefault() {
        val request = getCredentialRequest("{}", immediate = false)

        assertFalse(request.preferImmediatelyAvailableCredentials())
        assertEquals(1, request.credentialOptions.size)
    }

    @Test
    fun freshVerificationGetRequestsImmediatelyAvailableCredentials() {
        val request = getCredentialRequest("{}", immediate = true)

        assertTrue(request.preferImmediatelyAvailableCredentials())
        assertEquals(1, request.credentialOptions.size)
    }
}
