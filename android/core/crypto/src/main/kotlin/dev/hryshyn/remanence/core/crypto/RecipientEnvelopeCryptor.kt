package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import dev.hryshyn.remanence.core.model.CryptoContextEncoder
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput

/**
 * Seals and opens the single recipient envelope (docs/security.md section 6.5)
 * with the account HPKE keysets (`DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM`,
 * TINK output prefix) registered by [TinkPrimitives]. The HPKE context_info is
 * exactly the canonical `postmark/envelope/v1 || 0x00 || deterministic
 * RecipientEnvelopeContext protobuf` bytes produced by
 * [CryptoContextEncoder.recipientEnvelopeInfo]; it is rebuilt from typed inputs
 * before any primitive runs, so any field mismatch fails closed inside HPKE.
 *
 * Ciphertext wire bytes are the exact Tink output including its 5-byte TINK
 * prefix: `0x01 || key_id(4B big-endian) || ephemeral_public_key || iv ||
 * ciphertext || tag`. There is no stripping, no RAW variant, and no fallback
 * decode path; anything else fails closed. HPKE sealing is randomized, so two
 * seals of the same plaintext differ while every seal opens to that plaintext.
 */
class RecipientEnvelopeCryptor {

    fun seal(
        recipientEncryptionPublicKeyset: KeysetHandle,
        context: RecipientEnvelopeContextInput,
        envelopePlaintext: ByteArray,
    ): ByteArray {
        require(envelopePlaintext.isNotEmpty()) { "envelope plaintext is empty" }
        val hybrid = recipientEncryptionPublicKeyset.getPrimitive(HybridEncrypt::class.java)
        val ciphertext = hybrid.encrypt(envelopePlaintext, contextInfo(context))
        validateWireFormat(ciphertext, recipientEncryptionPublicKeyset)
        return ciphertext
    }

    fun open(
        recipientEncryptionPrivateKeyset: KeysetHandle,
        context: RecipientEnvelopeContextInput,
        ciphertext: ByteArray,
    ): ByteArray {
        // Structural wire-format guard before any primitive work; Tink remains
        // the cryptographic authority for key-id matching and decryption.
        validateWireFormat(ciphertext, recipientEncryptionPrivateKeyset)
        val hybrid = recipientEncryptionPrivateKeyset.getPrimitive(HybridDecrypt::class.java)
        return try {
            hybrid.decrypt(ciphertext, contextInfo(context))
        } catch (failure: GeneralSecurityException) {
            throw GeneralSecurityException("recipient envelope open failed")
        }
    }

    private fun contextInfo(context: RecipientEnvelopeContextInput): ByteArray =
        CryptoContextEncoder.recipientEnvelopeInfo(context).toByteArray()

    private fun validateWireFormat(ciphertext: ByteArray, handle: KeysetHandle) {
        val embeddedKeyId = if (ciphertext.size >= WIRE_HEADER_BYTES) {
            ((ciphertext[1].toInt() and 0xFF) shl 24) or
                ((ciphertext[2].toInt() and 0xFF) shl 16) or
                ((ciphertext[3].toInt() and 0xFF) shl 8) or
                (ciphertext[4].toInt() and 0xFF)
        } else {
            -1
        }
        if (ciphertext.size < MIN_CIPHERTEXT_BYTES ||
            ciphertext[0].toInt() != TINK_PREFIX_TYPE_BYTE ||
            embeddedKeyId != handle.keysetInfo.primaryKeyId
        ) {
            throw GeneralSecurityException("ciphertext is not protocol-v1 TINK-prefixed HPKE")
        }
    }

    companion object {
        /** TINK output-prefix header: prefix-type byte plus 4 big-endian key-id bytes. */
        const val WIRE_HEADER_BYTES: Int = 5

        /** First byte of a TINK output-prefix ciphertext. */
        const val TINK_PREFIX_TYPE_BYTE: Int = 0x01

        /**
         * Absolute floor for a v1 HPKE ciphertext: 5-byte TINK header plus the
         * X25519 ephemeral public key (32B) plus the AES-256-GCM tag (16B).
         */
        const val MIN_CIPHERTEXT_BYTES: Int = WIRE_HEADER_BYTES + 32 + 16
    }
}
