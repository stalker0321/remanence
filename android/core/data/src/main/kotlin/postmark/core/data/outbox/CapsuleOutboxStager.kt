package postmark.core.data.outbox

import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import postmark.core.data.db.OutboxBlobEntity
import postmark.core.data.db.OutboxBlobUploadState
import postmark.core.data.db.OutboxCapsuleDao
import postmark.core.data.db.OutboxCapsuleEntity
import postmark.core.data.db.OutboxCapsuleState
import postmark.core.data.db.PostmarkLocalDatabase

/** Kind of one declared ciphertext blob in the outbox. */
enum class OutboxArtifactKind { RECOGNITION_MANIFEST, CONTENT_MANIFEST, PHOTO }

/** One already-encrypted artifact ready for the ciphertext-only outbox. */
data class PreparedOutboxArtifact(
    val blobId: UUID,
    val kind: OutboxArtifactKind,
    val ordinal: Int,
    val ciphertext: ByteArray,
)

/**
 * Everything the outbox transaction needs: routing snapshots plus the exact
 * ciphertext bytes of the envelope and every declared blob. No plaintext ever
 * enters this type or this class.
 */
data class PreparedOutboxCapsule(
    val capsuleId: UUID,
    val idempotencyKey: String,
    val recipientUserId: UUID,
    val recipientKeyBundleId: UUID,
    val envelopeCiphertext: ByteArray,
    val artifacts: List<PreparedOutboxArtifact>,
)

/** The persisted outbox record after a successful atomic staging. */
data class StagedOutboxCapsule(
    val capsuleId: UUID,
    val envelopePath: String,
    val artifactPaths: List<String>,
)

/**
 * M1-C14 (docs/security.md section 6.2): persists a fully encrypted capsule
 * as one atomic local unit. Ciphertext files are written first (temp-then-
 * rename, temp deleted in finally), then capsule and blob rows are inserted
 * inside a single Room transaction; any local failure before commit removes
 * every file this invocation created, so neither a partial file set nor a
 * partial row set can ever be observed. Only ciphertext is ever written:
 * callers hand over encrypted bytes and this class stores them verbatim.
 */
class CapsuleOutboxStager(
    private val database: PostmarkLocalDatabase,
    private val ciphertextDirectory: File,
) {

    suspend fun stage(prepared: PreparedOutboxCapsule): StagedOutboxCapsule {
        validate(prepared)
        val capsuleDao = database.outboxCapsuleDao()
        withContext(Dispatchers.IO) {
            ciphertextDirectory.mkdirs()
        }

        val created = ArrayList<File>(prepared.artifacts.size + 1)
        try {
            val envelopePath = writeCiphertext(created, "envelope-${prepared.capsuleId}.bin", prepared.envelopeCiphertext)
            val artifactPaths = prepared.artifacts.map { artifact ->
                writeCiphertext(created, "${artifact.blobId}.bin", artifact.ciphertext)
            }

            database.withTransaction {
                // Existence check lives inside the transaction so a concurrent
                // staging of the same capsule cannot slip between check and
                // insert; refusal rolls the transaction back cleanly.
                if (capsuleDao.getByCapsuleId(prepared.capsuleId.toString()) != null) {
                    throw IllegalStateException("capsule already staged")
                }
                capsuleDao.upsert(
                    OutboxCapsuleEntity(
                        capsuleId = prepared.capsuleId.toString(),
                        idempotencyKey = prepared.idempotencyKey,
                        recipientUserId = prepared.recipientUserId.toString(),
                        recipientKeyBundleId = prepared.recipientKeyBundleId.toString(),
                        state = OutboxCapsuleState.ENCRYPTED,
                        recognitionManifestPath = artifactPaths[recognitionIndex(prepared)],
                        contentManifestPath = artifactPaths[contentIndex(prepared)],
                        envelopePath = envelopePath,
                        lastErrorCode = null,
                    ),
                )
                database.outboxBlobDao().upsertAll(
                    prepared.artifacts.mapIndexed { index, artifact ->
                        OutboxBlobEntity(
                            blobId = artifact.blobId.toString(),
                            capsuleId = prepared.capsuleId.toString(),
                            kind = artifact.kind.name,
                            ordinal = artifact.ordinal,
                            localCiphertextPath = artifactPaths[index],
                            sizeBytes = artifact.ciphertext.size.toLong(),
                            sha256 = sha256(artifact.ciphertext),
                            uploadState = OutboxBlobUploadState.PENDING,
                            attemptCount = 0,
                        )
                    },
                )
            }
            return StagedOutboxCapsule(prepared.capsuleId, envelopePath, artifactPaths)
        } catch (failure: Throwable) {
            created.forEach { it.delete() }
            throw failure
        }
    }

    private suspend fun writeCiphertext(created: MutableList<File>, name: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val target = File(ciphertextDirectory, name)
            val temporary = File(ciphertextDirectory, "$name.tmp")
            try {
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    throw IllegalStateException("could not persist $name")
                }
                created += target
                target.absolutePath
            } finally {
                temporary.delete()
            }
        }

    private fun validate(prepared: PreparedOutboxCapsule) {
        require(prepared.idempotencyKey.isNotBlank()) { "idempotency key missing" }
        require(prepared.envelopeCiphertext.isNotEmpty()) { "envelope ciphertext empty" }
        val kinds = prepared.artifacts.groupBy { it.kind }
        require(kinds[OutboxArtifactKind.RECOGNITION_MANIFEST].orEmpty().size == 1) {
            "exactly one recognition manifest required"
        }
        require(kinds[OutboxArtifactKind.CONTENT_MANIFEST].orEmpty().size == 1) {
            "exactly one content manifest required"
        }
        val photos = kinds[OutboxArtifactKind.PHOTO].orEmpty()
        require(photos.size in 3..5) { "3..5 photos required" }
        require(photos.map { it.ordinal } == photos.indices.toList()) {
            "photo ordinals must be sequential from 0"
        }
        val ids = prepared.artifacts.map { it.blobId }
        require(ids.size == ids.distinct().size) { "duplicate blob id" }
        require(prepared.artifacts.all { it.ciphertext.isNotEmpty() }) { "empty ciphertext" }
    }

    private fun recognitionIndex(prepared: PreparedOutboxCapsule): Int =
        prepared.artifacts.indexOfFirst { it.kind == OutboxArtifactKind.RECOGNITION_MANIFEST }

    private fun contentIndex(prepared: PreparedOutboxCapsule): Int =
        prepared.artifacts.indexOfFirst { it.kind == OutboxArtifactKind.CONTENT_MANIFEST }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
