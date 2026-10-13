package dev.hryshyn.remanence.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T186 M12b: out-of-band pin selection — valid pair selected,
 * absent/blank/malformed UUID or undecodable keyset fails closed to
 * null. Production/beta reach this only with empty BuildConfig values.
 */
class PendingServicePinConfigTest {

    private val uuid = "a3618c4c-fc28-4847-9f4c-0681e9b531bc"
    private val keysetB64 = "Q2lu"

    @Test
    fun validPairSelected() {
        val pin = PendingServicePinConfig.select(uuid, keysetB64)
        assertEquals(uuid, pin?.deploymentUuid)
        assertEquals(keysetB64, pin?.publicKeysetB64)
    }

    @Test
    fun blankPairFailsClosed() {
        assertNull(PendingServicePinConfig.select("", ""))
        assertNull(PendingServicePinConfig.select("   ", "   "))
    }

    @Test
    fun malformedUuidFailsClosed() {
        assertNull(PendingServicePinConfig.select("not-a-uuid", keysetB64))
        assertNull(PendingServicePinConfig.select("12345", keysetB64))
    }

    @Test
    fun undecodableKeysetFailsClosed() {
        assertNull(PendingServicePinConfig.select(uuid, "!!!not-base64!!!"))
        assertNull(PendingServicePinConfig.select(uuid, ""))
    }
}
