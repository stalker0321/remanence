package postmark.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.security.GeneralSecurityException

/**
 * Wraps/unwrap private Tink keysets through the non-exportable KEK.
 *
 * The keyset is serialized only inside the crypto module, encrypted with the
 * Keystore KEK (fresh 96-bit GCM nonce) and versioned domain-separated AAD,
 * and returned as a persistable [WrappedKeysetRecord]. Unwrapping fails
 * closed on any alias/version mismatch or ciphertext tampering.
 */
class KeysetKekWrapper(private val kekBoundary: KekBoundary) {

    fun wrap(alias: String, keysetHandle: KeysetHandle): WrappedKeysetRecord {
        val aead = kekBoundary.loadKekAead(alias)
        val serialized = TinkProtoKeysetFormat.serializeKeyset(keysetHandle, InsecureSecretKeyAccess.get())
        val sealed = aead.encrypt(serialized, associatedData(WrappedKeysetRecord.FORMAT_VERSION_1, alias))
        if (sealed.size <= NONCE_SIZE) {
            throw GeneralSecurityException("sealed keyset too short")
        }
        return WrappedKeysetRecord.create(
            alias = alias,
            nonce = sealed.copyOfRange(0, NONCE_SIZE),
            wrappedKeyset = sealed.copyOfRange(NONCE_SIZE, sealed.size),
        )
    }

    fun unwrap(record: WrappedKeysetRecord): KeysetHandle {
        val aead = loadAeadFor(record)
        val sealed = record.nonce + record.wrappedKeyset
        val serialized = aead.decrypt(sealed, associatedData(record.formatVersion, record.alias))
        return TinkProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get())
    }

    private fun loadAeadFor(record: WrappedKeysetRecord): Aead {
        if (!kekBoundary.hasKey(record.alias)) {
            throw GeneralSecurityException("no KEK stored for alias ${record.alias}")
        }
        return kekBoundary.loadKekAead(record.alias)
    }

    companion object {
        internal const val NONCE_SIZE = WrappedKeysetRecord.NONCE_SIZE
        private const val DOMAIN_PREFIX = "postmark/kek/wrap/v1"

        /**
         * Deterministic domain-separated AAD binding every wrap to its format
         * version and Keystore alias: prefix || 0x00 || u32be(version) || alias.
         */
        internal fun associatedData(formatVersion: Int, alias: String): ByteArray {
            val aliasBytes = alias.encodeToByteArray()
            val aad = ByteArray(DOMAIN_PREFIX.length + 1 + 4 + aliasBytes.size)
            var offset = 0
            DOMAIN_PREFIX.encodeToByteArray().copyInto(aad, offset)
            offset += DOMAIN_PREFIX.length
            aad[offset++] = 0x00
            aad[offset++] = ((formatVersion ushr 24) and 0xFF).toByte()
            aad[offset++] = ((formatVersion ushr 16) and 0xFF).toByte()
            aad[offset++] = ((formatVersion ushr 8) and 0xFF).toByte()
            aad[offset++] = (formatVersion and 0xFF).toByte()
            aliasBytes.copyInto(aad, offset)
            return aad
        }
    }
}
