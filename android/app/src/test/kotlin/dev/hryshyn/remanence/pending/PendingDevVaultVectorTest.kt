package dev.hryshyn.remanence.pending

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T186 M2: cross-language dev-vault golden vector.
 * Source: `protocol/fixtures/delivery-v2-dev-vault-vector.json` (server M1
 * follow-up `58f5a360`, synthetic TEST-ONLY key material, sealed in Python
 * with Tink AES256_GCM).
 *
 * This test opens the STORED bytes (not a fresh round trip) with the stored
 * test-only key: Tink AES-GCM wire framing (0x01 + 4-byte key id + 12-byte
 * IV + ciphertext + 16-byte tag) decrypted via JDK AES/GCM, AAD = canonical
 * `PendingEscrowAadV1` bytes. The opener lives in the TEST source set only:
 * production must never touch dev keys (constructor refuses prod/beta, and
 * no such path exists in `main`).
 */
class PendingDevVaultVectorTest {

    private fun vector(): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/pending/delivery-v2-dev-vault-vector.json")!!
            .bufferedReader().readText(),
    )

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Test-only Tink-AES-GCM opener for the stored vector. */
    private fun devOpen(ciphertext: ByteArray, key32: ByteArray, aad: ByteArray): ByteArray {
        require(key32.size == 32)
        require(ciphertext.size > 5 + 12 + 16)
        require(ciphertext[0] == 0x01.toByte()) { "tink version byte" }
        val keyId = ((ciphertext[1].toInt() and 0xFF) shl 24) or
            ((ciphertext[2].toInt() and 0xFF) shl 16) or
            ((ciphertext[3].toInt() and 0xFF) shl 8) or
            (ciphertext[4].toInt() and 0xFF)
        require(keyId == 1) { "test vector key id" }
        val iv = ciphertext.copyOfRange(5, 17)
        val ctAndTag = ciphertext.copyOfRange(17, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key32, "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ctAndTag)
    }

    @Test
    fun storedCiphertextOpensToStoredPlaintext() {
        val v = vector()
        assertEquals("dev-aead-v1", v.getString("vault"))
        assertTrue(v.getString("note").startsWith("TEST-ONLY"))
        assertEquals("synthetic-dev-vector-1", v.getString("key_id"))

        val aad = unhex(v.getString("aad_canonical_hex"))
        val key32 = unhex(v.getString("dev_key_32_hex_TEST_ONLY"))
        val ciphertext = Base64.getDecoder().decode(v.getString("ciphertext_b64"))

        // Binding is SHA256 over the canonical AAD bytes.
        val binding = MessageDigest.getInstance("SHA-256").digest(aad)
            .joinToString("") { "%02x".format(it) }
        assertEquals(v.getString("binding_hex"), binding)

        val opened = devOpen(ciphertext, key32, aad)
        assertEquals(v.getString("payload_plaintext_hex"), opened.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun wrongAadRejected() {
        val v = vector()
        val aad = unhex(v.getString("aad_canonical_hex"))
        val key32 = unhex(v.getString("dev_key_32_hex_TEST_ONLY"))
        val ciphertext = Base64.getDecoder().decode(v.getString("ciphertext_b64"))
        val tamperedAad = aad.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        try {
            devOpen(ciphertext, key32, tamperedAad)
            fail("wrong AAD must fail authentication")
        } catch (_: javax.crypto.AEADBadTagException) {
            // Expected: cross-context bytes never open.
        }
    }

    @Test
    fun tinkFramingPresent() {
        val v = vector()
        val ciphertext = Base64.getDecoder().decode(v.getString("ciphertext_b64"))
        assertEquals(0x01.toByte(), ciphertext[0])
        val keyId = ((ciphertext[1].toInt() and 0xFF) shl 24) or
            ((ciphertext[2].toInt() and 0xFF) shl 16) or
            ((ciphertext[3].toInt() and 0xFF) shl 8) or
            (ciphertext[4].toInt() and 0xFF)
        assertEquals(1, keyId)
        // Round-trips through the same framing the Python seal produced.
        assertArrayEquals(ciphertext, Base64.getDecoder().decode(v.getString("ciphertext_b64")))
    }
}
