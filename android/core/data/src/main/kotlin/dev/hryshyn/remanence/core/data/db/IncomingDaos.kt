package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.LocalMaterialTransition
import dev.hryshyn.remanence.core.model.LocalMaterialTransitionEvaluator
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

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

/** Room-only projection for one owner-scoped material-ack candidate. */
internal data class IncomingMaterialAckCandidateRow(
    @ColumnInfo(name = "capsule_id") val capsuleId: String,
    @ColumnInfo(name = "ready_at_epoch_ms") val readyAtEpochMs: Long,
) {
    override fun toString(): String = "IncomingMaterialAckCandidateRow(<redacted>)"
}

/** Room-only projection for one accepted incoming sender-index bundle. */
internal data class IncomingSenderIndexCandidateRow(
    @ColumnInfo(name = "capsule_id") val capsuleId: String,
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
) {
    override fun toString(): String = "IncomingSenderIndexCandidateRow(<redacted>)"
}

/** Typed owner-scoped identity of one accepted incoming sender index. */
data class IncomingSenderIndexCandidate(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
) {
    override fun toString(): String = "IncomingSenderIndexCandidate(<redacted>)"
}

/** Minimal identity and ordering key returned by the bounded ack selector. */
data class IncomingMaterialAckCandidate(
    val capsuleId: CapsuleId,
    val readyAtEpochMs: Long,
) {
    override fun toString(): String = "IncomingMaterialAckCandidate(<redacted>)"
}

sealed interface IncomingMaterialAckCandidateSelection {
    data class Page(val candidates: List<IncomingMaterialAckCandidate>) :
        IncomingMaterialAckCandidateSelection {
        override fun toString(): String = "IncomingMaterialAckCandidateSelection.Page(<redacted>)"
    }

    data object InvalidRequest : IncomingMaterialAckCandidateSelection

    /** Database access or malformed durable candidate identity is retryable. */
    data object Unavailable : IncomingMaterialAckCandidateSelection
}

/** Redacted result of one owner-scoped, exact material-ack CAS. */
sealed interface IncomingMaterialAckResult {
    data object Marked : IncomingMaterialAckResult
    data object AlreadyRecorded : IncomingMaterialAckResult
    data object MissingOrForeign : IncomingMaterialAckResult
    data object StateChanged : IncomingMaterialAckResult
    data object Unavailable : IncomingMaterialAckResult
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

    companion object {
        /** Absolute bound for one later material-ack drain invocation. */
        const val MATERIAL_ACK_HARD_MAX_PAGE_SIZE: Int = 32
    }

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
     * Bounded owner-scoped material-ack projection. Durable PENDING state is
     * the restart-safe progress boundary; no cursor or full row is exposed.
     */
    @Query(
        "SELECT capsule_id, ready_at_epoch_ms FROM incoming_capsule " +
            "WHERE owner_user_id = :ownerUserId " +
            "AND server_status = 'READY' " +
            "AND material_state IN ('MATERIAL_CACHED', 'FINGERPRINT_ACCEPTED') " +
            "AND material_ack_state = 'PENDING' " +
            "ORDER BY ready_at_epoch_ms ASC, capsule_id ASC LIMIT :limit",
    )
    internal abstract suspend fun selectMaterialAckCandidateRows(
        ownerUserId: String,
        limit: Int,
    ): List<IncomingMaterialAckCandidateRow>

    /**
     * Enumerates only owner-owned, READY capsules whose accepted sender-index
     * material can exist durably. The projection contains no statement,
     * envelope, blob, or private/plaintext fields.
     */
    @Query(
        "SELECT capsule_id, owner_user_id FROM incoming_capsule " +
            "WHERE owner_user_id = :ownerUserId " +
            "AND server_status = 'READY' " +
            "AND material_state IN ('INDEX_CACHED', 'MATERIAL_CACHED', 'FINGERPRINT_ACCEPTED') " +
            "ORDER BY ready_at_epoch_ms ASC, capsule_id ASC",
    )
    internal abstract suspend fun selectSenderIndexCandidateRows(
        ownerUserId: String,
    ): List<IncomingSenderIndexCandidateRow>

