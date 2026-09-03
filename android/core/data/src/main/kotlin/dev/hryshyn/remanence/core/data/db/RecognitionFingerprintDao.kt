package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO over encrypted fingerprint records. Queries are scoped to one capsule;
 * no method projects an enumerable list of all memories to UI code.
 *
 * M2-P02/P03: every account-owned read, preferred-origin transition, and delete
 * REQUIRES the row's immutable owner_user_id; no unscoped account-owned query
 * exists. Writes insert only through [insertAll] with the authoritative owner.
 */
@Dao
abstract class RecognitionFingerprintDao {

    /**
     * Strict owner-authorized insert. The complete batch is validated before
     * the first Room write; duplicate (capsule, origin) baselines are
     * still rejected by the existing uniqueness constraint.
     */
    @Transaction
    open suspend fun insertAll(
        ownerUserId: String,
        fingerprints: List<RecognitionFingerprintEntity>,
    ) {
        fingerprints.forEach { fingerprint ->
            require(fingerprint.ownerUserId == ownerUserId) {
                "fingerprint ${fingerprint.fingerprintId} owner does not match the authoritative owner"
            }
        }
        fingerprints.forEach { fingerprint ->
            val existingOwner = findOwnerOf(fingerprint.fingerprintId)
            if (existingOwner != null && existingOwner != ownerUserId) {
                throw IllegalStateException(
                    "fingerprint ${fingerprint.fingerprintId} already belongs to another local account",
                )
            }
        }
        insertStrict(fingerprints)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertStrict(fingerprints: List<RecognitionFingerprintEntity>)

    /** Minimal immutable-ID ownership probe used only during authorized insert preflight. */
    @Query("SELECT owner_user_id FROM recognition_fingerprint WHERE fingerprint_id = :fingerprintId LIMIT 1")
    protected abstract suspend fun findOwnerOf(fingerprintId: String): String?

    /** Owner-scoped teardown: removes only rows belonging to [ownerUserId]. */
    @Query("DELETE FROM recognition_fingerprint WHERE owner_user_id = :ownerUserId")
    abstract suspend fun clearForOwner(ownerUserId: String)

    // ------------------------------------------------------------------
    // Account-scoped surface (M2-P03): the ONLY scan source is the owner's
    // sealed fingerprint index.
    // ------------------------------------------------------------------

    /** The account's full sealed fingerprint index; the only sanctioned scan source from M2 onward. */
    @Query("SELECT * FROM recognition_fingerprint WHERE owner_user_id = :ownerUserId")
    abstract suspend fun getAllForOwner(ownerUserId: String): List<RecognitionFingerprintEntity>

    @Query(
        "SELECT * FROM recognition_fingerprint " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getAllByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): List<RecognitionFingerprintEntity>

    @Query(
        "SELECT * FROM recognition_fingerprint " +
            "WHERE fingerprint_id = :fingerprintId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getByFingerprintIdAndOwner(fingerprintId: String, ownerUserId: String): RecognitionFingerprintEntity?

    @Query(
        "SELECT * FROM recognition_fingerprint WHERE capsule_id = :capsuleId AND origin = :origin " +
            "AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getByCapsuleIdAndOriginAndOwner(
        capsuleId: String,
        origin: FingerprintOrigin,
        ownerUserId: String,
    ): List<RecognitionFingerprintEntity>

    /**
     * Owner-guarded preferred-origin transition: marks the FRONT row of
     * [origin] for [capsuleId] of THIS account and clears the flag from every
     * other row of the same owned capsule.
     */
    @Transaction
    open suspend fun setPreferredOriginForOwner(capsuleId: String, origin: FingerprintOrigin, ownerUserId: String) {
        clearPreferredForOwner(capsuleId, ownerUserId)
        markPreferredForOwner(capsuleId, origin, ownerUserId)
    }

    @Query(
        "UPDATE recognition_fingerprint SET preferred = 0 " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    protected abstract suspend fun clearPreferredForOwner(capsuleId: String, ownerUserId: String)

    @Query(
        "UPDATE recognition_fingerprint SET preferred = 1 " +
            "WHERE capsule_id = :capsuleId AND origin = :origin AND owner_user_id = :ownerUserId",
    )
    protected abstract suspend fun markPreferredForOwner(capsuleId: String, origin: FingerprintOrigin, ownerUserId: String)

    /** Removes one sealed baseline row ONLY when owned by [ownerUserId]; reports rows removed. */
    @Query(
        "DELETE FROM recognition_fingerprint " +
            "WHERE fingerprint_id = :fingerprintId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun deleteByFingerprintIdAndOwner(fingerprintId: String, ownerUserId: String): Int

    /** Reports rows removed; refuses any capsule not owned by [ownerUserId]. */
    @Query(
        "DELETE FROM recognition_fingerprint WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun deleteByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): Int
}
