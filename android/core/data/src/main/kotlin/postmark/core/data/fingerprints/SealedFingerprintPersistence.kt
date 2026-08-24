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

    /**
     * Marks exactly the [origin] front/back pair of [capsuleId] as preferred
     * and clears the flag from every other row of that capsule, demoting any
     * previous pair (e.g. sender fallback) in one transaction.
     */
    suspend fun setPreferredPair(
        capsuleId: String,
        origin: FingerprintOrigin,
    )

    /** Best-effort removal of one sealed baseline row plus its ciphertext. */
    suspend fun deleteBaseline(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
    )
}
