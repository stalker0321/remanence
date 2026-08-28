package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * One owner-scoped transaction boundary for an incoming server page.
 *
 * This DAO deliberately has no update/upsert SQL. A page either inserts a
 * missing immutable record or proves that the existing record is byte-for-
 * byte the same. Existing local cache state and timestamps are never replay
 * fields, so a page replay cannot rewind a later A11/A13 state.
 */
@Dao
abstract class IncomingPageDao {

    /**
     * Commits all page metadata and its opaque cursor as one Room transaction.
     * [expectedCursor] is the value read before the HTTP request; a changed
     * cursor means another sync won and this page is rejected without writes.
     */
    @Transaction
    open suspend fun commitPage(
        ownerUserId: String,
        expectedCursor: String?,
        capsules: List<IncomingCapsuleEntity>,
        envelopes: List<IncomingEnvelopeEntity>,
        blobs: List<BlobCacheEntity>,
        nextCursor: String?,
        committedAtEpochMs: Long,
    ) {
        require(ownerUserId.isNotEmpty()) { "incoming page owner is required" }
        require(committedAtEpochMs >= 0L) { "incoming page commit time is invalid" }

        val storedCursor = findCursor(ownerUserId, INCOMING_STREAM)
        require(storedCursor?.serverCursor == expectedCursor) {
            "incoming page cursor changed before commit"
        }

        val capsuleIds = capsules.map { it.capsuleId }
        require(capsuleIds.size == capsuleIds.toSet().size) {
            "incoming page contains duplicate capsule IDs"
        }
        val capsuleIdSet = capsuleIds.toSet()

        val envelopeIds = envelopes.map { it.capsuleId }
        require(envelopeIds.size == envelopeIds.toSet().size) {
            "incoming page contains duplicate envelope IDs"
        }
        require(envelopeIds.toSet() == capsuleIdSet) {
            "incoming page envelope bindings are incomplete"
        }

        val blobIds = blobs.map { it.blobId }
        require(blobIds.size == blobIds.toSet().size) {
            "incoming page contains duplicate blob IDs"
        }
        require(blobs.all { it.capsuleId in capsuleIdSet }) {
            "incoming page blob is bound to an unknown capsule"
        }

        for (capsule in capsules) {
            require(capsule.ownerUserId == ownerUserId) {
                "incoming capsule owner does not match the authoritative owner"
            }
            val existingOwner = findCapsuleOwner(capsule.capsuleId)
            require(existingOwner == null || existingOwner == ownerUserId) {
                "incoming capsule is already owned by another local account"
            }
            val existing = findCapsule(capsule.capsuleId)
            if (existing != null) requireSameCapsule(existing, capsule)
        }

        for (envelope in envelopes) {
            require(envelope.ownerUserId == ownerUserId) {
                "incoming envelope owner does not match the authoritative owner"
            }
            val existingOwner = findEnvelopeOwner(envelope.capsuleId)
            require(existingOwner == null || existingOwner == ownerUserId) {
                "incoming envelope is already owned by another local account"
            }
            val existing = findEnvelope(envelope.capsuleId)
            if (existing != null) requireSameEnvelope(existing, envelope)
        }

        for (blob in blobs) {
            require(blob.ownerUserId == ownerUserId) {
                "incoming blob owner does not match the authoritative owner"
            }
            val existingOwner = findBlobOwner(blob.blobId)
            require(existingOwner == null || existingOwner == ownerUserId) {
                "incoming blob is already owned by another local account"
            }
            val existing = findBlob(blob.blobId)
            if (existing != null) requireSameBlob(existing, blob)
        }

        // The complete preflight above is intentionally finished before any
        // INSERT. A late conflict therefore rolls back an otherwise valid
        // earlier capsule, envelope, or blob in this same transaction.
        val newCapsules = capsules.filter { findCapsule(it.capsuleId) == null }
        val newEnvelopes = envelopes.filter { findEnvelope(it.capsuleId) == null }
        val newBlobs = blobs.filter { findBlob(it.blobId) == null }
        if (newCapsules.isNotEmpty()) insertCapsules(newCapsules)
        if (newEnvelopes.isNotEmpty()) insertEnvelopes(newEnvelopes)
        if (newBlobs.isNotEmpty()) insertBlobs(newBlobs)

        if (storedCursor == null) {
            insertCursor(
                SyncCursorEntity(
                    userId = ownerUserId,
                    streamName = INCOMING_STREAM,
                    serverCursor = nextCursor,
                    lastSyncedAtEpochMs = committedAtEpochMs,
                ),
            )
        } else {
            check(
                updateCursor(
                    userId = ownerUserId,
                    streamName = INCOMING_STREAM,
                    serverCursor = nextCursor,
                    lastSyncedAtEpochMs = committedAtEpochMs,
                ) == 1,
            ) { "incoming page cursor commit failed" }
        }
    }

