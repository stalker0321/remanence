package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidation
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidator
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

/** Minimal owner-scoped projection for one bounded prefetch invocation. */
internal data class IncomingPrefetchBlobRow(
    val blobId: String,
    val ownerUserId: String,
    val capsuleId: String,
    val kind: String,
    val ordinal: Int?,
    val expectedSizeBytes: Long,
    val expectedSha256: ByteArray,
    val localPath: String,
    val cacheState: BlobCacheState,
) {
    override fun toString(): String = "IncomingPrefetchBlobRow(<redacted>)"
}

/** Result of the one Room transaction that completes one prefetched blob. */
sealed interface IncomingPrefetchCommitResult {
    data object BlobCached : IncomingPrefetchCommitResult
    data object BlobAlreadyCached : IncomingPrefetchCommitResult
    data object MaterialCached : IncomingPrefetchCommitResult
    data object AlreadyMaterialCached : IncomingPrefetchCommitResult
    data object MissingOrForeignOwner : IncomingPrefetchCommitResult
    data object InvalidBinding : IncomingPrefetchCommitResult
    data object IllegalState : IncomingPrefetchCommitResult
    data object ConcurrentOrStale : IncomingPrefetchCommitResult
}

/**
 * Owner-scoped selection and the cross-table prefetch completion transaction.
 * Filesystem verification is deliberately performed by the coordinator before
 * this transaction; this DAO only validates the durable Room bindings and
 * state machine.
 */
@Dao
abstract class IncomingPrefetchDao {

    /**
     * Selects only remaining content/photo blobs of READY, INDEX_CACHED
     * capsules. The bound and order are deterministic; recognition is already
     * complete at this state and is checked again by the completion predicate.
     */
    @Query(
        "SELECT b.blob_id AS blobId, b.owner_user_id AS ownerUserId, " +
            "b.capsule_id AS capsuleId, b.kind AS kind, b.ordinal AS ordinal, " +
            "b.expected_size_bytes AS expectedSizeBytes, " +
            "b.expected_sha256 AS expectedSha256, b.local_path AS localPath, " +
            "b.cache_state AS cacheState " +
            "FROM blob_cache AS b " +
            "INNER JOIN incoming_capsule AS c " +
            "ON c.capsule_id = b.capsule_id AND c.owner_user_id = b.owner_user_id " +
            "WHERE b.owner_user_id = :ownerUserId " +
            "AND c.server_status = 'READY' " +
            "AND c.material_state = 'INDEX_CACHED' " +
            "AND b.kind IN ('CONTENT_MANIFEST', 'PHOTO') " +
            "AND (b.cache_state = 'DOWNLOADING' OR " +
            "(b.cache_state = 'CACHED' AND NOT EXISTS (" +
            "SELECT 1 FROM blob_cache AS pending " +
            "WHERE pending.owner_user_id = b.owner_user_id " +
            "AND pending.capsule_id = b.capsule_id " +
            "AND pending.kind IN ('CONTENT_MANIFEST', 'PHOTO') " +
            "AND pending.cache_state = 'DOWNLOADING'))) " +
            "ORDER BY b.capsule_id ASC, " +
            "CASE b.kind WHEN 'CONTENT_MANIFEST' THEN 0 ELSE 1 END ASC, " +
            "COALESCE(b.ordinal, -1) ASC, b.blob_id ASC " +
            "LIMIT :limit",
    )
    internal abstract suspend fun selectMissingForOwner(
        ownerUserId: String,
        limit: Int,
    ): List<IncomingPrefetchBlobRow>

    /** Re-reads one candidate under both immutable owner and capsule state. */
    @Query(
        "SELECT b.blob_id AS blobId, b.owner_user_id AS ownerUserId, " +
            "b.capsule_id AS capsuleId, b.kind AS kind, b.ordinal AS ordinal, " +
            "b.expected_size_bytes AS expectedSizeBytes, " +
            "b.expected_sha256 AS expectedSha256, b.local_path AS localPath, " +
            "b.cache_state AS cacheState " +
            "FROM blob_cache AS b " +
            "INNER JOIN incoming_capsule AS c " +
            "ON c.capsule_id = b.capsule_id AND c.owner_user_id = b.owner_user_id " +
            "WHERE b.blob_id = :blobId AND b.capsule_id = :capsuleId " +
            "AND b.owner_user_id = :ownerUserId " +
            "AND c.server_status = 'READY' AND c.material_state = 'INDEX_CACHED' " +
            "AND b.kind IN ('CONTENT_MANIFEST', 'PHOTO') " +
            "LIMIT 1",
    )
    internal abstract suspend fun getCandidateForOwner(
        ownerUserId: String,
        capsuleId: String,
        blobId: String,
    ): IncomingPrefetchBlobRow?

