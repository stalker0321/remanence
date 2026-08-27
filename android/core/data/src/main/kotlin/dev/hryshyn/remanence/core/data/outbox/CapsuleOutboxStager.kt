package dev.hryshyn.remanence.core.data.outbox

import androidx.room.withTransaction
import java.io.File
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
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
 *
 * M2-P08 staging-integration: [senderRetryWrappedKeysetBytes] is the
 * OPTIONAL opaque bytes of the sender-owned wrapped retry keyset, the
 * same bytes the [dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper]
 * produced. The field is nullable with a default of `null` so the existing
 * M1/M2 publisher path - which does not yet generate the wrapped retry
 * material - keeps working byte-for-byte unchanged. When the publisher
 * supplies non-null bytes, the stager persists them through
 * [dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore]
 * under the typed owner / capsule pair and records the canonical path
 * in `outbox_capsule.sender_retry_keyset_path`. When null, no retry file
 * is written and the entity column stays NULL. The stager MUST NOT
 * invent placeholder bytes, copy the envelope bytes, serialize any
 * plaintext keyset, or broaden the contract in any other way.
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
    /**
     * M2-P08: opaque wrapped retry keyset bytes, or null when the
     * publisher did not generate a wrapped keyset for this capsule.
     * When non-null the stager persists these bytes under
     * `accounts/<owner>/retry-material/<capsule>.pwks` and writes the
     * canonical path into the entity column. When null the stager
     * skips retry storage entirely.
     */
    val senderRetryWrappedKeysetBytes: ByteArray? = null,
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
 *
 * M2-P04 account storage scoping: every stage call resolves the staged
 * owner's [AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT] root from
 * `prepared.ownerUserId`. There is no shared outbox directory; all writes,
 * pre-checks, and collision cleanup happen strictly inside THAT account's
 * own directory, so a failed foreign-owner collision can never touch another
 * owner's material.
 *
 * M2-P08 staging-integration: when
 * [PreparedOutboxCapsule.senderRetryWrappedKeysetBytes] is non-null, the
 * stager ALSO persists those opaque bytes through
 * [SenderRetryMaterialStore] under the typed owner / capsule pair and
 * records the canonical path in `outbox_capsule.sender_retry_keyset_path`
 * inside the same successful staging flow. The retry file is created
 * AFTER every outbox-ciphertext file is durably committed and BEFORE
 * the Room transaction, and is rolled back alongside every other file
 * this invocation created on any later failure. When
 * [PreparedOutboxCapsule.senderRetryWrappedKeysetBytes] is null, the
 * stager skips retry storage entirely and the entity column stays
 * NULL - the M1/M2 publisher path is byte-for-byte unchanged. The
 * capsule-existence pre-check fires BEFORE the retry write, so a
 * replayed or foreign-owner capsule never touches retry storage; a
 * losing concurrent stage never deletes the winner's retry file.
 */
