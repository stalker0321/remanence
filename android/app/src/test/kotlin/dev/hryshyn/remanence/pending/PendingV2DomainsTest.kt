package dev.hryshyn.remanence.pending

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * T186 M1: real v2 crypto golden/wrong-context acceptance against the frozen
 * T185 M1 fixtures (`delivery-v2-canonical.json`, `delivery-v2-crypto.json`,
 * server commit `175b0090`, synthetic data only). No invented wire shapes:
 * every byte comes from the frozen files.
 */
class PendingV2DomainsTest {

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

    private fun canonical(): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/pending/delivery-v2-canonical.json")!!
            .bufferedReader().readText(),
    )

    private fun crypto(): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/pending/delivery-v2-crypto.json")!!
            .bufferedReader().readText(),
    )

    @Test
    fun frozenDomainStringsMatchContract() {
        val domains = canonical().getJSONObject("domains")
        assertEquals(PendingV2Domains.DELIVERY_ADDRESS, domains.getString("DELIVERY_ADDRESS_DOMAIN"))
        assertEquals(PendingV2Domains.DELIVERY_KEY, domains.getString("DELIVERY_KEY_DOMAIN"))
        assertEquals(PendingV2Domains.ARTIFACT_V2, domains.getString("ARTIFACT_V2_DOMAIN"))
        assertEquals(PendingV2Domains.PENDING_PUBLISH_V2, domains.getString("PENDING_PUBLISH_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIM_BINDING_V1, domains.getString("CLAIM_BINDING_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIMED_ENVELOPE_V2, domains.getString("CLAIMED_ENVELOPE_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIM_CHALLENGE_V1, domains.getString("CLAIM_CHALLENGE_DOMAIN"))
        assertEquals(PendingV2Domains.CLAIM_INTENT_V2, domains.getString("CLAIM_INTENT_DOMAIN"))
    }

    @Test
    fun addressCommitmentGolden() {
        val f = canonical()
        val got = hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_ADDRESS, unhex(f.getString("address_opening_hex")))))
        assertEquals(f.getString("address_commitment_d_hex"), got)
    }

    @Test
    fun keyCommitmentGolden() {
        val f = canonical()
        val got = hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_KEY, unhex(f.getString("key_commitment_input_hex")))))
        assertEquals(f.getString("key_commitment_kc_hex"), got)
    }

    @Test
    fun publishDomainInputGolden() {
        val f = canonical()
        val c = crypto()
        val expected = PendingV2Domains.frame(PendingV2Domains.PENDING_PUBLISH_V2, unhex(f.getString("pending_publish_statement_s_hex")))
        assertArrayEquals(expected, unhex(c.getString("pending_publish_domain_input_hex")))
    }

    @Test
    fun artifactAadGolden() {
        val f = canonical()
        val c = crypto()
        val expected = PendingV2Domains.frame(PendingV2Domains.ARTIFACT_V2, unhex(f.getString("artifact_aad_v2_hex")))
        assertArrayEquals(expected, unhex(c.getString("aead_aad_hex")))
    }

    @Test
    fun wrongDomainAndContextRejected() {
        val f = canonical()
        val dHex = f.getString("address_commitment_d_hex")
        val kcHex = f.getString("key_commitment_kc_hex")
        // Cross-domain bytes must never verify: each commitment under the
        // wrong domain differs from the frozen digest.
        assertFalse(hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_KEY, unhex(f.getString("address_opening_hex"))))) == dHex)
        assertFalse(hex(sha256(PendingV2Domains.frame(PendingV2Domains.DELIVERY_ADDRESS, unhex(f.getString("key_commitment_input_hex"))))) == kcHex)
        // v1-style unframed digest also differs (framing is mandatory).
        assertFalse(hex(sha256(unhex(f.getString("address_opening_hex")))) == dHex)
    }

    @Test
    fun publishSignatureFramingLength() {
        // 69-byte Tink Ed25519 framing retained for v2 publish (contract §2).
        val sig = unhex(crypto().getString("pending_publish_signature_69_hex"))
        assertEquals(69, sig.size)
    }

    @Test
    fun claimedEnvelopeHpkeInfoPrefix() {
        val info = unhex(canonical().getString("claimed_envelope_hpke_info_hex"))
        val prefix = (PendingV2Domains.CLAIMED_ENVELOPE_V2.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00))
        assertArrayEquals(prefix, info.copyOf(prefix.size))
    }
}