    /**
     * Marks exactly one owner/capsule/blob row cached and, in the same Room
     * transaction, promotes the capsule only when the complete artifact layout
     * is cached with immutable bindings intact.
     */
    @Transaction
    open suspend fun markCachedAndMaybeMaterialCached(
        ownerUserId: String,
        capsuleId: String,
        blobId: String,
        expectedKind: String,
        expectedOrdinal: Int?,
        expectedSizeBytes: Long,
        expectedSha256: ByteArray,
        expectedLocalPath: String,
        incomingRootPath: String,
    ): IncomingPrefetchCommitResult {
        if (ownerUserId.isBlank() || capsuleId.isBlank() || blobId.isBlank() ||
            expectedLocalPath.isBlank() || incomingRootPath.isBlank() ||
            !isValidMetadata(expectedKind, expectedOrdinal, expectedSizeBytes, expectedSha256)
        ) {
            return IncomingPrefetchCommitResult.InvalidBinding
        }

        val capsule = findCapsule(ownerUserId, capsuleId)
            ?: return IncomingPrefetchCommitResult.MissingOrForeignOwner
        if (capsule.ownerUserId != ownerUserId || capsule.capsuleId != capsuleId ||
            capsule.serverStatus != READY_STATUS
        ) {
            return IncomingPrefetchCommitResult.InvalidBinding
        }
        if (capsule.materialState != LocalMaterialState.INDEX_CACHED &&
            capsule.materialState != LocalMaterialState.MATERIAL_CACHED
        ) {
            return IncomingPrefetchCommitResult.IllegalState
        }

        val target = findBlob(ownerUserId, capsuleId, blobId)
            ?: return IncomingPrefetchCommitResult.MissingOrForeignOwner
        if (!isExactBinding(
                target,
                ownerUserId = ownerUserId,
                capsuleId = capsuleId,
                blobId = blobId,
                expectedKind = expectedKind,
                expectedOrdinal = expectedOrdinal,
                expectedSizeBytes = expectedSizeBytes,
                expectedSha256 = expectedSha256,
                expectedLocalPath = expectedLocalPath,
                incomingRootPath = incomingRootPath,
            )
        ) {
            return IncomingPrefetchCommitResult.InvalidBinding
        }

        val alreadyCached = when (target.cacheState) {
            BlobCacheState.CACHED -> true
            BlobCacheState.DOWNLOADING -> false
            BlobCacheState.CORRUPT -> return IncomingPrefetchCommitResult.IllegalState
        }
        if (!alreadyCached && markCached(ownerUserId, capsuleId, blobId) != 1) {
            val reread = findBlob(ownerUserId, capsuleId, blobId)
                ?: return IncomingPrefetchCommitResult.MissingOrForeignOwner
            if (!isExactBinding(
                    reread,
                    ownerUserId = ownerUserId,
                    capsuleId = capsuleId,
                    blobId = blobId,
                    expectedKind = expectedKind,
                    expectedOrdinal = expectedOrdinal,
                    expectedSizeBytes = expectedSizeBytes,
                    expectedSha256 = expectedSha256,
                    expectedLocalPath = expectedLocalPath,
                    incomingRootPath = incomingRootPath,
                ) || reread.cacheState != BlobCacheState.CACHED
            ) {
                return IncomingPrefetchCommitResult.ConcurrentOrStale
            }
        }

        val allBlobs = findBlobs(ownerUserId, capsuleId)
        if (!hasCompleteCachedLayout(allBlobs, ownerUserId, capsuleId, incomingRootPath)) {
            return if (alreadyCached) {
                IncomingPrefetchCommitResult.BlobAlreadyCached
            } else {
                IncomingPrefetchCommitResult.BlobCached
            }
        }

        return when (capsule.materialState) {
            LocalMaterialState.MATERIAL_CACHED ->
                IncomingPrefetchCommitResult.AlreadyMaterialCached
            LocalMaterialState.INDEX_CACHED ->
                if (markMaterialCached(ownerUserId, capsuleId) == 1) {
                    IncomingPrefetchCommitResult.MaterialCached
                } else {
                    val reread = findCapsule(ownerUserId, capsuleId)
                        ?: return IncomingPrefetchCommitResult.MissingOrForeignOwner
                    if (reread.materialState == LocalMaterialState.MATERIAL_CACHED) {
                        IncomingPrefetchCommitResult.AlreadyMaterialCached
                    } else {
                        IncomingPrefetchCommitResult.ConcurrentOrStale
                    }
                }
        }
    }

    @Query(
        "SELECT * FROM incoming_capsule " +
            "WHERE owner_user_id = :ownerUserId AND capsule_id = :capsuleId LIMIT 1",
    )
    protected abstract suspend fun findCapsule(
        ownerUserId: String,
        capsuleId: String,
    ): IncomingCapsuleEntity?

    @Query(
        "SELECT * FROM blob_cache " +
            "WHERE owner_user_id = :ownerUserId AND capsule_id = :capsuleId " +
            "AND blob_id = :blobId LIMIT 1",
    )
    protected abstract suspend fun findBlob(
        ownerUserId: String,
        capsuleId: String,
        blobId: String,
    ): BlobCacheEntity?

