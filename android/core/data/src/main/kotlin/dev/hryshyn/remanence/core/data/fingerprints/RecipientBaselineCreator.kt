package dev.hryshyn.remanence.core.data.fingerprints

import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide

/** One captured side of the delivered postcard, ready for sealed persistence. */
data class ReceivedSideCapture(
    val profileId: String,
    val side: FingerprintSide,
    val serializedBytes: ByteArray,
)

/** The initial delivered baseline already exists and can never be replaced. */
class ImmutableBaselineException(capsuleId: String) :
    IllegalStateException("recipient baseline for capsule $capsuleId already exists; it is immutable")

/**
 * M1-M16 (docs/recognition.md section 10): after a verified first receipt -
 * automatic or explicitly confirmed - builds the recipient fingerprint pair
 * from the delivered front/back captures, seals it locally as RECIPIENT
 * origin, marks that pair preferred, and leaves any SENDER pair untouched as
 * fallback. The initial recipient baseline is immutable: later scans never
 * silently overwrite it.
 */
class RecipientBaselineCreator(
    private val persistence: SealedFingerprintPersistence,
) {

    suspend fun createAfterVerifiedReceipt(
        capsuleId: String,
        front: ReceivedSideCapture,
        back: ReceivedSideCapture,
    ) {
        require(front.side == FingerprintSide.FRONT && back.side == FingerprintSide.BACK) {
            "captures must arrive as FRONT then BACK"
        }
        require(front.serializedBytes.isNotEmpty() && back.serializedBytes.isNotEmpty()) {
            "captured fingerprints must not be empty"
        }
        if (persistence.hasBaseline(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT) ||
            persistence.hasBaseline(capsuleId, FingerprintSide.BACK, FingerprintOrigin.RECIPIENT)
        ) {
            throw ImmutableBaselineException(capsuleId)
        }

        persistence.persist(
            capsuleId = capsuleId,
            side = FingerprintSide.FRONT,
            origin = FingerprintOrigin.RECIPIENT,
            profileId = front.profileId,
            plaintextBytes = front.serializedBytes,
        )
        try {
            persistence.persist(
                capsuleId = capsuleId,
                side = FingerprintSide.BACK,
                origin = FingerprintOrigin.RECIPIENT,
                profileId = back.profileId,
                plaintextBytes = back.serializedBytes,
            )
        } catch (failure: Exception) {
            // Never leave a half-paired baseline behind.
            persistence.deleteBaseline(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.RECIPIENT)
            throw failure
        }
        // Demotes the sender pair to fallback in the same operation.
        persistence.setPreferredPair(capsuleId, FingerprintOrigin.RECIPIENT)
    }
}
