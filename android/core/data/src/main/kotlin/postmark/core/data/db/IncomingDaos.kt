package postmark.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO over incoming routed metadata. Replaying a synced page must be
 * idempotent: upserts key on immutable capsule IDs and never duplicates.
 * No query here may expose an enumerable inbox/gallery to UI code.
 */
@Dao
interface IncomingCapsuleDao {

    @Upsert
    suspend fun upsertAll(capsules: List<IncomingCapsuleEntity>)

    @Query("SELECT * FROM incoming_capsule WHERE capsule_id = :capsuleId")
    suspend fun getByCapsuleId(capsuleId: String): IncomingCapsuleEntity?

    @Query(
        "UPDATE incoming_capsule SET material_state = :newState " +
            "WHERE capsule_id = :capsuleId AND material_state IN (:allowedFrom)",
    )
    suspend fun transitionMaterialState(
        capsuleId: String,
        newState: IncomingMaterialState,
        allowedFrom: List<IncomingMaterialState>,
    ): Int

    @Query("DELETE FROM incoming_capsule")
    suspend fun clear()
}

@Dao
interface IncomingEnvelopeDao {

    @Upsert
    suspend fun upsert(envelope: IncomingEnvelopeEntity)

    @Query("SELECT * FROM incoming_envelope WHERE capsule_id = :capsuleId")
    suspend fun getByCapsuleId(capsuleId: String): IncomingEnvelopeEntity?

    @Query("DELETE FROM incoming_envelope")
    suspend fun clear()
}
