package dev.hryshyn.rv01probe.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Probe-only RVP1 authenticated sidecar. It is not a Remanence package or
 * recovery mechanism, and its claimed context is never trusted for AAD.
 */
object ProbeSidecar {
    const val MAX_SIZE = 1024
    const val TOTAL_BYTES = 121
    const val CANARY_BYTES = 32
    const val KEY_BYTES = 32

    private const val MAGIC = 0x52565031 // ASCII RVP1
    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val CIPHERTEXT_BYTES = CANARY_BYTES + TAG_BYTES
    private const val TAG_BITS = TAG_BYTES * 8

    /** Creates exactly one bounded RVP1 sidecar, or null for invalid inputs. */
    fun seal(
        canary: ByteArray,
        unwrapMaterial: ByteArray,
        expectedContext: ExpectedContext,
        random: SecureRandom = SecureRandom(),
    ): ByteArray? {
        if (canary.size != CANARY_BYTES || unwrapMaterial.size != KEY_BYTES) return null

        val contextBytes = expectedContext.canonicalBytes()
        val keyCopy = unwrapMaterial.copyOf()
        val canaryCopy = canary.copyOf()
        val nonce = ByteArray(NONCE_BYTES)
        var ciphertext: ByteArray? = null
        return try {
            random.nextBytes(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyCopy, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(contextBytes)
            ciphertext = cipher.doFinal(canaryCopy)
            if (ciphertext!!.size != CIPHERTEXT_BYTES) return null

            ByteBuffer.allocate(TOTAL_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(MAGIC)
                putShort(VERSION.toShort())
                putShort(ExpectedContext.CANONICAL_BYTES.toShort())
                put(contextBytes)
                put(NONCE_BYTES.toByte())
                put(nonce)
                putShort(CIPHERTEXT_BYTES.toShort())
                put(ciphertext!!)
            }.array()
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            contextBytes.fill(0)
            keyCopy.fill(0)
            canaryCopy.fill(0)
            nonce.fill(0)
            ciphertext?.fill(0)
        }
    }

    /**
     * Opens only a valid RVP1 sidecar with the independently supplied context.
     * Rejection returns null and never returns plaintext.
     */
    fun open(
        encoded: ByteArray,
        unwrapMaterial: ByteArray,
        expectedContext: ExpectedContext,
    ): ByteArray? {
        if (encoded.size > MAX_SIZE || unwrapMaterial.size != KEY_BYTES) return null

        val expectedBytes = expectedContext.canonicalBytes()
        var parsed: ParsedSidecar? = null
        var plaintext: ByteArray? = null
        var handedOff = false
        return try {
            parsed = parse(encoded) ?: return null
            if (!parsed!!.claimedContext.contentEquals(expectedBytes)) return null

            val keyCopy = unwrapMaterial.copyOf()
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyCopy, "AES"),
                    GCMParameterSpec(TAG_BITS, parsed!!.nonce),
                )
                cipher.updateAAD(expectedBytes)
                plaintext = cipher.doFinal(parsed!!.ciphertext)
            } finally {
                keyCopy.fill(0)
            }

            val opened = plaintext ?: return null
            if (opened.size != CANARY_BYTES) return null
            handedOff = true
            opened
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            expectedBytes.fill(0)
            parsed?.wipe()
            if (!handedOff) plaintext?.fill(0)
        }
    }

    private data class ParsedSidecar(
        val claimedContext: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    ) {
        fun wipe() {
            claimedContext.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun parse(encoded: ByteArray): ParsedSidecar? {
        if (encoded.size > MAX_SIZE || encoded.size != TOTAL_BYTES) return null

        var claimedContext: ByteArray? = null
        var nonce: ByteArray? = null
        var ciphertext: ByteArray? = null
        var result: ParsedSidecar? = null
        try {
            val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != MAGIC) return null
            if ((buffer.short.toInt() and 0xffff) != VERSION) return null
            if ((buffer.short.toInt() and 0xffff) != ExpectedContext.CANONICAL_BYTES) return null

            claimedContext = ByteArray(ExpectedContext.CANONICAL_BYTES).also(buffer::get)
            if (!ExpectedContext.isCanonical(claimedContext!!)) return null

            if ((buffer.get().toInt() and 0xff) != NONCE_BYTES) return null
            nonce = ByteArray(NONCE_BYTES).also(buffer::get)
            if ((buffer.short.toInt() and 0xffff) != CIPHERTEXT_BYTES) return null
            ciphertext = ByteArray(CIPHERTEXT_BYTES).also(buffer::get)
            if (buffer.hasRemaining()) return null

            result = ParsedSidecar(claimedContext!!, nonce!!, ciphertext!!)
            return result
        } catch (_: RuntimeException) {
            return null
        } finally {
            if (result == null) {
                claimedContext?.fill(0)
                nonce?.fill(0)
                ciphertext?.fill(0)
            }
        }
    }
}
