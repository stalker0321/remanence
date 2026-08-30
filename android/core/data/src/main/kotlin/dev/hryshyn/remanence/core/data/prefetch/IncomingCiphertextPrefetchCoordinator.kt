package dev.hryshyn.remanence.core.data.prefetch

import dev.hryshyn.remanence.core.data.db.BlobCacheDao
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
import dev.hryshyn.remanence.core.data.db.IncomingPrefetchBlobRow
import dev.hryshyn.remanence.core.data.db.IncomingPrefetchCommitResult
import dev.hryshyn.remanence.core.data.db.IncomingPrefetchDao
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleQuarantineResult
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRequest
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRepository
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdoptionRequest
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdoptionResult
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdopter
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdoptionFailure
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class IncomingPrefetchRetryReason {
    SESSION_UNAVAILABLE,
    DATABASE_UNAVAILABLE,
    DOWNLOAD,
    LOCAL_STORAGE,
    ADOPTION,
}

enum class IncomingPrefetchTerminalReason {
    NO_AUTHENTICATED_OWNER,
    ACCOUNT_CHANGED,
    INVALID_METADATA,
    CAPSULE_NOT_READY,
    DOWNLOAD_REJECTED,
    DOWNLOAD_NOT_FOUND,
    AUTH_INVALID,
    CACHED_FILE_INVALID,
    ADOPTION_REJECTED,
    DESTINATION_CONFLICT,
    DATABASE_STATE_INVALID,
}

sealed interface IncomingPrefetchResult {
    data class Completed(
        val processedBlobCount: Int,
        val materialCachedCapsuleCount: Int,
        val quarantinedCapsuleCount: Int = 0,
    ) : IncomingPrefetchResult {
        override fun toString(): String = "IncomingPrefetchResult.Completed(<redacted>)"
    }

    data class Retryable(val reason: IncomingPrefetchRetryReason) : IncomingPrefetchResult

    data class AccountStopped(val reason: IncomingPrefetchTerminalReason) : IncomingPrefetchResult

    data class Terminal(val reason: IncomingPrefetchTerminalReason) : IncomingPrefetchResult
}

/**
 * Bounded local content/photo prefetch after index acceptance. The caller
 * supplies only the expected authenticated owner; all metadata and paths are
 * re-read and derived from that owner-scoped Room state.
 */
