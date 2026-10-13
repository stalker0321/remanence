package dev.hryshyn.remanence.pending

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * T186 M1: real v2 crypto golden/wrong-context acceptance against the frozen
 * T185 M1 fixtures (`delivery-v2-canonical.json`, `delivery-v2-crypto.json`,
 * synthetic data only). No invented wire shapes: every byte comes from the
 * frozen files. Pure-Kotlin JSON (no Android stub) so plain-JVM tests run
 * without Robolectric.
 */
class PendingV2DomainsTest {

    private val fixtures = Json { ignoreUnknownKeys = true }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun canonical(): JsonObject = fixtures.parseToJsonElement(
        javaClass.getResourceAsStream("/pending/delivery-v2-canonical.json")!!
            .bufferedReader().readText(),
    ).jsonObject

    private fun crypto(): JsonObject = fixtures.parseToJsonElement(
        javaClass.getResourceAsStream("/pending/delivery-v2-crypto.json")!!
            .bufferedReader().readText(),
    ).jsonObject

    private fun str(o: JsonObject, key: String): String =
        o.getValue(key).jsonPrimitive.content

    @Test
    fun frozenDomainStringsMatchContract() {
        val domains = canonical().getValue("domains").jsonObject
        assertEquals(PendingV2Domains.DELIVERY_ADDRESS, str(domains, "DELIVERY_ADDRESS_DOMAIN"))
        assertEquals(PendingV2Domains.DELIVERY_KEY, str(domains, "DELIVERY_KEY_DOMAIN"))
        assertEquals(PendingV2Domains.ARTIFACT_V2, str(domains, "ARTIFACT_V2_DOMAIN"))
        assertEquals(PendingV2Domains.PENDING_PUBLISH_V2, str(domains, "PENDING_PUBLISH_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIM_BINDING_V1, str(domains, "CLAIM_BINDING_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIMED_ENVELOPE_V2, str(domains, "CLAIMED_ENVELOPE_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIM_CHALLENGE_V1, str(domains, "CLAIM_CHALLENGE_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIM_INTENT_V2, str(domains, "CLAIM_INTENT_DOMAIN"))
    }

    @Test
    fun addressCommitmentGolden() {
        val f = canonical()
        val got = hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_ADDRESS, unhex(str(f, "address_opening_hex")))))
        assertEquals(str(f, "address_commitment_d_hex"), got)
    }

    @Test
    fun keyCommitmentGolden() {
        val f = canonical()
        val got = hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_KEY, unhex(str(f, "key_commitment_input_hex")))))
        assertEquals(str(f, "key_commitment_kc_hex"), got)
    }

    @Test
    fun publishDomainInputGolden() {
        val f = canonical()
        val c = crypto()
        val expected = PendingV2Domains.frame(PendingV2Domains.PENDING_PUBLISH_V2, unhex(str(f, "pending_publish_statement_s_hex")))
        assertArrayEquals(expected, unhex(str(c, "pending_publish_domain_input_hex")))
    }

    @Test
    fun artifactAadGolden() {
        val f = canonical()
        val c = crypto()
        val expected = PendingV2Domains.frame(PendingV2Domains.ARTIFACT_V2, unhex(str(f, "artifact_aad_v2_hex")))
        assertArrayEquals(expected, unhex(str(c, "aead_aad_hex")))
    }

    @Test
    fun wrongDomainAndContextRejected() {
        val f = canonical()
        val dHex = str(f, "address_commitment_d_hex")
        val kcHex = str(f, "key_commitment_kc_hex")
        // Cross-domain bytes must never verify: each commitment under the
        // wrong domain differs from the frozen digest.
        assertFalse(hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_KEY, unhex(str(f, "address_opening_hex"))))) == dHex)
        assertFalse(hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_ADDRESS, unhex(str(f, "key_commitment_input_hex"))))) == kcHex)
        // v1-style unframed digest also differs (framing is mandatory).
        assertFalse(hex(sha256(unhex(str(f, "address_opening_hex")))) == dHex)
    }

    @Test
    fun publishSignatureFramingLength() {
        // 69-byte Tink Ed25519 framing retained for v2 publish (contract §2).
        val sig = unhex(str(crypto(), "pending_publish_signature_69_hex"))
        assertEquals(69, sig.size)
    }

    @Test
    fun claimedEnvelopeHpkeInfoPrefix() {
        val info = unhex(str(canonical(), "claimed_envelope_hpke_info_hex"))
        val prefix = (PendingV2Domains.CLAIMED_ENVELOPE_V2.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00))
        assertArrayEquals(prefix, info.copyOf(prefix.size))
    }
}
