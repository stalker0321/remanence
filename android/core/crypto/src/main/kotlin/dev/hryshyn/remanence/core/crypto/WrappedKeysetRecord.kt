package dev.hryshyn.remanence.core.crypto

import java.security.GeneralSecurityException

/**
 * Versioned on-disk record `{format_version, keystore_alias, nonce,
 * wrapped_keyset}` persisted next to the app-private wrapped identity
 * keysets (docs/security.md section 5).
 *
 * Wire layout (big-endian):
 * ```
 * magic        4 bytes  "PWKS"
 * version      4 bytes  unsigned, must be 1
 * alias_len    4 bytes  unsigned, 1..ALIAS_MAX_BYTES
 * alias        alias_len bytes, ASCII [A-Za-z0-9._-]
 * nonce        12 bytes (96-bit GCM IV)
 * keyset_len   4 bytes  unsigned, 1..WRAPPED_KEYSET_MAX_BYTES
 * wrapped      keyset_len bytes of KEK-wrapped keyset ciphertext
 * ```
 * Any unknown version, bad length, invalid alias character, short buffer, or
 * trailing byte fails closed before the record can be used.
 */
class WrappedKeysetRecord private constructor(
    val formatVersion: Int,
    val alias: String,
    val nonce: ByteArray,
    val wrappedKeyset: ByteArray,
) {

    fun serialize(): ByteArray {
        val out = ByteArray(
            MAGIC.size + VERSION_SIZE + LENGTH_FIELD_SIZE + alias.length +
                NONCE_SIZE + LENGTH_FIELD_SIZE + wrappedKeyset.size,
        )
        var offset = 0
        MAGIC.copyInto(out, offset)
        offset += MAGIC.size
        writeUInt32(out, offset, formatVersion)
        offset += VERSION_SIZE
        writeUInt32(out, offset, alias.length)
        offset += LENGTH_FIELD_SIZE
        alias.encodeToByteArray().copyInto(out, offset)
        offset += alias.length
        nonce.copyInto(out, offset)
        offset += NONCE_SIZE
        writeUInt32(out, offset, wrappedKeyset.size)
        offset += LENGTH_FIELD_SIZE
        wrappedKeyset.copyInto(out, offset)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is WrappedKeysetRecord &&
            formatVersion == other.formatVersion &&
            alias == other.alias &&
            nonce.contentEquals(other.nonce) &&
            wrappedKeyset.contentEquals(other.wrappedKeyset)

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + alias.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + wrappedKeyset.contentHashCode()
        return result
    }

    companion object {

        const val FORMAT_VERSION_1: Int = 1
        const val NONCE_SIZE: Int = 12
        const val ALIAS_MAX_BYTES: Int = 128
        const val WRAPPED_KEYSET_MAX_BYTES: Int = 128 * 1024
        internal val SUPPORTED_FORMAT_VERSIONS: IntRange = FORMAT_VERSION_1..FORMAT_VERSION_1

        private val MAGIC = byteArrayOf('P'.code.toByte(), 'W'.code.toByte(), 'K'.code.toByte(), 'S'.code.toByte())
        private const val VERSION_SIZE = 4
        private const val LENGTH_FIELD_SIZE = 4

        fun create(alias: String, nonce: ByteArray, wrappedKeyset: ByteArray): WrappedKeysetRecord {
            validateAlias(alias)
            validateNonce(nonce)
            validateWrapped(wrappedKeyset)
            return WrappedKeysetRecord(FORMAT_VERSION_1, alias, nonce.copyOf(), wrappedKeyset.copyOf())
        }

        fun parse(bytes: ByteArray): WrappedKeysetRecord {
            var offset = 0
            fun take(count: Int): ByteArray {
                if (count < 0 || offset + count > bytes.size) {
                    throw GeneralSecurityException("wrapped-keyset record truncated")
                }
                val slice = bytes.copyOfRange(offset, offset + count)
                offset += count
                return slice
            }

            if (!take(MAGIC.size).contentEquals(MAGIC)) {
                throw GeneralSecurityException("wrapped-keyset record has bad magic")
            }
            val version = readUInt32(take(VERSION_SIZE))
            if (version !in SUPPORTED_FORMAT_VERSIONS) {
                throw GeneralSecurityException("unsupported wrapped-keyset format version $version")
            }
            val aliasLength = readUInt32(take(LENGTH_FIELD_SIZE))
            val alias = take(aliasLength).decodeToString()
            validateAlias(alias)
            val nonce = take(NONCE_SIZE)
            validateNonce(nonce)
            val keysetLength = readUInt32(take(LENGTH_FIELD_SIZE))
            val wrapped = take(keysetLength)
            validateWrapped(wrapped)
            if (offset != bytes.size) {
                throw GeneralSecurityException("wrapped-keyset record has trailing bytes")
            }
            return WrappedKeysetRecord(version, alias, nonce, wrapped)
        }

        private fun validateAlias(alias: String) {
            if (alias.isEmpty() || alias.length > ALIAS_MAX_BYTES) {
                throw GeneralSecurityException("wrapped-keyset alias length out of bounds")
            }
            if (!ALIAS_CHARS.matches(alias)) {
                throw GeneralSecurityException("wrapped-keyset alias has invalid characters")
            }
        }

        private fun validateNonce(nonce: ByteArray) {
            if (nonce.size != NONCE_SIZE) {
                throw GeneralSecurityException("wrapped-keyset nonce must be exactly $NONCE_SIZE bytes")
            }
        }

        private fun validateWrapped(wrappedKeyset: ByteArray) {
            if (wrappedKeyset.isEmpty()) {
                throw GeneralSecurityException("wrapped-keyset payload is empty")
            }
            if (wrappedKeyset.size > WRAPPED_KEYSET_MAX_BYTES) {
                throw GeneralSecurityException("wrapped-keyset payload exceeds bound")
            }
        }

        private fun readUInt32(bytes: ByteArray): Int {
            require(bytes.size == LENGTH_FIELD_SIZE)
            return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        }

        private fun writeUInt32(destination: ByteArray, offset: Int, value: Int) {
            destination[offset] = (value ushr 24).toByte()
            destination[offset + 1] = (value ushr 16).toByte()
            destination[offset + 2] = (value ushr 8).toByte()
            destination[offset + 3] = value.toByte()
        }

        private val ALIAS_CHARS = Regex("[A-Za-z0-9._\\-]+")
    }
}
