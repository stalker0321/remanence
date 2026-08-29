package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.LocalMaterialTransition
import dev.hryshyn.remanence.core.model.LocalMaterialTransitionEvaluator

/** Outcome of the owner-scoped, canonical local-material transition operation. */
sealed interface LocalMaterialTransitionResult {

    /** The exact evaluator-approved transition was persisted by CAS. */
    data class Accepted(val transition: LocalMaterialTransition.Accepted) : LocalMaterialTransitionResult

    /** The requested state already equals the observed state; no SQL write was issued. */
    data class IdempotentReplay(val transition: LocalMaterialTransition.IdempotentReplay) :
        LocalMaterialTransitionResult

    /** The evaluator rejected the requested transition; no SQL write was issued. */
    data class Rejected(val transition: LocalMaterialTransition.Rejected) : LocalMaterialTransitionResult

    /** No row exists for this capsule under this owner; no SQL write was issued. */
    data object MissingRow : LocalMaterialTransitionResult

    /** The observed state changed before the exact observed-state CAS; no transition was accepted. */
    data class ConcurrentOrStale(val transition: LocalMaterialTransition.Accepted) :
        LocalMaterialTransitionResult
}

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
abstract class IncomingCapsuleDao {

    /**
     * Bounded acceptance work projection for one exact owner. The nullable
     * tuple is invocation-local keyset state; durable material-state changes
     * make restarting from the beginning safe after process death.
     */
    @Query(
        "SELECT capsule_id, ready_at_epoch_ms FROM incoming_capsule " +
            "WHERE owner_user_id = :ownerUserId " +
            "AND server_status = 'READY' AND material_state = 'DISCOVERED' " +
            "AND (:afterReadyAtEpochMs IS NULL " +
            "OR ready_at_epoch_ms > :afterReadyAtEpochMs " +
            "OR (ready_at_epoch_ms = :afterReadyAtEpochMs AND capsule_id > :afterCapsuleId)) " +
            "ORDER BY ready_at_epoch_ms ASC, capsule_id ASC LIMIT :limit",
    )
    internal abstract suspend fun selectAcceptanceCandidateRows(
        ownerUserId: String,
        afterReadyAtEpochMs: Long?,
        afterCapsuleId: String?,
        limit: Int,
    ): List<IncomingAcceptanceCandidateRow>

    /**
     * Owner-preserving idempotent page write. [ownerUserId] is authoritative;
     * every entity must carry exactly that owner before any database work.
     * On the same immutable capsule
     * ID: a row for another local account aborts the whole write
     * ([IllegalStateException]); otherwise insert-ignore plus an owner-scoped
     * update applies exactly the replay fields - routing identities,
     * protocol version, and material state stay immutable here. A new row may
     * enter only as [LocalMaterialState.DISCOVERED]; existing same-owner
     * replays do not use their candidate material state.
     */
    @Transaction
    open suspend fun upsertAllForOwner(
        ownerUserId: String,
        capsules: List<IncomingCapsuleEntity>,
    ) {
        // Validate the complete page before any ownership probe or write. The
        // entity-carried owner is data to validate, never the authority.
        for (capsule in capsules) {
            require(capsule.ownerUserId == ownerUserId) {
                "incoming capsule ${capsule.capsuleId} owner does not match the authoritative owner"
            }
        }

        // Preflight the complete page before issuing any write so an invalid
        // fresh candidate cannot leave an earlier page item partially cached.
        for (capsule in capsules) {
            val existingOwner = findOwnerOf(capsule.capsuleId)
            if (existingOwner == null && capsule.materialState != LocalMaterialState.DISCOVERED) {
                throw IllegalArgumentException(
                    "new incoming capsule ${capsule.capsuleId} must start in DISCOVERED",
                )
            }
            if (existingOwner != null && existingOwner != ownerUserId) {
                throw IllegalStateException(
                    "incoming capsule ${capsule.capsuleId} already cached for another local account",
                )
            }
        }

        for (capsule in capsules) {
            val existingOwner = findOwnerOf(capsule.capsuleId)
            if (existingOwner != null && existingOwner != ownerUserId) {
                throw IllegalStateException(
                    "incoming capsule ${capsule.capsuleId} already cached for another local account",
                )
            }
            insertIgnoring(listOf(capsule))
            updateReplayFieldsForOwner(
                capsuleId = capsule.capsuleId,
                ownerUserId = ownerUserId,
                serverStatus = capsule.serverStatus,
                readyAtEpochMs = capsule.readyAtEpochMs,
                signedStatementBytes = capsule.signedStatementBytes,
            )
        }
    }

