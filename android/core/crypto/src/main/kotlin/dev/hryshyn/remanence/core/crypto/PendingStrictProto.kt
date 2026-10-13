package dev.hryshyn.remanence.core.crypto

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.security.GeneralSecurityException

/**
 * T186 M4: strict canonical unknown-field gate (mirrors server
 * `_require_no_unknown_fields`: re-serialize equality alone does NOT catch
 * preserved unknown fields, so they are rejected on a wire-level scan
 * BEFORE any parsing or crypto).
 *
 * The scanner walks raw tags with [CodedInputStream] and rejects unknown
 * field numbers, group wire types, truncation, and trailing data. Known
 * nested-message fields recurse with their own frozen tag sets (from
 * `delivery_v2.proto`; tags frozen by contract). Wire-type mismatches on
 * known fields fail later at typed parse; they cannot pass this gate into
 * crypto either way since parsing throws first.
 */
object PendingStrictProto {

    data class MessageSpec(
        val knownFields: Set<Int>,
        val nested: Map<Int, MessageSpec> = emptyMap(),
    )

    val ADDRESS_OPENING: MessageSpec = MessageSpec(setOf(1, 2, 3, 4))
    // KeyCommitmentInput: version=1, capsule_id=2, delivery_target_id=3,
    // capsule_aead_keyset=4 (key bytes). Verified against delivery_v2.proto.
    val KEY_COMMITMENT_INPUT: MessageSpec = MessageSpec(setOf(1, 2, 3, 4))
    val ARTIFACT_AAD_V2: MessageSpec = MessageSpec((1..7).toSet())
    val ARTIFACT_BINDING_V2: MessageSpec = MessageSpec((1..5).toSet())
    val PUBLISH_STATEMENT_V2: MessageSpec =
        MessageSpec((1..12).toSet(), mapOf(12 to ARTIFACT_BINDING_V2))
    val CLAIM_BINDING_V1: MessageSpec = MessageSpec((1..13).toSet())
    val CLAIM_CHALLENGE_V1: MessageSpec = MessageSpec((1..8).toSet())
    val CLAIMED_ENVELOPE_CONTEXT_V2: MessageSpec = MessageSpec((1..8).toSet())
    val CLAIMED_ENVELOPE_PLAINTEXT_V2: MessageSpec = MessageSpec(
        setOf(1, 2, 3),
        mapOf(1 to CLAIMED_ENVELOPE_CONTEXT_V2, 3 to ADDRESS_OPENING),
    )

    fun requireNoUnknownFields(bytes: ByteArray, spec: MessageSpec, what: String) {
        if (bytes.isEmpty()) throw GeneralSecurityException("pending $what is empty")
        val input = CodedInputStream.newInstance(bytes)
        try {
            while (true) {
                val tag = input.readTag()
                if (tag == 0) break
                val fieldNumber = WireFormat.getTagFieldNumber(tag)
                val wireType = WireFormat.getTagWireType(tag)
                if (wireType == WireFormat.WIRETYPE_END_GROUP || fieldNumber == 0) {
                    throw GeneralSecurityException("pending $what has invalid framing")
                }
                val nested = spec.nested[fieldNumber]
                if (fieldNumber !in spec.knownFields) {
                    throw GeneralSecurityException("pending $what has unknown fields")
                }
                if (nested != null) {
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        throw GeneralSecurityException("pending $what has invalid framing")
                    }
                    val length = input.readRawVarint32()
                    if (length < 0) throw GeneralSecurityException("pending $what is malformed")
                    requireNoUnknownFields(input.readRawBytes(length), nested, what)
                } else {
                    if (!input.skipField(tag)) {
                        throw GeneralSecurityException("pending $what is malformed")
                    }
                }
            }
            input.checkLastTagWas(0)
        } catch (failure: GeneralSecurityException) {
            throw failure
        } catch (_: Exception) {
            throw GeneralSecurityException("pending $what is malformed")
        }
    }
}
