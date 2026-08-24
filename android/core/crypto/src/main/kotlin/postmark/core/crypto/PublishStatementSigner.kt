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
 */
class PublishStatementSigner {

    fun sign(signingPrivateHandle: KeysetHandle, deterministicStatementBytes: ByteArray): SignedPublishStatement {
        require(deterministicStatementBytes.isNotEmpty()) { "statement bytes are empty" }
        val signer = signingPrivateHandle.getPrimitive(PublicKeySign::class.java)
        val signature = signer.sign(signatureInput(deterministicStatementBytes))
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
        val verifier = verifyingHandle.getPrimitive(PublicKeyVerify::class.java)
        verifier.verify(signed.signature, signatureInput(signed.deterministicStatementBytes))
    }

    private fun signatureInput(statementBytes: ByteArray): ByteArray =
        DOMAIN_PREFIX.toByteArray(Charsets.US_ASCII) + statementBytes

    companion object {
        const val DOMAIN_PREFIX: String = "postmark/publish/v1"
    }
}
