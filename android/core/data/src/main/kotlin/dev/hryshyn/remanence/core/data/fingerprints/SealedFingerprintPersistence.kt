package dev.hryshyn.remanence.core.data.fingerprints

import dev.hryshyn.remanence.core.data.db.FingerprintOrigin

/**
 * Persistence boundary for sealed local fingerprints; implemented by
 * [EncryptedFingerprintStore] and consumed by session repositories so
 * orchestration code never depends on Room or file layout.
 */
interface SealedFingerprintPersistence {

    suspend fun persist(
        capsuleId: String,
        origin: FingerprintOrigin,
        profileId: String,
        plaintextBytes: ByteArray,
    ): String

    /** True when this capsule already holds its sealed FRONT [origin] baseline. */
    suspend fun hasBaseline(
        capsuleId: String,
        origin: FingerprintOrigin,
    ): Boolean

    /** Unseals exactly one stored fingerprint for local matching/staging use. */
    suspend fun decrypt(fingerprintId: String): ByteArray

    /**
     * Marks the [origin] FRONT baseline of [capsuleId] as preferred and clears
     * the flag from every other row of that capsule.
     */
    suspend fun setPreferredOrigin(
        capsuleId: String,
        origin: FingerprintOrigin,
    )

    /** Best-effort removal of one sealed baseline row plus its ciphertext. */
    suspend fun deleteBaseline(
        capsuleId: String,
        origin: FingerprintOrigin,
    )
}
