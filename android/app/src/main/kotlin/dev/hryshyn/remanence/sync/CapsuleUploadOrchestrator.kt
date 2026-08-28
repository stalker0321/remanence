package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.data.db.OutboxBlobDao
import dev.hryshyn.remanence.core.data.db.OutboxBlobEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadFailure
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadRequest
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadResult
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlobDeclaration
import dev.hryshyn.remanence.core.data.network.CapsuleDraftFailure
import dev.hryshyn.remanence.core.data.network.CapsuleDraftRequest
import dev.hryshyn.remanence.core.data.network.CapsuleDraftResult
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeFailure
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeRequest
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeResult
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialLifecycle
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidation
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidator
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CanonicalArtifactOrder
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientTarget
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
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
 * network call and durable transition. A04 deliberately replays every
 * declared blob on a retry; server/local STORED reconciliation and skipping
 * belong to A05. Ciphertext is read from the staged paths and is never
 * regenerated. A05 startup discovery must exclude RETRYABLE_FAILURE rows
 * marked RECIPIENT_KEY_STALE until A06 owns their recovery.
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
                    return CapsuleUploadOutcome.RecipientKeyStale
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
            when (draftResult) {
                is CapsuleDraftResult.Success -> Unit
                is CapsuleDraftResult.Failure ->
                    return applyDraftFailure(owner, capsuleId, draftResult)
            }

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
        const val SHA256_BYTES = 32
    }
}
