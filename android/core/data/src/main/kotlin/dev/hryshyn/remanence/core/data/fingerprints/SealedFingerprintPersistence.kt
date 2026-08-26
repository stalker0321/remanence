package dev.hryshyn.remanence.core.data.fingerprints

import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide

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

    /** Unseals exactly one stored fingerprint for local matching/staging use. */
    suspend fun decrypt(fingerprintId: String): ByteArray

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