class IncomingCiphertextPrefetchCoordinator internal constructor(
    private val prefetchDao: IncomingPrefetchDao,
    private val blobCacheDao: BlobCacheDao,
    private val roots: AccountScopedFileRoots,
    private val currentSession: suspend () -> IncomingSyncSession?,
    private val download: suspend (
        RecipientBlobDownloadRequest,
        String,
    ) -> RecipientBlobDownloadResult,
    private val adopt: suspend (IncomingCiphertextAdoptionRequest) -> IncomingCiphertextAdoptionResult,
    private val maxBlobsPerRun: Int,
) {

    constructor(
        prefetchDao: IncomingPrefetchDao,
        blobCacheDao: BlobCacheDao,
        roots: AccountScopedFileRoots,
        currentSession: suspend () -> IncomingSyncSession?,
        repository: RecipientBlobDownloadRepository,
        adopter: IncomingCiphertextAdopter,
        maxBlobsPerRun: Int = DEFAULT_MAX_BLOBS_PER_RUN,
    ) : this(
        prefetchDao = prefetchDao,
        blobCacheDao = blobCacheDao,
        roots = roots,
        currentSession = currentSession,
        download = { request, token -> repository.downloadBlob(request, token) },
        adopt = { request -> adopter.adopt(request) },
        maxBlobsPerRun = maxBlobsPerRun,
    )

    init {
        require(maxBlobsPerRun in 1..HARD_MAX_BLOBS_PER_RUN) {
            "prefetch bound is invalid"
        }
    }

    suspend fun prefetch(ownerUserId: UserId): IncomingPrefetchResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        when (val initial = checkSession(ownerUserId)) {
            is SessionCheck.Ready -> Unit
            is SessionCheck.Stopped -> return@withContext initial.result
            is SessionCheck.Retry -> return@withContext initial.result
        }

        val selected = try {
            prefetchDao.selectMissingForOwner(ownerUserId.toRestString(), maxBlobsPerRun)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext IncomingPrefetchResult.Retryable(
                IncomingPrefetchRetryReason.DATABASE_UNAVAILABLE,
            )
        }

        var processed = 0
        var materialCached = 0
        var quarantinedCapsules = 0
        for (selectedRow in selected) {
            when (val session = checkSession(ownerUserId)) {
                is SessionCheck.Ready -> Unit
                is SessionCheck.Stopped -> return@withContext session.result
                is SessionCheck.Retry -> return@withContext session.result
            }

            val outcome = prefetchLocks.forKey(ownerUserId, selectedRow.capsuleId, selectedRow.blobId)
                .withLock {
                    processCandidate(ownerUserId, selectedRow)
                }
            when (outcome) {
                is CandidateOutcome.Progress -> {
                    processed++
                    if (outcome.materialCached) materialCached++
                }
                CandidateOutcome.Skipped -> Unit
                is CandidateOutcome.Stopped -> return@withContext outcome.result
                is CandidateOutcome.Retry -> return@withContext outcome.result
                is CandidateOutcome.Terminal -> {
                    if (!outcome.quarantineCapsule) return@withContext outcome.result
                    when (val session = checkSession(ownerUserId)) {
                        is SessionCheck.Ready -> Unit
                        is SessionCheck.Stopped -> return@withContext session.result
                        is SessionCheck.Retry -> return@withContext session.result
                    }
                    val quarantine = try {
                        prefetchDao.quarantineReadyIndexCachedForOwner(
                            ownerUserId.toRestString(),
                            selectedRow.capsuleId,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        IncomingCapsuleQuarantineResult.DatabaseUnavailable
                    }
                    when (quarantine) {
                        IncomingCapsuleQuarantineResult.Quarantined,
                        IncomingCapsuleQuarantineResult.AlreadyCorrupt,
                        -> quarantinedCapsules++
                        IncomingCapsuleQuarantineResult.DatabaseUnavailable,
                        IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged,
                        -> return@withContext IncomingPrefetchResult.Retryable(
                            IncomingPrefetchRetryReason.DATABASE_UNAVAILABLE,
                        )
                        IncomingCapsuleQuarantineResult.MissingOrForeignOwner,
                        -> return@withContext IncomingPrefetchResult.Terminal(
                            IncomingPrefetchTerminalReason.DATABASE_STATE_INVALID,
                        )
                    }
                }
            }
        }

        IncomingPrefetchResult.Completed(
            processedBlobCount = processed,
            materialCachedCapsuleCount = materialCached,
            quarantinedCapsuleCount = quarantinedCapsules,
        )
    }

    private suspend fun processCandidate(
        ownerUserId: UserId,
        selectedRow: IncomingPrefetchBlobRow,
    ): CandidateOutcome {
        val owner = ownerUserId.toRestString()
        val current = try {
            prefetchDao.getCandidateForOwner(owner, selectedRow.capsuleId, selectedRow.blobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.DATABASE_UNAVAILABLE),
            )
        } ?: return CandidateOutcome.Skipped

        val candidate = try {
            parseCandidate(ownerUserId, current)
        } catch (_: IllegalArgumentException) {
            return CandidateOutcome.Terminal(
                IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.INVALID_METADATA),
            )
        }
        val paths = try {
            derivePaths(candidate)
        } catch (_: SecurityException) {
            return CandidateOutcome.Terminal(
                IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.INVALID_METADATA),
            )
        } catch (_: IllegalArgumentException) {
            return CandidateOutcome.Terminal(
                IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.INVALID_METADATA),
            )
        } catch (_: IOException) {
            return CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
            )
        } catch (_: UnsupportedOperationException) {
            return CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
            )
        }

        if (candidate.cacheState == BlobCacheState.CACHED) {
            return when (verifyExactFile(paths.destination, candidate.expectedSizeBytes, candidate.expectedSha256)) {
                FileVerification.MATCH -> when (verifyCachedFiles(ownerUserId, candidate.capsuleId)) {
                    CachedFilesVerification.Ready -> commitRoom(ownerUserId, candidate, paths)
                    CachedFilesVerification.Retry -> CandidateOutcome.Retry(
                        IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
                    )
                    CachedFilesVerification.Invalid -> CandidateOutcome.Terminal(
                        IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.CACHED_FILE_INVALID),
                    )
                }
                FileVerification.MISSING,
                FileVerification.READ_FAILURE,
                -> CandidateOutcome.Retry(
                    IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
                )
                FileVerification.SYMLINK,
                FileVerification.NOT_REGULAR,
                FileVerification.MISMATCH,
                -> CandidateOutcome.Terminal(
                    IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.CACHED_FILE_INVALID),
                )
            }
        }

        val temp = when (val prepared = prepareTemp(paths.temp, candidate)) {
            is TempPreparation.Ready -> prepared
            is TempPreparation.Download -> prepared
            TempPreparation.Unsafe -> return CandidateOutcome.Terminal(
                IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.INVALID_METADATA),
            )
            TempPreparation.Unavailable -> return CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
            )
        }

        val downloaded = if (temp.alreadyVerified) {
            RecipientBlobDownloadResult.Success(temp.path.toFile(), candidate.expectedSizeBytes)
        } else {
            when (val session = checkSession(ownerUserId)) {
                is SessionCheck.Ready -> Unit
                is SessionCheck.Stopped -> return CandidateOutcome.Stopped(session.result)
                is SessionCheck.Retry -> return CandidateOutcome.Retry(session.result)
            }
            val networkSession = when (val session = checkSession(ownerUserId)) {
                is SessionCheck.Ready -> session.session
                is SessionCheck.Stopped -> return CandidateOutcome.Stopped(session.result)
                is SessionCheck.Retry -> return CandidateOutcome.Retry(session.result)
            }
            try {
                download(
                    RecipientBlobDownloadRequest(
                        capsuleId = candidate.capsuleId,
                        blobId = candidate.blobId,
                        expectedCiphertextSize = candidate.expectedSizeBytes,
                        expectedCiphertextSha256 = candidate.expectedSha256,
                        destination = temp.path.toFile(),
                    ),
                    networkSession.accessToken,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CandidateOutcome.Retry(
                    IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.DOWNLOAD),
                )
            }
        }

        when (downloaded) {
            is RecipientBlobDownloadResult.Success -> {
                if (!sameNormalizedPath(downloaded.ciphertextFile.toPath(), temp.path) ||
                    downloaded.sizeBytes != candidate.expectedSizeBytes
                ) {
                    cleanupTemp(temp.path)
                    return CandidateOutcome.Terminal(
                        IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.DOWNLOAD_REJECTED),
                    )
                }
                when (verifyExactFile(temp.path, candidate.expectedSizeBytes, candidate.expectedSha256)) {
                    FileVerification.MATCH -> Unit
                    FileVerification.MISSING,
                    FileVerification.READ_FAILURE,
                    -> return CandidateOutcome.Retry(
                        IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
                    )
                    FileVerification.SYMLINK,
                    FileVerification.NOT_REGULAR,
                    FileVerification.MISMATCH,
                    -> {
                        cleanupTemp(temp.path)
                        return CandidateOutcome.Terminal(
                            IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.DOWNLOAD_REJECTED),
                        )
                    }
                }
            }
            is RecipientBlobDownloadResult.Failure -> {
                if (!downloaded.retryable) cleanupTemp(temp.path)
                return mapDownloadFailure(downloaded)
            }
        }

        when (val session = checkSession(ownerUserId)) {
            is SessionCheck.Ready -> Unit
            is SessionCheck.Stopped -> return CandidateOutcome.Stopped(session.result)
            is SessionCheck.Retry -> return CandidateOutcome.Retry(session.result)
        }

        val adopted = try {
            adopt(
                IncomingCiphertextAdoptionRequest(
                    ownerUserId = ownerUserId,
                    capsuleId = candidate.capsuleId,
                    blobId = candidate.blobId,
                    expectedSizeBytes = candidate.expectedSizeBytes,
                    expectedSha256 = candidate.expectedSha256,
                    sourceTempFile = temp.path.toFile(),
                    artifactKind = candidate.kind,
                    ordinal = candidate.ordinal,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.ADOPTION),
            )
        }
        when (adopted) {
            is IncomingCiphertextAdoptionResult.Failure -> {
                if (!adopted.retryable) cleanupTemp(temp.path)
                return if (adopted.retryable) {
                    CandidateOutcome.Retry(
                        IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.ADOPTION),
                    )
                } else {
                    CandidateOutcome.Terminal(
                        IncomingPrefetchResult.Terminal(
                            when (adopted.reason) {
                                IncomingCiphertextAdoptionFailure.DESTINATION_CONFLICT ->
                                    IncomingPrefetchTerminalReason.DESTINATION_CONFLICT
                                IncomingCiphertextAdoptionFailure.SOURCE_OUTSIDE_OWNER_TEMP,
                                IncomingCiphertextAdoptionFailure.SOURCE_PATH_UNSAFE,
                                IncomingCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
                                -> IncomingPrefetchTerminalReason.INVALID_METADATA
                                IncomingCiphertextAdoptionFailure.SOURCE_MISSING,
                                IncomingCiphertextAdoptionFailure.SOURCE_NOT_REGULAR,
                                IncomingCiphertextAdoptionFailure.SOURCE_INTEGRITY_FAILED,
                                IncomingCiphertextAdoptionFailure.ATOMIC_MOVE_UNAVAILABLE,
                                IncomingCiphertextAdoptionFailure.DURABILITY_UNAVAILABLE,
                                IncomingCiphertextAdoptionFailure.LOCAL_STORAGE,
                                -> IncomingPrefetchTerminalReason.ADOPTION_REJECTED
                            },
                        ),
                    )
                }
            }
            is IncomingCiphertextAdoptionResult.Adopted -> {
                val destination = adopted.destination
                if (destination.ownerUserId != ownerUserId ||
                    destination.capsuleId != candidate.capsuleId ||
                    destination.blobId != candidate.blobId ||
                    !sameNormalizedPath(destination.asFile().toPath(), paths.destination)
                ) {
                    return CandidateOutcome.Terminal(
                        IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.ADOPTION_REJECTED),
                    )
                }
            }
        }

        when (val session = checkSession(ownerUserId)) {
            is SessionCheck.Ready -> Unit
            is SessionCheck.Stopped -> return CandidateOutcome.Stopped(session.result)
            is SessionCheck.Retry -> return CandidateOutcome.Retry(session.result)
        }

        return when (verifyCachedFiles(ownerUserId, candidate.capsuleId)) {
            CachedFilesVerification.Ready -> commitRoom(ownerUserId, candidate, paths)
            CachedFilesVerification.Retry -> CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
            )
            CachedFilesVerification.Invalid -> CandidateOutcome.Terminal(
                IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.CACHED_FILE_INVALID),
            )
        }
    }

    private suspend fun commitRoom(
        owner: UserId,
        candidate: PrefetchCandidate,
        paths: PrefetchPaths,
    ): CandidateOutcome {
        when (val session = checkSession(owner)) {
            is SessionCheck.Ready -> Unit
            is SessionCheck.Stopped -> return CandidateOutcome.Stopped(session.result)
            is SessionCheck.Retry -> return CandidateOutcome.Retry(session.result)
        }
        val result = try {
            prefetchDao.markCachedAndMaybeMaterialCached(
                ownerUserId = owner.toRestString(),
                capsuleId = candidate.capsuleId.toRestString(),
                blobId = candidate.blobId.toRestString(),
                expectedKind = candidate.kind.name,
                expectedOrdinal = if (candidate.kind == CapsuleArtifactKind.PHOTO) candidate.ordinal else null,
                expectedSizeBytes = candidate.expectedSizeBytes,
                expectedSha256 = candidate.expectedSha256,
                expectedLocalPath = paths.destination.toString(),
                incomingRootPath = paths.incomingRoot.toString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.DATABASE_UNAVAILABLE),
            )
        }
        return when (result) {
            IncomingPrefetchCommitResult.BlobCached,
            IncomingPrefetchCommitResult.BlobAlreadyCached,
            -> CandidateOutcome.Progress(materialCached = false)
            IncomingPrefetchCommitResult.MaterialCached,
            IncomingPrefetchCommitResult.AlreadyMaterialCached,
            -> CandidateOutcome.Progress(materialCached = true)
            IncomingPrefetchCommitResult.ConcurrentOrStale -> CandidateOutcome.Retry(
                IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.DATABASE_UNAVAILABLE),
            )
            IncomingPrefetchCommitResult.MissingOrForeignOwner -> CandidateOutcome.Terminal(
                IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.DATABASE_STATE_INVALID),
            )
            IncomingPrefetchCommitResult.InvalidBinding,
            IncomingPrefetchCommitResult.IllegalState,
            -> CandidateOutcome.Terminal(
                IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.DATABASE_STATE_INVALID),
            )
        }
    }

    private suspend fun verifyCachedFiles(
        owner: UserId,
        capsule: CapsuleId,
    ): CachedFilesVerification {
        val rows = try {
            blobCacheDao.getAllByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CachedFilesVerification.Retry
        }
        for (row in rows.filter { it.cacheState == BlobCacheState.CACHED }) {
            val parsed = try {
                parseCachedCandidate(owner, row.toPrefetchRow(owner, capsule))
            } catch (_: IllegalArgumentException) {
                return CachedFilesVerification.Invalid
            }
            val paths = try {
                derivePaths(parsed)
            } catch (_: IOException) {
                return CachedFilesVerification.Retry
            } catch (_: UnsupportedOperationException) {
                return CachedFilesVerification.Retry
            } catch (_: Exception) {
                return CachedFilesVerification.Invalid
            }
            when (verifyExactFile(paths.destination, parsed.expectedSizeBytes, parsed.expectedSha256)) {
                FileVerification.MATCH -> Unit
                FileVerification.MISSING,
                FileVerification.READ_FAILURE,
                -> return CachedFilesVerification.Retry
                FileVerification.SYMLINK,
                FileVerification.NOT_REGULAR,
                FileVerification.MISMATCH,
                -> return CachedFilesVerification.Invalid
            }
        }
        return CachedFilesVerification.Ready
    }

    private fun dev.hryshyn.remanence.core.data.db.BlobCacheEntity.toPrefetchRow(
        owner: UserId,
        capsule: CapsuleId,
    ): IncomingPrefetchBlobRow = IncomingPrefetchBlobRow(
        blobId = blobId,
        ownerUserId = owner.toRestString(),
        capsuleId = capsule.toRestString(),
        kind = kind,
        ordinal = ordinal,
        expectedSizeBytes = expectedSizeBytes,
        expectedSha256 = expectedSha256,
        localPath = localPath,
        cacheState = cacheState,
    )

    private fun parseCandidate(
        owner: UserId,
        row: IncomingPrefetchBlobRow,
    ): PrefetchCandidate {
        require(row.ownerUserId == owner.toRestString())
        require(row.cacheState == BlobCacheState.DOWNLOADING || row.cacheState == BlobCacheState.CACHED)
        val capsule = CapsuleId.parseRest(row.capsuleId)
        val blob = BlobId.parseRest(row.blobId)
        val kind = CapsuleArtifactKind.valueOf(row.kind)
        require(kind == CapsuleArtifactKind.CONTENT_MANIFEST || kind == CapsuleArtifactKind.PHOTO)
        val ordinal = when (kind) {
                CapsuleArtifactKind.CONTENT_MANIFEST -> {
                    require(row.ordinal == null)
                    ProtocolV1Limits.NON_PHOTO_ORDINAL
                }
            CapsuleArtifactKind.PHOTO -> row.ordinal
                ?.also { require(it in ProtocolV1Limits.PHOTO_ORDINAL_MIN..ProtocolV1Limits.PHOTO_ORDINAL_MAX) }
                ?: throw IllegalArgumentException("photo ordinal is missing")
            CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                throw IllegalArgumentException("recognition is not prefetch work")
        }
        val maxBytes = when (kind) {
            CapsuleArtifactKind.CONTENT_MANIFEST -> ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.PHOTO -> ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                throw IllegalArgumentException("recognition is not prefetch work")
        }
        require(row.expectedSizeBytes in 1L..maxBytes)
        require(row.expectedSha256.size == SHA256_BYTES)
        return PrefetchCandidate(
            capsuleId = capsule,
            blobId = blob,
            kind = kind,
            ordinal = ordinal,
            expectedSizeBytes = row.expectedSizeBytes,
            expectedSha256 = row.expectedSha256.copyOf(),
            localPath = row.localPath,
            cacheState = row.cacheState,
            ownerUserId = owner,
        )
    }

    private fun parseCachedCandidate(
        owner: UserId,
        row: IncomingPrefetchBlobRow,
    ): PrefetchCandidate {
        require(row.ownerUserId == owner.toRestString())
        require(row.cacheState == BlobCacheState.CACHED)
        val capsule = CapsuleId.parseRest(row.capsuleId)
        val blob = BlobId.parseRest(row.blobId)
        val kind = CapsuleArtifactKind.valueOf(row.kind)
        val ordinal = when (kind) {
            CapsuleArtifactKind.PHOTO -> row.ordinal
                ?.also { require(it in ProtocolV1Limits.PHOTO_ORDINAL_MIN..ProtocolV1Limits.PHOTO_ORDINAL_MAX) }
                ?: throw IllegalArgumentException("photo ordinal is missing")
            CapsuleArtifactKind.RECOGNITION_MANIFEST,
            CapsuleArtifactKind.CONTENT_MANIFEST,
            -> {
                require(row.ordinal == null)
                ProtocolV1Limits.NON_PHOTO_ORDINAL
            }
        }
        val maxBytes = when (kind) {
            CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.CONTENT_MANIFEST -> ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.PHOTO -> ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES
        }
        require(row.expectedSizeBytes in 1L..maxBytes)
        require(row.expectedSha256.size == SHA256_BYTES)
        return PrefetchCandidate(
            capsuleId = capsule,
            blobId = blob,
            kind = kind,
            ordinal = ordinal,
            expectedSizeBytes = row.expectedSizeBytes,
            expectedSha256 = row.expectedSha256.copyOf(),
            localPath = row.localPath,
            cacheState = row.cacheState,
            ownerUserId = owner,
        )
    }

    private fun derivePaths(candidate: PrefetchCandidate): PrefetchPaths {
        val incomingRoot = roots.child(
            candidate.ownerUserId,
            AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT,
        ).canonicalFile.toPath().toAbsolutePath().normalize()
        val tempRoot = roots.child(
            candidate.ownerUserId,
            AccountScopedFileRoots.ChildRoot.TEMP,
        ).canonicalFile.toPath().toAbsolutePath().normalize()
        val destination = incomingRoot.resolve(
            "capsules/${candidate.capsuleId.toRestString()}/blobs/" +
                "${candidate.blobId.toRestString()}.ciphertext",
        ).normalize()
        val temp = tempRoot.resolve(
            "incoming-prefetch/${candidate.capsuleId.toRestString()}/" +
                "${candidate.blobId.toRestString()}.ciphertext.tmp",
        ).normalize()
        require(isContained(destination, incomingRoot) && isContained(temp, tempRoot))
        require(candidate.localPath == destination.toString())
        return PrefetchPaths(incomingRoot, tempRoot, temp, destination)
    }

    private suspend fun prepareTemp(
        path: Path,
        candidate: PrefetchCandidate,
    ): TempPreparation {
        val parent = path.parent ?: return TempPreparation.Unsafe
        val tempRoot = try {
            roots.child(
                candidate.ownerUserId,
                AccountScopedFileRoots.ChildRoot.TEMP,
            ).canonicalFile.toPath().toAbsolutePath().normalize()
        } catch (_: IOException) {
            return TempPreparation.Unavailable
        } catch (_: SecurityException) {
            return TempPreparation.Unavailable
        } catch (_: UnsupportedOperationException) {
            return TempPreparation.Unavailable
        }
        if (!isContained(path, tempRoot)) return TempPreparation.Unsafe
        when (isNoSymlinkPath(tempRoot)) {
            PathSafety.SAFE -> Unit
            PathSafety.UNSAFE -> return TempPreparation.Unsafe
            PathSafety.UNAVAILABLE -> return TempPreparation.Unavailable
        }
        try {
            Files.createDirectories(parent)
        } catch (_: IOException) {
            return TempPreparation.Unavailable
        } catch (_: SecurityException) {
            return TempPreparation.Unavailable
        } catch (_: UnsupportedOperationException) {
            return TempPreparation.Unavailable
        }
        when (isNoSymlinkPath(parent)) {
            PathSafety.SAFE -> Unit
            PathSafety.UNSAFE -> return TempPreparation.Unsafe
            PathSafety.UNAVAILABLE -> return TempPreparation.Unavailable
        }
        val attrs = try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) {
            return TempPreparation.Download(path)
        } catch (_: IOException) {
            return TempPreparation.Unavailable
        } catch (_: SecurityException) {
            return TempPreparation.Unavailable
        } catch (_: UnsupportedOperationException) {
            return TempPreparation.Unavailable
        }
        if (attrs.isSymbolicLink || !attrs.isRegularFile) return TempPreparation.Unsafe
        return when (verifyExactFile(path, candidate.expectedSizeBytes, candidate.expectedSha256)) {
            FileVerification.MATCH -> TempPreparation.Ready(path)
            FileVerification.MISSING -> TempPreparation.Download(path)
            FileVerification.MISMATCH -> try {
                if (Files.deleteIfExists(path)) Unit
                TempPreparation.Download(path)
            } catch (_: IOException) {
                TempPreparation.Unavailable
            } catch (_: SecurityException) {
                TempPreparation.Unavailable
            } catch (_: UnsupportedOperationException) {
                TempPreparation.Unavailable
            }
            FileVerification.SYMLINK,
            FileVerification.NOT_REGULAR,
            -> TempPreparation.Unsafe
            FileVerification.READ_FAILURE -> TempPreparation.Unavailable
        }
    }

    private fun cleanupTemp(path: Path) {
        try {
            val attrs = Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attrs.isSymbolicLink && attrs.isRegularFile) Files.deleteIfExists(path)
        } catch (_: Exception) {
            // A failed cleanup must never replace the typed prefetch outcome.
        }
    }

    private suspend fun verifyExactFile(
        path: Path,
        expectedSize: Long,
        expectedSha256: ByteArray,
    ): FileVerification {
        val attrs = try {
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: java.nio.file.NoSuchFileException) {
            return FileVerification.MISSING
        } catch (_: IOException) {
            return FileVerification.READ_FAILURE
        } catch (_: SecurityException) {
            return FileVerification.READ_FAILURE
        } catch (_: UnsupportedOperationException) {
            return FileVerification.READ_FAILURE
        }
        if (attrs.isSymbolicLink) return FileVerification.SYMLINK
        if (!attrs.isRegularFile) return FileVerification.NOT_REGULAR
        if (attrs.size() != expectedSize) return FileVerification.MISMATCH

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input: InputStream ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) return FileVerification.READ_FAILURE
                    total += read
                    if (total > expectedSize) return FileVerification.MISMATCH
                    digest.update(buffer, 0, read)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return FileVerification.READ_FAILURE
        } catch (_: SecurityException) {
            return FileVerification.READ_FAILURE
        } catch (_: UnsupportedOperationException) {
            return FileVerification.READ_FAILURE
        }
        if (total != expectedSize) return FileVerification.MISMATCH
        return if (MessageDigest.isEqual(digest.digest(), expectedSha256)) {
            FileVerification.MATCH
        } else {
            FileVerification.MISMATCH
        }
    }

    private fun sameNormalizedPath(first: Path, second: Path): Boolean =
        first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()

    private fun isNoSymlinkPath(path: Path): PathSafety {
        var current: Path? = path
        return try {
            while (current != null) {
                val examined = current
                val attrs = try {
                    Files.readAttributes(
                        examined,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: java.nio.file.NoSuchFileException) {
                    current = examined.parent
                    continue
                }
                if (attrs.isSymbolicLink) return PathSafety.UNSAFE
                current = examined.parent
            }
            PathSafety.SAFE
        } catch (_: IOException) {
            PathSafety.UNAVAILABLE
        } catch (_: SecurityException) {
            PathSafety.UNAVAILABLE
        } catch (_: UnsupportedOperationException) {
            PathSafety.UNAVAILABLE
        }
    }

    private fun isContained(candidate: Path, root: Path): Boolean =
        candidate != root && candidate.startsWith(root)

    private suspend fun checkSession(owner: UserId): SessionCheck = try {
        val session = currentSession()
        when {
            session == null -> SessionCheck.Stopped(
                IncomingPrefetchResult.AccountStopped(IncomingPrefetchTerminalReason.NO_AUTHENTICATED_OWNER),
            )
            session.ownerUserId != owner -> SessionCheck.Stopped(
                IncomingPrefetchResult.AccountStopped(IncomingPrefetchTerminalReason.ACCOUNT_CHANGED),
            )
            else -> SessionCheck.Ready(session)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SessionCheck.Retry(
            IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.SESSION_UNAVAILABLE),
        )
    }

    private fun mapDownloadFailure(
        failure: RecipientBlobDownloadResult.Failure,
    ): CandidateOutcome = if (failure.retryable) {
        CandidateOutcome.Retry(
            IncomingPrefetchResult.Retryable(
                if (failure.reason == RecipientBlobDownloadFailure.LOCAL_STORAGE) {
                    IncomingPrefetchRetryReason.LOCAL_STORAGE
                } else {
                    IncomingPrefetchRetryReason.DOWNLOAD
                },
            ),
        )
    } else {
        CandidateOutcome.Terminal(
            result = IncomingPrefetchResult.Terminal(
                when (failure.reason) {
                    RecipientBlobDownloadFailure.AUTH_INVALID -> IncomingPrefetchTerminalReason.AUTH_INVALID
                    RecipientBlobDownloadFailure.NOT_FOUND -> IncomingPrefetchTerminalReason.DOWNLOAD_NOT_FOUND
                    RecipientBlobDownloadFailure.INTEGRITY_FAILED,
                    RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH,
                    RecipientBlobDownloadFailure.INVALID_RESPONSE,
                    RecipientBlobDownloadFailure.HTTP,
                    RecipientBlobDownloadFailure.VALIDATION_FAILED,
                    RecipientBlobDownloadFailure.RATE_LIMITED,
                    RecipientBlobDownloadFailure.NETWORK,
                    RecipientBlobDownloadFailure.LOCAL_STORAGE,
                    RecipientBlobDownloadFailure.INTERNAL_ERROR,
                    -> IncomingPrefetchTerminalReason.DOWNLOAD_REJECTED
                },
            ),
            quarantineCapsule = failure.reason != RecipientBlobDownloadFailure.AUTH_INVALID,
        )
    }

    private sealed interface CandidateOutcome {
        data object Skipped : CandidateOutcome
        data class Progress(val materialCached: Boolean) : CandidateOutcome
        data class Retry(val result: IncomingPrefetchResult.Retryable) : CandidateOutcome
        data class Stopped(val result: IncomingPrefetchResult.AccountStopped) : CandidateOutcome
        data class Terminal(
            val result: IncomingPrefetchResult.Terminal,
            val quarantineCapsule: Boolean = true,
        ) : CandidateOutcome
    }

    private sealed interface SessionCheck {
        data class Ready(val session: IncomingSyncSession) : SessionCheck
        data class Retry(val result: IncomingPrefetchResult.Retryable) : SessionCheck
        data class Stopped(val result: IncomingPrefetchResult.AccountStopped) : SessionCheck
    }

    private sealed interface TempPreparation {
        val path: Path
        val alreadyVerified: Boolean

        data class Ready(override val path: Path) : TempPreparation {
            override val alreadyVerified: Boolean = true
        }

        data class Download(override val path: Path) : TempPreparation {
            override val alreadyVerified: Boolean = false
        }

        data object Unsafe : TempPreparation {
            override val path: Path get() = error("unsafe temp has no path")
            override val alreadyVerified: Boolean = false
        }

        data object Unavailable : TempPreparation {
            override val path: Path get() = error("unavailable temp has no path")
            override val alreadyVerified: Boolean = false
        }
    }

    private enum class CachedFilesVerification {
        Ready,
        Retry,
        Invalid,
    }

    private enum class FileVerification {
        MATCH,
        MISSING,
        SYMLINK,
        NOT_REGULAR,
        MISMATCH,
        READ_FAILURE,
    }

    private enum class PathSafety {
        SAFE,
        UNSAFE,
        UNAVAILABLE,
    }

    private data class PrefetchCandidate(
        val capsuleId: CapsuleId,
        val blobId: BlobId,
        val kind: CapsuleArtifactKind,
        val ordinal: Int,
        val expectedSizeBytes: Long,
        val expectedSha256: ByteArray,
        val localPath: String,
        val cacheState: BlobCacheState,
        val ownerUserId: UserId,
    )

    private data class PrefetchPaths(
        val incomingRoot: Path,
        val tempRoot: Path,
        val temp: Path,
        val destination: Path,
    )

    private companion object {
        const val DEFAULT_MAX_BLOBS_PER_RUN = 4
        const val HARD_MAX_BLOBS_PER_RUN = 8
        const val STREAM_BUFFER_BYTES = 32 * 1024
        const val SHA256_BYTES = 32
        val prefetchLocks = PrefetchLockStripes()
    }
}

private class PrefetchLockStripes {
    private val locks = Array(32) { Mutex() }

    fun forKey(owner: UserId, capsuleId: String, blobId: String): Mutex =
        locks[("${owner.toRestString()}|$capsuleId|$blobId".hashCode() and Int.MAX_VALUE) % locks.size]
}
