package dev.hryshyn.remanence.core.data.db

import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.DurableIncomingCiphertextFile
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** The safe, redacted outcomes of one incoming index persistence attempt. */
enum class IncomingIndexAcceptanceFailure {
    NO_AUTHENTICATED_OWNER,
    OWNER_MISMATCH,
    CAPSULE_MISMATCH,
    BLOB_MISMATCH,
    CAPABILITY_MISMATCH,
    PATH_MISMATCH,
    SOURCE_MISSING,
    SOURCE_NOT_REGULAR,
    SOURCE_INTEGRITY_MISMATCH,
    MISSING_ROW,
    IMMUTABLE_BINDING_MISMATCH,
    ILLEGAL_STATE,
    CONCURRENT_OR_STALE,
    LOCAL_STORAGE,
}

sealed interface IncomingIndexAcceptanceCommitResult {
    /** Both owner-scoped state changes committed in one Room transaction. */
    data object Committed : IncomingIndexAcceptanceCommitResult

    /** Both terminal states and all immutable bindings already match exactly. */
    data object IdempotentReplay : IncomingIndexAcceptanceCommitResult

    data class Failure(
        val reason: IncomingIndexAcceptanceFailure,
        val retryable: Boolean,
    ) : IncomingIndexAcceptanceCommitResult
}

/**
 * Exact input to the A11c2 persistence boundary. The destination is not a
 * caller-selected path: it is the opaque capability returned by A11c1 and is
 * checked against the canonical owner/capsule/blob layout before Room work.
 */
