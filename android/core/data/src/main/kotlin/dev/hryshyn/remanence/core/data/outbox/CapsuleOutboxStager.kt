package dev.hryshyn.remanence.core.data.outbox

import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.hryshyn.remanence.core.data.db.OutboxBlobEntity
import dev.hryshyn.remanence.core.data.db.OutboxBlobUploadState
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase

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
 *
 * FIX-REVIEW-04: sender and recipient identities are carried SEPARATELY -
 * immutable user IDs, key-bundle IDs, and the sender's public signing keyset
 * export - so persisted verification material never conflates the two ends.
 *
 * M2-P02: [ownerUserId] is the immutable local account that will own every
 * staged row; it is validated as a canonical UUID string before any byte or
 * row is written (docs/architecture.md section 6).
 */
data class PreparedOutboxCapsule(
    val capsuleId: UUID,
    val idempotencyKey: String,
    val ownerUserId: String,
    val senderUserId: UUID,
    val recipientUserId: UUID,
    val senderKeyBundleId: UUID,
    val recipientKeyBundleId: UUID,
    /** Sender Ed25519 PUBLIC keyset export (base64url); public material only. */
    val senderSigningPublicKeysetB64Url: String,
    val envelopeCiphertext: ByteArray,
    val artifacts: List<PreparedOutboxArtifact>,
    /** Signed deterministic statement carried for the finalize call (M2). */
    val publishStatementBytes: ByteArray,
    val publishStatementSignature: ByteArray,
)

/** The persisted outbox record after a successful atomic staging. */
data class StagedOutboxCapsule(
    val capsuleId: UUID,
    val envelopePath: String,
    val artifactPaths: List<String>,
)

/**
 * M1-C14 (docs/security.md section 6.2): persists a fully encrypted capsule
 * as one atomic local unit. Ciphertext files are written first (unique-temp-
 * then-rename, temp deleted in finally), then capsule and blob rows are
 * inserted and moved PREPARING -> ENCRYPTED through the guarded transition
 * inside a single Room transaction; any local failure before commit removes
 * only the files this invocation created, so neither a partial file set nor a
 * partial row set can ever be observed.
 *
 * Concurrent or replayed staging of the same capsule refuses before touching
 * the filesystem and can never overwrite or delete an already-committed
 * winner: the existence pre-check runs before any byte is written, a process
 * mutex serializes staging, and the authoritative re-check lives inside the
 * transaction. Only ciphertext is ever written: callers hand over encrypted
 * bytes and this class stores them verbatim.
 */
