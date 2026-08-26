package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * JVM fake standing in for the Android Keystore KEK store. Keys persist in a
 * process-wide map so separate instances simulate app restarts without ever
 * exposing raw key material to callers.
 */
class InMemoryKekBoundary : KekBoundary {

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

    private companion object {
        val store = ConcurrentHashMap<String, SecretKey>()
    }
}
