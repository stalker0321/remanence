package dev.hryshyn.remanence.core.data.db

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import dev.hryshyn.remanence.core.data.network.IncomingCapsule
import dev.hryshyn.remanence.core.data.network.IncomingCapsulePage
import dev.hryshyn.remanence.core.data.network.IncomingCapsuleRepository
import dev.hryshyn.remanence.core.data.network.IncomingCapsuleResult
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.UserId

/** The minimum live credential snapshot needed by one incoming page request. */
class IncomingSyncSession(
    val ownerUserId: UserId,
    val accessToken: String,
) {
    init {
        require(accessToken.isNotEmpty()) { "incoming sync access token is required" }
    }

    /** Access tokens rotate during an authenticated request; the owner ID does not. */
    internal fun isSameSession(other: IncomingSyncSession): Boolean =
        ownerUserId == other.ownerUserId

    override fun toString(): String = "IncomingSyncSession(<redacted>)"
}

enum class IncomingSyncFailure {
    NO_ACTIVE_SESSION,
    ACCOUNT_CHANGED,
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    INTERNAL_ERROR,
    DATABASE_FAILURE,
}

sealed interface IncomingSyncResult {
    data class Committed(
        val page: IncomingCapsulePage,
    ) : IncomingSyncResult

    data class Failure(
        val reason: IncomingSyncFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : IncomingSyncResult
}

/**
 * Fetches and commits one incoming page for one live authenticated account.
 * A10 may schedule this boundary, but this class owns no WorkManager policy.
 */
class IncomingCapsuleSyncRepository(
    private val remote: IncomingCapsuleRepository,
    private val database: RemanenceLocalDatabase,
    private val roots: AccountScopedFileRoots,
    private val currentSession: suspend () -> IncomingSyncSession?,
    private val clockEpochMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun syncNextPage(limit: Int = DEFAULT_LIMIT): IncomingSyncResult {
        if (limit !in 1..MAX_PAGE_SIZE) {
            return IncomingSyncResult.Failure(
                reason = IncomingSyncFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }

        val initialSession = liveSession() ?: return noActiveSession()
        val owner = initialSession.ownerUserId.toRestString()
        val expectedCursor = try {
            database.syncCursorDao()
                .get(owner, INCOMING_STREAM)
                ?.serverCursor
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return databaseFailure()
        }

        // The account/session is checked again after the owner-scoped cursor
        // read, immediately before the authenticated HTTP request.
        val requestSession = liveSession()
        if (requestSession == null || !initialSession.isSameSession(requestSession)) {
            return accountChanged()
        }

        val remoteResult = remote.fetchPage(
            ownerUserId = requestSession.ownerUserId,
            cursor = expectedCursor,
            limit = limit,
            accessToken = requestSession.accessToken,
        )
        if (remoteResult !is IncomingCapsuleResult.Success) {
            return remoteFailure(remoteResult)
        }

        val committedAt = try {
            clockEpochMs()
        } catch (_: Exception) {
            return databaseFailure()
        }
        if (committedAt < 0L) return databaseFailure()
        val page = remoteResult.page
        val ownerId = requestSession.ownerUserId.toRestString()
        val mapped = try {
            mapForCommit(page, requestSession.ownerUserId, ownerId, committedAt)
        } catch (_: IncomingStoragePathFailure) {
            return databaseFailure()
        } catch (_: IllegalArgumentException) {
            // A concrete remote repository rejects all malformed response
            // material before Success can be returned. Keep this defensive
            // boundary transport-shaped for alternate repository seams.
            return invalidResponse()
        }

        // This is intentionally the final suspend/session boundary before
        // the DAO transaction. Mapping and path derivation above do not yield,
        // so a logout or account switch cannot commit under the old owner.
        val commitSession = liveSession()
        if (commitSession == null || !requestSession.isSameSession(commitSession)) {
            return accountChanged()
        }

        try {
            database.incomingPageDao().commitPage(
                ownerUserId = ownerId,
                expectedCursor = expectedCursor,
                capsules = mapped.capsules,
                envelopes = mapped.envelopes,
                blobs = mapped.blobs,
                nextCursor = page.nextCursor,
                committedAtEpochMs = committedAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The DAO transaction rolls back every page row and the cursor.
            return databaseFailure()
        }
        return IncomingSyncResult.Committed(page)
    }

    private suspend fun liveSession(): IncomingSyncSession? = try {
        currentSession()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun mapForCommit(
        page: IncomingCapsulePage,
        owner: UserId,
        ownerString: String,
        committedAt: Long,
    ): MappedPage {
        require(committedAt >= 0L)
        val capsules = page.items.map { capsule ->
            IncomingCapsuleEntity(
                capsuleId = capsule.capsuleId.toRestString(),
                ownerUserId = ownerString,
                senderUserId = capsule.senderUserId.toRestString(),
                recipientUserId = capsule.recipientUserId.toRestString(),
                senderSigningKeyBundleId = capsule.senderKeyBundleId.toRestString(),
                recipientEncryptionKeyBundleId = capsule.recipientKeyBundleId.toRestString(),
                protocolVersion = capsule.protocolVersion,
                serverStatus = READY_STATUS,
                readyAtEpochMs = capsule.readyAtEpochMs,
                signedStatementBytes = capsule.signedStatementBytes.copyOf(),
                signedStatementSha256 = capsule.signedStatementSha256.copyOf(),
                publishSignatureBytes = capsule.publishSignatureBytes.copyOf(),
                materialState = dev.hryshyn.remanence.core.model.LocalMaterialState.DISCOVERED,
            )
        }
        val envelopes = page.items.map { capsule ->
            IncomingEnvelopeEntity(
                capsuleId = capsule.capsuleId.toRestString(),
                ownerUserId = ownerString,
                recipientKeyBundleId = capsule.envelope.recipientKeyBundleId.toRestString(),
                hpkeCiphertext = capsule.envelope.ciphertext.copyOf(),
                transportSha256 = capsule.envelope.ciphertextSha256.copyOf(),
                receivedAtEpochMs = committedAt,
            )
        }
        val blobs = page.items.flatMap { capsule ->
            capsule.blobs.map { blob ->
                BlobCacheEntity(
                    blobId = blob.blobId.toRestString(),
                    ownerUserId = ownerString,
                    capsuleId = capsule.capsuleId.toRestString(),
                    kind = blob.kind.name,
                    ordinal = blob.ordinal,
                    expectedSizeBytes = blob.ciphertextSize,
                    expectedSha256 = blob.ciphertextSha256.copyOf(),
                    localPath = deterministicBlobPath(owner, capsule, blob.blobId.toRestString()),
                    cacheState = BlobCacheState.DOWNLOADING,
                )
            }
        }
        return MappedPage(capsules, envelopes, blobs)
    }

    private fun deterministicBlobPath(
        owner: UserId,
        capsule: IncomingCapsule,
        blobId: String,
    ): String {
        try {
            val root = roots.child(owner, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)
            val path = File(
                root,
                "capsules/${capsule.capsuleId.toRestString()}/blobs/$blobId.ciphertext",
            ).canonicalFile
            val rootPath = root.canonicalFile.path
            val prefix = if (rootPath.endsWith(File.separator)) rootPath else "$rootPath${File.separator}"
            require(path.path.startsWith(prefix)) { "incoming blob path escapes account root" }
            return path.path
        } catch (_: Exception) {
            throw IncomingStoragePathFailure()
        }
    }

    private fun remoteFailure(result: IncomingCapsuleResult): IncomingSyncResult.Failure {
        val failure = result as IncomingCapsuleResult.Failure
        return IncomingSyncResult.Failure(
            reason = when (failure.reason) {
                dev.hryshyn.remanence.core.data.network.IncomingCapsuleFailure.NETWORK -> IncomingSyncFailure.NETWORK
                dev.hryshyn.remanence.core.data.network.IncomingCapsuleFailure.RATE_LIMITED -> IncomingSyncFailure.RATE_LIMITED
                dev.hryshyn.remanence.core.data.network.IncomingCapsuleFailure.HTTP -> IncomingSyncFailure.HTTP
                dev.hryshyn.remanence.core.data.network.IncomingCapsuleFailure.INVALID_RESPONSE -> IncomingSyncFailure.INVALID_RESPONSE
                dev.hryshyn.remanence.core.data.network.IncomingCapsuleFailure.AUTH_INVALID -> IncomingSyncFailure.AUTH_INVALID
                dev.hryshyn.remanence.core.data.network.IncomingCapsuleFailure.VALIDATION_FAILED -> IncomingSyncFailure.VALIDATION_FAILED
                dev.hryshyn.remanence.core.data.network.IncomingCapsuleFailure.INTERNAL_ERROR -> IncomingSyncFailure.INTERNAL_ERROR
            },
            httpStatus = failure.httpStatus,
            retryable = failure.retryable,
        )
    }

    private fun noActiveSession() = IncomingSyncResult.Failure(
        reason = IncomingSyncFailure.NO_ACTIVE_SESSION,
        retryable = false,
    )

    private fun accountChanged() = IncomingSyncResult.Failure(
        reason = IncomingSyncFailure.ACCOUNT_CHANGED,
        retryable = false,
    )

    private fun databaseFailure() = IncomingSyncResult.Failure(
        reason = IncomingSyncFailure.DATABASE_FAILURE,
        retryable = true,
    )

    private fun invalidResponse() = IncomingSyncResult.Failure(
        reason = IncomingSyncFailure.INVALID_RESPONSE,
        retryable = false,
    )

    private data class MappedPage(
        val capsules: List<IncomingCapsuleEntity>,
        val envelopes: List<IncomingEnvelopeEntity>,
        val blobs: List<BlobCacheEntity>,
    )

    private class IncomingStoragePathFailure : RuntimeException()

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_PAGE_SIZE = 100
        const val INCOMING_STREAM = "incoming"
        const val READY_STATUS = "READY"
    }
}
