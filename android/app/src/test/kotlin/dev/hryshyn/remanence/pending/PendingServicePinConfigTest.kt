package dev.hryshyn.remanence.pending

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M12b/c: out-of-band pin selection — a REAL Ed25519 public
 * keyset is selected; junk bytes, wrong-algorithm keysets, private
 * keysets and non-canonical UUIDs all fail closed to null. A pin that
 * cannot authenticate a signer must never reach publication.
 */
class PendingServicePinConfigTest {

    private val uuid = "a3618c4c-fc28-4847-9f4c-0681e9b531bc"

    private fun ed25519PublicB64(): String {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return Base64.getEncoder().encodeToString(
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signing.publicKeysetHandle),
        )
    }

    @Test
    fun realPublicKeysetSelected() {
        val pin = PendingServicePinConfig.select(uuid, ed25519PublicB64())
        assertEquals(uuid, pin?.deploymentUuid)
        assertTrue(PendingServicePinConfig.usable(pin))
        // And the handle behind it actually verifies.
        assertTrue(PendingServicePinConfig.parseVerificationKeyset(pin!!.publicKeysetB64) != null)
    }

    @Test
    fun blankPairFailsClosed() {
        assertNull(PendingServicePinConfig.select("", ""))
        assertNull(PendingServicePinConfig.select("   ", "   "))
        assertFalse(PendingServicePinConfig.usable(null))
        assertFalse(
            PendingServicePinConfig.usable(PendingServicePin("", "")),
        )
    }

    @Test
    fun malformedUuidFailsClosed() {
        assertNull(PendingServicePinConfig.select("not-a-uuid", ed25519PublicB64()))
        assertNull(PendingServicePinConfig.select("12345", ed25519PublicB64()))
    }

    @Test
    fun nonCanonicalUuidFailsClosed() {
        // Strict canonical form: fromString aliases (uppercase, urn,
        // braces) must never select a deployment pin.
        assertNull(PendingServicePinConfig.select(uuid.uppercase(), ed25519PublicB64()))
        assertNull(PendingServicePinConfig.select("urn:uuid:$uuid", ed25519PublicB64()))
        assertNull(PendingServicePinConfig.select("{$uuid}", ed25519PublicB64()))
    }

    @Test
    fun junkValidBase64FailsClosed() {
        // Decodes as base64 but is not a keyset at all.
        val junk = Base64.getEncoder().encodeToString("hello world, not a keyset".toByteArray())
        assertNull(PendingServicePinConfig.select(uuid, junk))
        assertNull(PendingServicePinConfig.parseVerificationKeyset(junk))
        assertFalse(
            PendingServicePinConfig.usable(PendingServicePin(uuid, junk)),
        )
    }

    @Test
    fun wrongAlgorithmKeysetFailsClosed() {
        TinkPrimitives.ensureRegistered()
        // Asymmetric but not Ed25519: public shape, foreign algorithm.
        val ecdsa = KeysetHandle.generateNew(KeyTemplates.get("ECDSA_P256"))
        val ecdsaPublicB64 = Base64.getEncoder().encodeToString(
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(ecdsa.publicKeysetHandle),
        )
        assertNull(PendingServicePinConfig.select(uuid, ecdsaPublicB64))
        assertNull(PendingServicePinConfig.parseVerificationKeyset(ecdsaPublicB64))
    }

    @Test
    fun privateKeysetFailsClosed() {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val privateB64 = Base64.getEncoder().encodeToString(
            TinkProtoKeysetFormat.serializeKeyset(
                signing,
                InsecureSecretKeyAccess.get(),
            ),
        )
        // Secret material is refused even though the algorithm is right.
        assertNull(PendingServicePinConfig.select(uuid, privateB64))
        assertNull(PendingServicePinConfig.parseVerificationKeyset(privateB64))
    }
}
