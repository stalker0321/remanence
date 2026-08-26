package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import com.google.protobuf.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

class PublishStatementBuilderTest {
    @Test
    fun fixtureProducesExactDeterministicBytesAndSortedBindings() {
        val fixture = loadFixture()
        val input = fixture.input()
        val original = input.artifacts.toList()
        val success = assertIs<PublishStatementBuildResult.Success>(PublishStatementBuilder.build(input))
        assertEquals(original, input.artifacts)
        assertEquals(400, success.deterministicBytes.size())
        assertEquals(fixture.expectedBytes, success.deterministicBytes)
        assertEquals(ProtocolV1Limits.PROTOCOL_VERSION, success.statement.protocolVersion)
        assertEquals(fixture.capsuleId.toProtoBytes(), success.statement.capsuleId)
        assertEquals(fixture.senderUserId.toProtoBytes(), success.statement.senderUserId)
        assertEquals(fixture.recipientUserId.toProtoBytes(), success.statement.recipientUserId)
        assertEquals(fixture.senderKeyBundleId.toProtoBytes(), success.statement.senderKeyBundleId)
        assertEquals(fixture.recipientKeyBundleId.toProtoBytes(), success.statement.recipientKeyBundleId)
        assertEquals(1_700_000_000L, success.statement.createdAtEpochSeconds)
        assertEquals(fixture.expectedSortedBlobIds, success.statement.artifactsList.map { BlobId.fromProtoBytes(it.blobId) })
        assertEquals(
            listOf(
                ArtifactKind.RECOGNITION_MANIFEST,
                ArtifactKind.CONTENT_MANIFEST,
                ArtifactKind.PHOTO,
                ArtifactKind.PHOTO,
                ArtifactKind.PHOTO,
            ),
            success.statement.artifactsList.map { it.kind },
        )
        assertEquals(listOf(-1, -1, 0, 1, 2), success.statement.artifactsList.map { it.ordinal })
        assertEquals(listOf(101L, 202L, 303L, 404L, 505L), success.statement.artifactsList.map { it.ciphertextSize })
    }

    @Test
    fun differentPermutationsProduceIdenticalBytes() {
        val fixture = loadFixture()
        val first = fixture.input()
        val second = first.copy(artifacts = first.artifacts.reversed())
        val third = first.copy(artifacts = listOf(first.artifacts[2], first.artifacts[4], first.artifacts[0], first.artifacts[3], first.artifacts[1]))
        val a = assertIs<PublishStatementBuildResult.Success>(PublishStatementBuilder.build(first))
        val b = assertIs<PublishStatementBuildResult.Success>(PublishStatementBuilder.build(second))
        val c = assertIs<PublishStatementBuildResult.Success>(PublishStatementBuilder.build(third))
        assertEquals(fixture.expectedBytes, a.deterministicBytes)
        assertEquals(a.deterministicBytes, b.deterministicBytes)
        assertEquals(a.deterministicBytes, c.deterministicBytes)
        assertEquals(first.artifacts.size, second.artifacts.size)
        assertEquals(fixture.input().artifacts, first.artifacts)
    }

    @Test
    fun inputListIsNotMutated() {
        val fixture = loadFixture()
        val mutable = fixture.input().artifacts.toMutableList()
        val snapshot = mutable.toList()
        PublishStatementBuilder.build(fixture.input().copy(artifacts = mutable))
        assertEquals(snapshot, mutable)
    }

    @Test
    fun invalidLayoutPropagates() {
        val fixture = loadFixture()
        val input = fixture.input().copy(artifacts = fixture.input().artifacts.take(2))
        val result = PublishStatementBuilder.build(input)
        val invalid = assertIs<PublishStatementBuildResult.InvalidLayout>(result)
        assertEquals(ArtifactLayoutError.RECOGNITION_MANIFEST_CARDINALITY, invalid.reason)
    }

    @Test
    fun hashLengthAndSizeBoundsReject() {
        val fixture = loadFixture()
        val base = fixture.input()
        val first = base.artifacts.first()
        assertEquals(
            PublishStatementBuildResult.InvalidCiphertextSha256,
            PublishStatementBuilder.build(base.copy(artifacts = base.artifacts.toMutableList().also {
                it[0] = first.copy(ciphertextSha256 = ByteString.copyFrom(ByteArray(31) { 0x11 }))
            })),
        )
        assertEquals(
            PublishStatementBuildResult.InvalidCiphertextSha256,
            PublishStatementBuilder.build(base.copy(artifacts = base.artifacts.toMutableList().also {
                it[0] = first.copy(ciphertextSha256 = ByteString.copyFrom(ByteArray(33) { 0x11 }))
            })),
        )
        assertEquals(
            PublishStatementBuildResult.InvalidCiphertextSize,
            PublishStatementBuilder.build(replaceSize(base, CapsuleArtifactKind.PHOTO, 0L)),
        )
        assertEquals(
            PublishStatementBuildResult.InvalidCiphertextSize,
            PublishStatementBuilder.build(replaceSize(base, CapsuleArtifactKind.PHOTO, -1L)),
        )
        assertEquals(
            PublishStatementBuildResult.InvalidCiphertextSize,
            PublishStatementBuilder.build(
                replaceSize(base, CapsuleArtifactKind.RECOGNITION_MANIFEST, ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES + 1),
            ),
        )
        assertEquals(
            PublishStatementBuildResult.InvalidCiphertextSize,
            PublishStatementBuilder.build(
                replaceSize(base, CapsuleArtifactKind.CONTENT_MANIFEST, ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES + 1),
            ),
        )
        assertEquals(
            PublishStatementBuildResult.InvalidCiphertextSize,
            PublishStatementBuilder.build(
                replaceSize(base, CapsuleArtifactKind.PHOTO, ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES + 1),
            ),
        )
    }

