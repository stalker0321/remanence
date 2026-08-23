package postmark.core.model

import com.google.protobuf.ByteString
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProtocolIdsTest {
    @Test
    fun fixtureMapsToNetworkOrderBytesAndRoundTripsEveryWrapper() {
        val uuid = UUID.fromString(REST)
        val proto = hexBytes(PROTO_HEX)
        assertEquals(proto, UserId(uuid).toProtoBytes())
        assertEquals(proto, CapsuleId(uuid).toProtoBytes())
        assertEquals(proto, BlobId(uuid).toProtoBytes())
        assertEquals(proto, KeyBundleId(uuid).toProtoBytes())

        assertEquals(UserId(uuid), UserId.fromProtoBytes(proto))
        assertEquals(CapsuleId(uuid), CapsuleId.fromProtoBytes(proto))
        assertEquals(BlobId(uuid), BlobId.fromProtoBytes(proto))
        assertEquals(KeyBundleId(uuid), KeyBundleId.fromProtoBytes(proto))

        assertEquals(uuid, UserId.fromProtoBytes(UserId(uuid).toProtoBytes()).value)
        assertEquals(uuid, CapsuleId.fromProtoBytes(CapsuleId(uuid).toProtoBytes()).value)
        assertEquals(uuid, BlobId.fromProtoBytes(BlobId(uuid).toProtoBytes()).value)
        assertEquals(uuid, KeyBundleId.fromProtoBytes(KeyBundleId(uuid).toProtoBytes()).value)
    }

    @Test
    fun canonicalRestRoundTripsEveryWrapper() {
        assertEquals(REST, UserId.parseRest(REST).toRestString())
        assertEquals(REST, CapsuleId.parseRest(REST).toRestString())
        assertEquals(REST, BlobId.parseRest(REST).toRestString())
        assertEquals(REST, KeyBundleId.parseRest(REST).toRestString())
        assertEquals(UserId.parseRest(REST), UserId(UUID.fromString(REST)))
        assertEquals(CapsuleId.parseRest(REST), CapsuleId(UUID.fromString(REST)))
        assertEquals(BlobId.parseRest(REST), BlobId(UUID.fromString(REST)))
        assertEquals(KeyBundleId.parseRest(REST), KeyBundleId(UUID.fromString(REST)))
    }

    @Test
    fun nilUuidSyntacticallyRoundTrips() {
        val rest = "00000000-0000-0000-0000-000000000000"
        val proto = ByteString.copyFrom(ByteArray(16))
        assertEquals(rest, UserId.parseRest(rest).toRestString())
        assertEquals(UserId.parseRest(rest), UserId.fromProtoBytes(proto))
        assertEquals(proto, UserId.parseRest(rest).toProtoBytes())
    }

    @Test
    fun restRejectsNoncanonicalFormsWithoutEchoingInput() {
        val samples = listOf(
            "00112233-4455-6677-8899-AABBCCDDEEFF",
            " 00112233-4455-6677-8899-aabbccddeeff",
            "00112233-4455-6677-8899-aabbccddeeff ",
            "{00112233-4455-6677-8899-aabbccddeeff}",
            "00112233445566778899aabbccddeeff",
            "1-2-3-4-5",
            "00112233-4455-6677-8899-aabbccddeegg",
            "00112233-4455-6677-8899-aabbccddeeеf",
        )
        for (sample in samples) {
            assertGenericInvalid(sample) { UserId.parseRest(sample) }
            assertGenericInvalid(sample) { CapsuleId.parseRest(sample) }
            assertGenericInvalid(sample) { BlobId.parseRest(sample) }
            assertGenericInvalid(sample) { KeyBundleId.parseRest(sample) }
        }
    }

    @Test
    fun protoRejectsWrongLengthsWithoutEchoingInput() {
        for (size in intArrayOf(0, 15, 17)) {
            val sample = ByteString.copyFrom(ByteArray(size) { 0xab.toByte() })
            assertGenericInvalid("ab") { UserId.fromProtoBytes(sample) }
            assertGenericInvalid("ab") { CapsuleId.fromProtoBytes(sample) }
            assertGenericInvalid("ab") { BlobId.fromProtoBytes(sample) }
            assertGenericInvalid("ab") { KeyBundleId.fromProtoBytes(sample) }
        }
    }

    private fun assertGenericInvalid(sample: String, block: () -> Unit) {
        val error = assertFailsWith<IllegalArgumentException>(block = block)
        assertEquals(INVALID_MESSAGE, error.message)
        assertFalse(sample in (error.message ?: ""))
    }

    private fun hexBytes(hex: String): ByteString {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return ByteString.copyFrom(bytes)
    }

    private companion object {
        const val REST = "00112233-4455-6677-8899-aabbccddeeff"
        const val PROTO_HEX = "00112233445566778899aabbccddeeff"
        const val INVALID_MESSAGE = "invalid protocol UUID"
    }
}
