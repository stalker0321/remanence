package dev.hryshyn.remanence.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

class ProtocolV1LimitsTest {
    @Test
    fun fixtureMatchesEveryConstantAndIdentities() {
        val root = loadLimitsFixture()
        assertInt(root, "protocol_version", ProtocolV1Limits.PROTOCOL_VERSION)
        assertInt(root, "email_max_utf8_bytes", ProtocolV1Limits.EMAIL_MAX_UTF8_BYTES)
        assertInt(root, "password_min_code_points", ProtocolV1Limits.PASSWORD_MIN_CODE_POINTS)
        assertInt(root, "password_max_code_points", ProtocolV1Limits.PASSWORD_MAX_CODE_POINTS)
        assertInt(root, "handle_min_ascii_chars", ProtocolV1Limits.HANDLE_MIN_ASCII_CHARS)
        assertInt(root, "handle_max_ascii_chars", ProtocolV1Limits.HANDLE_MAX_ASCII_CHARS)
        assertInt(root, "note_max_utf8_bytes", ProtocolV1Limits.NOTE_MAX_UTF8_BYTES)
        assertInt(root, "place_label_max_utf8_bytes", ProtocolV1Limits.PLACE_LABEL_MAX_UTF8_BYTES)
        assertInt(root, "photo_count_min", ProtocolV1Limits.PHOTO_COUNT_MIN)
        assertInt(root, "photo_count_max", ProtocolV1Limits.PHOTO_COUNT_MAX)
        assertInt(root, "photo_ordinal_min", ProtocolV1Limits.PHOTO_ORDINAL_MIN)
        assertInt(root, "photo_ordinal_max", ProtocolV1Limits.PHOTO_ORDINAL_MAX)
        assertInt(root, "non_photo_ordinal", ProtocolV1Limits.NON_PHOTO_ORDINAL)
        assertInt(root, "recognition_manifest_count", ProtocolV1Limits.RECOGNITION_MANIFEST_COUNT)
        assertInt(root, "content_manifest_count", ProtocolV1Limits.CONTENT_MANIFEST_COUNT)
        assertInt(root, "recipient_envelope_count", ProtocolV1Limits.RECIPIENT_ENVELOPE_COUNT)
        assertString(root, "normalized_photo_media_type", ProtocolV1Limits.NORMALIZED_PHOTO_MEDIA_TYPE)
        assertInt(root, "normalized_photo_max_long_edge_px", ProtocolV1Limits.NORMALIZED_PHOTO_MAX_LONG_EDGE_PX)
        assertLong(root, "normalized_photo_max_plaintext_bytes", ProtocolV1Limits.NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES)
        assertLong(root, "artifact_aead_overhead_bytes", ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES)
        assertLong(root, "encrypted_photo_max_ciphertext_bytes", ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES)
        assertLong(root, "recognition_manifest_max_ciphertext_bytes", ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES)
        assertLong(root, "content_manifest_max_ciphertext_bytes", ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES)
        assertLong(root, "recipient_envelope_max_ciphertext_bytes", ProtocolV1Limits.RECIPIENT_ENVELOPE_MAX_CIPHERTEXT_BYTES)
        assertLong(root, "total_capsule_max_ciphertext_bytes", ProtocolV1Limits.TOTAL_CAPSULE_MAX_CIPHERTEXT_BYTES)
        assertLong(root, "draft_lifetime_seconds", ProtocolV1Limits.DRAFT_LIFETIME_SECONDS)
        assertInt(root, "incoming_page_default", ProtocolV1Limits.INCOMING_PAGE_DEFAULT)
        assertInt(root, "incoming_page_max", ProtocolV1Limits.INCOMING_PAGE_MAX)
        assertBoolean(root, "mvp_track_attachment_allowed", ProtocolV1Limits.MVP_TRACK_ATTACHMENT_ALLOWED)

        assertEquals(
            ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES,
            ProtocolV1Limits.ENCRYPTED_PHOTO_PLAINTEXT_PLUS_OVERHEAD_BYTES,
        )
        assertEquals(
            ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES,
            ProtocolV1Limits.NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES + ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES,
        )
        assertEquals(ProtocolV1Limits.PHOTO_ORDINAL_MIN, 0)
        assertEquals(ProtocolV1Limits.NON_PHOTO_ORDINAL, -1)
        assertEquals(ProtocolV1Limits.PHOTO_COUNT_MAX - 1, ProtocolV1Limits.PHOTO_ORDINAL_MAX)
        assertEquals(ProtocolV1Limits.PHOTO_COUNT_MAX - ProtocolV1Limits.PHOTO_COUNT_MIN, 2)
        assertEquals(42L * 1024L * 1024L, ProtocolV1Limits.TOTAL_CAPSULE_MAX_CIPHERTEXT_BYTES)
        val packed =
            ProtocolV1Limits.PHOTO_COUNT_MAX * ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES +
                ProtocolV1Limits.RECOGNITION_MANIFEST_COUNT * ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES +
                ProtocolV1Limits.CONTENT_MANIFEST_COUNT * ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES +
                ProtocolV1Limits.RECIPIENT_ENVELOPE_COUNT * ProtocolV1Limits.RECIPIENT_ENVELOPE_MAX_CIPHERTEXT_BYTES
        assertTrue(packed <= ProtocolV1Limits.TOTAL_CAPSULE_MAX_CIPHERTEXT_BYTES)

        assertTrue(ProtocolV1Limits.PASSWORD_MIN_CODE_POINTS < ProtocolV1Limits.PASSWORD_MAX_CODE_POINTS)
        assertEquals(12, ProtocolV1Limits.PASSWORD_MIN_CODE_POINTS)
        assertEquals(128, ProtocolV1Limits.PASSWORD_MAX_CODE_POINTS)
        assertEquals(3, ProtocolV1Limits.HANDLE_MIN_ASCII_CHARS)
        assertEquals(30, ProtocolV1Limits.HANDLE_MAX_ASCII_CHARS)
        assertTrue(ProtocolV1Limits.HANDLE_MIN_ASCII_CHARS < ProtocolV1Limits.HANDLE_MAX_ASCII_CHARS)
        assertTrue(ProtocolV1Limits.PHOTO_COUNT_MIN <= ProtocolV1Limits.PHOTO_COUNT_MAX)
        assertTrue(ProtocolV1Limits.PHOTO_ORDINAL_MIN <= ProtocolV1Limits.PHOTO_ORDINAL_MAX)
        assertTrue(ProtocolV1Limits.INCOMING_PAGE_DEFAULT <= ProtocolV1Limits.INCOMING_PAGE_MAX)
        assertEquals(50, ProtocolV1Limits.INCOMING_PAGE_DEFAULT)
        assertEquals(100, ProtocolV1Limits.INCOMING_PAGE_MAX)
        assertTrue(ProtocolV1Limits.NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES > 0L)
        assertTrue(ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES > ProtocolV1Limits.NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES)
        assertTrue(ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES > 0L)
        assertTrue(ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES > 0L)
        assertTrue(ProtocolV1Limits.RECIPIENT_ENVELOPE_MAX_CIPHERTEXT_BYTES > 0L)
        assertTrue(ProtocolV1Limits.TOTAL_CAPSULE_MAX_CIPHERTEXT_BYTES > packed)
        assertEquals(false, ProtocolV1Limits.MVP_TRACK_ATTACHMENT_ALLOWED)
        assertEquals(1, ProtocolV1Limits.PROTOCOL_VERSION)
        assertEquals(604_800L, ProtocolV1Limits.DRAFT_LIFETIME_SECONDS)
        assertEquals(7L * 24L * 60L * 60L, ProtocolV1Limits.DRAFT_LIFETIME_SECONDS)
    }