    private fun replaceSize(
        input: PublishStatementInput,
        kind: CapsuleArtifactKind,
        size: Long,
    ): PublishStatementInput {
        val artifacts = input.artifacts.map { artifact ->
            if (artifact.slot.kind == kind) artifact.copy(ciphertextSize = size) else artifact
        }
        return input.copy(artifacts = artifacts)
    }

    private fun loadFixture(): GoldenFixture {
        val resource = javaClass.classLoader.getResource("publish-statement-v1.json")
            ?: error("publish-statement-v1.json is missing")
        val root = Json.parseToJsonElement(resource.readText(Charsets.UTF_8))
        assertTrue(root is JsonObject, "fixture root must be an object")
        assertEquals(EXPECTED_ROOT_KEYS, root.keys)
        val version = root.getValue("schema_version")
        assertTrue(version is JsonPrimitive && !version.isString)
        assertEquals(1, version.intOrNull)
        val artifactsElement = root.getValue("artifacts")
        assertTrue(artifactsElement is JsonArray)
        assertEquals(5, artifactsElement.size)
        val artifacts = artifactsElement.map { entry ->
            assertTrue(entry is JsonObject)
            assertEquals(EXPECTED_ARTIFACT_KEYS, entry.keys)
            val kind = CapsuleArtifactKind.valueOf(stringField(entry, "kind"))
            val ordinal = intField(entry, "ordinal")
            val blobId = BlobId.parseRest(stringField(entry, "blob_id"))
            val size = longField(entry, "ciphertext_size")
            val sha = hexBytes(stringField(entry, "ciphertext_sha256_hex"))
            PublishArtifact(ArtifactSlot(blobId, kind, ordinal), size, sha)
        }
        val sortedElement = root.getValue("expected_sorted_blob_ids")
        assertTrue(sortedElement is JsonArray)
        val expectedSorted = sortedElement.map { entry ->
            assertTrue(entry is JsonPrimitive && entry.isString)
            BlobId.parseRest(entry.content)
        }
        val hex = stringField(root, "expected_deterministic_hex")
        assertEquals(800, hex.length)
        return GoldenFixture(
            capsuleId = CapsuleId.parseRest(stringField(root, "capsule_id")),
            senderUserId = UserId.parseRest(stringField(root, "sender_user_id")),
            recipientUserId = UserId.parseRest(stringField(root, "recipient_user_id")),
            senderKeyBundleId = KeyBundleId.parseRest(stringField(root, "sender_key_bundle_id")),
            recipientKeyBundleId = KeyBundleId.parseRest(stringField(root, "recipient_key_bundle_id")),
            createdAtEpochSeconds = longField(root, "created_at_epoch_seconds"),
            artifacts = artifacts,
            expectedSortedBlobIds = expectedSorted,
            expectedBytes = hexBytes(hex),
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

    private fun longField(obj: JsonObject, key: String): Long {
        val value = obj.getValue(key)
        assertTrue(value is JsonPrimitive && !value.isString, "$key must be a number")
        assertTrue('.' !in value.content, "$key must not be a float")
        return requireNotNull(value.longOrNull)
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
        val senderUserId: UserId,
        val recipientUserId: UserId,
        val senderKeyBundleId: KeyBundleId,
        val recipientKeyBundleId: KeyBundleId,
        val createdAtEpochSeconds: Long,
        val artifacts: List<PublishArtifact>,
        val expectedSortedBlobIds: List<BlobId>,
        val expectedBytes: ByteString,
    ) {
        fun input(): PublishStatementInput =
            PublishStatementInput(
                capsuleId = capsuleId,
                senderUserId = senderUserId,
                recipientUserId = recipientUserId,
                senderKeyBundleId = senderKeyBundleId,
                recipientKeyBundleId = recipientKeyBundleId,
                createdAtEpochSeconds = createdAtEpochSeconds,
                artifacts = artifacts,
            )
    }

    private companion object {
        val EXPECTED_ROOT_KEYS = setOf(
            "schema_version",
            "capsule_id",
            "sender_user_id",
            "recipient_user_id",
            "sender_key_bundle_id",
            "recipient_key_bundle_id",
            "created_at_epoch_seconds",
            "artifacts",
            "expected_sorted_blob_ids",
            "expected_deterministic_hex",
        )
        val EXPECTED_ARTIFACT_KEYS = setOf(
            "kind",
            "ordinal",
            "blob_id",
            "ciphertext_size",
            "ciphertext_sha256_hex",
        )
    }
}
