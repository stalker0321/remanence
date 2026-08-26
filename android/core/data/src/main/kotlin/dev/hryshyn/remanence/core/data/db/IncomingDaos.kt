package dev.hryshyn.remanence.core.data.db

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

    // M2-P02 account-scoped primitives: ownership-guarded reads and CAS
    // transitions for P03's production conversion.

    /** Resolves the incoming capsule ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM incoming_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): IncomingCapsuleEntity?

    /** Owner-guarded material-state CAS; 0 rows means refused. */
    @Query(
        "UPDATE incoming_capsule SET material_state = :newState " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId AND material_state IN (:allowedFrom)",
    )
    suspend fun transitionMaterialStateForOwner(
        capsuleId: String,
        ownerUserId: String,
        newState: IncomingMaterialState,
        allowedFrom: List<IncomingMaterialState>,
    ): Int
}

@Dao
interface IncomingEnvelopeDao {

    @Upsert
    suspend fun upsert(envelope: IncomingEnvelopeEntity)

    @Query("SELECT * FROM incoming_envelope WHERE capsule_id = :capsuleId")
    suspend fun getByCapsuleId(capsuleId: String): IncomingEnvelopeEntity?

    @Query("DELETE FROM incoming_envelope")
    suspend fun clear()

    /** M2-P02: resolves the envelope ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM incoming_envelope " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): IncomingEnvelopeEntity?
}
