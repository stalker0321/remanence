package dev.hryshyn.remanence.core.crypto

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T186 M4: strict wire-gate regressions against the frozen fixtures.
 * Tag sets are verified against `delivery_v2.proto` (notably
 * `KeyCommitmentInput` 1..4 — `capsule_aead_keyset` is field 4).
 */
class PendingStrictProtoTest {

    private val fixtures = Json { ignoreUnknownKeys = true }

    private fun canonical(): JsonObject = fixtures.parseToJsonElement(
        javaClass.getResourceAsStream("/delivery-v2-canonical.json")!!
            .bufferedReader().readText(),
    ).jsonObject

    private fun str(o: JsonObject, key: String): String =
        o.getValue(key).jsonPrimitive.content

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun frozenKeyCommitmentInputPassesFixedSpec() {
        // Regression: the spec must be 1..4 (field 4 is the key bytes).
        val kcInput = unhex(str(canonical(), "key_commitment_input_hex"))
        PendingStrictProto.requireNoUnknownFields(
            kcInput,
            PendingStrictProto.KEY_COMMITMENT_INPUT,
            "key commitment input",
        )
        assertEquals(setOf(1, 2, 3, 4), PendingStrictProto.KEY_COMMITMENT_INPUT.knownFields)
    }

    @Test
    fun frozenPublishAndClaimPass() {
        val f = canonical()
        PendingStrictProto.requireNoUnknownFields(
            unhex(str(f, "pending_publish_statement_s_hex")),
            PendingStrictProto.PUBLISH_STATEMENT_V2,
            "publish statement",
        )
        PendingStrictProto.requireNoUnknownFields(
            unhex(str(f, "claim_binding_r_hex")),
            PendingStrictProto.CLAIM_BINDING_V1,
            "claim binding",
        )
    }

    @Test
    fun unknownFieldRejected() {
        val f = canonical()
        val withUnknown = unhex(str(f, "key_commitment_input_hex")) +
            byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingStrictProto.requireNoUnknownFields(
                withUnknown,
                PendingStrictProto.KEY_COMMITMENT_INPUT,
                "key commitment input",
            )
        }
        assertEquals("pending key commitment input has unknown fields", failure.message)
    }

    @Test
    fun truncatedInputMalformed() {
        val f = canonical()
        val truncated = unhex(str(f, "pending_publish_statement_s_hex")).copyOf(10)
        assertFailsWith<GeneralSecurityException> {
            PendingStrictProto.requireNoUnknownFields(
                truncated,
                PendingStrictProto.PUBLISH_STATEMENT_V2,
                "publish statement",
            )
        }
    }
}