    /** Typed owner-scoped sender-index enumeration; no untyped public path. */
    open suspend fun selectSenderIndexCandidatesForOwner(
        ownerUserId: UserId,
    ): List<IncomingSenderIndexCandidate> = try {
        selectSenderIndexCandidateRows(ownerUserId.toRestString()).map { row ->
            require(row.ownerUserId == ownerUserId.toRestString())
            IncomingSenderIndexCandidate(
                ownerUserId = UserId.parseRest(row.ownerUserId),
                capsuleId = CapsuleId.parseRest(row.capsuleId),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    }

    /**
     * Selects only bounded, typed identities. Invalid limits are rejected
     * before Room is called; cancellation identity is preserved exactly.
     */
    open suspend fun selectMaterialAckCandidatesForOwner(
        ownerUserId: UserId,
        limit: Int,
    ): IncomingMaterialAckCandidateSelection = selectMaterialAckCandidatesForOwnerInternal(
        ownerUserId = ownerUserId,
        limit = limit,
    )

    private suspend fun selectMaterialAckCandidatesForOwnerInternal(
        ownerUserId: UserId,
        limit: Int,
    ): IncomingMaterialAckCandidateSelection {
        if (limit !in 1..MATERIAL_ACK_HARD_MAX_PAGE_SIZE) {
            return IncomingMaterialAckCandidateSelection.InvalidRequest
        }
        return try {
            IncomingMaterialAckCandidateSelection.Page(
                selectMaterialAckCandidateRows(ownerUserId.toRestString(), limit).map { row ->
                    require(row.readyAtEpochMs >= 0L) {
                        "durable material-ack ordering key is invalid"
                    }
                    IncomingMaterialAckCandidate(
                        capsuleId = CapsuleId.parseRest(row.capsuleId),
                        readyAtEpochMs = row.readyAtEpochMs,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IncomingMaterialAckCandidateSelection.Unavailable
        }
    }

    /** Marks one eligible owned capsule acknowledged, or reconciles a CAS miss. */
    @Transaction
    open suspend fun markMaterialAckedForOwner(
        ownerUserId: UserId,
        capsuleId: CapsuleId,
    ): IncomingMaterialAckResult = markMaterialAckForOwnerInternal(
        ownerUserId = ownerUserId,
        capsuleId = capsuleId,
        targetState = MaterialAckState.ACKED,
    )

    /** Marks one eligible owned capsule terminal, or reconciles a CAS miss. */
    @Transaction
    open suspend fun markMaterialTerminalForOwner(
        ownerUserId: UserId,
        capsuleId: CapsuleId,
    ): IncomingMaterialAckResult = markMaterialAckForOwnerInternal(
        ownerUserId = ownerUserId,
        capsuleId = capsuleId,
        targetState = MaterialAckState.TERMINAL,
    )

    private suspend fun markMaterialAckForOwnerInternal(
        ownerUserId: UserId,
        capsuleId: CapsuleId,
        targetState: MaterialAckState,
    ): IncomingMaterialAckResult {
        return try {
            if (
                compareAndSetMaterialAckStateForOwner(
                    ownerUserId = ownerUserId.toRestString(),
                    capsuleId = capsuleId.toRestString(),
                    targetState = targetState,
                ) == 1
            ) {
                IncomingMaterialAckResult.Marked
            } else {
                val row = getMaterialAckCapsuleForOwner(
                    capsuleId = capsuleId.toRestString(),
                    ownerUserId = ownerUserId.toRestString(),
                )
                    ?: return IncomingMaterialAckResult.MissingOrForeign
                when {
                    row.materialAckState == targetState -> IncomingMaterialAckResult.AlreadyRecorded
                    else -> IncomingMaterialAckResult.StateChanged
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IncomingMaterialAckResult.Unavailable
        }
    }

    /** Exact owner/capsule/READY/eligible/PENDING material-ack CAS. */
    @Query(
        "UPDATE incoming_capsule SET material_ack_state = :targetState " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND server_status = 'READY' " +
            "AND material_state IN ('MATERIAL_CACHED', 'FINGERPRINT_ACCEPTED') " +
            "AND material_ack_state = 'PENDING'",
    )
    protected abstract suspend fun compareAndSetMaterialAckStateForOwner(
        ownerUserId: String,
        capsuleId: String,
        targetState: MaterialAckState,
    ): Int

    /** Owner-scoped reconciliation read kept behind the typed ack seam. */
    @Query(
        "SELECT * FROM incoming_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    protected abstract suspend fun getMaterialAckCapsuleForOwner(
        capsuleId: String,
        ownerUserId: String,
    ): IncomingCapsuleEntity?

    /**
     * Owner-preserving idempotent page write. [ownerUserId] is authoritative;
     * every entity must carry exactly that owner before any database work.
     * On the same immutable capsule
     * ID: a row for another local account aborts the whole write
     * ([IllegalStateException]); otherwise insert-ignore plus an owner-scoped
     * update applies exactly the replay fields - routing identities,
     * protocol version, material state, and material-ack progress stay
     * immutable here. A new row may enter only as
     * [LocalMaterialState.DISCOVERED]; existing same-owner replays do not use
     * their candidate local states.
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
            if (existingOwner == null && capsule.materialAckState != MaterialAckState.PENDING) {
                throw IllegalArgumentException(
                    "new incoming capsule ${capsule.capsuleId} must start with PENDING material acknowledgement",
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
     * Exact owner-scoped quarantine CAS. Only a READY capsule still in
     * DISCOVERED may enter CORRUPT. A refused CAS is reconciled through the
     * same owner/capsule lookup, so a foreign row is indistinguishable from a
     * missing row. This operation never mutates blob or filesystem state.
     */
    open suspend fun quarantineReadyDiscoveredForOwner(
        ownerUserId: String,
        capsuleId: String,
    ): IncomingCapsuleQuarantineResult = resolveIncomingCapsuleQuarantine(
        compareAndSet = {
            quarantineReadyDiscoveredCas(
                ownerUserId = ownerUserId,
                capsuleId = capsuleId,
            )
        },
        rereadOwnedCapsule = {
            getByCapsuleIdAndOwner(capsuleId, ownerUserId)
        },
    )

    /** Dedicated quarantine CAS; do not replace with the generic transition. */
    @Query(
        "UPDATE incoming_capsule SET material_state = 'CORRUPT' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND server_status = 'READY' AND material_state = 'DISCOVERED'",
    )
    protected abstract suspend fun quarantineReadyDiscoveredCas(
        ownerUserId: String,
        capsuleId: String,
    ): Int

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
