package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO over incoming routed metadata. Replaying a synced page must be
 * idempotent. No query here may expose an enumerable inbox/gallery to UI
 * code.
 *
 * M2-P02/P03 + review fix: every account-owned lookup, list, and CAS
 * REQUIRES the row's immutable owner_user_id; writes go ONLY through
 * owner-preserving replay semantics - the same owner may update the allowed
 * replay fields, while a different local account presenting the same
 * immutable capsule ID is REFUSED and the original row stays unchanged.
 */
@Dao
interface IncomingCapsuleDao {

    /**
     * Owner-preserving idempotent page write. On the same immutable capsule
     * ID: a row for another local account aborts the whole write
     * ([IllegalStateException]); otherwise insert-ignore plus an owner-scoped
     * update applies exactly the replay fields - routing identities,
     * protocol version, and material state stay immutable here.
     */
    @Transaction
    suspend fun upsertAllForOwner(capsules: List<IncomingCapsuleEntity>) {
        for (capsule in capsules) {
            val existingOwner = findOwnerOf(capsule.capsuleId)
            if (existingOwner != null && existingOwner != capsule.ownerUserId) {
                throw IllegalStateException(
                    "incoming capsule ${capsule.capsuleId} already cached for another local account",
                )
            }
            insertIgnoring(listOf(capsule))
            updateReplayFieldsForOwner(
                capsuleId = capsule.capsuleId,
                ownerUserId = capsule.ownerUserId,
                serverStatus = capsule.serverStatus,
                readyAtEpochMs = capsule.readyAtEpochMs,
                signedStatementBytes = capsule.signedStatementBytes,
            )
        }
    }

    /** Minimal ownership probe: never returns full unscoped rows. */
    @Query("SELECT owner_user_id FROM incoming_capsule WHERE capsule_id = :capsuleId LIMIT 1")
    suspend fun findOwnerOf(capsuleId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(capsules: List<IncomingCapsuleEntity>)

    @Query(
        "UPDATE incoming_capsule SET server_status = :serverStatus, " +
            "ready_at_epoch_ms = :readyAtEpochMs, signed_statement_bytes = :signedStatementBytes " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun updateReplayFieldsForOwner(
        capsuleId: String,
        ownerUserId: String,
        serverStatus: String,
        readyAtEpochMs: Long,
        signedStatementBytes: ByteArray,
    )

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

    /**
     * Owner-preserving idempotent envelope write. Same rules as
     * [IncomingCapsuleDao.upsertAllForOwner]: a foreign owner with the same
     * immutable capsule ID is refused and the original bytes stay unchanged;
     * the same owner replays its transport payload.
     */
    @Transaction
    suspend fun upsertForOwner(envelope: IncomingEnvelopeEntity) {
        val existingOwner = findOwnerOf(envelope.capsuleId)
        if (existingOwner != null && existingOwner != envelope.ownerUserId) {
            throw IllegalStateException(
                "incoming envelope ${envelope.capsuleId} already cached for another local account",
            )
        }
        insertIgnoring(envelope)
        updateReplayFieldsForOwner(
            capsuleId = envelope.capsuleId,
            ownerUserId = envelope.ownerUserId,
            recipientKeyBundleId = envelope.recipientKeyBundleId,
            hpkeCiphertext = envelope.hpkeCiphertext,
            transportSha256 = envelope.transportSha256,
            receivedAtEpochMs = envelope.receivedAtEpochMs,
        )
    }

    /** Minimal ownership probe: never returns full unscoped rows. */
    @Query("SELECT owner_user_id FROM incoming_envelope WHERE capsule_id = :capsuleId LIMIT 1")
    suspend fun findOwnerOf(capsuleId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(envelope: IncomingEnvelopeEntity)

    @Query(
        "UPDATE incoming_envelope SET recipient_key_bundle_id = :recipientKeyBundleId, " +
            "hpke_ciphertext = :hpkeCiphertext, transport_sha256 = :transportSha256, " +
            "received_at_epoch_ms = :receivedAtEpochMs " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun updateReplayFieldsForOwner(
        capsuleId: String,
        ownerUserId: String,
        recipientKeyBundleId: String,
        hpkeCiphertext: ByteArray,
        transportSha256: ByteArray,
        receivedAtEpochMs: Long,
    )

    @Query("DELETE FROM incoming_envelope")
    suspend fun clear()

    /** Resolves the envelope ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM incoming_envelope " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): IncomingEnvelopeEntity?
}
