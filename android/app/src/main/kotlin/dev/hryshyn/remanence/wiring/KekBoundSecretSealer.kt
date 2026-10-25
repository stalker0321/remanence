package dev.hryshyn.remanence.wiring

import com.google.crypto.tink.Aead
import dev.hryshyn.remanence.core.crypto.KekBoundary
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer

/**
 * Seals local sensitive material (fingerprint baselines) under the
 * non-exportable Android Keystore key bound to [alias] — a separate
 * fingerprint-storage key as required by docs/security.md section 11.
 */
class KekBoundSecretSealer(
    private val boundary: KekBoundary,
    private val alias: String,
) : SecretSealer {

    init {
        if (!boundary.hasKey(alias)) {
            boundary.createAes256GcmKey(alias)
        }
    }

    private val aead: Aead by lazy { boundary.loadKekAead(alias) }

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = timed { aead.encrypt(plaintext, aad) }

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray = timed { aead.decrypt(ciphertext, aad) }

    private inline fun <T> timed(block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            dev.hryshyn.remanence.ui.scan.ScanTiming.keystore(System.nanoTime() - started)
        }
    }

    companion object {
        /** Stable Keystore alias for the local fingerprint-storage AEAD key. */
        const val FINGERPRINT_SEALING_ALIAS: String = "remanence.fingerprint.v1"
    }
}
