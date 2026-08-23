package postmark.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.security.GeneralSecurityException

/**
 * [KekBoundary] backed by the non-exportable AES-256-GCM Android Keystore key
 * (purposes: encrypt/decrypt only, no export path). StrongBox/TEE selection is
 * decided by the platform when the key is generated; the boundary never
 * exposes the resulting key material.
 */
class AndroidKeystoreKekBoundary : KekBoundary {

    override fun hasKey(alias: String): Boolean = AndroidKeystore.hasKey(alias)

    override fun createAes256GcmKey(alias: String) {
        if (AndroidKeystore.hasKey(alias)) {
            throw GeneralSecurityException("KEK already exists for alias; refusing replacement")
        }
        AndroidKeystore.generateNewAes256GcmKey(alias)
    }

    override fun loadKekAead(alias: String): Aead {
        if (!AndroidKeystore.hasKey(alias)) {
            throw GeneralSecurityException("No KEK stored for alias")
        }
        return AndroidKeystore.getAead(alias)
    }
}
