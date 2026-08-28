package dev.hryshyn.remanence.sync

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.RecipientRewrapRequest
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.crypto.WrappedKeysetRecord
import dev.hryshyn.remanence.core.data.db.OutboxBlobDao
import dev.hryshyn.remanence.core.data.db.OutboxBlobEntity
import dev.hryshyn.remanence.core.data.db.OutboxBlobUploadState
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadFailure
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadRequest
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadResult
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlobDeclaration
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlobState
import dev.hryshyn.remanence.core.data.network.CapsuleDraft
import dev.hryshyn.remanence.core.data.network.CapsuleDraftFailure
import dev.hryshyn.remanence.core.data.network.CapsuleDraftRequest
import dev.hryshyn.remanence.core.data.network.CapsuleDraftResult
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeFailure
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeRequest
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeResult
import dev.hryshyn.remanence.core.data.network.RecipientUserLookupResult
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialLifecycle
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidation
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidator
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CanonicalArtifactOrder
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.PublishArtifact
import dev.hryshyn.remanence.core.model.PublishStatementBuildResult
import dev.hryshyn.remanence.core.model.PublishStatementBuilder
import dev.hryshyn.remanence.core.model.PublishStatementInput
import dev.hryshyn.remanence.core.model.RecipientTarget
import dev.hryshyn.remanence.core.model.SenderRetryPurpose
import dev.hryshyn.remanence.core.model.SenderRetryWrapContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Result of one account/capsule-scoped upload attempt. */
sealed interface CapsuleUploadOutcome {
    data object Succeeded : CapsuleUploadOutcome

    /** The worker was stale after logout/account switch; no durable write was attempted. */
    data object AccountMismatch : CapsuleUploadOutcome

    /** The scoped capsule row no longer exists. */
    data object Missing : CapsuleUploadOutcome

    /** A06 owns recovery; this marker intentionally parks unique work. */
    data object RecipientKeyStale : CapsuleUploadOutcome

    data class Retryable(val errorCode: String) : CapsuleUploadOutcome

    data class TerminalFailure(val errorCode: String) : CapsuleUploadOutcome
}

/**
 * Orchestrates one already-staged sender capsule.
 *
 * The worker identity is the only caller-supplied scope. Every Room read and
 * CAS carries that owner, and the live account is checked before every
 * network call and durable transition. A05a reconciles authoritative server
 * STORED blobs from the draft replay and skips only those blobs; remaining
 * blobs retain canonical order. Ciphertext is read from the staged paths and
 * is never regenerated. A05 startup discovery must exclude
 * RETRYABLE_FAILURE rows marked RECIPIENT_KEY_STALE until A06 owns their
 * recovery.
 */