class IncomingIndexAcceptanceCommitRequest(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val recognitionBlobId: BlobId,
    val expectedSizeBytes: Long,
    expectedSha256: ByteArray,
    val durableCiphertext: DurableIncomingCiphertextFile,
) {
    private val expectedSha256Snapshot = expectedSha256.copyOf()

    val expectedSha256: ByteArray
        get() = expectedSha256Snapshot.copyOf()

    init {
        require(expectedSizeBytes in 1L..ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES) {
            "recognition ciphertext size is outside the protocol limit"
        }
        require(expectedSha256Snapshot.size == SHA256_BYTES) {
            "recognition ciphertext hash must be SHA-256"
        }
    }

    override fun toString(): String =
        "IncomingIndexAcceptanceCommitRequest(<redacted>)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/**
 * Binds A11c1's durable file capability to the owner-scoped incoming rows,
 * then performs the blob and capsule CAS operations through one Room
 * transaction. It has no network, worker, plaintext, or UI responsibility.
 * File verification is a preflight outside Room; this boundary does not claim
 * filesystem-plus-database atomicity. A retry re-verifies the durable file and
 * replays the owner-scoped Room transaction safely.
 */
class IncomingIndexAcceptanceCommitter(
    private val database: RemanenceLocalDatabase,
    private val roots: AccountScopedFileRoots,
) {

    suspend fun commit(
        request: IncomingIndexAcceptanceCommitRequest,
        authenticatedOwnerUserId: UserId?,
    ): IncomingIndexAcceptanceCommitResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        if (authenticatedOwnerUserId == null) {
            return@withContext failure(IncomingIndexAcceptanceFailure.NO_AUTHENTICATED_OWNER, false)
        }
        if (authenticatedOwnerUserId != request.ownerUserId) {
            return@withContext failure(IncomingIndexAcceptanceFailure.OWNER_MISMATCH, false)
        }

        val owner = request.ownerUserId.toRestString()
        val capsule = request.capsuleId.toRestString()
        val blob = request.recognitionBlobId.toRestString()

        val expectedDestination = try {
            derivedDestination(request.ownerUserId, request.capsuleId, request.recognitionBlobId)
        } catch (_: SecurityException) {
            return@withContext failure(IncomingIndexAcceptanceFailure.PATH_MISMATCH, false)
        } catch (_: IllegalArgumentException) {
            return@withContext failure(IncomingIndexAcceptanceFailure.PATH_MISMATCH, false)
        } catch (_: Exception) {
            return@withContext failure(IncomingIndexAcceptanceFailure.PATH_MISMATCH, false)
        }

        if (request.durableCiphertext.ownerUserId != request.ownerUserId) {
            return@withContext failure(IncomingIndexAcceptanceFailure.OWNER_MISMATCH, false)
        }
        if (request.durableCiphertext.capsuleId != request.capsuleId) {
            return@withContext failure(IncomingIndexAcceptanceFailure.CAPSULE_MISMATCH, false)
        }
        if (request.durableCiphertext.blobId != request.recognitionBlobId) {
            return@withContext failure(IncomingIndexAcceptanceFailure.BLOB_MISMATCH, false)
        }

        val capabilityPath = try {
            request.durableCiphertext.asFile().toPath().toAbsolutePath().normalize()
        } catch (_: SecurityException) {
            return@withContext failure(IncomingIndexAcceptanceFailure.CAPABILITY_MISMATCH, false)
        }
        val incomingRoot = try {
            roots.child(
                request.ownerUserId,
                AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT,
            ).toPath().toAbsolutePath().normalize()
        } catch (_: SecurityException) {
            return@withContext failure(IncomingIndexAcceptanceFailure.PATH_MISMATCH, false)
        } catch (_: Exception) {
            return@withContext failure(IncomingIndexAcceptanceFailure.PATH_MISMATCH, false)
        }
        if (capabilityPath != expectedDestination ||
            !isContained(capabilityPath, incomingRoot) ||
            !isNoSymlinkPath(capabilityPath)
        ) {
            return@withContext failure(IncomingIndexAcceptanceFailure.CAPABILITY_MISMATCH, false)
        }

        when (verifyDestination(capabilityPath, request)) {
            DestinationVerification.MATCH -> Unit
            DestinationVerification.MISSING ->
                return@withContext failure(IncomingIndexAcceptanceFailure.SOURCE_MISSING, true)
            DestinationVerification.NOT_REGULAR ->
                return@withContext failure(IncomingIndexAcceptanceFailure.SOURCE_NOT_REGULAR, false)
            DestinationVerification.SYMLINK ->
                return@withContext failure(IncomingIndexAcceptanceFailure.PATH_MISMATCH, false)
            DestinationVerification.MISMATCH ->
                return@withContext failure(IncomingIndexAcceptanceFailure.SOURCE_INTEGRITY_MISMATCH, false)
            DestinationVerification.READ_FAILURE ->
                return@withContext failure(IncomingIndexAcceptanceFailure.LOCAL_STORAGE, true)
        }

        val transactionResult = try {
            database.incomingIndexAcceptanceDao().commitAcceptedIndex(
                ownerUserId = owner,
                capsuleId = capsule,
                recognitionBlobId = blob,
                expectedSizeBytes = request.expectedSizeBytes,
                expectedSha256 = request.expectedSha256,
                expectedLocalPath = capabilityPath.toString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IncomingIndexAcceptanceTransactionAbort) {
            return@withContext failure(IncomingIndexAcceptanceFailure.CONCURRENT_OR_STALE, true)
        } catch (_: Exception) {
            return@withContext failure(IncomingIndexAcceptanceFailure.LOCAL_STORAGE, true)
        }

        when (transactionResult) {
            is IncomingIndexAcceptanceTransactionResult.Committed ->
                IncomingIndexAcceptanceCommitResult.Committed
            is IncomingIndexAcceptanceTransactionResult.IdempotentReplay ->
                IncomingIndexAcceptanceCommitResult.IdempotentReplay
            is IncomingIndexAcceptanceTransactionResult.Rejected ->
                failure(transactionResult.reason, retryable = false)
        }
    }

    private fun derivedDestination(
        owner: UserId,
        capsule: CapsuleId,
        blob: BlobId,
    ): Path {
        return roots.incomingCiphertextPath(owner, capsule, blob)
    }

    private suspend fun verifyDestination(
        destination: Path,
        request: IncomingIndexAcceptanceCommitRequest,
    ): DestinationVerification {
        val attributes = try {
            Files.readAttributes(
                destination,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: java.nio.file.NoSuchFileException) {
            return DestinationVerification.MISSING
        } catch (_: IOException) {
            return DestinationVerification.READ_FAILURE
        } catch (_: SecurityException) {
            return DestinationVerification.READ_FAILURE
        }
        if (attributes.isSymbolicLink) return DestinationVerification.SYMLINK
        if (!attributes.isRegularFile) return DestinationVerification.NOT_REGULAR
        if (attributes.size() != request.expectedSizeBytes) return DestinationVerification.MISMATCH

        val digest = try {
            MessageDigest.getInstance(SHA256).also { hash ->
                Files.newInputStream(destination, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > request.expectedSizeBytes) return DestinationVerification.MISMATCH
                        hash.update(buffer, 0, read)
                    }
                    if (total != request.expectedSizeBytes) return DestinationVerification.MISMATCH
                }
            }.digest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return DestinationVerification.READ_FAILURE
        } catch (_: SecurityException) {
            return DestinationVerification.READ_FAILURE
        }
        return if (MessageDigest.isEqual(digest, request.expectedSha256)) {
            DestinationVerification.MATCH
        } else {
            DestinationVerification.MISMATCH
        }
    }

    private fun isNoSymlinkPath(path: Path): Boolean {
        var current: Path? = path
        return try {
            while (current != null) {
                val examined = current
                val attributes = try {
                    Files.readAttributes(
                        examined,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: java.nio.file.NoSuchFileException) {
                    current = examined.parent
                    continue
                }
                if (attributes.isSymbolicLink) return false
                current = examined.parent
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isContained(candidate: Path, root: Path): Boolean =
        candidate != root && candidate.startsWith(root)

    private fun failure(
        reason: IncomingIndexAcceptanceFailure,
        retryable: Boolean,
    ): IncomingIndexAcceptanceCommitResult =
        IncomingIndexAcceptanceCommitResult.Failure(reason, retryable)

    private enum class DestinationVerification {
        MATCH,
        MISSING,
        NOT_REGULAR,
        SYMLINK,
        MISMATCH,
        READ_FAILURE,
    }

    private companion object {
        const val SHA256 = "SHA-256"
        const val STREAM_BUFFER_BYTES = 32 * 1024
    }
}

/** Result returned by the Room transaction before redaction/classification. */
sealed interface IncomingIndexAcceptanceTransactionResult {
    data object Committed : IncomingIndexAcceptanceTransactionResult
    data object IdempotentReplay : IncomingIndexAcceptanceTransactionResult
    data class Rejected(val reason: IncomingIndexAcceptanceFailure) :
        IncomingIndexAcceptanceTransactionResult
}

/** Thrown only to force Room to roll back a preceding CAS in this transaction. */
private class IncomingIndexAcceptanceTransactionAbort : IllegalStateException()

/**
 * Dedicated cross-table Room transaction for the exact A11c2 state change.
 * All reads and writes carry the immutable owner predicate.
 */
@Dao
abstract class IncomingIndexAcceptanceDao {

    @Transaction
    open suspend fun commitAcceptedIndex(
        ownerUserId: String,
        capsuleId: String,
        recognitionBlobId: String,
        expectedSizeBytes: Long,
        expectedSha256: ByteArray,
        expectedLocalPath: String,
    ): IncomingIndexAcceptanceTransactionResult {
        if (ownerUserId.isBlank() || capsuleId.isBlank() || recognitionBlobId.isBlank()) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            )
        }
        if (expectedSizeBytes !in 1L..ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES ||
            expectedSha256.size != SHA256_BYTES ||
            expectedLocalPath.isBlank()
        ) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            )
        }

        val capsule = findCapsule(capsuleId, ownerUserId)
            ?: return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.MISSING_ROW,
            )
        val envelope = findEnvelope(capsuleId, ownerUserId)
            ?: return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.MISSING_ROW,
            )
        val recognitionRows = findRecognitionBlobs(capsuleId, ownerUserId)
        if (recognitionRows.size != 1) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            )
        }
        val recognition = recognitionRows.single()

        if (capsule.ownerUserId != ownerUserId || envelope.ownerUserId != ownerUserId ||
            recognition.ownerUserId != ownerUserId
        ) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.OWNER_MISMATCH,
            )
        }
        if (capsule.capsuleId != capsuleId || envelope.capsuleId != capsuleId ||
            recognition.capsuleId != capsuleId || recognition.blobId != recognitionBlobId
        ) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.CAPSULE_MISMATCH,
            )
        }
        if (capsule.recipientUserId != ownerUserId ||
            capsule.protocolVersion != ProtocolV1Limits.PROTOCOL_VERSION ||
            capsule.serverStatus != READY_STATUS ||
            envelope.recipientKeyBundleId != capsule.recipientEncryptionKeyBundleId
        ) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            )
        }
        if (recognition.kind != CapsuleArtifactKind.RECOGNITION_MANIFEST.name ||
            recognition.ordinal != null ||
            recognition.expectedSizeBytes != expectedSizeBytes ||
            !recognition.expectedSha256.contentEquals(expectedSha256) ||
            recognition.localPath != expectedLocalPath
        ) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            )
        }

        val capsuleState = capsule.materialState
        val blobState = recognition.cacheState
        if (capsuleState == LocalMaterialState.INDEX_CACHED &&
            blobState == BlobCacheState.CACHED
        ) {
            return IncomingIndexAcceptanceTransactionResult.IdempotentReplay
        }
        if (capsuleState != LocalMaterialState.DISCOVERED ||
            blobState != BlobCacheState.DOWNLOADING
        ) {
            return IncomingIndexAcceptanceTransactionResult.Rejected(
                IncomingIndexAcceptanceFailure.ILLEGAL_STATE,
            )
        }

        if (markRecognitionCached(
                ownerUserId = ownerUserId,
                capsuleId = capsuleId,
                recognitionBlobId = recognitionBlobId,
            ) != 1
        ) {
            throw IncomingIndexAcceptanceTransactionAbort()
        }
        if (markCapsuleIndexCached(ownerUserId, capsuleId) != 1) {
            throw IncomingIndexAcceptanceTransactionAbort()
        }
        return IncomingIndexAcceptanceTransactionResult.Committed
    }

    @Query(
        "SELECT * FROM incoming_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId LIMIT 1",
    )
    protected abstract suspend fun findCapsule(
        capsuleId: String,
        ownerUserId: String,
    ): IncomingCapsuleEntity?

    @Query(
        "SELECT * FROM incoming_envelope " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId LIMIT 1",
    )
    protected abstract suspend fun findEnvelope(
        capsuleId: String,
        ownerUserId: String,
    ): IncomingEnvelopeEntity?

    @Query(
        "SELECT * FROM blob_cache " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND kind = 'RECOGNITION_MANIFEST'",
    )
    protected abstract suspend fun findRecognitionBlobs(
        capsuleId: String,
        ownerUserId: String,
    ): List<BlobCacheEntity>

    @Query(
        "UPDATE blob_cache SET cache_state = 'CACHED' " +
            "WHERE blob_id = :recognitionBlobId AND capsule_id = :capsuleId " +
            "AND owner_user_id = :ownerUserId AND kind = 'RECOGNITION_MANIFEST' " +
            "AND cache_state = 'DOWNLOADING'",
    )
    protected abstract suspend fun markRecognitionCached(
        ownerUserId: String,
        capsuleId: String,
        recognitionBlobId: String,
    ): Int

    @Query(
        "UPDATE incoming_capsule SET material_state = 'INDEX_CACHED' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND server_status = 'READY' AND material_state = 'DISCOVERED'",
    )
    protected abstract suspend fun markCapsuleIndexCached(
        ownerUserId: String,
        capsuleId: String,
    ): Int

    private companion object {
        const val READY_STATUS = "READY"
        const val SHA256_BYTES = 32
    }
}