class CapsuleOutboxStager(
    private val database: RemanenceLocalDatabase,
    private val roots: AccountScopedFileRoots,
    private val senderRetryMaterialStore: SenderRetryMaterialStore,
) {

    private val stagingMutex = Mutex()

    /**
     * M2-P04 review fix: fail closed BEFORE any byte is written if the
     * resolved owner OUTBOX_CIPHERTEXT root cannot be materialized - a plain
     * file (or symlink) occupying the directory path, or a refused mkdirs.
     * Everything that follows (file writes, cleanup, Room rows) assumes a
     * usable directory, so staging must never start half-ready. The failure
     * is strictly local to this account's own root: no other account's
     * directory can be inspected or touched by a wrong or missing owner
     * directory.
     */
    private suspend fun ensureOwnerCiphertextRoot(directory: File) =
        withContext(Dispatchers.IO) {
            val created = !directory.exists() && directory.mkdirs()
            check(created || (directory.exists() && directory.isDirectory)) {
                "cannot prepare outbox ciphertext root for this account"
            }
        }

    suspend fun stage(prepared: PreparedOutboxCapsule): StagedOutboxCapsule {
        validate(prepared)
        // One typed owner resolution per stage: the directory this invocation
        // reads, writes, and cleans up belongs to exactly this account.
        val ownerId = UserId.parseRest(prepared.ownerUserId)
        val capsuleId = CapsuleId.parseRest(prepared.capsuleId.toString())
        val ciphertextDirectory = roots.child(
            ownerId,
            AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT,
        )
        val capsuleDao = database.outboxCapsuleDao()
        // M2-P03: account-scoped replay refusals - a lookup and every durable
        // transition carry the staging owner so only the owning account's own
        // row can be observed or resumed (a colliding UUID owned by another
        // account is refused later by the strict insert constraint).
        // Refuse a replayed capsule BEFORE writing any bytes so a losing
        // invocation cannot touch, overwrite, or clean up winner-owned files.
        // M2-P08: the pre-check fires before the retry store is ever
        // touched; a losing concurrent stage never deletes the winner's
        // retry file.
        capsuleDao.getByCapsuleIdAndOwner(
            prepared.capsuleId.toString(),
            prepared.ownerUserId,
        )?.let {
            throw IllegalStateException("capsule already staged")
        }
        ensureOwnerCiphertextRoot(ciphertextDirectory)

        return stagingMutex.withLock {
            // Re-check under the lock and BEFORE any byte is written so a
            // queued invocation can neither overwrite nor clean up
            // winner-owned files.
            capsuleDao.getByCapsuleIdAndOwner(
                prepared.capsuleId.toString(),
                prepared.ownerUserId,
            )?.let {
                throw IllegalStateException("capsule already staged")
            }
            val created = ArrayList<File>(prepared.artifacts.size + 3)
            // M2-P08: the retry material file (when one is staged) is added
            // to `created` so any later failure in this invocation rolls
            // it back alongside every other file. The retry file is the
            // LAST file written so a clean successful staging has the
            // canonical retry path available for the entity column.
            var retryKeysetPath: String? = null
            try {
                val envelopePath =
                    writeBytes(
                        ciphertextDirectory,
                        created,
                        "envelope-${prepared.capsuleId}.bin",
                        prepared.envelopeCiphertext,
                    )
                val artifactPaths = prepared.artifacts.map { artifact ->
                    writeBytes(ciphertextDirectory, created, "${artifact.blobId}.bin", artifact.ciphertext)
                }
                val statementPath = writeBytes(
                    ciphertextDirectory,
                    created,
                    "statement-${prepared.capsuleId}.bin",
                    prepared.publishStatementBytes,
                )
                val signaturePath = writeBytes(
                    ciphertextDirectory,
                    created,
                    "signature-${prepared.capsuleId}.bin",
                    prepared.publishStatementSignature,
                )

                // M2-P08: persist the wrapped retry keyset under the typed
                // owner / capsule pair BEFORE the Room transaction so the
                // canonical path is known when the entity is inserted.
                // The retry store refuses to overwrite, so a concurrent
                // winner cannot be silently replaced.
                if (prepared.senderRetryWrappedKeysetBytes != null) {
                    retryKeysetPath = senderRetryMaterialStore.write(
                        owner = ownerId,
                        capsule = capsuleId,
                        bytes = prepared.senderRetryWrappedKeysetBytes,
                    )
                    // Register the persisted file for rollback if any
                    // later step fails. The retry store already removes
                    // its own temp on failure; this entry is only the
                    // committed file.
                    created += File(retryKeysetPath)
                }

                database.withTransaction {
                    // Authoritative re-check inside the transaction so a
                    // concurrent staging of the same capsule cannot slip
                    // between check and insert; refusal rolls the transaction
                    // back cleanly and leaves the winner untouched.
                    if (capsuleDao.getByCapsuleIdAndOwner(
                            prepared.capsuleId.toString(),
                            prepared.ownerUserId,
                        ) != null
                    ) {
                        throw IllegalStateException("capsule already staged")
                    }
                    capsuleDao.insertOrAbort(
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
                            // M2-P08: the canonical retry path (null when
                            // the publisher did not supply bytes).
                            senderRetryKeysetPath = retryKeysetPath,
                            lastErrorCode = null,
                        ),
                    )
                    val transitioned = capsuleDao.transitionStateForOwner(
                        prepared.capsuleId.toString(),
                        prepared.ownerUserId,
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
                // Rollback: delete every file this invocation created,
                // including the retry file (added above). Order does not
                // matter - a half-rolled-back state is no worse than the
                // pre-call state, and the retry store is a separate
                // owner-scoped surface.
                created.forEach { it.delete() }
                throw failure
            }
        }
    }

    /** Writes [bytes] beneath the owner's own ciphertext directory; refuses to overwrite any pre-existing target. */
    private suspend fun writeBytes(
        ownerCiphertextRoot: File,
        created: MutableList<File>,
        name: String,
        bytes: ByteArray,
    ): String =
        withContext(Dispatchers.IO) {
            require(bytes.isNotEmpty()) { "refusing to persist empty bytes for $name" }
            val target = File(ownerCiphertextRoot, name)
            // M2 review fix: a target that already exists is either another
            // account's committed ciphertext or corrupt residue; overwriting
            // it would silently damage the winner. Refuse BEFORE any byte of
            // this invocation lands, so a foreign-owner capsule_id collision
            // cannot touch winner-owned files either.
            if (target.exists()) {
                throw IllegalStateException("outbox file already present: $name")
            }
            // Unique per-invocation temp name: two stagings can never rename
            // over or delete each other's in-flight temporary file.
            val temporary = File(ownerCiphertextRoot, "$name.tmp-${UUID.randomUUID()}")
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
        // M2-P08: when the publisher supplies wrapped retry bytes, the
        // payload MUST be non-empty - an empty byte array is an invariant
        // violation (the crypto layer never produces one) and is refused
        // here before any retry file is created.
        val retryBytes = prepared.senderRetryWrappedKeysetBytes
        require(retryBytes == null || retryBytes.isNotEmpty()) {
            "sender retry wrapped keyset bytes must be null or non-empty"
        }
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