class CapsuleOutboxStager(
    private val database: RemanenceLocalDatabase,
    private val ciphertextDirectory: File,
) {

    private val stagingMutex = Mutex()

    suspend fun stage(prepared: PreparedOutboxCapsule): StagedOutboxCapsule {
        validate(prepared)
        val capsuleDao = database.outboxCapsuleDao()
        // Refuse a replayed capsule BEFORE writing any bytes so a losing
        // invocation cannot touch, overwrite, or clean up winner-owned files.
        capsuleDao.getByCapsuleId(prepared.capsuleId.toString())?.let {
            throw IllegalStateException("capsule already staged")
        }
        withContext(Dispatchers.IO) {
            ciphertextDirectory.mkdirs()
        }

        return stagingMutex.withLock {
            // Re-check under the lock and BEFORE any byte is written so a
            // queued invocation can neither overwrite nor clean up
            // winner-owned files.
            capsuleDao.getByCapsuleId(prepared.capsuleId.toString())?.let {
                throw IllegalStateException("capsule already staged")
            }
            val created = ArrayList<File>(prepared.artifacts.size + 3)
            try {
                val envelopePath =
                    writeBytes(created, "envelope-${prepared.capsuleId}.bin", prepared.envelopeCiphertext)
                val artifactPaths = prepared.artifacts.map { artifact ->
                    writeBytes(created, "${artifact.blobId}.bin", artifact.ciphertext)
                }
                val statementPath = writeBytes(
                    created,
                    "statement-${prepared.capsuleId}.bin",
                    prepared.publishStatementBytes,
                )
                val signaturePath = writeBytes(
                    created,
                    "signature-${prepared.capsuleId}.bin",
                    prepared.publishStatementSignature,
                )

                database.withTransaction {
                    // Authoritative re-check inside the transaction so a
                    // concurrent staging of the same capsule cannot slip
                    // between check and insert; refusal rolls the transaction
                    // back cleanly and leaves the winner untouched.
                    if (capsuleDao.getByCapsuleId(prepared.capsuleId.toString()) != null) {
                        throw IllegalStateException("capsule already staged")
                    }
                    capsuleDao.upsert(
                        OutboxCapsuleEntity(
                            capsuleId = prepared.capsuleId.toString(),
                            idempotencyKey = prepared.idempotencyKey,
                            ownerUserId = prepared.ownerUserId,
                            senderUserId = prepared.senderUserId.toString(),
                            recipientUserId = prepared.recipientUserId.toString(),
                            senderKeyBundleId = prepared.senderKeyBundleId.toString(),
                            recipientKeyBundleId = prepared.recipientKeyBundleId.toString(),
                            senderSigningPublicKeysetB64 = prepared.senderSigningPublicKeysetB64Url,
                            state = OutboxCapsuleState.PREPARING,
                            recognitionManifestPath = artifactPaths[recognitionIndex(prepared)],
                            contentManifestPath = artifactPaths[contentIndex(prepared)],
                            envelopePath = envelopePath,
                            publishStatementPath = statementPath,
                            publishStatementSignaturePath = signaturePath,
                            lastErrorCode = null,
                        ),
                    )
                    val transitioned = capsuleDao.transitionState(
                        prepared.capsuleId.toString(),
                        OutboxCapsuleState.ENCRYPTED,
                        listOf(OutboxCapsuleState.PREPARING),
                    )
                    check(transitioned == 1) { "staging must move PREPARING to ENCRYPTED" }
                    database.outboxBlobDao().upsertAll(
                        prepared.artifacts.mapIndexed { index, artifact ->
                            OutboxBlobEntity(
                                blobId = artifact.blobId.toString(),
                                ownerUserId = prepared.ownerUserId,
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
                StagedOutboxCapsule(prepared.capsuleId, envelopePath, artifactPaths)
            } catch (failure: Throwable) {
                created.forEach { it.delete() }
                throw failure
            }
        }
    }

    private suspend fun writeBytes(created: MutableList<File>, name: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            require(bytes.isNotEmpty()) { "refusing to persist empty bytes for $name" }
            val target = File(ciphertextDirectory, name)
            // Unique per-invocation temp name: two stagings can never rename
            // over or delete each other's in-flight temporary file.
            val temporary = File(ciphertextDirectory, "$name.tmp-${UUID.randomUUID()}")
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
        // M2-P02: the owning account must be a canonical lowercase UUID
        // string - round-tripping through UUID.fromString must reproduce it
        // exactly, so non-canonical forms fail before anything is persisted.
        val canonicalOwner = runCatching { UUID.fromString(prepared.ownerUserId) }.getOrNull()
        require(canonicalOwner != null && canonicalOwner.toString() == prepared.ownerUserId) {
            "owner account id must be a canonical UUID string"
        }
        require(prepared.envelopeCiphertext.isNotEmpty()) { "envelope ciphertext empty" }
        require(prepared.publishStatementBytes.isNotEmpty()) { "publish statement bytes empty" }
        require(prepared.publishStatementSignature.size == PUBLISH_SIGNATURE_LENGTH) {
            "publish signature must be the protocol-v1 69-byte TINK-prefixed Ed25519 form"
        }
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

    private companion object {
        const val PUBLISH_SIGNATURE_LENGTH = 69
    }
}
