package dev.hryshyn.remanence.create

import java.util.UUID
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.fingerprints.DuplicateFingerprintException
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence

/** The serialized FRONT fingerprint handed off by the capture pipeline. */
class StagedSideFingerprint(
    val profileId: String,
    /** Caller hands this buffer to the repository, which wipes it on return. */
    val serializedBytes: ByteArray,
)

/**
 * Extraction port over :core:recognition so this repository stays free of
 * OpenCV and bitmap types; the real adapter runs the bounded capture
 * pipeline plus ORB extraction at wiring time.
 */
fun interface SideFingerprintExtractor {
    fun extract(): StagedSideFingerprint
}

/**
 * Create-session capture persistence (docs/implementation-plan.md M1-R17):
 * captures the postcard FRONT, sealing the fingerprint before it ever
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
        val staged = extractor.extract()
        return try {
            persistence.persist(
                capsuleId = capsuleId,
                origin = FingerprintOrigin.SENDER,
                profileId = staged.profileId,
                plaintextBytes = staged.serializedBytes,
            )
        } catch (duplicate: DuplicateFingerprintException) {
            throw IllegalStateException("front baseline already captured for this capsule", duplicate)
        } finally {
            staged.serializedBytes.fill(0)
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
