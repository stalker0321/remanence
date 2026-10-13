package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import java.security.GeneralSecurityException

/**
 * T186 M14a: trusted admission of a fetched claimed envelope for the
 * receiver-owned sync/scan path (R12 redownload, R18 control restore,
 * feed envelope refs).
 *
 * Gate order: directory bundle owner vs statement sender (fail closed
 * on OWNER_MISMATCH before any crypto — a substituted bundle never
 * yields proof readiness) → full [PendingClaimedEnvelope.accept]
 * (digest, strict/canonical, authoritative sender signature, S/R
 * agreement, deployment-pinned service signature, owned
 * account/bundle/digest/time checks, HPKE open, D/KC recomputation,
 * exact-K shape). The opened plaintext is zeroized in `finally`;
 * callers own the returned copies and must seal or zeroize them —
 * never persist them next to the index.
 *
 * Result typing matches acceptance: [Admitted] requires the
 * deployment-pinned service signature; without it the same chain
 * yields [PartiallyAdmitted], typed untrusted, never admission into
 * scan/display.
 */
object PendingTrustedAdmission {

    /** Locally owned expectations, checked BEFORE admission/HPKE. */
    data class LocalExpectation(
        val recipientUserId: String,
        val recipientKeyBundleId: String,
        /** Directory-fetched bundle owner (must equal the S sender). */
        val directoryBundleOwnerUserId: String,
        /** Directory-fetched public keyset bytes (what the server hashed). */
        val recipientPublicKeyset: ByteArray,
        val nowEpochSeconds: Long,
        /**
         * Authorized deployment-pinned service key id. When non-null, BOTH
         * carried ids must equal the pin. Null while undistributed:
         * partial path only.
         */
        val authorizedServiceKeyId: String?,
    )

    sealed interface Admission {
        data class Admitted(
            val capsuleAeadKeyset: ByteArray,
            val addressOpening: AddressOpening,
        ) : Admission

        /**
         * Everything EXCEPT the service signature verified. Explicitly
         * untrusted: never scan/display admission, never a grant.
         */
        data class PartiallyAdmitted(
            val capsuleAeadKeyset: ByteArray,
            val addressOpening: AddressOpening,
        ) : Admission
    }

    @Suppress("LongParameterList")
    fun accept(
        sealedEnvelope: ByteArray,
        expectedEnvelopeSha256: ByteArray,
        statementBytes: ByteArray,
        claimBytes: ByteArray,
        senderSignature: ByteArray,
        claimSignature: ByteArray,
        recipientPrivateHandle: KeysetHandle,
        senderVerifyHandle: KeysetHandle,
        /** Null until the service key is distributed → partial only. */
        serviceVerifyHandle: KeysetHandle?,
        expected: LocalExpectation,
    ): Admission {
        // Owner-pair gate BEFORE any crypto: the directory bundle owner
        // must be the statement's claimed sender.
        val statement = try {
            dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
                .parseFrom(statementBytes)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending publish statement malformed")
        }
        if (!uuidEquals(statement.senderUserId, expected.directoryBundleOwnerUserId)) {
            throw GeneralSecurityException("pending sender owner mismatch")
        }
        return when (
            val acceptance = PendingClaimedEnvelope.accept(
                sealedEnvelope = sealedEnvelope,
                expectedEnvelopeSha256 = expectedEnvelopeSha256,
                statementBytes = statementBytes,
                claimBytes = claimBytes,
                senderSignature = senderSignature,
                claimSignature = claimSignature,
                recipientPrivateHandle = recipientPrivateHandle,
                senderVerifyHandle = senderVerifyHandle,
                serviceVerifyHandle = serviceVerifyHandle,
                expected = PendingClaimedEnvelope.LocalExpectation(
                    recipientUserId = expected.recipientUserId,
                    recipientKeyBundleId = expected.recipientKeyBundleId,
                    recipientPublicKeyset = expected.recipientPublicKeyset,
                    nowEpochSeconds = expected.nowEpochSeconds,
                    authorizedServiceKeyId = expected.authorizedServiceKeyId,
                ),
            )
        ) {
            is PendingClaimedEnvelope.EnvelopeAcceptance.Accepted ->
                Admission.Admitted(acceptance.capsuleAeadKeyset, acceptance.addressOpening)
            is PendingClaimedEnvelope.EnvelopeAcceptance.PartiallyAccepted ->
                Admission.PartiallyAdmitted(acceptance.capsuleAeadKeyset, acceptance.addressOpening)
        }
    }

    private fun uuidEquals(actual: com.google.protobuf.ByteString, expectedRest: String): Boolean {
        val expected = try {
            PendingUuid16.of(expectedRest, "expectation")
        } catch (_: GeneralSecurityException) {
            return false
        }
        return actual.toByteArray().contentEquals(expected)
    }
}
