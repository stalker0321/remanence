package postmark.core.model

import com.google.protobuf.ByteString
import java.util.UUID

@JvmInline
value class UserId(val value: UUID) {
    fun toRestString(): String = ProtocolUuid.toRestString(value)

    fun toProtoBytes(): ByteString = ProtocolUuid.toProtoBytes(value)

    companion object {
        fun parseRest(raw: String): UserId = UserId(ProtocolUuid.parseRest(raw))

        fun fromProtoBytes(bytes: ByteString): UserId = UserId(ProtocolUuid.fromProtoBytes(bytes))
    }
}

@JvmInline
value class CapsuleId(val value: UUID) {
    fun toRestString(): String = ProtocolUuid.toRestString(value)

    fun toProtoBytes(): ByteString = ProtocolUuid.toProtoBytes(value)

    companion object {
        fun parseRest(raw: String): CapsuleId = CapsuleId(ProtocolUuid.parseRest(raw))

        fun fromProtoBytes(bytes: ByteString): CapsuleId = CapsuleId(ProtocolUuid.fromProtoBytes(bytes))
    }
}

@JvmInline
value class BlobId(val value: UUID) {
    fun toRestString(): String = ProtocolUuid.toRestString(value)

    fun toProtoBytes(): ByteString = ProtocolUuid.toProtoBytes(value)

    companion object {
        fun parseRest(raw: String): BlobId = BlobId(ProtocolUuid.parseRest(raw))

        fun fromProtoBytes(bytes: ByteString): BlobId = BlobId(ProtocolUuid.fromProtoBytes(bytes))
    }
}

@JvmInline
value class KeyBundleId(val value: UUID) {
    fun toRestString(): String = ProtocolUuid.toRestString(value)

    fun toProtoBytes(): ByteString = ProtocolUuid.toProtoBytes(value)

    companion object {
        fun parseRest(raw: String): KeyBundleId = KeyBundleId(ProtocolUuid.parseRest(raw))

        fun fromProtoBytes(bytes: ByteString): KeyBundleId = KeyBundleId(ProtocolUuid.fromProtoBytes(bytes))
    }
}

private object ProtocolUuid {
    const val INVALID = "invalid protocol UUID"
    private val canonicalRest = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun toRestString(value: UUID): String = value.toString()

    fun parseRest(raw: String): UUID {
        if (!canonicalRest.matches(raw)) {
            throw IllegalArgumentException(INVALID)
        }
        return UUID.fromString(raw)
    }

    fun toProtoBytes(value: UUID): ByteString {
        val bytes = ByteArray(16)
        writeBigEndian(value.mostSignificantBits, bytes, 0)
        writeBigEndian(value.leastSignificantBits, bytes, 8)
        return ByteString.copyFrom(bytes)
    }

    fun fromProtoBytes(bytes: ByteString): UUID {
        if (bytes.size() != 16) {
            throw IllegalArgumentException(INVALID)
        }
        val raw = bytes.toByteArray()
        return UUID(readBigEndian(raw, 0), readBigEndian(raw, 8))
    }

    private fun writeBigEndian(value: Long, dest: ByteArray, offset: Int) {
        var bits = value
        for (i in offset + 7 downTo offset) {
            dest[i] = bits.toByte()
            bits = bits ushr 8
        }
    }

    private fun readBigEndian(src: ByteArray, offset: Int): Long {
        var bits = 0L
        for (i in offset until offset + 8) {
            bits = (bits shl 8) or (src[i].toLong() and 0xffL)
        }
        return bits
    }
}