class CapsuleUploadOrchestrator(
    private val capsuleDao: OutboxCapsuleDao,
    private val blobDao: OutboxBlobDao,
    private val currentAccountUserId: suspend () -> String?,
    private val accessToken: () -> String?,
    private val createDraft: suspend (CapsuleDraftRequest, String) -> CapsuleDraftResult,
    private val uploadBlob: suspend (CapsuleBlobUploadRequest, String) -> CapsuleBlobUploadResult,
    private val finalizeCapsule: suspend (CapsuleFinalizeRequest, String) -> CapsuleFinalizeResult,
    private val cleanupRetryMaterial: suspend (UserId, CapsuleId) -> SenderRetryMaterialLifecycle.Result,
    private val readCiphertext: suspend (String) -> ByteArray = { path -> File(path).readBytes() },
    private val recipientUserLookup: (suspend (UserId, String) -> RecipientUserLookupResult)? = null,
    private val retryMaterialStore: SenderRetryMaterialStore? = null,
    private val senderRetryKeysetWrapper: SenderRetryKeysetWrapper? = null,
    private val loadSenderSigningKeyset: (suspend (UserId, KeyBundleId) -> KeysetHandle?)? = null,
    private val accountScopedFileRoots: AccountScopedFileRoots? = null,
) {

    suspend fun run(owner: UserId, capsuleId: CapsuleId): CapsuleUploadOutcome {
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch

        val ownerText = owner.toRestString()
        var capsule = capsuleDao.getByCapsuleIdAndOwner(capsuleId.toRestString(), ownerText)
            ?: return CapsuleUploadOutcome.Missing
        if (capsule.ownerUserId != ownerText || capsule.senderUserId != ownerText) {
            return markTerminal(owner, capsuleId, "INTERNAL_ERROR")
        }

        when (capsule.state) {
            OutboxCapsuleState.PUBLISHED -> return finishPublished(owner, capsuleId)
            OutboxCapsuleState.TERMINAL_FAILURE ->
                return finalizeTerminalFailure(
                    owner,
                    capsuleId,
                    capsule.lastErrorCode ?: "INTERNAL_ERROR",
                )
            OutboxCapsuleState.PREPARING ->
                return markTerminal(owner, capsuleId, "CAPSULE_STATE_INVALID")
            OutboxCapsuleState.ENCRYPTED,
            OutboxCapsuleState.UPLOADING,
            OutboxCapsuleState.FINALIZING,
            -> Unit
            OutboxCapsuleState.RETRYABLE_FAILURE -> {
                if (capsule.lastErrorCode == RECIPIENT_KEY_STALE) {
                    return recoverStaleRecipient(owner, capsuleId, capsule)
                }
            }
        }

        val prepared = try {
            prepare(capsule, owner, capsuleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (prepared == null) return markTerminal(owner, capsuleId, "INTERNAL_ERROR")

        var serverStoredBlobIds: Set<BlobId> = emptySet()
        if (capsule.state != OutboxCapsuleState.FINALIZING) {
            val token = accessToken() ?: return markRetryable(owner, capsuleId, "AUTH_INVALID")
            if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
            val draftResult = try {
                createDraft(prepared.draftRequest, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return markRetryable(owner, capsuleId, "INTERNAL_UNAVAILABLE")
            }
            val draft = when (draftResult) {
                is CapsuleDraftResult.Success -> draftResult.draft
                is CapsuleDraftResult.Failure ->
                    return applyDraftFailure(owner, capsuleId, draftResult)
            }
            reconcileStoredBlobs(owner, capsuleId, prepared, draft)?.let { return it }
            serverStoredBlobIds = draft.blobs
                .filter { it.state == CapsuleDraftBlobState.STORED }
                .map { it.blobId }
                .toSet()

            if (capsule.state == OutboxCapsuleState.ENCRYPTED ||
                capsule.state == OutboxCapsuleState.RETRYABLE_FAILURE
            ) {
                if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
                val moved = try {
                    capsuleDao.beginUploadForOwner(capsuleId.toRestString(), ownerText)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
                }
                if (moved != 1) {
                    capsule = capsuleDao.getByCapsuleIdAndOwner(capsuleId.toRestString(), ownerText)
                        ?: return CapsuleUploadOutcome.Missing
                    if (capsule.state != OutboxCapsuleState.UPLOADING) {
                        return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
                    }
                }
                capsule = capsule.copy(state = OutboxCapsuleState.UPLOADING)
            }

            for (blob in prepared.blobs) {
                if (blob.blobId in serverStoredBlobIds) continue
                val result = uploadOne(owner, capsuleId, blob)
                if (result != null) return result
            }
        }

        val finalizeRequest = try {
            readFinalizeRequest(capsule, prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return markTerminal(owner, capsuleId, "ENVELOPE_INVALID")
        }

        if (capsule.state == OutboxCapsuleState.UPLOADING) {
            if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
            val moved = try {
                capsuleDao.beginFinalizeForOwner(capsuleId.toRestString(), ownerText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
            }
            if (moved != 1) {
                capsule = capsuleDao.getByCapsuleIdAndOwner(capsuleId.toRestString(), ownerText)
                    ?: return CapsuleUploadOutcome.Missing
                when (capsule.state) {
                    OutboxCapsuleState.FINALIZING -> Unit
                    OutboxCapsuleState.PUBLISHED -> return finishPublished(owner, capsuleId)
                    else -> return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
                }
            }
            capsule = capsule.copy(state = OutboxCapsuleState.FINALIZING)
        }

        val token = accessToken() ?: return markRetryable(owner, capsuleId, "AUTH_INVALID")
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        val finalizeResult = try {
            finalizeCapsule(finalizeRequest, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return markRetryable(owner, capsuleId, "INTERNAL_UNAVAILABLE")
        }
        when (finalizeResult) {
            is CapsuleFinalizeResult.Failure ->
                return applyFinalizeFailure(owner, capsuleId, finalizeResult)
            is CapsuleFinalizeResult.Success -> Unit
        }

        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        val published = try {
            capsuleDao.markPublishedForOwner(capsuleId.toRestString(), ownerText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        }
        if (published != 1) {
            capsule = capsuleDao.getByCapsuleIdAndOwner(capsuleId.toRestString(), ownerText)
                ?: return CapsuleUploadOutcome.Missing
            if (capsule.state != OutboxCapsuleState.PUBLISHED) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
            }
        }
        return finishPublished(owner, capsuleId)
    }

    /**
     * A06 recovery for a parked stale-recipient row. The old recipient
     * envelope is never used as a key source: the capsule key comes only
     * from the sender-owned retry record, and the immutable recipient ID is
     * the only directory lookup input.
     */
    private suspend fun recoverStaleRecipient(
        owner: UserId,
        capsuleId: CapsuleId,
        staleCapsule: OutboxCapsuleEntity,
    ): CapsuleUploadOutcome {
        val retryStore = retryMaterialStore ?: return CapsuleUploadOutcome.RecipientKeyStale
        val retryWrapper = senderRetryKeysetWrapper ?: return CapsuleUploadOutcome.RecipientKeyStale
        val signingLoader = loadSenderSigningKeyset ?: return CapsuleUploadOutcome.RecipientKeyStale
        val roots = accountScopedFileRoots ?: return CapsuleUploadOutcome.RecipientKeyStale
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch

        val prepared = try {
            prepare(staleCapsule, owner, capsuleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        val retryPath = staleCapsule.senderRetryKeysetPath ?: return CapsuleUploadOutcome.RecipientKeyStale
        val expectedRetryPath = try {
            retryStore.expectedPath(owner, capsuleId).canonicalPath
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        if (retryPath != expectedRetryPath) return CapsuleUploadOutcome.RecipientKeyStale

        val wrappedBytes = try {
            retryStore.read(owner, capsuleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        } ?: return CapsuleUploadOutcome.RecipientKeyStale

        val retryRecord = try {
            WrappedKeysetRecord.parse(wrappedBytes)
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        val capsuleKeyset = try {
            retryWrapper.unwrap(
                retryRecord,
                SenderRetryWrapContextInput(
                    ownerUserId = owner,
                    capsuleId = capsuleId,
                    senderKeyBundleId = prepared.senderKeyBundleId,
                    purpose = SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP,
                ),
            )
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch

        val token = accessToken() ?: return CapsuleUploadOutcome.RecipientKeyStale
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        val lookup = try {
            recipientUserLookup?.invoke(prepared.recipientUserId, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return CapsuleUploadOutcome.RecipientKeyStale
        val snapshot = (lookup as? RecipientUserLookupResult.Found)?.snapshot
            ?: return CapsuleUploadOutcome.RecipientKeyStale
        if (!isUsableActiveRecipient(prepared.recipientUserId, snapshot)) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }

        TinkPrimitives.ensureRegistered()
        val recipientPublicKeyset = try {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                Base64.urlSafeDecode(snapshot.encryptionPublicKeysetB64Url),
            )
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        val signingKeyset = try {
            signingLoader(owner, prepared.senderKeyBundleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return CapsuleUploadOutcome.RecipientKeyStale
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        if (!sameSigningPublicKey(staleCapsule, signingKeyset)) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }

        val root = try {
            roots.child(owner, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT).canonicalFile
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        val oldStatementPath = staleCapsule.publishStatementPath ?: return CapsuleUploadOutcome.RecipientKeyStale
        if (!isContained(root, File(oldStatementPath))) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        val oldStatementBytes = try {
            readCiphertext(oldStatementPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        val oldStatement = try {
            PublishStatement.parseFrom(oldStatementBytes)
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        val publishArtifacts = prepared.blobs.map { blob ->
            PublishArtifact(
                slot = ArtifactSlot(blob.blobId, blob.kind, blob.ordinal),
                ciphertextSize = blob.row.sizeBytes,
                ciphertextSha256 = ByteString.copyFrom(blob.row.sha256),
            )
        }
        val expectedOldStatement = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId = capsuleId,
                senderUserId = owner,
                recipientUserId = prepared.recipientUserId,
                senderKeyBundleId = prepared.senderKeyBundleId,
                recipientKeyBundleId = prepared.recipientKeyBundleId,
                createdAtEpochSeconds = oldStatement.createdAtEpochSeconds,
                artifacts = publishArtifacts,
            ),
        ) as? PublishStatementBuildResult.Success ?: return CapsuleUploadOutcome.RecipientKeyStale
        if (!expectedOldStatement.deterministicBytes.toByteArray().contentEquals(oldStatementBytes)) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch

        val rewrapped = try {
            CapsulePublisher(retryWrapper, retryRecord.alias).rewrapRecipient(
                RecipientRewrapRequest(
                    capsuleId = capsuleId,
                    senderUserId = owner,
                    recipientUserId = prepared.recipientUserId,
                    senderKeyBundleId = prepared.senderKeyBundleId,
                    recipientKeyBundleId = snapshot.keyBundleId,
                    createdAtEpochSeconds = oldStatement.createdAtEpochSeconds,
                    artifacts = publishArtifacts,
                    capsuleKeyset = capsuleKeyset,
                    signingKeyset = signingKeyset,
                    recipientEncryptionPublicKeyset = recipientPublicKeyset,
                ),
            )
        } catch (_: Exception) {
            return CapsuleUploadOutcome.RecipientKeyStale
        }
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch

        val files = writeRewrappedFiles(root, capsuleId, snapshot.keyBundleId, rewrapped)
            ?: return CapsuleUploadOutcome.RecipientKeyStale
        var committed = false
        try {
            if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
            val transitioned = try {
                capsuleDao.applyRecipientKeyRewrapForOwner(
                    capsuleId = capsuleId.toRestString(),
                    ownerUserId = owner.toRestString(),
                    recipientUserId = prepared.recipientUserId.toRestString(),
                    expectedRecipientKeyBundleId = prepared.recipientKeyBundleId.toRestString(),
                    newRecipientKeyBundleId = snapshot.keyBundleId.toRestString(),
                    newEnvelopePath = files.envelope.canonicalPath,
                    newPublishStatementPath = files.statement.canonicalPath,
                    newPublishStatementSignaturePath = files.signature.canonicalPath,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CapsuleUploadOutcome.RecipientKeyStale
            }
            if (transitioned == 1) {
                committed = true
                return run(owner, capsuleId)
            }

            val current = try {
                capsuleDao.getByCapsuleIdAndOwner(capsuleId.toRestString(), owner.toRestString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CapsuleUploadOutcome.RecipientKeyStale
            } ?: return CapsuleUploadOutcome.Missing
            val alreadyRewrapped = current.ownerUserId == owner.toRestString() &&
                current.recipientUserId == prepared.recipientUserId.toRestString() &&
                current.recipientKeyBundleId == snapshot.keyBundleId.toRestString() &&
                current.state != OutboxCapsuleState.PREPARING &&
                current.envelopePath?.let { isContained(root, File(it)) } == true &&
                current.publishStatementPath?.let { isContained(root, File(it)) } == true &&
                current.publishStatementSignaturePath?.let { isContained(root, File(it)) } == true
            if (!alreadyRewrapped) return CapsuleUploadOutcome.RecipientKeyStale
            committed = current.envelopePath == files.envelope.canonicalPath &&
                current.publishStatementPath == files.statement.canonicalPath &&
                current.publishStatementSignaturePath == files.signature.canonicalPath
            return run(owner, capsuleId)
        } finally {
            if (!committed) files.delete()
        }
    }

    private fun isUsableActiveRecipient(
        requestedUserId: UserId,
        snapshot: dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot,
    ): Boolean =
        snapshot.userId == requestedUserId &&
            snapshot.keyBundleStatus == SUPPORTED_ACTIVE_STATUS &&
            snapshot.suite == SUPPORTED_SUITE &&
            snapshot.protocolVersion == SUPPORTED_PROTOCOL_VERSION

    private fun sameSigningPublicKey(capsule: OutboxCapsuleEntity, signingKeyset: KeysetHandle): Boolean {
        val persisted = capsule.senderSigningPublicKeysetB64 ?: return false
        return try {
            val actual = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signingKeyset.publicKeysetHandle)
            actual.contentEquals(Base64.urlSafeDecode(persisted))
        } catch (_: Exception) {
            false
        }
    }

    private fun writeRewrappedFiles(
        root: File,
        capsuleId: CapsuleId,
        recipientKeyBundleId: KeyBundleId,
        material: dev.hryshyn.remanence.create.RewrappedRecipientMaterial,
    ): RewrappedFiles? {
        val suffix = "${capsuleId.toRestString()}-${recipientKeyBundleId.toRestString()}-${UUID.randomUUID()}"
        val created = ArrayList<File>(3)
        var complete = false
        return try {
            if (!root.exists() && !root.mkdirs()) return null
            if (!root.isDirectory) return null
            val envelope = writeRewrappedFile(root, "envelope-$suffix.bin", material.envelopeCiphertext, created)
                ?: return null
            val statement = writeRewrappedFile(root, "statement-$suffix.bin", material.publishStatementBytes, created)
                ?: return null
            val signature = writeRewrappedFile(root, "signature-$suffix.bin", material.publishStatementSignature, created)
                ?: return null
            complete = true
            RewrappedFiles(envelope, statement, signature)
        } catch (_: Exception) {
            null
        } finally {
            if (!complete) created.forEach(File::delete)
        }
    }

    private fun writeRewrappedFile(
        root: File,
        name: String,
        bytes: ByteArray,
        created: MutableList<File>,
    ): File? {
        if (bytes.isEmpty()) return null
        val target = File(root, name)
        if (!isContained(root, target) || target.exists()) return null
        val temporary = File(root, "$name.tmp-${UUID.randomUUID()}")
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(target) || !isContained(root, target)) return null
            val canonical = target.canonicalFile
            created += canonical
            canonical
        } finally {
            temporary.delete()
        }
    }

    private fun isContained(root: File, candidate: File): Boolean = try {
        val rootPath = root.canonicalFile.path
        val candidatePath = candidate.canonicalFile.path
        candidatePath.startsWith(
            if (rootPath.endsWith(File.separator)) rootPath else "$rootPath${File.separator}",
        )
    } catch (_: Exception) {
        false
    }

    private data class RewrappedFiles(
        val envelope: File,
        val statement: File,
        val signature: File,
    ) {
        fun delete() {
            envelope.delete()
            statement.delete()
            signature.delete()
        }
    }

    private suspend fun reconcileStoredBlobs(
        owner: UserId,
        capsuleId: CapsuleId,
        prepared: PreparedCapsule,
        draft: CapsuleDraft,
    ): CapsuleUploadOutcome? {
        val ownerText = owner.toRestString()
        val capsuleText = capsuleId.toRestString()
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        if (draft.capsuleId != capsuleId ||
            draft.blobs.map { it.blobId } != prepared.blobs.map { it.blobId }
        ) {
            return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
        }

        val preparedById = prepared.blobs.associateBy { it.blobId }
        for (serverBlob in draft.blobs.filter { it.state == CapsuleDraftBlobState.STORED }) {
            if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
            val preparedBlob = preparedById[serverBlob.blobId]
                ?: return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
            if (preparedBlob.row.blobId != serverBlob.blobId.toRestString() ||
                preparedBlob.row.capsuleId != capsuleText ||
                preparedBlob.row.ownerUserId != ownerText
            ) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
            }

            val existing = try {
                blobDao.getAllByCapsuleIdAndOwner(capsuleText, ownerText)
                    .firstOrNull { it.blobId == serverBlob.blobId.toRestString() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
            }
            if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
            if (existing == null ||
                existing.blobId != serverBlob.blobId.toRestString() ||
                existing.capsuleId != capsuleText ||
                existing.ownerUserId != ownerText
            ) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
            }

            val marked = try {
                blobDao.markStoredForOwner(serverBlob.blobId.toRestString(), ownerText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
            }
            if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
            if (marked == 1) continue
            if (marked != 0) return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")

            val reread = try {
                blobDao.getAllByCapsuleIdAndOwner(capsuleText, ownerText)
                    .firstOrNull { it.blobId == serverBlob.blobId.toRestString() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
            }
            if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
            if (reread == null ||
                reread.blobId != serverBlob.blobId.toRestString() ||
                reread.capsuleId != capsuleText ||
                reread.ownerUserId != ownerText ||
                reread.uploadState != OutboxBlobUploadState.STORED
            ) {
                return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
            }
        }
        return null
    }

    private suspend fun uploadOne(
        owner: UserId,
        capsuleId: CapsuleId,
        blob: PreparedBlob,
    ): CapsuleUploadOutcome? {
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        val ownerText = owner.toRestString()
        val counted = try {
            blobDao.incrementAttemptCountForOwner(blob.row.blobId, ownerText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        }
        if (counted != 1) return markRetryable(owner, capsuleId, "INTERNAL_ERROR")

        val bytes = try {
            readCiphertext(blob.row.localCiphertextPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return markTerminal(owner, capsuleId, "INTERNAL_ERROR")
        }
        if (bytes.size.toLong() != blob.row.sizeBytes) {
            return markTerminal(owner, capsuleId, "BLOB_SIZE_INVALID")
        }
        if (!MessageDigest.isEqual(sha256(bytes), blob.row.sha256)) {
            return markTerminal(owner, capsuleId, "BLOB_HASH_MISMATCH")
        }

        val request = try {
            CapsuleBlobUploadRequest(
                capsuleId = capsuleId,
                blobId = blob.blobId,
                ciphertext = bytes,
                ciphertextSha256 = blob.row.sha256,
                idempotencyKey = blobIdempotencyKey(capsuleId, blob.blobId),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return markTerminal(owner, capsuleId, "BLOB_HASH_MISMATCH")
        }

        val token = accessToken() ?: return markRetryable(owner, capsuleId, "AUTH_INVALID")
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        val result = try {
            uploadBlob(request, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return markRetryable(owner, capsuleId, "INTERNAL_UNAVAILABLE")
        }
        when (result) {
            is CapsuleBlobUploadResult.Failure -> return applyBlobFailure(owner, capsuleId, result)
            is CapsuleBlobUploadResult.Success -> Unit
        }

        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        val stored = try {
            blobDao.markStoredForOwner(blob.row.blobId, ownerText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        }
        if (stored == 0) {
            val current = blobDao.getAllByCapsuleIdAndOwner(capsuleId.toRestString(), ownerText)
                .firstOrNull { it.blobId == blob.row.blobId }
            if (current?.uploadState != dev.hryshyn.remanence.core.data.db.OutboxBlobUploadState.STORED) {
                return markRetryable(owner, capsuleId, "INTERNAL_ERROR")
            }
        }
        return null
    }

    private suspend fun prepare(
        capsule: OutboxCapsuleEntity,
        owner: UserId,
        capsuleId: CapsuleId,
    ): PreparedCapsule {
        require(capsule.capsuleId == capsuleId.toRestString())
        require(capsule.ownerUserId == owner.toRestString())
        require(capsule.senderUserId == owner.toRestString())
        val recipientUserId = UserId.parseRest(capsule.recipientUserId)
        val senderKeyBundleId = capsule.senderKeyBundleId?.let(KeyBundleId::parseRest)
            ?: throw IllegalArgumentException("sender key bundle missing")
        val recipientKeyBundleId = KeyBundleId.parseRest(capsule.recipientKeyBundleId)
        val idempotencyKey = UUID.fromString(capsule.idempotencyKey)
        require(idempotencyKey.toString() == capsule.idempotencyKey)

        val rows = blobDao.getAllByCapsuleIdAndOwner(capsuleId.toRestString(), owner.toRestString())
        require(rows.size in 5..7)
        val parsed = rows.map { row ->
            val blobId = BlobId.parseRest(row.blobId)
            val kind = CapsuleArtifactKind.valueOf(row.kind)
            val ordinal = row.ordinal ?: throw IllegalArgumentException("blob ordinal missing")
            require(row.capsuleId == capsuleId.toRestString())
            require(row.ownerUserId == owner.toRestString())
            require(row.sizeBytes > 0L)
            require(row.sha256.size == SHA256_BYTES)
            require(row.localCiphertextPath.isNotBlank())
            PreparedBlob(row, blobId, kind, ordinal)
        }
        val slots = parsed.map { ArtifactSlot(it.blobId, it.kind, it.ordinal) }
        require(ArtifactLayoutValidator.validate(slots) is ArtifactLayoutValidation.Valid)
        val ordered = parsed.sortedWith { left, right ->
            CanonicalArtifactOrder.compare(
                ArtifactSlot(left.blobId, left.kind, left.ordinal),
                ArtifactSlot(right.blobId, right.kind, right.ordinal),
            )
        }
        return PreparedCapsule(
            capsule = capsule,
            recipientUserId = recipientUserId,
            senderKeyBundleId = senderKeyBundleId,
            recipientKeyBundleId = recipientKeyBundleId,
            idempotencyKey = idempotencyKey,
            blobs = ordered,
            draftRequest = CapsuleDraftRequest(
                capsuleId = capsuleId,
                senderKeyBundleId = senderKeyBundleId,
                recipientTarget = RecipientTarget.ExistingUser(recipientUserId, recipientKeyBundleId),
                idempotencyKey = idempotencyKey,
                blobs = ordered.map { blob ->
                    CapsuleDraftBlobDeclaration(
                        blobId = blob.blobId,
                        kind = blob.kind,
                        ordinal = if (blob.kind == CapsuleArtifactKind.PHOTO) blob.ordinal else null,
                        ciphertextSize = blob.row.sizeBytes,
                        ciphertextSha256 = blob.row.sha256,
                    )
                },
            ),
        )
    }

    private suspend fun readFinalizeRequest(
        capsule: OutboxCapsuleEntity,
        prepared: PreparedCapsule,
    ): CapsuleFinalizeRequest {
        val statementPath = capsule.publishStatementPath ?: throw IllegalArgumentException("statement missing")
        val signaturePath = capsule.publishStatementSignaturePath ?: throw IllegalArgumentException("signature missing")
        val envelopePath = capsule.envelopePath ?: throw IllegalArgumentException("envelope missing")
        val statement = readCiphertext(statementPath)
        val signature = readCiphertext(signaturePath)
        val envelope = readCiphertext(envelopePath)
        return CapsuleFinalizeRequest(
            capsuleId = CapsuleId.parseRest(capsule.capsuleId),
            statement = statement,
            signature = signature,
            senderKeyBundleId = prepared.senderKeyBundleId,
            recipientKeyBundleId = prepared.recipientKeyBundleId,
            recipientEnvelopeCiphertext = envelope,
            recipientEnvelopeCiphertextSha256 = sha256(envelope),
        )
    }

    private suspend fun applyDraftFailure(
        owner: UserId,
        capsuleId: CapsuleId,
        failure: CapsuleDraftResult.Failure,
    ): CapsuleUploadOutcome {
        if (failure.reason == CapsuleDraftFailure.RECIPIENT_KEY_STALE) {
            return markRecipientKeyStale(owner, capsuleId)
        }
        if (failure.reason == CapsuleDraftFailure.AUTH_INVALID || failure.retryable) {
            return markRetryable(owner, capsuleId, draftRetryableCode(failure.reason))
        }
        val code = when (failure.reason) {
            CapsuleDraftFailure.VALIDATION_FAILED,
            CapsuleDraftFailure.IDEMPOTENCY_CONFLICT,
            CapsuleDraftFailure.RECIPIENT_NOT_CONFIRMED,
            CapsuleDraftFailure.KEY_BUNDLE_NOT_FOUND,
            CapsuleDraftFailure.KEY_BUNDLE_INVALID,
            CapsuleDraftFailure.CAPSULE_STATE_INVALID,
            CapsuleDraftFailure.DRAFT_EXPIRED,
            -> failure.reason.name
            CapsuleDraftFailure.NETWORK,
            CapsuleDraftFailure.RATE_LIMITED,
            CapsuleDraftFailure.HTTP,
            CapsuleDraftFailure.INVALID_RESPONSE,
            CapsuleDraftFailure.INTERNAL_UNAVAILABLE,
            CapsuleDraftFailure.INTERNAL_ERROR,
            CapsuleDraftFailure.AUTH_INVALID,
            CapsuleDraftFailure.RECIPIENT_KEY_STALE,
            -> "INTERNAL_ERROR"
        }
        return markTerminal(owner, capsuleId, code)
    }

    private suspend fun applyBlobFailure(
        owner: UserId,
        capsuleId: CapsuleId,
        failure: CapsuleBlobUploadResult.Failure,
    ): CapsuleUploadOutcome {
        if (failure.reason == CapsuleBlobUploadFailure.AUTH_INVALID || failure.retryable) {
            return markRetryable(owner, capsuleId, blobRetryableCode(failure.reason))
        }
        return when (failure.reason) {
            CapsuleBlobUploadFailure.VALIDATION_FAILED,
            CapsuleBlobUploadFailure.CAPSULE_NOT_FOUND,
            CapsuleBlobUploadFailure.CAPSULE_STATE_INVALID,
            CapsuleBlobUploadFailure.DRAFT_EXPIRED,
            CapsuleBlobUploadFailure.BLOB_NOT_DECLARED,
            CapsuleBlobUploadFailure.BLOB_SIZE_INVALID,
            CapsuleBlobUploadFailure.BLOB_HASH_MISMATCH,
            CapsuleBlobUploadFailure.BLOB_CONFLICT,
            -> markTerminal(owner, capsuleId, failure.reason.name)
            CapsuleBlobUploadFailure.NETWORK,
            CapsuleBlobUploadFailure.RATE_LIMITED,
            CapsuleBlobUploadFailure.HTTP,
            CapsuleBlobUploadFailure.INVALID_RESPONSE,
            CapsuleBlobUploadFailure.AUTH_INVALID,
            CapsuleBlobUploadFailure.INTERNAL_UNAVAILABLE,
            CapsuleBlobUploadFailure.INTERNAL_ERROR,
            -> markTerminal(owner, capsuleId, "INTERNAL_ERROR")
        }
    }

    private suspend fun applyFinalizeFailure(
        owner: UserId,
        capsuleId: CapsuleId,
        failure: CapsuleFinalizeResult.Failure,
    ): CapsuleUploadOutcome {
        if (failure.reason == CapsuleFinalizeFailure.RECIPIENT_KEY_STALE) {
            return markRecipientKeyStale(owner, capsuleId)
        }
        if (failure.reason == CapsuleFinalizeFailure.AUTH_INVALID || failure.retryable) {
            return markRetryable(owner, capsuleId, finalizeRetryableCode(failure.reason))
        }
        return when (failure.reason) {
            CapsuleFinalizeFailure.VALIDATION_FAILED,
            CapsuleFinalizeFailure.CAPSULE_NOT_FOUND,
            CapsuleFinalizeFailure.CAPSULE_STATE_INVALID,
            CapsuleFinalizeFailure.DRAFT_EXPIRED,
            CapsuleFinalizeFailure.KEY_BUNDLE_NOT_FOUND,
            CapsuleFinalizeFailure.KEY_BUNDLE_INVALID,
            CapsuleFinalizeFailure.KEY_BUNDLE_REVOKED,
            CapsuleFinalizeFailure.STATEMENT_INVALID,
            CapsuleFinalizeFailure.SIGNATURE_INVALID,
            CapsuleFinalizeFailure.ENVELOPE_INVALID,
            CapsuleFinalizeFailure.FINALIZE_CONFLICT,
            -> markTerminal(owner, capsuleId, failure.reason.name)
            CapsuleFinalizeFailure.NETWORK,
            CapsuleFinalizeFailure.RATE_LIMITED,
            CapsuleFinalizeFailure.HTTP,
            CapsuleFinalizeFailure.INVALID_RESPONSE,
            CapsuleFinalizeFailure.AUTH_INVALID,
            CapsuleFinalizeFailure.RECIPIENT_KEY_STALE,
            CapsuleFinalizeFailure.INTERNAL_UNAVAILABLE,
            CapsuleFinalizeFailure.INTERNAL_ERROR,
            -> markTerminal(owner, capsuleId, "INTERNAL_ERROR")
        }
    }

    private fun draftRetryableCode(reason: CapsuleDraftFailure): String = when (reason) {
        CapsuleDraftFailure.HTTP,
        CapsuleDraftFailure.INVALID_RESPONSE,
        -> "INTERNAL_UNAVAILABLE"
        else -> reason.name
    }

    private fun blobRetryableCode(reason: CapsuleBlobUploadFailure): String = when (reason) {
        CapsuleBlobUploadFailure.HTTP,
        CapsuleBlobUploadFailure.INVALID_RESPONSE,
        -> "INTERNAL_UNAVAILABLE"
        else -> reason.name
    }

    private fun finalizeRetryableCode(reason: CapsuleFinalizeFailure): String = when (reason) {
        CapsuleFinalizeFailure.HTTP,
        CapsuleFinalizeFailure.INVALID_RESPONSE,
        -> "INTERNAL_UNAVAILABLE"
        else -> reason.name
    }

    private suspend fun markRetryable(
        owner: UserId,
        capsuleId: CapsuleId,
        code: String,
    ): CapsuleUploadOutcome {
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        return try {
            if (capsuleDao.markRetryableFailureForOwner(capsuleId.toRestString(), owner.toRestString(), code) == 1) {
                CapsuleUploadOutcome.Retryable(code)
            } else {
                CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        }
    }

    private suspend fun markRecipientKeyStale(
        owner: UserId,
        capsuleId: CapsuleId,
    ): CapsuleUploadOutcome {
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        return try {
            if (capsuleDao.markRetryableFailureForOwner(
                    capsuleId.toRestString(),
                    owner.toRestString(),
                    RECIPIENT_KEY_STALE,
                ) == 1
            ) {
                CapsuleUploadOutcome.RecipientKeyStale
            } else {
                CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        }
    }

    private suspend fun markTerminal(
        owner: UserId,
        capsuleId: CapsuleId,
        code: String,
    ): CapsuleUploadOutcome {
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        return try {
            val transitioned = capsuleDao.markTerminalFailureForOwner(
                capsuleId.toRestString(),
                owner.toRestString(),
                code,
            )
            if (transitioned == 1) {
                finalizeTerminalFailure(owner, capsuleId, code)
            } else {
                val current = capsuleDao.getByCapsuleIdAndOwner(
                    capsuleId.toRestString(),
                    owner.toRestString(),
                )
                if (current?.state == OutboxCapsuleState.TERMINAL_FAILURE) {
                    finalizeTerminalFailure(
                        owner,
                        capsuleId,
                        current.lastErrorCode ?: "INTERNAL_ERROR",
                    )
                } else {
                    CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        }
    }

    /**
     * Completes terminal handling only after an owner-scoped read confirms
     * that the durable row is terminal. This is shared by a newly accepted
     * terminal CAS and by a replay that starts with TERMINAL_FAILURE, so a
     * cleanup failure remains retryable without changing the original error.
     */
    private suspend fun finalizeTerminalFailure(
        owner: UserId,
        capsuleId: CapsuleId,
        requestedErrorCode: String,
    ): CapsuleUploadOutcome {
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch

        val persisted = try {
            capsuleDao.getByCapsuleIdAndOwner(capsuleId.toRestString(), owner.toRestString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        } ?: return CapsuleUploadOutcome.Missing

        if (persisted.state != OutboxCapsuleState.TERMINAL_FAILURE) {
            return CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
        }
        val originalErrorCode = persisted.lastErrorCode ?: requestedErrorCode
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch

        val cleanup = try {
            cleanupRetryMaterial(owner, capsuleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.Retryable(TERMINAL_CLEANUP_RETRY)
        }
        return if (cleanup == SenderRetryMaterialLifecycle.Result.OK) {
            CapsuleUploadOutcome.TerminalFailure(originalErrorCode)
        } else {
            CapsuleUploadOutcome.Retryable(TERMINAL_CLEANUP_RETRY)
        }
    }

    private suspend fun finishPublished(owner: UserId, capsuleId: CapsuleId): CapsuleUploadOutcome {
        if (!accountMatches(owner)) return CapsuleUploadOutcome.AccountMismatch
        val cleanup = try {
            cleanupRetryMaterial(owner, capsuleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CapsuleUploadOutcome.Retryable("INTERNAL_UNAVAILABLE")
        }
        return if (cleanup == SenderRetryMaterialLifecycle.Result.OK) {
            CapsuleUploadOutcome.Succeeded
        } else {
            CapsuleUploadOutcome.Retryable("INTERNAL_ERROR")
        }
    }

    private suspend fun accountMatches(owner: UserId): Boolean = try {
        currentAccountUserId() == owner.toRestString()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun blobIdempotencyKey(capsuleId: CapsuleId, blobId: BlobId): UUID =
        UUID.nameUUIDFromBytes(
            "postmark/blob-upload/v1/${capsuleId.toRestString()}/${blobId.toRestString()}".toByteArray(
                StandardCharsets.UTF_8,
            ),
        )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class PreparedCapsule(
        val capsule: OutboxCapsuleEntity,
        val recipientUserId: UserId,
        val senderKeyBundleId: KeyBundleId,
        val recipientKeyBundleId: KeyBundleId,
        val idempotencyKey: UUID,
        val blobs: List<PreparedBlob>,
        val draftRequest: CapsuleDraftRequest,
    )

    private data class PreparedBlob(
        val row: OutboxBlobEntity,
        val blobId: BlobId,
        val kind: CapsuleArtifactKind,
        val ordinal: Int,
    )

    private companion object {
        const val TERMINAL_CLEANUP_RETRY = "RETRY_MATERIAL_CLEANUP"
        const val RECIPIENT_KEY_STALE = "RECIPIENT_KEY_STALE"
        const val SUPPORTED_ACTIVE_STATUS = "ACTIVE"
        const val SUPPORTED_SUITE = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
        const val SUPPORTED_PROTOCOL_VERSION = 1
        const val SHA256_BYTES = 32
    }
}
