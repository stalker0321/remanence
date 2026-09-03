package dev.hryshyn.remanence.core.data.fingerprints

import dev.hryshyn.remanence.core.data.db.FingerprintOrigin

/** The captured FRONT of the delivered postcard, ready for sealed persistence. */
data class ReceivedFrontCapture(
    val profileId: String,
    val serializedBytes: ByteArray,
)

/** The initial delivered baseline already exists and can never be replaced. */
class ImmutableBaselineException(capsuleId: String) :
    IllegalStateException("recipient baseline for capsule $capsuleId already exists; it is immutable")

/**
 * M1-M16 (docs/recognition.md section 10): after a verified first receipt -
 * automatic or explicitly confirmed - builds the recipient fingerprint from
 * the delivered front capture, seals it locally as RECIPIENT origin, marks
 * it preferred, and leaves any SENDER baseline untouched as fallback. The
 * initial recipient baseline is immutable: later scans never silently
 * overwrite it.
 */
class RecipientBaselineCreator(
    private val persistence: SealedFingerprintPersistence,
) {

    suspend fun createAfterVerifiedReceipt(
        capsuleId: String,
        front: ReceivedFrontCapture,
    ) {
        require(front.serializedBytes.isNotEmpty()) {
            "captured fingerprint must not be empty"
        }
        if (persistence.hasBaseline(capsuleId, FingerprintOrigin.RECIPIENT)) {
            throw ImmutableBaselineException(capsuleId)
        }

        persistence.persist(
            capsuleId = capsuleId,
            origin = FingerprintOrigin.RECIPIENT,
            profileId = front.profileId,
            plaintextBytes = front.serializedBytes,
        )
        // Demotes the sender baseline to fallback in the same operation.
        persistence.setPreferredOrigin(capsuleId, FingerprintOrigin.RECIPIENT)
    }
}
