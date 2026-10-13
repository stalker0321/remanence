package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * T186 M3b: real v2 publish/claim verification (T185 `crypto-contract.md`
 * §2–§4, messages §1). No mocks, no disabled checks:
 *
 * - [verifyPublish]: parses `PendingPublishStatementV2` with the committed
 *   generated lite classes, pins `protocol_version == 2`, rebuilds the exact
 *   domain input `postmark/pending-publish/v2 || 0x00 || S`, and verifies the
 *   69-byte Tink Ed25519 signature with the sender public keyset. The NUL
 *   delimiter intentionally differs from the frozen v1 prefix, so v1 bytes
 *   can never verify here (and vice versa).
 * - [verifyClaimAgreement]: parses `ClaimBindingV1` and checks the §4
 *   S/R agreement (versions, capsule/target, `SHA256(S)`, `D`, policy and
 *   service-key ids) before any envelope/HPKE work. Service-signature and
 *   full HPKE round-trip vectors arrive with server M3; this gate already
 *   rejects mismatched bindings with zero plaintext/grants.
 */
object PendingPublishVerifier {

    /**
     * Structural gate without a sender key (the server checked the S
     * signature at finalize; no sender key is on record client-side).
     * Strict unknown-field scan + typed parse + canonical equality;
     * returns the parsed statement. Real shared caller: the proof flow.
     */
    fun verifyStructure(statementBytes: ByteArray): PendingPublishStatementV2 {
        if (statementBytes.isEmpty()) {
            throw GeneralSecurityException("pending publish statement is empty")
        }
        PendingStrictProto.requireNoUnknownFields(
            statementBytes,
            PendingStrictProto.PUBLISH_STATEMENT_V2,
            "publish statement",
        )
        val statement = try {
            PendingPublishStatementV2.parseFrom(statementBytes)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending publish statement malformed")
        }
        if (statement.protocolVersion != PENDING_PROTOCOL_VERSION) {
            throw GeneralSecurityException("pending publish version unsupported")
        }
        if (!statement.toByteArray().contentEquals(statementBytes)) {
            throw GeneralSecurityException("pending publish statement not canonical")
        }
        return statement
    }

    fun verifyPublish(
        senderEd25519PublicKeyset: ByteArray,
        statementBytes: ByteArray,
        signature: ByteArray,
    ) {
        if (statementBytes.isEmpty()) {
            throw GeneralSecurityException("pending publish statement is empty")
        }
        // Strict canonical gate BEFORE parsing/crypto: preserved unknown
        // fields must never reach verification.
        PendingStrictProto.requireNoUnknownFields(
            statementBytes,
            PendingStrictProto.PUBLISH_STATEMENT_V2,
            "publish statement",
        )
        val statement = try {
            PendingPublishStatementV2.parseFrom(statementBytes)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending publish statement malformed")
        }
        if (statement.protocolVersion != PENDING_PROTOCOL_VERSION) {
            throw GeneralSecurityException("pending publish version unsupported")
        }
        // Canonical form: lite re-serialization emits field-number order
        // with minimal varints and no duplicates, so byte equality holds
        // exactly for canonical inputs (reordered/duplicate/non-minimal
        // bytes parse but never match). Unknowns were already excluded.
        if (!statement.toByteArray().contentEquals(statementBytes)) {
            throw GeneralSecurityException("pending publish statement not canonical")
        }
        if (signature.size != SIGNATURE_LENGTH || signature[0] != TINK_PREFIX_TYPE_BYTE) {
            throw GeneralSecurityException("pending publish signature framing invalid")
        }
        val verifyingHandle = try {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderEd25519PublicKeyset)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending sender keyset malformed")
        }
        val embeddedKeyId = ((signature[1].toInt() and 0xFF) shl 24) or
            ((signature[2].toInt() and 0xFF) shl 16) or
            ((signature[3].toInt() and 0xFF) shl 8) or
            (signature[4].toInt() and 0xFF)
        if (embeddedKeyId != verifyingHandle.keysetInfo.primaryKeyId) {
            throw GeneralSecurityException("pending publish signature key mismatch")
        }
        val input = PENDING_PUBLISH_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + statementBytes
        try {
            verifyingHandle.getPrimitive(PublicKeyVerify::class.java).verify(signature, input)
        } catch (failure: GeneralSecurityException) {
            throw failure
        } catch (_: Exception) {
            throw GeneralSecurityException("pending publish verification failed")
        }
    }

    /**
     * S/R agreement gate (§4): canonical S + claimed R must agree on
     * versions, capsule/target, `H = SHA256(S)`, `D`, policy and the
     * deployment-pinned service key id. Anything else fails closed.
     */
    fun verifyClaimAgreement(statementBytes: ByteArray, claimBytes: ByteArray) {
        PendingStrictProto.requireNoUnknownFields(
            statementBytes,
            PendingStrictProto.PUBLISH_STATEMENT_V2,
            "publish statement",
        )
        PendingStrictProto.requireNoUnknownFields(
            claimBytes,
            PendingStrictProto.CLAIM_BINDING_V1,
            "claim binding",
        )
        val statement = try {
            PendingPublishStatementV2.parseFrom(statementBytes)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending publish statement malformed")
        }
        val claim = try {
            ClaimBindingV1.parseFrom(claimBytes)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending claim binding malformed")
        }
        if (!claim.toByteArray().contentEquals(claimBytes)) {
            throw GeneralSecurityException("pending claim binding not canonical")
        }
        if (!statement.toByteArray().contentEquals(statementBytes)) {
            throw GeneralSecurityException("pending publish statement not canonical")
        }
        if (statement.protocolVersion != PENDING_PROTOCOL_VERSION ||
            claim.bindingVersion != CLAIM_BINDING_VERSION ||
            claim.capsuleProtocolVersion != PENDING_PROTOCOL_VERSION
        ) {
            throw GeneralSecurityException("pending claim version unsupported")
        }
        val statementHash = MessageDigest.getInstance("SHA-256").digest(statementBytes)
        fun eq(a: ByteString, b: ByteString, what: String) {
            if (!a.toByteArray().contentEquals(b.toByteArray())) {
                throw GeneralSecurityException("pending claim agreement failed: $what")
            }
        }
        eq(claim.capsuleId, statement.capsuleId, "capsule")
        eq(claim.deliveryTargetId, statement.deliveryTargetId, "target")
        eq(claim.addressCommitment, statement.addressCommitment, "address-commitment")
        eq(claim.claimSigningKeyId, statement.claimSigningKeyId, "service-key")
        if (!claim.publishStatementSha256.toByteArray().contentEquals(statementHash)) {
            throw GeneralSecurityException("pending claim agreement failed: statement-hash")
        }
        if (claim.claimPolicyVersion != statement.claimPolicyVersion) {
            throw GeneralSecurityException("pending claim agreement failed: policy")
        }
    }

    /** Retired-locator completion must reference the exact published bytes. */
    fun statementHash(statementBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(statementBytes)

    const val PENDING_PUBLISH_DOMAIN: String = "postmark/pending-publish/v2"
    const val PENDING_PROTOCOL_VERSION: Int = 2
    const val CLAIM_BINDING_VERSION: Int = 1

    /** 1 prefix byte + 4 key-id bytes + 64-byte Ed25519 (ADR-007 framing). */
    const val SIGNATURE_LENGTH: Int = 69
    const val TINK_PREFIX_TYPE_BYTE: Byte = 0x01
}
