package postmark.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat

/**
 * Generates the two independent account identity keysets required by
 * docs/security.md section 4: HPKE recipient encryption and Ed25519 signing.
 * The private handles stay inside the crypto module; only serialized
 * public-only keysets leave through [AccountIdentity].
 */
class AccountIdentityGenerator {

    class AccountIdentity(
        val encryptionPrivateHandle: KeysetHandle,
        val signingPrivateHandle: KeysetHandle,
        val encryptionPublicKeyset: ByteArray,
        val signingPublicKeyset: ByteArray,
    )

    fun generate(): AccountIdentity {
        TinkPrimitives.ensureRegistered()
        val encryption = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val signing = KeysetHandle.generateNew(KeyTemplates.get(ED25519_TEMPLATE))
        return AccountIdentity(
            encryptionPrivateHandle = encryption,
            signingPrivateHandle = signing,
            encryptionPublicKeyset = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encryption.publicKeysetHandle),
            signingPublicKeyset = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signing.publicKeysetHandle),
        )
    }

    companion object {
        /** Exact signing suite required by docs/security.md section 4. */
        const val ED25519_TEMPLATE: String = "ED25519"

        const val HPKE_KEY_TYPE_URL: String = "type.googleapis.com/google.crypto.tink.HpkePrivateKey"
        const val ED25519_KEY_TYPE_URL: String = "type.googleapis.com/google.crypto.tink.Ed25519PrivateKey"
    }
}
