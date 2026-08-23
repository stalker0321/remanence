package app.postmark.memory.auth

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** JVM stand-in for the Android Keystore KEK store (process-wide key map). */
class SoftwareKekBoundary : postmark.core.crypto.KekBoundary {

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
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            if (associatedData != null && associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
            if (ciphertext.size <= 12) throw GeneralSecurityException("ciphertext too short")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
            if (associatedData != null && associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
        }
    }

    private companion object {
        val store = ConcurrentHashMap<String, SecretKey>()
    }
}