    @Query(
        "SELECT * FROM blob_cache " +
            "WHERE owner_user_id = :ownerUserId AND capsule_id = :capsuleId",
    )
    protected abstract suspend fun findBlobs(
        ownerUserId: String,
        capsuleId: String,
    ): List<BlobCacheEntity>

    @Query(
        "UPDATE blob_cache SET cache_state = 'CACHED' " +
            "WHERE owner_user_id = :ownerUserId AND capsule_id = :capsuleId " +
            "AND blob_id = :blobId AND cache_state = 'DOWNLOADING'",
    )
    protected abstract suspend fun markCached(
        ownerUserId: String,
        capsuleId: String,
        blobId: String,
    ): Int

    @Query(
        "UPDATE incoming_capsule SET material_state = 'MATERIAL_CACHED' " +
            "WHERE owner_user_id = :ownerUserId AND capsule_id = :capsuleId " +
            "AND server_status = 'READY' AND material_state = 'INDEX_CACHED'",
    )
    protected abstract suspend fun markMaterialCached(
        ownerUserId: String,
        capsuleId: String,
    ): Int

    private fun isExactBinding(
        row: BlobCacheEntity,
        ownerUserId: String,
        capsuleId: String,
        blobId: String,
        expectedKind: String,
        expectedOrdinal: Int?,
        expectedSizeBytes: Long,
        expectedSha256: ByteArray,
        expectedLocalPath: String,
        incomingRootPath: String,
    ): Boolean = row.ownerUserId == ownerUserId &&
        row.capsuleId == capsuleId &&
        row.blobId == blobId &&
        row.kind == expectedKind &&
        row.ordinal == expectedOrdinal &&
        row.expectedSizeBytes == expectedSizeBytes &&
        row.expectedSha256.contentEquals(expectedSha256) &&
        isValidMetadata(expectedKind, expectedOrdinal, expectedSizeBytes, expectedSha256) &&
        row.localPath == expectedLocalPath &&
        expectedLocalPath == expectedPath(incomingRootPath, capsuleId, blobId)

    private fun isValidMetadata(
        kind: String,
        ordinal: Int?,
        sizeBytes: Long,
        sha256: ByteArray,
    ): Boolean {
        val parsedKind = try {
            CapsuleArtifactKind.valueOf(kind)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val ordinalValid = when (parsedKind) {
            CapsuleArtifactKind.PHOTO ->
                ordinal != null && ordinal in ProtocolV1Limits.PHOTO_ORDINAL_MIN..ProtocolV1Limits.PHOTO_ORDINAL_MAX
            CapsuleArtifactKind.RECOGNITION_MANIFEST,
            CapsuleArtifactKind.CONTENT_MANIFEST,
            -> ordinal == null
        }
        val maxSize = when (parsedKind) {
            CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.CONTENT_MANIFEST -> ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.PHOTO -> ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES
        }
        return ordinalValid && sizeBytes in 1L..maxSize && sha256.size == SHA256_BYTES
    }

    private fun hasCompleteCachedLayout(
        rows: List<BlobCacheEntity>,
        ownerUserId: String,
        capsuleId: String,
        incomingRootPath: String,
    ): Boolean {
        if (rows.any { it.ownerUserId != ownerUserId || it.capsuleId != capsuleId }) return false
        val slots = try {
            rows.map { row ->
                val blobId = BlobId.parseRest(row.blobId)
                val kind = CapsuleArtifactKind.valueOf(row.kind)
                val maxSize = when (kind) {
                    CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                        ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
                    CapsuleArtifactKind.CONTENT_MANIFEST ->
                        ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES
                    CapsuleArtifactKind.PHOTO -> ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES
                }
                val ordinal = when (kind) {
                    CapsuleArtifactKind.PHOTO -> row.ordinal ?: return false
                    CapsuleArtifactKind.RECOGNITION_MANIFEST,
                    CapsuleArtifactKind.CONTENT_MANIFEST,
                    -> if (row.ordinal == null) ProtocolV1Limits.NON_PHOTO_ORDINAL else return false
                }
                if (row.expectedSizeBytes !in 1L..maxSize || row.expectedSha256.size != SHA256_BYTES ||
                    row.localPath != expectedPath(incomingRootPath, capsuleId, row.blobId) ||
                    row.cacheState != BlobCacheState.CACHED
                ) {
                    return false
                }
                ArtifactSlot(blobId, kind, ordinal)
            }
        } catch (_: Exception) {
            return false
        }
        return ArtifactLayoutValidator.validate(slots) is ArtifactLayoutValidation.Valid
    }

    private fun expectedPath(
        incomingRootPath: String,
        capsuleId: String,
        blobId: String,
    ): String = java.io.File(
        java.io.File(
            java.io.File(incomingRootPath, "capsules"),
            capsuleId,
        ),
        "blobs/$blobId.ciphertext",
    ).absoluteFile.normalize().path

    private companion object {
        const val READY_STATUS = "READY"
        const val SHA256_BYTES = 32
    }
}