    private fun loadLimitsFixture(): JsonObject {
        val resource = javaClass.classLoader.getResource("limits-v1.json")
            ?: error("limits-v1.json is missing")
        val root = Json.parseToJsonElement(resource.readText(Charsets.UTF_8))
        assertTrue(root is JsonObject, "fixture root must be an object")
        assertEquals(EXPECTED_KEYS, root.keys)
        val version = root.getValue("schema_version")
        assertTrue(version is JsonPrimitive && !version.isString, "schema_version must be a number")
        assertEquals(1, version.intOrNull)
        assertEquals("1", version.content)
        return root
    }

    private fun assertInt(root: JsonObject, key: String, expected: Int) {
        val value = number(root, key)
        assertEquals(expected, value.intOrNull, key)
        assertEquals(expected.toLong(), value.longOrNull, key)
    }

    private fun assertLong(root: JsonObject, key: String, expected: Long) {
        val value = number(root, key)
        assertEquals(expected, value.longOrNull, key)
    }

    private fun assertString(root: JsonObject, key: String, expected: String) {
        val value = root.getValue(key)
        assertTrue(value is JsonPrimitive && value.isString, "$key must be a string")
        assertEquals(expected, value.contentOrNull, key)
    }

    private fun assertBoolean(root: JsonObject, key: String, expected: Boolean) {
        val value = root.getValue(key)
        assertTrue(value is JsonPrimitive && !value.isString, "$key must be a boolean")
        assertEquals(expected, value.booleanOrNull, key)
    }

    private fun number(root: JsonObject, key: String): JsonPrimitive {
        val value = root.getValue(key)
        assertTrue(value is JsonPrimitive && !value.isString, "$key must be a number")
        assertTrue('.' !in value.content, "$key must not be a float")
        return value
    }

    private companion object {
        val EXPECTED_KEYS = setOf(
            "schema_version",
            "protocol_version",
            "email_max_utf8_bytes",
            "password_min_code_points",
            "password_max_code_points",
            "handle_min_ascii_chars",
            "handle_max_ascii_chars",
            "note_max_utf8_bytes",
            "place_label_max_utf8_bytes",
            "photo_count_min",
            "photo_count_max",
            "photo_ordinal_min",
            "photo_ordinal_max",
            "non_photo_ordinal",
            "recognition_manifest_count",
            "content_manifest_count",
            "recipient_envelope_count",
            "normalized_photo_media_type",
            "normalized_photo_max_long_edge_px",
            "normalized_photo_max_plaintext_bytes",
            "artifact_aead_overhead_bytes",
            "encrypted_photo_max_ciphertext_bytes",
            "recognition_manifest_max_ciphertext_bytes",
            "content_manifest_max_ciphertext_bytes",
            "recipient_envelope_max_ciphertext_bytes",
            "total_capsule_max_ciphertext_bytes",
            "draft_lifetime_seconds",
            "incoming_page_default",
            "incoming_page_max",
            "mvp_track_attachment_allowed",
        )
    }
}