    /** Minimal ownership probe: never returns full unscoped rows. */
    @Query("SELECT owner_user_id FROM incoming_capsule WHERE capsule_id = :capsuleId LIMIT 1")
    protected abstract suspend fun findOwnerOf(capsuleId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoring(capsules: List<IncomingCapsuleEntity>)

    @Query(
        "UPDATE incoming_capsule SET server_status = :serverStatus, " +
            "ready_at_epoch_ms = :readyAtEpochMs, signed_statement_bytes = :signedStatementBytes " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    protected abstract suspend fun updateReplayFieldsForOwner(
        capsuleId: String,
        ownerUserId: String,
        serverStatus: String,
        readyAtEpochMs: Long,
        signedStatementBytes: ByteArray,
    )

    /** Owner-scoped teardown: removes only rows belonging to [ownerUserId]. */
    @Query("DELETE FROM incoming_capsule WHERE owner_user_id = :ownerUserId")
    abstract suspend fun clearForOwner(ownerUserId: String)

    /** Resolves the incoming capsule ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM incoming_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): IncomingCapsuleEntity?

    /**
     * Applies one canonical local-material request under the authenticated
     * owner. The row is loaded with the owner predicate, evaluated by the
     * core:model state machine, and only an evaluator-approved transition is
     * exact-CASed from that observed state.
     */
    @Transaction
    open suspend fun transitionMaterialStateForOwner(
        ownerUserId: String,
        capsuleId: String,
        requestedTarget: LocalMaterialState,
    ): LocalMaterialTransitionResult {
        val row = getByCapsuleIdAndOwner(capsuleId, ownerUserId)
            ?: return LocalMaterialTransitionResult.MissingRow
        return when (
            val transition = LocalMaterialTransitionEvaluator.evaluate(
                row.materialState,
                requestedTarget,
            )
        ) {
            is LocalMaterialTransition.IdempotentReplay ->
                LocalMaterialTransitionResult.IdempotentReplay(transition)
            is LocalMaterialTransition.Rejected ->
                LocalMaterialTransitionResult.Rejected(transition)
            is LocalMaterialTransition.Accepted -> {
                val updatedRows = compareAndSetMaterialStateForOwner(
                    capsuleId = capsuleId,
                    ownerUserId = ownerUserId,
                    observedState = transition.from,
                    newState = transition.to,
                )
                if (updatedRows == 1) {
                    LocalMaterialTransitionResult.Accepted(transition)
                } else {
                    LocalMaterialTransitionResult.ConcurrentOrStale(transition)
                }
            }
        }
    }

    /** Exact observed-state CAS used only by [transitionMaterialStateForOwner]. */
    @Query(
        "UPDATE incoming_capsule SET material_state = :newState " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND material_state = :observedState",
    )
    protected abstract suspend fun compareAndSetMaterialStateForOwner(
        capsuleId: String,
        ownerUserId: String,
        observedState: LocalMaterialState,
        newState: LocalMaterialState,
    ): Int
}

/**
 * DAO over the single HPKE recipient envelope per incoming capsule. Converts
 * from interface to abstract class so internal probe/write primitives are
 * protected and only owner-scoped operations are public.
 *
 * M2-P02/P03: every operation REQUIRES the row's immutable owner_user_id.
 */
@Dao
abstract class IncomingEnvelopeDao {

    /**
     * Owner-preserving idempotent envelope write. [ownerUserId] is
     * authoritative and the entity owner must match it before any database
     * work. Same rules as
     * [IncomingCapsuleDao.upsertAllForOwner]: a foreign owner with the same
     * immutable capsule ID is refused and the original bytes stay unchanged;
     * the same owner replays its transport payload.
    */
    @Transaction
    open suspend fun upsertForOwner(ownerUserId: String, envelope: IncomingEnvelopeEntity) {
        require(envelope.ownerUserId == ownerUserId) {
            "incoming envelope ${envelope.capsuleId} owner does not match the authoritative owner"
        }
        val existingOwner = findOwnerOf(envelope.capsuleId)
        if (existingOwner != null && existingOwner != ownerUserId) {
            throw IllegalStateException(
                "incoming envelope ${envelope.capsuleId} already cached for another local account",
            )
        }
        insertIgnoring(envelope)
        updateReplayFieldsForOwner(
            capsuleId = envelope.capsuleId,
            ownerUserId = ownerUserId,
            recipientKeyBundleId = envelope.recipientKeyBundleId,
            hpkeCiphertext = envelope.hpkeCiphertext,
            transportSha256 = envelope.transportSha256,
            receivedAtEpochMs = envelope.receivedAtEpochMs,
        )
    }

    /** Minimal ownership probe: never returns full unscoped rows. */
    @Query("SELECT owner_user_id FROM incoming_envelope WHERE capsule_id = :capsuleId LIMIT 1")
    protected abstract suspend fun findOwnerOf(capsuleId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoring(envelope: IncomingEnvelopeEntity)

    @Query(
        "UPDATE incoming_envelope SET recipient_key_bundle_id = :recipientKeyBundleId, " +
            "hpke_ciphertext = :hpkeCiphertext, transport_sha256 = :transportSha256, " +
            "received_at_epoch_ms = :receivedAtEpochMs " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    protected abstract suspend fun updateReplayFieldsForOwner(
        capsuleId: String,
        ownerUserId: String,
        recipientKeyBundleId: String,
        hpkeCiphertext: ByteArray,
        transportSha256: ByteArray,
        receivedAtEpochMs: Long,
    )

    /** Owner-scoped teardown: removes only rows belonging to [ownerUserId]. */
    @Query("DELETE FROM incoming_envelope WHERE owner_user_id = :ownerUserId")
    abstract suspend fun clearForOwner(ownerUserId: String)

    /** Resolves the envelope ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM incoming_envelope " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): IncomingEnvelopeEntity?
}
