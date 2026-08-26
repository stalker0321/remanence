package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO over encrypted fingerprint records. Queries are scoped to one capsule;
 * no method projects an enumerable list of all memories to UI code.
 */
@Dao
interface RecognitionFingerprintDao {

    /** Strict insert; duplicate (capsule, side, origin) baselines are rejected. */
    @Insert
    suspend fun insertAll(fingerprints: List<RecognitionFingerprintEntity>)

    @Query("SELECT * FROM recognition_fingerprint WHERE capsule_id = :capsuleId")
    suspend fun getAllByCapsuleId(capsuleId: String): List<RecognitionFingerprintEntity>

    /** Scan index source: every locally sealed fingerprint row of this account. */
    @Query("SELECT * FROM recognition_fingerprint")
    suspend fun getAll(): List<RecognitionFingerprintEntity>

    /** Single-record lookup used for load/delete of one sealed baseline. */
    @Query("SELECT * FROM recognition_fingerprint WHERE fingerprint_id = :fingerprintId")
    suspend fun getByFingerprintId(fingerprintId: String): RecognitionFingerprintEntity?

    @Query("SELECT * FROM recognition_fingerprint WHERE capsule_id = :capsuleId AND origin = :origin")
    suspend fun getByCapsuleIdAndOrigin(capsuleId: String, origin: FingerprintOrigin): List<RecognitionFingerprintEntity>

    /**
     * Marks exactly the two rows of [origin] for [capsuleId] as preferred and
     * clears the flag from every other row of that capsule.
     */
    @Transaction
    suspend fun setPreferredPair(capsuleId: String, origin: FingerprintOrigin) {
        clearPreferred(capsuleId)
        markPreferred(capsuleId, origin)
    }

    @Query("UPDATE recognition_fingerprint SET preferred = 0 WHERE capsule_id = :capsuleId")
    suspend fun clearPreferred(capsuleId: String)

    @Query(
        "UPDATE recognition_fingerprint SET preferred = 1 " +
            "WHERE capsule_id = :capsuleId AND origin = :origin AND side IN ('FRONT', 'BACK')",
    )
    suspend fun markPreferred(capsuleId: String, origin: FingerprintOrigin)

    /** Removes exactly one sealed baseline row (used for pair-rollback). */
    @Query("DELETE FROM recognition_fingerprint WHERE fingerprint_id = :fingerprintId")
    suspend fun deleteByFingerprintId(fingerprintId: String)

    @Query("DELETE FROM recognition_fingerprint WHERE capsule_id = :capsuleId")
    suspend fun deleteByCapsuleId(capsuleId: String)

    @Query("DELETE FROM recognition_fingerprint")
    suspend fun clear()

    // ------------------------------------------------------------------
    // M2-P02 account-scoped primitives. Every durable read, CAS, and delete
    // is guarded by the row's immutable owning account so another login can
    // never enumerate or resume it. P03 converts the scan/index paths onto
    // these (notably the account-scoped candidate index).
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
