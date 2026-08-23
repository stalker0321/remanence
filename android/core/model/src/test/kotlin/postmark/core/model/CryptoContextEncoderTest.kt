package postmark.core.model

import com.google.protobuf.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class CryptoContextEncoderTest {
    @Test
    fun fixtureProducesExactWholeContextBytesAndLengths() {
        val fixture = loadFixture()
        val artifactAad = CryptoContextEncoder.artifactAad(fixture.artifactAadInput())
        val envelopeInfo = CryptoContextEncoder.recipientEnvelopeInfo(fixture.envelopeInput())
        assertEquals(ARTIFACT_AAD_LENGTH, artifactAad.size())
        assertEquals(ENVELOPE_INFO_LENGTH, envelopeInfo.size())
        assertEquals(fixture.expectedArtifactAad, artifactAad)
        assertEquals(fixture.expectedEnvelopeInfo, envelopeInfo)
    }

    @Test
    fun exactlyOneZeroDelimiterSitsAtPrefixBoundary() {
        val fixture = loadFixture()
        assertSingleDelimiter(CryptoContextEncoder.ARTIFACT_AAD_PREFIX, CryptoContextEncoder.artifactAad(fixture.artifactAadInput()))
        assertSingleDelimiter(CryptoContextEncoder.ENVELOPE_INFO_PREFIX, CryptoContextEncoder.recipientEnvelopeInfo(fixture.envelopeInput()))
    }

    @Test
    fun invalidOrdinalsFailClosed() {
        val fixture = loadFixture()
        val photo = fixture.artifactAadInput()
        assertFailsWith<IllegalArgumentException> { CryptoContextEncoder.artifactAad(photo.copy(ordinal = -1)) }
        assertFailsWith<IllegalArgumentException> { CryptoContextEncoder.artifactAad(photo.copy(ordinal = 5)) }
        for (kind in listOf(CapsuleArtifactKind.RECOGNITION_MANIFEST, CapsuleArtifactKind.CONTENT_MANIFEST)) {
            assertFailsWith<IllegalArgumentException> { CryptoContextEncoder.artifactAad(photo.copy(artifactKind = kind, ordinal = 0)) }
        }
    }

    @Test
    fun manifestKindsAcceptNonPhotoOrdinal() {
        val fixture = loadFixture()
        for (kind in listOf(CapsuleArtifactKind.RECOGNITION_MANIFEST, CapsuleArtifactKind.CONTENT_MANIFEST)) {
            val bytes = CryptoContextEncoder.artifactAad(
                fixture.artifactAadInput().copy(artifactKind = kind, ordinal = ProtocolV1Limits.NON_PHOTO_ORDINAL),
            )
            assertEquals(ARTIFACT_AAD_LENGTH, bytes.size())
        }
    }

    @Test
    fun domainPrefixesAreDistinctAndOutputsDiffer() {
        val fixture = loadFixture()
        assertEquals("postmark/artifact/v1", CryptoContextEncoder.ARTIFACT_AAD_PREFIX)
        assertEquals("postmark/envelope/v1", CryptoContextEncoder.ENVELOPE_INFO_PREFIX)
        assertTrue(CryptoContextEncoder.ARTIFACT_AAD_PREFIX != CryptoContextEncoder.ENVELOPE_INFO_PREFIX)
        val artifactAad = CryptoContextEncoder.artifactAad(fixture.artifactAadInput())
        val envelopeInfo = CryptoContextEncoder.recipientEnvelopeInfo(fixture.envelopeInput())
        assertTrue(artifactAad != envelopeInfo)
    }

    @Test
    fun repeatedCallsReturnIdenticalBytes() {
        val fixture = loadFixture()
        val firstArtifactAad = CryptoContextEncoder.artifactAad(fixture.artifactAadInput())
        val firstEnvelopeInfo = CryptoContextEncoder.recipientEnvelopeInfo(fixture.envelopeInput())
        repeat(3) {
            assertEquals(firstArtifactAad, CryptoContextEncoder.artifactAad(fixture.artifactAadInput()))
            assertEquals(firstEnvelopeInfo, CryptoContextEncoder.recipientEnvelopeInfo(fixture.envelopeInput()))
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
        val resource = javaClass.classLoader.getResource("crypto-context-v1.json")
            ?: error("crypto-context-v1.json is missing")
        val root = Json.parseToJsonElement(resource.readText(Charsets.UTF_8))
        assertTrue(root is JsonObject, "fixture root must be an object")
        assertEquals(EXPECTED_ROOT_KEYS, root.keys)
        val version = root.getValue("schema_version")
        assertTrue(version is JsonPrimitive && !version.isString)
        assertEquals(1, version.intOrNull)
        val artifactAadHex = stringField(root, "expected_artifact_aad_hex")
        val envelopeInfoHex = stringField(root, "expected_envelope_info_hex")
        assertEquals(2 * ARTIFACT_AAD_LENGTH, artifactAadHex.length)
        assertEquals(2 * ENVELOPE_INFO_LENGTH, envelopeInfoHex.length)
        return GoldenFixture(
            capsuleId = CapsuleId.parseRest(stringField(root, "capsule_id")),
            blobId = BlobId.parseRest(stringField(root, "blob_id")),
            artifactKind = CapsuleArtifactKind.valueOf(stringField(root, "artifact_kind")),
            ordinal = intField(root, "ordinal"),
            senderUserId = UserId.parseRest(stringField(root, "sender_user_id")),
            recipientUserId = UserId.parseRest(stringField(root, "recipient_user_id")),
            recipientKeyBundleId = KeyBundleId.parseRest(stringField(root, "recipient_key_bundle_id")),
            expectedArtifactAad = hexBytes(artifactAadHex),
            expectedEnvelopeInfo = hexBytes(envelopeInfoHex),
        )
    }

    private fun stringField(obj: JsonObject, key: String): String {
        val value = obj.getValue(key)
        assertTrue(value is JsonPrimitive && value.isString, "$key must be a string")
        return requireNotNull(value.contentOrNull)
    }

    private fun intField(obj: JsonObject, key: String): Int {
        val value = obj.getValue(key)
        assertTrue(value is JsonPrimitive && !value.isString, "$key must be a number")
        assertTrue('.' !in value.content, "$key must not be a float")
        return requireNotNull(value.intOrNull)
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

    private data class GoldenFixture(
        val capsuleId: CapsuleId,
        val blobId: BlobId,
        val artifactKind: CapsuleArtifactKind,
        val ordinal: Int,
        val senderUserId: UserId,
        val recipientUserId: UserId,
        val recipientKeyBundleId: KeyBundleId,
        val expectedArtifactAad: ByteString,
        val expectedEnvelopeInfo: ByteString,
    ) {
        fun artifactAadInput(): ArtifactAadInput =
            ArtifactAadInput(
                capsuleId = capsuleId,
                blobId = blobId,
                artifactKind = artifactKind,
                ordinal = ordinal,
                senderUserId = senderUserId,
                recipientUserId = recipientUserId,
            )

        fun envelopeInput(): RecipientEnvelopeContextInput =
            RecipientEnvelopeContextInput(
                capsuleId = capsuleId,
                senderUserId = senderUserId,
                recipientUserId = recipientUserId,
                recipientKeyBundleId = recipientKeyBundleId,
            )
    }

    private companion object {
        val EXPECTED_ROOT_KEYS = setOf(
            "schema_version",
            "capsule_id",
            "blob_id",
            "artifact_kind",
            "ordinal",
            "sender_user_id",
            "recipient_user_id",
            "recipient_key_bundle_id",
            "expected_artifact_aad_hex",
            "expected_envelope_info_hex",
        )
        const val ARTIFACT_AAD_LENGTH = 99
        const val ENVELOPE_INFO_LENGTH = 95
    }
}
