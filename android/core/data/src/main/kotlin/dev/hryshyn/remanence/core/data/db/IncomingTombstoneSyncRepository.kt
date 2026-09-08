package dev.hryshyn.remanence.core.data.db

import dev.hryshyn.remanence.core.data.network.IncomingTombstoneFailure
import dev.hryshyn.remanence.core.data.network.IncomingTombstoneFeed
import dev.hryshyn.remanence.core.data.network.IncomingTombstoneResult
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.TrustedPathSafety
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface IncomingTombstoneSyncResult {
    data class Committed(
        val page: dev.hryshyn.remanence.core.data.network.IncomingTombstonePage,
        val capabilityUnsupported: Boolean = false,
    ) : IncomingTombstoneSyncResult {
        val hasMore: Boolean get() = page.hasMore
    }

    data class Failure(
        val reason: IncomingSyncFailure,
        val retryable: Boolean,
    ) : IncomingTombstoneSyncResult
}

/**
 * Fetches and applies one authenticated tombstone page. The cursor is read
 * from and written to the same Room transaction as the durable marker and
 * database purge. Files are exact owner/capsule/blob paths, deleted before
 * that transaction, and a replay treats already-missing files as success.
 */
class IncomingTombstoneSyncRepository(
    private val remote: IncomingTombstoneFeed,
    private val database: RemanenceLocalDatabase,
    private val roots: AccountScopedFileRoots,
    private val currentSession: suspend () -> IncomingSyncSession?,
    private val revocationBoundary: RecipientTombstonePresentationBoundary =
        RecipientTombstonePresentationBoundary(),
    private val clockEpochMs: () -> Long = System::currentTimeMillis,
    private val filePurger: suspend (UserId, List<TombstoneBlobRow>) -> Unit =
        IncomingTombstoneFilePurger(roots)::purge,
) {

    suspend fun syncNextPage(
        limit: Int = DEFAULT_LIMIT,
        expectedOwner: UserId? = null,
    ): IncomingTombstoneSyncResult {
        if (limit !in 1..MAX_PAGE_SIZE) {
            return IncomingTombstoneSyncResult.Failure(
                IncomingSyncFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }
        val initialSession = liveSession() ?: return failure(IncomingSyncFailure.NO_ACTIVE_SESSION, false)
        if (expectedOwner != null && initialSession.ownerUserId != expectedOwner) {
            return failure(IncomingSyncFailure.ACCOUNT_CHANGED, false)
        }
        val owner = initialSession.ownerUserId
        val ownerString = owner.toRestString()
        val expectedCursor = try {
            database.recipientTombstoneDao().getWatermarkForOwner(ownerString)?.serverCursor
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(IncomingSyncFailure.DATABASE_FAILURE, true)
        }
        val requestSession = liveSession()
        if (requestSession == null || !initialSession.isSameSession(requestSession)) {
            return failure(IncomingSyncFailure.ACCOUNT_CHANGED, false)
        }

        val remoteResult = remote.fetchPage(
            ownerUserId = requestSession.ownerUserId,
            cursor = expectedCursor,
            limit = limit,
            accessToken = requestSession.accessToken,
        )
        if (remoteResult !is IncomingTombstoneResult.Success) {
            val remoteFailure = remoteResult as IncomingTombstoneResult.Failure
            return failure(mapFailure(remoteFailure.reason), remoteFailure.retryable)
        }

        val committedAt = try {
            clockEpochMs()
        } catch (_: Exception) {
            return failure(IncomingSyncFailure.DATABASE_FAILURE, true)
        }
        if (committedAt < 0L) return failure(IncomingSyncFailure.DATABASE_FAILURE, true)
        val page = remoteResult.page
        val tombstones = try {
            page.items.map {
                RecipientTombstoneEntity(
                    ownerUserId = ownerString,
                    capsuleId = it.capsuleId.toRestString(),
                    revokedAtEpochMs = it.revokedAtEpochMs,
                )
            }
        } catch (_: IllegalArgumentException) {
            return failure(IncomingSyncFailure.INVALID_RESPONSE, false)
        }
        val blobRows = try {
            if (tombstones.isEmpty()) emptyList() else {
                database.recipientTombstoneDao().getBlobRowsForOwner(
                    ownerString,
                    tombstones.map { it.capsuleId },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(IncomingSyncFailure.DATABASE_FAILURE, true)
        }

        // A crash here leaves the DB marker and cursor unchanged. A retry
        // deletes the same exact paths (or observes them already absent) and
        // then commits the durable marker/purge/watermark atomically. The
        // owner boundary also orders this transaction against an offline
        // presentation's final state check and continuation handoff.
        try {
            revocationBoundary.withCapsules(owner, page.items.map { it.capsuleId }) {
                filePurger(owner, blobRows)
                coroutineContext.ensureActive()

                val commitSession = liveSession()
                if (commitSession == null || !requestSession.isSameSession(commitSession)) {
                    throw AccountChangedDuringCommit()
                }
                database.recipientTombstoneDao().applyPage(
                    ownerUserId = ownerString,
                    expectedCursor = expectedCursor,
                    tombstones = tombstones,
                    nextCursor = page.nextCursor,
                    committedAtEpochMs = committedAt,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AccountChangedDuringCommit) {
            return failure(IncomingSyncFailure.ACCOUNT_CHANGED, false)
        } catch (_: Exception) {
            return failure(IncomingSyncFailure.DATABASE_FAILURE, true)
        }
        return IncomingTombstoneSyncResult.Committed(
            page = page,
            capabilityUnsupported = remoteResult.capabilityUnsupported,
        )
    }

    private suspend fun liveSession(): IncomingSyncSession? = try {
        currentSession()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun mapFailure(failure: IncomingTombstoneFailure): IncomingSyncFailure = when (failure) {
        IncomingTombstoneFailure.NETWORK -> IncomingSyncFailure.NETWORK
        IncomingTombstoneFailure.RATE_LIMITED -> IncomingSyncFailure.RATE_LIMITED
        IncomingTombstoneFailure.HTTP -> IncomingSyncFailure.HTTP
        IncomingTombstoneFailure.INVALID_RESPONSE -> IncomingSyncFailure.INVALID_RESPONSE
        IncomingTombstoneFailure.AUTH_INVALID -> IncomingSyncFailure.AUTH_INVALID
        IncomingTombstoneFailure.VALIDATION_FAILED -> IncomingSyncFailure.VALIDATION_FAILED
        IncomingTombstoneFailure.INTERNAL_ERROR -> IncomingSyncFailure.INTERNAL_ERROR
    }

    private fun failure(reason: IncomingSyncFailure, retryable: Boolean) =
        IncomingTombstoneSyncResult.Failure(reason, retryable)

    private class AccountChangedDuringCommit : Exception()

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_PAGE_SIZE = 100
    }
}

/**
 * Deletes only incoming encrypted blob files for the requested account. The
 * sender index bundle and recognition fingerprint rows are deliberately not
 * touched: they are already-decrypted/derived recipient material and an
 * exported copy is outside the app's deletion authority. Envelope and blob
 * database rows are removed by [RecipientTombstoneDao] in its transaction.
 */
private class IncomingTombstoneFilePurger(
    private val roots: AccountScopedFileRoots,
) {

    suspend fun purge(owner: UserId, rows: List<TombstoneBlobRow>) = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        rows.forEach { row ->
            require(row.ownerUserId == owner.toRestString())
            val capsule = CapsuleId.parseRest(row.capsuleId)
            val blob = BlobId.parseRest(row.blobId)
            val incomingRoot = roots.child(
                owner,
                AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT,
            ).toPath().toAbsolutePath().normalize()
            val expected = roots.incomingCiphertextPath(owner, capsule, blob)
                .toAbsolutePath().normalize()
            require(row.localPath == expected.toString())
            deleteExact(expected, incomingRoot)

            val tempRoot = roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP)
                .toPath().toAbsolutePath().normalize()
            val temp = roots.resolveTrustedRelative(
                tempRoot,
                "incoming-prefetch",
                capsule.toRestString(),
                "${blob.toRestString()}.ciphertext.tmp",
            )
            deleteExact(temp, tempRoot)

            val acceptanceTemp = roots.resolveTrustedRelative(
                tempRoot,
                "incoming-recognition",
                capsule.toRestString(),
                "blobs",
                "${blob.toRestString()}.ciphertext.tmp",
            )
            deleteExact(acceptanceTemp, tempRoot)
        }
    }

    private fun deleteExact(path: Path, root: Path) {
        require(roots.isContainedPath(path, root))
        check(roots.trustedPathSafety(path) == TrustedPathSafety.SAFE)
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) {
            return
        } catch (failure: IOException) {
            throw failure
        }
        require(!attributes.isSymbolicLink && attributes.isRegularFile) {
            "tombstone file is not a regular file"
        }
        Files.deleteIfExists(path)
    }
}
