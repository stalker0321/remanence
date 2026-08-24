package app.postmark.memory.create

import java.util.UUID
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.data.fingerprints.DuplicateFingerprintException
import postmark.core.data.fingerprints.SealedFingerprintPersistence

/** One side's serialized fingerprint ready for encrypted persistence. */
class StagedSideFingerprint(
    val profileId: String,
    val side: FingerprintSide,
    val serializedBytes: ByteArray,
)

/**
 * Extraction port over :core:recognition so this repository stays free of
 * OpenCV and bitmap types; the real adapter runs the bounded capture
 * pipeline plus ORB extraction at wiring time.
 */
fun interface SideFingerprintExtractor {
    fun extract(side: FingerprintSide): StagedSideFingerprint
}

/**
 * Create-session capture persistence (docs/implementation-plan.md M1-R17):
 * captures the postcard FRONT first, sealing the fingerprint before it ever
 * touches disk, and refuses a second front for the same capsule instead of
 * silently replacing the baseline.
 */
class CreateSessionFingerprintRepository(
    private val persistence: SealedFingerprintPersistence,
    private val extractor: SideFingerprintExtractor,
) {

    /**
     * Extracts and persists the sender FRONT baseline for [capsuleId];
     * returns the new fingerprint ID.
     */
    suspend fun captureFront(capsuleId: String): String {
        requireValidCapsuleId(capsuleId)
        val staged = extractor.extract(FingerprintSide.FRONT)
        require(staged.side == FingerprintSide.FRONT) { "extractor returned wrong side" }
        return try {
            persistence.persist(
                capsuleId = capsuleId,
                side = FingerprintSide.FRONT,
                origin = FingerprintOrigin.SENDER,
                profileId = staged.profileId,
                plaintextBytes = staged.serializedBytes,
            )
        } catch (duplicate: DuplicateFingerprintException) {
            throw IllegalStateException("front baseline already captured for this capsule", duplicate)
        }
    }

    private fun requireValidCapsuleId(capsuleId: String) {
        try {
            UUID.fromString(capsuleId)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("capsule id must be a canonical UUID string")
        }
        require(capsuleId == capsuleId.lowercase()) { "capsule id must be lowercase" }
    }
}
