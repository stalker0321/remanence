package dev.hryshyn.remanence.core.model

import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.v1.SenderRetryPurpose as SenderRetryPurposeProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class SenderRetryWrapContextEncoderTest {

    @Test
    fun fixtureProducesExactWholeContextBytesAndLengths() {
        val fixture = loadFixture()
        val aad = CryptoContextEncoder.senderRetryWrapAad(fixture.input())
        assertEquals(SENDER_RETRY_WRAP_AAD_LENGTH, aad.size())
        assertEquals(fixture.expectedAad, aad)
    }

    @Test
    fun exactlyOneZeroDelimiterSitsAtPrefixBoundary() {
        val fixture = loadFixture()
        val aad = CryptoContextEncoder.senderRetryWrapAad(fixture.input())
        assertSingleDelimiter(CryptoContextEncoder.SENDER_RETRY_WRAP_AAD_PREFIX, aad)
    }

    @Test
    fun repeatedCallsReturnIdenticalBytes() {
        val fixture = loadFixture()
        val first = CryptoContextEncoder.senderRetryWrapAad(fixture.input())
        repeat(3) {
            assertEquals(first, CryptoContextEncoder.senderRetryWrapAad(fixture.input()))
        }
    }

    @Test
    fun distinctOwnerCapsuleAndSenderBundleEachChangeTheAad() {
        val base = loadFixture()
        val ownerShifted = base.copy(
            ownerUserId = UserId.parseRest("aa0aa0aa-0aa0-4a0a-8a0a-0a0a0a0a0a0a"),
        )
        val capsuleShifted = base.copy(
            capsuleId = CapsuleId.parseRest("bb0bb0bb-0bb0-4b0b-8b0b-0b0b0b0b0b0b"),
        )
        val bundleShifted = base.copy(
            senderKeyBundleId = KeyBundleId.parseRest("cc0cc0cc-0cc0-4c0c-8c0c-0c0c0c0c0c0c"),
        )
        val baseAad = CryptoContextEncoder.senderRetryWrapAad(base.input()).toByteArray()
        val ownerAad = CryptoContextEncoder.senderRetryWrapAad(ownerShifted.input()).toByteArray()
        val capsuleAad = CryptoContextEncoder.senderRetryWrapAad(capsuleShifted.input()).toByteArray()
        val bundleAad = CryptoContextEncoder.senderRetryWrapAad(bundleShifted.input()).toByteArray()
        assertNotAadEquals(
            "changing the owner_user_id must change the AAD",
            baseAad,
            ownerAad,
        )
        assertNotAadEquals(
            "changing the capsule_id must change the AAD",
            baseAad,
            capsuleAad,
        )
        assertNotAadEquals(
            "changing the sender_key_bundle_id must change the AAD",
            baseAad,
            bundleAad,
        )
    }

    private fun assertNotAadEquals(message: String, expected: ByteArray, actual: ByteArray) {
        if (expected.contentEquals(actual)) {
            throw AssertionError("$message; both were ${expected.toList()}")
        }
    }

    @Test
    fun fieldNumberBindingsMatchTheProtoContract() {
        val fixture = loadFixture()
        val aad = CryptoContextEncoder.senderRetryWrapAad(fixture.input())
        val prefixBytes = CryptoContextEncoder.SENDER_RETRY_WRAP_AAD_PREFIX
            .toByteArray(Charsets.US_ASCII)
        val protoBytes = aad.substring(prefixBytes.size + 1, aad.size()).toByteArray()
        val parsed = dev.hryshyn.remanence.protocol.v1.SenderRetryWrapContext.parseFrom(protoBytes)
        assertEquals(1, parsed.protocolVersion)
        assertEquals(
            fixture.ownerUserId.value.toString(),
            UserId.fromProtoBytes(parsed.ownerUserId).value.toString(),
        )
        assertEquals(
            fixture.capsuleId.value.toString(),
            CapsuleId.fromProtoBytes(parsed.capsuleId).value.toString(),
        )
        assertEquals(
            fixture.senderKeyBundleId.value.toString(),
            KeyBundleId.fromProtoBytes(parsed.senderKeyBundleId).value.toString(),
        )
        assertEquals(
            SenderRetryPurposeProto.RECIPIENT_KEY_STALE_REWRAP_VALUE,
            parsed.purposeValue,
        )
    }

    @Test
    fun unspecifiedPurposeFailsClosedAtConstruction() {
        val fixture = loadFixture()
        val rejected = assertFailsWith<IllegalArgumentException> {
            SenderRetryWrapContextInput(
                ownerUserId = fixture.ownerUserId,
                capsuleId = fixture.capsuleId,
                senderKeyBundleId = fixture.senderKeyBundleId,
                purpose = SenderRetryPurpose.UNSPECIFIED,
            )
        }
        assertTrue(rejected.message!!.contains("RECIPIENT_KEY_STALE_REWRAP"))
    }

    @Test
    fun unknownPurposeFailsClosedAtTheEncoder() {
        val fixture = loadFixture()
        val tamperedProto = dev.hryshyn.remanence.protocol.v1.SenderRetryWrapContext
            .newBuilder()
            .setProtocolVersion(ProtocolV1Limits.PROTOCOL_VERSION)
            .setOwnerUserId(fixture.ownerUserId.toProtoBytes())
            .setCapsuleId(fixture.capsuleId.toProtoBytes())
            .setSenderKeyBundleId(fixture.senderKeyBundleId.toProtoBytes())
            .setPurposeValue(0xFE)
            .build()
        val tamperedBytes = tamperedProto.toByteArray()
        val tamperedContext = dev.hryshyn.remanence.protocol.v1.SenderRetryWrapContext
            .parseFrom(tamperedBytes)
        // The protobuf parser maps an unknown wire value to UNRECOGNIZED.
        // Our typed boundary refuses to map that into a usable purpose so
        // no keyset material is ever decrypted for a purpose the crypto
        // layer has not been told to accept.
        assertEquals(
            SenderRetryPurposeProto.UNRECOGNIZED,
            tamperedContext.purpose,
        )
        val rejectedMapping = assertFailsWith<IllegalArgumentException> {
            SenderRetryPurpose.fromProto(tamperedContext.purpose)
        }
        assertTrue(rejectedMapping.message!!.contains("unsupported"))
    }

    @Test
    fun malformedProtoIdBytesFailClosedAtTheTypedBoundary() {
        // A tampered AAD payload that re-parses into the SenderRetryWrapContext
        // proto but carries a 15-byte owner_user_id instead of the
        // contract-required 16 bytes must never round-trip back into a
        // typed [UserId]. The proto library stores the bytes as-is; the
        // typed wrapper is the only place that enforces the exact
        // 16-byte UUID contract.
        val fixture = loadFixture()
        val prefix = CryptoContextEncoder.SENDER_RETRY_WRAP_AAD_PREFIX
            .toByteArray(Charsets.US_ASCII)
        val malformedProto = dev.hryshyn.remanence.protocol.v1.SenderRetryWrapContext
            .newBuilder()
            .setProtocolVersion(ProtocolV1Limits.PROTOCOL_VERSION)
            .setOwnerUserId(com.google.protobuf.ByteString.copyFrom(ByteArray(15) { 0x42 }))
            .setCapsuleId(fixture.capsuleId.toProtoBytes())
            .setSenderKeyBundleId(fixture.senderKeyBundleId.toProtoBytes())
            .setPurpose(SenderRetryPurposeProto.RECIPIENT_KEY_STALE_REWRAP)
            .build()
        val protoBytes = malformedProto.toByteArray()
        val parsed = dev.hryshyn.remanence.protocol.v1.SenderRetryWrapContext
            .parseFrom(protoBytes)
        assertEquals(15, parsed.ownerUserId.size())
        assertFailsWith<IllegalArgumentException> { UserId.fromProtoBytes(parsed.ownerUserId) }
        assertFailsWith<IllegalArgumentException> { CapsuleId.fromProtoBytes(ByteString.copyFrom(ByteArray(8))) }
        assertFailsWith<IllegalArgumentException> { KeyBundleId.fromProtoBytes(ByteString.copyFrom(ByteArray(0))) }
    }

    @Test
    fun senderRetryWrapPrefixIsDistinctFromEveryOtherDomainPrefix() {
        // The prefix MUST be distinct from the artifact-AAD and the
        // recipient-envelope prefixes so a wrapped retry keyset can
        // never share an AAD with another protocol context.
        val prefix = CryptoContextEncoder.SENDER_RETRY_WRAP_AAD_PREFIX
        assertEquals("postmark/sender-retry-wrap/v1", prefix)
        assertNotEquals(CryptoContextEncoder.ARTIFACT_AAD_PREFIX, prefix)
        assertNotEquals(CryptoContextEncoder.ENVELOPE_INFO_PREFIX, prefix)
        val fixture = loadFixture()
        val retryAad = CryptoContextEncoder.senderRetryWrapAad(fixture.input()).toByteArray()
        val artifactAad = CryptoContextEncoder.artifactAad(fixture.artifactAadInput()).toByteArray()
        val envelopeInfo = CryptoContextEncoder.recipientEnvelopeInfo(fixture.envelopeInput()).toByteArray()
        if (retryAad.contentEquals(artifactAad)) {
            throw AssertionError("retry AAD must not equal artifact AAD")
        }
        if (retryAad.contentEquals(envelopeInfo)) {
            throw AssertionError("retry AAD must not equal envelope info")
        }
    }

    private fun assertSingleDelimiter(prefix: String, whole: ByteString) {
        val prefixBytes = prefix.toByteArray(Charsets.US_ASCII)
        assertTrue(whole.size() > prefixBytes.size + 1)
        for (index in prefixBytes.indices) {
            assertEquals(prefixBytes[index], whole.byteAt(index), "prefix byte mismatch at $index")
        }
        assertEquals(0, whole.byteAt(prefixBytes.size).toInt(), "missing zero delimiter at prefix boundary")
        val firstZero = (0 until whole.size()).firstOrNull { whole.byteAt(it).toInt() == 0 }
        assertEquals(prefixBytes.size, firstZero, "first zero byte must be the delimiter itself")
        assertTrue(whole.byteAt(prefixBytes.size + 1).toInt() != 0, "delimiter must be exactly one zero byte")
    }

    private fun loadFixture(): GoldenFixture {
        val resource = javaClass.classLoader.getResource("sender-retry-wrap-v1.json")
            ?: error("sender-retry-wrap-v1.json is missing")
        val root = Json.parseToJsonElement(resource.readText(Charsets.UTF_8))
        assertTrue(root is JsonObject, "fixture root must be an object")
        assertEquals(EXPECTED_ROOT_KEYS, root.keys)
        val version = root.getValue("schema_version")
        assertTrue(version is JsonPrimitive && !version.isString)
        assertEquals(1, version.intOrNull)
        val purpose = stringField(root, "purpose")
        assertEquals("RECIPIENT_KEY_STALE_REWRAP", purpose)
        val aadHex = stringField(root, "expected_sender_retry_wrap_aad_hex")
        assertEquals(2 * SENDER_RETRY_WRAP_AAD_LENGTH, aadHex.length)
        return GoldenFixture(
            ownerUserId = UserId.parseRest(stringField(root, "owner_user_id")),
            capsuleId = CapsuleId.parseRest(stringField(root, "capsule_id")),
            senderKeyBundleId = KeyBundleId.parseRest(stringField(root, "sender_key_bundle_id")),
            expectedAad = hexBytes(aadHex),
        )
    }

    private data class GoldenFixture(
        val ownerUserId: UserId,
        val capsuleId: CapsuleId,
        val senderKeyBundleId: KeyBundleId,
        val expectedAad: ByteString,
    ) {
        fun input(): SenderRetryWrapContextInput = SenderRetryWrapContextInput(
            ownerUserId = ownerUserId,
            capsuleId = capsuleId,
            senderKeyBundleId = senderKeyBundleId,
            purpose = SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP,
        )

        fun artifactAadInput(): ArtifactAadInput = ArtifactAadInput(
            capsuleId = capsuleId,
            blobId = BlobId.parseRest("00000000-0000-0000-0000-0000000000e5"),
            artifactKind = CapsuleArtifactKind.PHOTO,
            ordinal = 2,
            senderUserId = UserId.parseRest("10111213-1415-1617-1819-1a1b1c1d1e1f"),
            recipientUserId = UserId.parseRest("20212223-2425-2627-2829-2a2b2c2d2e2f"),
        )

        fun envelopeInput(): RecipientEnvelopeContextInput = RecipientEnvelopeContextInput(
            capsuleId = capsuleId,
            senderUserId = UserId.parseRest("10111213-1415-1617-1819-1a1b1c1d1e1f"),
            recipientUserId = UserId.parseRest("20212223-2425-2627-2829-2a2b2c2d2e2f"),
            recipientKeyBundleId = senderKeyBundleId,
        )
    }

    private fun stringField(obj: JsonObject, key: String): String {
        val value = obj.getValue(key)
        assertTrue(value is JsonPrimitive && value.isString, "$key must be a string")
        return requireNotNull(value.contentOrNull)
    }

    private fun hexBytes(hex: String): ByteString {
        require(hex.length % 2 == 0) { "odd hex length" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "non-lowercase hex" }
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return ByteString.copyFrom(bytes)
    }

    private companion object {
        const val SENDER_RETRY_WRAP_AAD_LENGTH = 88
        val EXPECTED_ROOT_KEYS = setOf(
            "schema_version",
            "owner_user_id",
            "capsule_id",
            "sender_key_bundle_id",
            "purpose",
            "expected_sender_retry_wrap_aad_hex",
        )
    }
}
