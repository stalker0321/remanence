package postmark.core.data.fingerprints

import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide

/**
 * Persistence boundary for sealed local fingerprints; implemented by
 * [EncryptedFingerprintStore] and consumed by session repositories so
 * orchestration code never depends on Room or file layout.
 */
interface SealedFingerprintPersistence {

    suspend fun persist(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
        profileId: String,
        plaintextBytes: ByteArray,
    ): String

    /** True when this capsule already holds a sealed [side]/[origin] baseline. */
    suspend fun hasBaseline(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
    ): Boolean
}