    @Query(
        "SELECT * FROM sync_cursor " +
            "WHERE user_id = :ownerUserId AND stream_name = :streamName LIMIT 1",
    )
    protected abstract suspend fun findCursor(ownerUserId: String, streamName: String): SyncCursorEntity?

    @Query("SELECT owner_user_id FROM incoming_capsule WHERE capsule_id = :capsuleId LIMIT 1")
    protected abstract suspend fun findCapsuleOwner(capsuleId: String): String?

    @Query("SELECT * FROM incoming_capsule WHERE capsule_id = :capsuleId LIMIT 1")
    protected abstract suspend fun findCapsule(capsuleId: String): IncomingCapsuleEntity?

    @Query("SELECT owner_user_id FROM incoming_envelope WHERE capsule_id = :capsuleId LIMIT 1")
    protected abstract suspend fun findEnvelopeOwner(capsuleId: String): String?

    @Query("SELECT * FROM incoming_envelope WHERE capsule_id = :capsuleId LIMIT 1")
    protected abstract suspend fun findEnvelope(capsuleId: String): IncomingEnvelopeEntity?

    @Query("SELECT owner_user_id FROM blob_cache WHERE blob_id = :blobId LIMIT 1")
    protected abstract suspend fun findBlobOwner(blobId: String): String?

    @Query("SELECT * FROM blob_cache WHERE blob_id = :blobId LIMIT 1")
    protected abstract suspend fun findBlob(blobId: String): BlobCacheEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCapsules(capsules: List<IncomingCapsuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEnvelopes(envelopes: List<IncomingEnvelopeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertBlobs(blobs: List<BlobCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCursor(cursor: SyncCursorEntity)

    @Query(
        "UPDATE sync_cursor SET server_cursor = :serverCursor, " +
            "last_synced_at_epoch_ms = :lastSyncedAtEpochMs " +
            "WHERE user_id = :userId AND stream_name = :streamName",
    )
    protected abstract suspend fun updateCursor(
        userId: String,
        streamName: String,
        serverCursor: String?,
        lastSyncedAtEpochMs: Long,
    ): Int

    private fun requireSameCapsule(
        existing: IncomingCapsuleEntity,
        candidate: IncomingCapsuleEntity,
    ) {
        require(existing.ownerUserId == candidate.ownerUserId)
        require(existing.senderUserId == candidate.senderUserId)
        require(existing.recipientUserId == candidate.recipientUserId)
        require(existing.senderSigningKeyBundleId == candidate.senderSigningKeyBundleId)
        require(existing.recipientEncryptionKeyBundleId == candidate.recipientEncryptionKeyBundleId)
        require(existing.protocolVersion == candidate.protocolVersion)
        require(existing.serverStatus == candidate.serverStatus)
        require(existing.readyAtEpochMs == candidate.readyAtEpochMs)
        require(existing.signedStatementBytes.contentEquals(candidate.signedStatementBytes))
        require(existing.signedStatementSha256.contentEquals(candidate.signedStatementSha256))
        require(existing.publishSignatureBytes.contentEquals(candidate.publishSignatureBytes))
    }

    private fun requireSameEnvelope(
        existing: IncomingEnvelopeEntity,
        candidate: IncomingEnvelopeEntity,
    ) {
        require(existing.ownerUserId == candidate.ownerUserId)
        require(existing.recipientKeyBundleId == candidate.recipientKeyBundleId)
        require(existing.hpkeCiphertext.contentEquals(candidate.hpkeCiphertext))
        require(existing.transportSha256.contentEquals(candidate.transportSha256))
    }

    private fun requireSameBlob(
        existing: BlobCacheEntity,
        candidate: BlobCacheEntity,
    ) {
        require(existing.ownerUserId == candidate.ownerUserId)
        require(existing.capsuleId == candidate.capsuleId)
        require(existing.kind == candidate.kind)
        require(existing.ordinal == candidate.ordinal)
        require(existing.expectedSizeBytes == candidate.expectedSizeBytes)
        require(existing.expectedSha256.contentEquals(candidate.expectedSha256))
        require(existing.localPath == candidate.localPath)
        // cacheState is deliberately not compared: a downloaded/corrupt
        // local state belongs to the later download/verification lifecycle.
    }

    private companion object {
        const val INCOMING_STREAM = "incoming"
    }
}
