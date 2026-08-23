package postmark.core.crypto

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM contract tests for every [KekBoundary]. The Android Keystore
 * implementation must satisfy the same contract when a device or emulator is
 * available (instrumented roundtrip remains explicitly unverified here).
 */
class KekBoundaryContractTest {

    private class InMemoryKekBoundary : KekBoundary {

        override fun hasKey(alias: String): Boolean = store.containsKey(alias)

        override fun createAes256GcmKey(alias: String) {
            if (store.containsKey(alias)) {
                throw GeneralSecurityException("KEK already exists for alias; refusing replacement")
            }
            val generator = KeyGenerator.getInstance("AES")
            generator.init(256)
            store[alias] = generator.generateKey()
        }

        override fun loadKekAead(alias: String): Aead {
            val key = store[alias] ?: throw GeneralSecurityException("No KEK stored for alias")
            return SoftwareGcmAead(key)
        }

        private class SoftwareGcmAead(private val key: SecretKey) : Aead {
            private val random = SecureRandom()

            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                val iv = ByteArray(IV_SIZE).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                if (associatedData != null && associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
                return iv + cipher.doFinal(plaintext)
            }

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (ciphertext.size <= IV_SIZE) throw GeneralSecurityException("ciphertext too short")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_SIZE))
                if (associatedData != null && associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
                return cipher.doFinal(ciphertext, IV_SIZE, ciphertext.size - IV_SIZE)
            }

            private companion object {
                const val IV_SIZE = 12
                const val TAG_BITS = 128
            }
        }
    }

    @Test
    fun freshAliasIsAbsentThenCreatedOnce() {
        val boundary: KekBoundary = InMemoryKekBoundary()
        val alias = "postmark.kek.contract"
        assertFalse(boundary.hasKey(alias))
        boundary.createAes256GcmKey(alias)
        assertTrue(boundary.hasKey(alias))
        assertFailsWith<GeneralSecurityException> { boundary.createAes256GcmKey(alias) }
    }

    @Test
    fun loadedKekAeadRoundTripsAndRejectsWrongAssociatedData() {
        val boundary: KekBoundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("postmark.kek.roundtrip")
        val aead = boundary.loadKekAead("postmark.kek.roundtrip")
        val plaintext = "wrapped keyset bytes".toByteArray(Charsets.UTF_8)
        val aad = "postmark/kek/v1".toByteArray(Charsets.UTF_8)
        val ciphertext = aead.encrypt(plaintext, aad)
        assertContentEquals(plaintext, aead.decrypt(ciphertext, aad))
        assertFailsWith<GeneralSecurityException> { aead.decrypt(ciphertext, "wrong".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun loadingMissingAliasFailsClosed() {
        val boundary: KekBoundary = InMemoryKekBoundary()
        assertFailsWith<GeneralSecurityException> { boundary.loadKekAead("postmark.kek.missing") }
    }

    @Test
    fun keySurvivesNewBoundaryInstanceLikeProcessRestart() {
        val first: KekBoundary = InMemoryKekBoundary()
        first.createAes256GcmKey("postmark.kek.restart")
        val ciphertext = first.loadKekAead("postmark.kek.restart").encrypt("payload".toByteArray(), null)

        val second: KekBoundary = InMemoryKekBoundary()
        assertTrue(second.hasKey("postmark.kek.restart"))
        assertContentEquals("payload".toByteArray(), second.loadKekAead("postmark.kek.restart").decrypt(ciphertext, null))
    }

    private companion object {
        val store = ConcurrentHashMap<String, SecretKey>()
    }
}
