package postmark.core.data.db

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

    @Query("DELETE FROM recognition_fingerprint WHERE capsule_id = :capsuleId")
    suspend fun deleteByCapsuleId(capsuleId: String)

    @Query("DELETE FROM recognition_fingerprint")
    suspend fun clear()
}
