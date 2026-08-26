package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO over incoming routed metadata. Replaying a synced page must be
 * idempotent: upserts key on immutable capsule IDs and never duplicates.
 * No query here may expose an enumerable inbox/gallery to UI code.
 *
 * M2-P02/P03: every account-owned lookup, list, and compare-and-set REQUIRES
 * the row's immutable owner_user_id; no unscoped account-owned query exists.
 */
@Dao
interface IncomingCapsuleDao {

    /** Row creation/replay write; reads and transitions stay owner-guarded. */
    @Upsert
    suspend fun upsertAll(capsules: List<IncomingCapsuleEntity>)

    @Query("DELETE FROM incoming_capsule")
    suspend fun clear()

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

    /** Row creation/replay write; reads stay owner-guarded. */
    @Upsert
    suspend fun upsert(envelope: IncomingEnvelopeEntity)

    @Query("DELETE FROM incoming_envelope")
    suspend fun clear()

    /** Resolves the envelope ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM incoming_envelope " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): IncomingEnvelopeEntity?
}
