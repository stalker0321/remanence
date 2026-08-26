package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO over encrypted fingerprint records. Queries are scoped to one capsule;
 * no method projects an enumerable list of all memories to UI code.
 *
 * M2-P02/P03: every account-owned read, preferred-pair transition, and delete
 * REQUIRES the row's immutable owner_user_id; no unscoped account-owned query
 * exists. Writes insert only through [insertAll] with the authoritative owner.
 */
@Dao
interface RecognitionFingerprintDao {

    /** Strict insert; duplicate (capsule, side, origin) baselines are rejected. */
    @Insert
    suspend fun insertAll(fingerprints: List<RecognitionFingerprintEntity>)

    @Query("DELETE FROM recognition_fingerprint")
    suspend fun clear()

    // ------------------------------------------------------------------
    // Account-scoped surface (M2-P03): the ONLY scan source is the owner's
    // sealed fingerprint index.
    // ------------------------------------------------------------------

    /** The account's full sealed fingerprint index; the only sanctioned scan source from M2 onward. */
    @Query("SELECT * FROM recognition_fingerprint WHERE owner_user_id = :ownerUserId")
    suspend fun getAllForOwner(ownerUserId: String): List<RecognitionFingerprintEntity>

    @Query(
        "SELECT * FROM recognition_fingerprint " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getAllByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): List<RecognitionFingerprintEntity>

    @Query(
        "SELECT * FROM recognition_fingerprint " +
            "WHERE fingerprint_id = :fingerprintId AND owner_user_id = :ownerUserId",
    )
    suspend fun getByFingerprintIdAndOwner(fingerprintId: String, ownerUserId: String): RecognitionFingerprintEntity?

    @Query(
        "SELECT * FROM recognition_fingerprint WHERE capsule_id = :capsuleId AND origin = :origin " +
            "AND owner_user_id = :ownerUserId",
    )
    suspend fun getByCapsuleIdAndOriginAndOwner(
        capsuleId: String,
        origin: FingerprintOrigin,
        ownerUserId: String,
    ): List<RecognitionFingerprintEntity>

    /**
     * Owner-guarded preferred-pair transition: marks exactly the two rows of
     * [origin] for [capsuleId] of THIS account and clears the flag from every
     * other row of the same owned capsule.
     */
    @Transaction
    suspend fun setPreferredPairForOwner(capsuleId: String, origin: FingerprintOrigin, ownerUserId: String) {
        clearPreferredForOwner(capsuleId, ownerUserId)
        markPreferredForOwner(capsuleId, origin, ownerUserId)
    }

    @Query(
        "UPDATE recognition_fingerprint SET preferred = 0 " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun clearPreferredForOwner(capsuleId: String, ownerUserId: String)

    @Query(
        "UPDATE recognition_fingerprint SET preferred = 1 " +
            "WHERE capsule_id = :capsuleId AND origin = :origin AND owner_user_id = :ownerUserId " +
            "AND side IN ('FRONT', 'BACK')",
    )
    suspend fun markPreferredForOwner(capsuleId: String, origin: FingerprintOrigin, ownerUserId: String)

    /** Removes one sealed baseline row ONLY when owned by [ownerUserId]; reports rows removed. */
    @Query(
        "DELETE FROM recognition_fingerprint " +
            "WHERE fingerprint_id = :fingerprintId AND owner_user_id = :ownerUserId",
    )
    suspend fun deleteByFingerprintIdAndOwner(fingerprintId: String, ownerUserId: String): Int

    /** Reports rows removed; refuses any capsule not owned by [ownerUserId]. */
    @Query(
        "DELETE FROM recognition_fingerprint WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun deleteByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): Int
}
