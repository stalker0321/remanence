package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.GeneralSecurityException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T186 M3b: real v2 publish/claim acceptance against the frozen T185 M1
 * fixtures. Real Tink Ed25519 verification over the exact frozen domain
 * input — never mocks, never disabled checks.
 */
class PendingPublishVerifierTest {

    private val fixtures = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): JsonObject = fixtures.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText(),
    ).jsonObject

    private fun str(o: JsonObject, key: String): String =
        o.getValue(key).jsonPrimitive.content

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun triple(): Triple<ByteArray, ByteArray, ByteArray> {
        val canonical = resource("delivery-v2-canonical.json")
        val crypto = resource("delivery-v2-crypto.json")
        return Triple(
            Base64.getDecoder().decode(str(crypto, "sender_ed25519_public_keyset_b64")),
            unhex(str(canonical, "pending_publish_statement_s_hex")),
            unhex(str(crypto, "pending_publish_signature_69_hex")),
        )
    }

    @Test
    fun frozenPublishTripleVerifies() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        PendingPublishVerifier.verifyPublish(keyset, statement, signature)
        // The generated classes parse the same bytes the signature covers.
        assertEquals(2, PendingPublishStatementV2.parseFrom(statement).protocolVersion)
    }

    @Test
    fun wrongKeyRejected() {
        TinkPrimitives.ensureRegistered()
        val (_, statement, signature) = triple()
        // A well-formed but unrelated Ed25519 keyset must never verify a
        // frozen publish signature (key binding, not just format).
        val other = com.google.crypto.tink.KeysetHandle.generateNew(
            com.google.crypto.tink.KeyTemplates.get("ED25519"),
        ).publicKeysetHandle
        val otherPublicBytes = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            other,
        )
        assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(otherPublicBytes, statement, signature)
        }
    }

    @Test
    fun truncatedSignatureRejected() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(keyset, statement, signature.copyOf(68))
        }
    }

    @Test
    fun tamperedStatementRejected() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        val tampered = statement.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(keyset, tampered, signature)
        }
    }

    @Test
    fun downgradedVersionRejectedBeforeCrypto() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        val downgraded = PendingPublishStatementV2.parseFrom(statement)
            .toBuilder().setProtocolVersion(1).build().toByteArray()
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(keyset, downgraded, signature)
        }
        assertEquals("pending publish version unsupported", failure.message)
    }

    @Test
    fun frozenClaimAgreementHolds() {
        val canonical = resource("delivery-v2-canonical.json")
        PendingPublishVerifier.verifyClaimAgreement(
            unhex(str(canonical, "pending_publish_statement_s_hex")),
            unhex(str(canonical, "claim_binding_r_hex")),
        )
    }

    @Test
    fun mismatchedClaimRejected() {
        val canonical = resource("delivery-v2-canonical.json")
        val statement = unhex(str(canonical, "pending_publish_statement_s_hex"))
        val claim = unhex(str(canonical, "claim_binding_r_hex")).copyOf()
            .also { it[10] = (it[10] + 1).toByte() }
        assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyClaimAgreement(statement, claim)
        }
    }

    @Test
    fun statementHashMatchesFrozenDigest() {
        val canonical = resource("delivery-v2-canonical.json")
        val statement = unhex(str(canonical, "pending_publish_statement_s_hex"))
        assertContentEquals(
            unhex(str(canonical, "publish_statement_sha256_h_hex")),
            PendingPublishVerifier.statementHash(statement),
        )
    }

    @Test
    fun unknownFieldsRejectedBeforeCrypto() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        // Field 99, varint wire type: preserved by protobuf runtimes, so
        // re-serialize equality alone would miss it — the strict gate must
        // reject it before any signature work.
        val withUnknown = statement + byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(keyset, withUnknown, signature)
        }
        assertEquals("pending publish statement has unknown fields", failure.message)
    }

    @Test
    fun unknownClaimFieldsRejected() {
        val canonical = resource("delivery-v2-canonical.json")
        val statement = unhex(str(canonical, "pending_publish_statement_s_hex"))
        val claim = unhex(str(canonical, "claim_binding_r_hex")) +
            byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyClaimAgreement(statement, claim)
        }
        assertEquals("pending claim binding has unknown fields", failure.message)
    }

    @Test
    fun truncatedStatementMalformed() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(keyset, statement.copyOf(10), signature)
        }
    }

    /** Splits top-level TLV spans so tests can build non-canonical inputs. */
    private fun topLevelSpans(bytes: ByteArray): List<ByteArray> {
        val input = com.google.protobuf.CodedInputStream.newInstance(bytes)
        val spans = mutableListOf<ByteArray>()
        while (true) {
            val start = input.totalBytesRead
            val tag = input.readTag()
            if (tag == 0) break
            if (!input.skipField(tag)) break
            spans += bytes.copyOfRange(start, input.totalBytesRead)
        }
        return spans
    }

    @Test
    fun duplicateFieldRejectedAsNonCanonical() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        val firstSpan = topLevelSpans(statement).first()
        // Same value twice: parses to the identical message, but the bytes
        // are not canonical.
        val withDup = statement + firstSpan
        val parsedDup = dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2.parseFrom(withDup)
        val parsedOrig = dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2.parseFrom(statement)
        assertEquals(parsedOrig, parsedDup)
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(keyset, withDup, signature)
        }
        assertEquals("pending publish statement not canonical", failure.message)
    }

    @Test
    fun reorderedFieldsRejectedAsNonCanonical() {
        TinkPrimitives.ensureRegistered()
        val (keyset, statement, signature) = triple()
        val spans = topLevelSpans(statement)
        assertEquals(true, spans.size > 1)
        val reordered = spans.reversed().reduce { acc, span -> acc + span }
        val parsedReordered = dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2.parseFrom(reordered)
        val parsedOrig = dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2.parseFrom(statement)
        assertEquals(parsedOrig, parsedReordered)
        val failure = assertFailsWith<GeneralSecurityException> {
            PendingPublishVerifier.verifyPublish(keyset, reordered, signature)
        }
        assertEquals("pending publish statement not canonical", failure.message)
    }
}
