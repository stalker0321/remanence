package dev.hryshyn.rv01probe.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A probe-only authenticated package. It is not a Remanence recovery format.
 */
data class AuthenticatedTestPackage(
    val version: Int,
    val context: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    fun encode(): ByteArray {
        val contextBytes = context.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(
            4 + 4 + 4 + contextBytes.size + 4 + nonce.size + 4 + ciphertext.size,
        ).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(MAGIC)
            putInt(version)
            putInt(contextBytes.size)
            put(contextBytes)
            putInt(nonce.size)
            put(nonce)
            putInt(ciphertext.size)
            put(ciphertext)
        }.array()
    }

    companion object {
        private const val MAGIC = 0x52563031
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private const val MAX_CONTEXT_BYTES = 128
        private const val MAX_CIPHERTEXT_BYTES = 64 * 1024

        fun seal(
            canary: ByteArray,
            unwrapMaterial: ByteArray,
            context: TestPackageContext,
            random: SecureRandom = SecureRandom(),
        ): AuthenticatedTestPackage? {
            if (canary.size != KEY_BYTES || unwrapMaterial.size != KEY_BYTES) return null
            val contextBytes = context.purpose.toByteArray(StandardCharsets.UTF_8)
            if (context.version < 1 || contextBytes.isEmpty() || contextBytes.size > MAX_CONTEXT_BYTES) {
                return null
            }
            return try {
                val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(unwrapMaterial, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                cipher.updateAAD(aad(context))
                AuthenticatedTestPackage(
                    version = context.version,
                    context = context.purpose,
                    nonce = nonce,
                    ciphertext = cipher.doFinal(canary),
                )
            } catch (_: GeneralSecurityException) {
                null
            }
        }

        fun open(
            encoded: ByteArray,
            unwrapMaterial: ByteArray,
            expectedContext: TestPackageContext,
        ): ByteArray? {
            if (unwrapMaterial.size != KEY_BYTES) return null
            val parsed = decode(encoded) ?: return null
            if (parsed.version != expectedContext.version || parsed.context != expectedContext.purpose) {
                return null
            }
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(unwrapMaterial, "AES"),
                    GCMParameterSpec(TAG_BITS, parsed.nonce),
                )
                cipher.updateAAD(aad(expectedContext))
                cipher.doFinal(parsed.ciphertext)
            } catch (_: GeneralSecurityException) {
                null
            }
        }

        fun decode(encoded: ByteArray): AuthenticatedTestPackage? {
            return try {
                val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
                if (buffer.remaining() < 16 || buffer.int != MAGIC) return null
                val version = buffer.int
                if (version < 1) return null
                val contextLength = buffer.int
                if (contextLength <= 0 || contextLength > MAX_CONTEXT_BYTES || buffer.remaining() < contextLength) {
                    return null
                }
                val contextBytes = ByteArray(contextLength).also(buffer::get)
                val nonceLength = buffer.int
                if (nonceLength != NONCE_BYTES || buffer.remaining() < nonceLength + 4) return null
                val nonce = ByteArray(nonceLength).also(buffer::get)
                val ciphertextLength = buffer.int
                if (
                    ciphertextLength < TAG_BITS / 8 ||
                    ciphertextLength > MAX_CIPHERTEXT_BYTES ||
                    buffer.remaining() != ciphertextLength
                ) {
                    return null
                }
                val ciphertext = ByteArray(ciphertextLength).also(buffer::get)
                AuthenticatedTestPackage(
                    version = version,
                    context = contextBytes.toString(StandardCharsets.UTF_8),
                    nonce = nonce,
                    ciphertext = ciphertext,
                )
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun aad(context: TestPackageContext): ByteArray =
            "rv01/authenticated-test-package/v${context.version}/${context.purpose}"
                .toByteArray(StandardCharsets.UTF_8)
    }
}
