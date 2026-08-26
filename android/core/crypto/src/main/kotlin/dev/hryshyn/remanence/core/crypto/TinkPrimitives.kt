package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig

/**
 * Single entry point that registers every Tink primitive Remanence uses:
 * AEAD (capsule artifacts), hybrid encryption (recipient envelopes), and
 * digital signatures (publish statements). Call [ensureRegistered] once before
 * any primitive is created; repeated calls are no-ops.
 */
object TinkPrimitives {

    /** Exact HPKE template required by docs/security.md for recipient envelopes. */
    const val HPKE_TEMPLATE: String = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

    @Volatile
    private var registered: Boolean = false

    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            AeadConfig.register()
            HybridConfig.register()
            SignatureConfig.register()
            registered = true
        }
    }
}
