package postmark.core.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import java.security.GeneralSecurityException

/**
 * The signed publish object as transported by REST (docs/protocol.md
 * section 3): deterministic statement bytes plus their Ed25519 signature.
 */
class SignedPublishStatement(
    val deterministicStatementBytes: ByteArray,
    val signature: ByteArray,
)

/**
 * Binds the complete declared ciphertext set to the sender signing key
 * (docs/security.md section 6.4). Signature input is exactly
 * `"postmark/publish/v1" || deterministic_statement_bytes` over the frozen
 * protobuf bytes produced by PublishStatementBuilder — no re-serialization,
 * no canonicalization, no alternate domains.
 *
 * The REST signature is the raw Tink output with the TINK output prefix and
 * is exactly [SIGNATURE_LENGTH] bytes: `0x01 || key_id(4B big-endian) ||
 * r||s(64B)` (ADR-007). There is no prefix stripping, no RAW signing, and no
 * fallback decode path; anything else fails closed before verification.
 */
class PublishStatementSigner {

    fun sign(signingPrivateHandle: KeysetHandle, deterministicStatementBytes: ByteArray): SignedPublishStatement {
        require(deterministicStatementBytes.isNotEmpty()) { "statement bytes are empty" }
        val signer = signingPrivateHandle.getPrimitive(PublicKeySign::class.java)
        val signature = signer.sign(signatureInput(deterministicStatementBytes))
        validateWireFormat(signature, signingPrivateHandle)
        return SignedPublishStatement(
            deterministicStatementBytes = deterministicStatementBytes.copyOf(),
            signature = signature,
        )
    }

    /** Fails closed on any mismatch between statement bytes and signature. */
    fun verify(verifyingHandle: KeysetHandle, signed: SignedPublishStatement) {
        if (signed.deterministicStatementBytes.isEmpty()) {
            throw GeneralSecurityException("statement bytes are empty")
        }
        // Structural wire-format guard before any primitive work; Tink remains
        // the cryptographic authority for key-id matching and verification.
        validateWireFormat(signed.signature, verifyingHandle)
        val verifier = verifyingHandle.getPrimitive(PublicKeyVerify::class.java)
        verifier.verify(signed.signature, signatureInput(signed.deterministicStatementBytes))
    }

    private fun validateWireFormat(signature: ByteArray, handle: KeysetHandle) {
        val expectedKeyId = handle.keysetInfo.primaryKeyId
        val embeddedKeyId = ((signature[1].toInt() and 0xFF) shl 24) or
            ((signature[2].toInt() and 0xFF) shl 16) or
            ((signature[3].toInt() and 0xFF) shl 8) or
            (signature[4].toInt() and 0xFF)
        if (signature.size != SIGNATURE_LENGTH ||
            signature[0].toInt() != TINK_PREFIX_TYPE_BYTE.toInt() ||
            embeddedKeyId != expectedKeyId
        ) {
            throw GeneralSecurityException("signature is not protocol-v1 69-byte TINK-prefixed Ed25519")
        }
    }

    private fun signatureInput(statementBytes: ByteArray): ByteArray =
        DOMAIN_PREFIX.toByteArray(Charsets.US_ASCII) + statementBytes

    companion object {
        const val DOMAIN_PREFIX: String = "postmark/publish/v1"

        /** REST signature length: 1 prefix byte + 4 key-id bytes + 64-byte Ed25519. */
        const val SIGNATURE_LENGTH: Int = 69

        /** First byte of a TINK output-prefix signature. */
        const val TINK_PREFIX_TYPE_BYTE: Byte = 0x01
    }
}
