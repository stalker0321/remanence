package postmark.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface OutboxCapsuleDao {

    @Upsert
    suspend fun upsert(capsule: OutboxCapsuleEntity)

    @Query("SELECT * FROM outbox_capsule WHERE capsule_id = :capsuleId")
    suspend fun getByCapsuleId(capsuleId: String): OutboxCapsuleEntity?

    @Query(
        "UPDATE outbox_capsule SET state = :newState " +
            "WHERE capsule_id = :capsuleId AND state IN (:allowedFrom)",
    )
    suspend fun transitionState(
        capsuleId: String,
        newState: OutboxCapsuleState,
        allowedFrom: List<OutboxCapsuleState>,
    ): Int

    @Query(
        "UPDATE outbox_capsule SET last_error_code = :errorCode, state = :newState " +
            "WHERE capsule_id = :capsuleId AND state IN (:allowedFrom)",
    )
    suspend fun transitionStateWithError(
        capsuleId: String,
        newState: OutboxCapsuleState,
        allowedFrom: List<OutboxCapsuleState>,
        errorCode: String?,
    ): Int

    @Query("DELETE FROM outbox_capsule")
    suspend fun clear()
}

@Dao
interface OutboxBlobDao {

    @Upsert
    suspend fun upsertAll(blobs: List<OutboxBlobEntity>)

    @Query("SELECT * FROM outbox_blob WHERE capsule_id = :capsuleId")
    suspend fun getAllByCapsuleId(capsuleId: String): List<OutboxBlobEntity>

    @Query("SELECT COUNT(*) FROM outbox_blob WHERE capsule_id = :capsuleId AND kind = :kind")
    suspend fun countByKind(capsuleId: String, kind: String): Int

    @Query(
        "UPDATE outbox_blob SET upload_state = :newState " +
            "WHERE blob_id = :blobId AND upload_state IN (:allowedFrom)",
    )
    suspend fun transitionUploadState(
        blobId: String,
        newState: OutboxBlobUploadState,
        allowedFrom: List<OutboxBlobUploadState>,
    ): Int

    /** Counts a retry attempt; returns 1 when the blob exists, else 0. */
    @Query("UPDATE outbox_blob SET attempt_count = attempt_count + 1 WHERE blob_id = :blobId")
    suspend fun incrementAttemptCount(blobId: String): Int

    @Query("DELETE FROM outbox_blob WHERE capsule_id = :capsuleId")
    suspend fun deleteByCapsuleId(capsuleId: String)

    @Query("DELETE FROM outbox_blob")
    suspend fun clear()
}
