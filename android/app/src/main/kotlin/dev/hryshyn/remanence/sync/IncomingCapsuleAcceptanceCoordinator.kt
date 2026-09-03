package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.data.db.BlobCacheDao
import dev.hryshyn.remanence.core.data.db.BlobCacheEntity
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeDao
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceCommitRequest
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceCommitResult
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceCommitter
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRepository
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRequest
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.DurableIncomingCiphertextFile
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdopter
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdoptionRequest
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdoptionResult
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.index.SenderIndexBundleSenderVerification
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The exact one-capsule input; no file or credential is caller-selected. */
class IncomingCapsuleAcceptanceRequest(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
) {
    override fun toString(): String = "IncomingCapsuleAcceptanceRequest(<redacted>)"
}

enum class IncomingCapsuleAcceptanceRetryReason {
    SESSION_UNAVAILABLE,
    LOCAL_STORAGE,
    DOWNLOAD,
    CRYPTO_ACCEPTANCE,
    VERIFIED_PAYLOAD_PERSISTENCE,
    ADOPTION,
    ROOM_COMMIT,
}

enum class IncomingCapsuleAcceptanceRejectionReason {
    NO_AUTHENTICATED_OWNER,
    OWNER_MISMATCH,
    ACCOUNT_CHANGED,
    CAPSULE_METADATA_MISSING,
    ENVELOPE_METADATA_MISSING,
    RECOGNITION_METADATA_INVALID,
    CAPSULE_STATE_INVALID,
    TEMP_PATH_UNSAFE,
    RECOVERY_TEMP_INVALID,
    DOWNLOAD_REJECTED,
    CRYPTO_REJECTED,
    PERSISTENCE_REJECTED,
    ADOPTION_REJECTED,
    ROOM_COMMIT_REJECTED,
    DURABLE_STATE_INVALID,
}

/** Redacted result of one incoming recognition-index composition attempt. */
sealed interface IncomingCapsuleAcceptanceResult {
    data object Committed : IncomingCapsuleAcceptanceResult
    data object IdempotentReplay : IncomingCapsuleAcceptanceResult

    data class Retryable(val reason: IncomingCapsuleAcceptanceRetryReason) :
        IncomingCapsuleAcceptanceResult

    data class Rejected(val reason: IncomingCapsuleAcceptanceRejectionReason) :
        IncomingCapsuleAcceptanceResult
}

/** Production binding for the already accepted A11a download boundary. */
fun interface IncomingRecipientBlobDownloader {
    suspend fun download(
        request: RecipientBlobDownloadRequest,
        accessToken: String,
    ): RecipientBlobDownloadResult
}

/** Production binding for the already accepted A11b crypto boundary. */
fun interface IncomingControlIndexAcceptancePort {
    suspend fun accept(request: IncomingControlIndexAcceptanceRequest):
        IncomingControlIndexAcceptancePortResult
}

/** Redacted A11b outcome needed by this composition boundary. */
sealed interface IncomingControlIndexAcceptancePortResult {
    class Verified(
        val payload: IncomingVerifiedControlIndexPayload,
    ) : IncomingControlIndexAcceptancePortResult {
        override fun toString(): String =
            "IncomingControlIndexAcceptancePortResult.Verified(<redacted>)"
    }
    data class Retryable(val reason: IncomingAcceptanceRetryReason) :
        IncomingControlIndexAcceptancePortResult
    data class Rejected(val reason: IncomingAcceptanceRejectionReason) :
        IncomingControlIndexAcceptancePortResult
}

/** The only in-memory handoff of A11b's verified statement and recognition. */
class IncomingVerifiedControlIndexPayload internal constructor(
    val statement: PublishStatement,
    val recognition: RecognitionManifestContent,
    internal val senderVerification: SenderIndexBundleSenderVerification,
) {
    override fun toString(): String = "IncomingVerifiedControlIndexPayload(<redacted>)"
}

class IncomingVerifiedControlIndexPersistenceRequest internal constructor(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val verified: IncomingVerifiedControlIndexPayload,
) {
    override fun toString(): String =
        "IncomingVerifiedControlIndexPersistenceRequest(<redacted>)"
}

enum class IncomingVerifiedControlIndexPersistenceRetryReason {
    DEPENDENCY_UNAVAILABLE,
    LOCAL_STORAGE,
}

enum class IncomingVerifiedControlIndexPersistenceRejectionReason {
    OWNER_MISMATCH,
    ACCOUNT_CHANGED,
    INVALID_VERIFIED_PAYLOAD,
    LOCAL_CAPABILITY_UNAVAILABLE,
}

/**
 * Mandatory A12 boundary. Implementations must return [Durable] only after
 * the verified FRONT fingerprint and chooser hints are durably encrypted under
 * this account;
 * there is intentionally no no-op production implementation.
 */
fun interface IncomingVerifiedControlIndexPersistencePort {
    suspend fun persist(
        request: IncomingVerifiedControlIndexPersistenceRequest,
        authenticatedOwnerUserId: UserId,
    ): IncomingVerifiedControlIndexPersistenceResult
}

sealed interface IncomingVerifiedControlIndexPersistenceResult {
    data object Durable : IncomingVerifiedControlIndexPersistenceResult

    data class Retryable(
        val reason: IncomingVerifiedControlIndexPersistenceRetryReason,
    ) : IncomingVerifiedControlIndexPersistenceResult

    data class Rejected(
        val reason: IncomingVerifiedControlIndexPersistenceRejectionReason,
    ) : IncomingVerifiedControlIndexPersistenceResult
}

/** Production binding for the already accepted A11c1 file adoption boundary. */
fun interface IncomingRecognitionAdoptionPort {
    suspend fun adopt(
        request: IncomingRecognitionCiphertextAdoptionRequest,
    ): IncomingRecognitionCiphertextAdoptionResult
}

/** Production binding for the already accepted A11c2 owner-authenticated Room boundary. */
fun interface IncomingIndexCommitPort {
    suspend fun commit(
        request: IncomingIndexAcceptanceCommitRequest,
        authenticatedOwnerUserId: UserId?,
    ): IncomingIndexAcceptanceCommitResult
}

/**
 * Composes one discovered capsule's recognition path. It owns no scheduling,
 * page loop, content prefetch, UI, or A12 storage implementation. Its
 * mandatory A12 persistence port must succeed before adoption or Room commit.
 *
 * The Room declaration and file are revalidated at each boundary. The only
 * filesystem/Room guarantee is A11c2's documented file preflight followed by
 * its owner-scoped Room transaction; this coordinator does not claim those
 * domains are one atomic operation. Its fixed striped Mutexes serialize
 * callers in the default single-process WorkManager process; a future
 * multi-process caller must add a no-follow filesystem claim before using
 * this boundary across processes.
 */
class IncomingCapsuleAcceptanceCoordinator internal constructor(
    private val incomingCapsuleDao: IncomingCapsuleDao,
    private val incomingEnvelopeDao: IncomingEnvelopeDao,
    private val blobCacheDao: BlobCacheDao,
    private val roots: AccountScopedFileRoots,
    private val currentSession: suspend () -> IncomingSyncSession?,
    private val download: IncomingRecipientBlobDownloader,
    private val controlAcceptance: IncomingControlIndexAcceptancePort,
    private val verifiedControlIndexPersistence: IncomingVerifiedControlIndexPersistencePort,
    private val adoption: IncomingRecognitionAdoptionPort,
    private val commit: IncomingIndexCommitPort,
    private val senderIndexBundleInspection: IncomingSenderIndexBundleInspectionPort,
) {

    /** Binds production concrete primitives without adding lifecycle or DI wiring. */
    constructor(
        incomingCapsuleDao: IncomingCapsuleDao,
        incomingEnvelopeDao: IncomingEnvelopeDao,
        blobCacheDao: BlobCacheDao,
        roots: AccountScopedFileRoots,
        currentSession: suspend () -> IncomingSyncSession?,
        recipientBlobDownloadRepository: RecipientBlobDownloadRepository,
        controlIndexAcceptanceCoordinator: IncomingControlIndexAcceptanceCoordinator,
        senderIndexBundleReader: SenderIndexBundleReader,
        verifiedControlIndexPersistence: IncomingVerifiedControlIndexPersistencePort,
        incomingRecognitionCiphertextAdopter: IncomingRecognitionCiphertextAdopter,
        incomingIndexAcceptanceCommitter: IncomingIndexAcceptanceCommitter,
    ) : this(
        incomingCapsuleDao = incomingCapsuleDao,
        incomingEnvelopeDao = incomingEnvelopeDao,
        blobCacheDao = blobCacheDao,
        roots = roots,
        currentSession = currentSession,
        download = IncomingRecipientBlobDownloader { request, accessToken ->
            recipientBlobDownloadRepository.downloadBlob(request, accessToken)
        },
        controlAcceptance = IncomingControlIndexAcceptancePort { request ->
            when (val result = controlIndexAcceptanceCoordinator.accept(request)) {
                is IncomingControlIndexAcceptanceResult.Verified ->
                    IncomingControlIndexAcceptancePortResult.Verified(
                        IncomingVerifiedControlIndexPayload(
                            statement = result.statement,
                            recognition = result.recognition,
                            senderVerification = result.senderVerification.copyForHandoff(),
                        ),
                    )
                is IncomingControlIndexAcceptanceResult.Retryable ->
                    IncomingControlIndexAcceptancePortResult.Retryable(result.reason)
                is IncomingControlIndexAcceptanceResult.Rejected ->
                    IncomingControlIndexAcceptancePortResult.Rejected(result.reason)
            }
        },
        senderIndexBundleInspection = SenderIndexBundleInspectionAdapter(senderIndexBundleReader),
        verifiedControlIndexPersistence = verifiedControlIndexPersistence,
        adoption = IncomingRecognitionAdoptionPort { request ->
            incomingRecognitionCiphertextAdopter.adopt(request)
        },
        commit = IncomingIndexCommitPort { request, authenticatedOwnerUserId ->
            incomingIndexAcceptanceCommitter.commit(request, authenticatedOwnerUserId)
        },
    )

    suspend fun accept(
        request: IncomingCapsuleAcceptanceRequest,
    ): IncomingCapsuleAcceptanceResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        capsuleProbeLocks[stripe(request.ownerUserId.toRestString() + "|" + request.capsuleId.toRestString())]
            .withLock {
                when (val checked = sessionFor(request.ownerUserId, initial = true)) {
                    is SessionCheck.Ready -> Unit
                    is SessionCheck.Failure -> return@withContext checked.result
                }

                val initialDeclaration = try {
                    loadDeclaration(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    return@withContext rejected(
                        IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
                    )
                } catch (_: Exception) {
                    return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.LOCAL_STORAGE)
                }

                when (initialDeclaration) {
                    is DeclarationLoad.Rejected -> return@withContext rejected(initialDeclaration.reason)
                    DeclarationLoad.AlreadyAccepted ->
                        return@withContext reconcileAlreadyAccepted(request)
                    is DeclarationLoad.Ready -> {
                        val lockKey = buildString {
                            append(request.ownerUserId.toRestString())
                            append('|')
                            append(request.capsuleId.toRestString())
                            append('|')
                            append(initialDeclaration.declaration.recognitionBlobId.toRestString())
                        }
                        attemptLocks[stripe(lockKey)].withLock {
                            // Re-read both the live account and the Room
                            // declaration after waiting for the active writer.
                            when (val checked = sessionFor(request.ownerUserId, initial = false)) {
                                is SessionCheck.Ready -> Unit
                                is SessionCheck.Failure -> return@withContext checked.result
                            }
                            val declaration = try {
                                loadDeclaration(request)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: IllegalArgumentException) {
                                return@withContext rejected(
                                    IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
                                )
                            } catch (_: Exception) {
                                return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.LOCAL_STORAGE)
                            }
                            val readyDeclaration = when (declaration) {
                                is DeclarationLoad.Rejected ->
                                    return@withContext rejected(declaration.reason)
                                DeclarationLoad.AlreadyAccepted ->
                                    return@withContext reconcileAlreadyAccepted(request)
                                is DeclarationLoad.Ready -> declaration.declaration
                            }

        // Revalidate the live account and use the latest token immediately
        // before the authenticated network request.
        val networkSession = when (val checked = sessionFor(request.ownerUserId, initial = false)) {
            is SessionCheck.Ready -> checked.session
            is SessionCheck.Failure -> return@withContext checked.result
        }

        val tempPreparation = try {
            prepareRecoveryTempPath(
                owner = request.ownerUserId,
                capsule = request.capsuleId,
                blob = readyDeclaration.recognitionBlobId,
                expectedSize = readyDeclaration.expectedSizeBytes,
                expectedSha256 = readyDeclaration.expectedSha256,
            )
        } catch (_: UnsafeTempPath) {
            return@withContext rejected(IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE)
        } catch (_: IOException) {
            return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.LOCAL_STORAGE)
        } catch (_: SecurityException) {
            return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.LOCAL_STORAGE)
        }
        when (tempPreparation) {
            is TempPreparation.Unavailable ->
                return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.LOCAL_STORAGE)
            is TempPreparation.Invalid -> {
                deleteExactTemp(tempPreparation.path, request.ownerUserId)
                return@withContext rejected(IncomingCapsuleAcceptanceRejectionReason.RECOVERY_TEMP_INVALID)
            }
            is TempPreparation.Ready -> Unit
        }
        val tempPath = tempPreparation.path
        val tempFile = tempPath.toFile()
        var preserveTemp = false
        // The deterministic path is owned by this owner/capsule/blob recovery
        // attempt. A11a owns creation of a missing path; cleanup remains an
        // exact-file operation and never follows a directory or symlink.
        val invocationOwnsTemp = true

        try {
            val downloaded = if (tempPreparation.existingVerified) {
                RecipientBlobDownloadResult.Success(
                    ciphertextFile = tempFile,
                    sizeBytes = readyDeclaration.expectedSizeBytes,
                )
            } else {
                try {
                    when (val result = download.download(
                        RecipientBlobDownloadRequest(
                            capsuleId = request.capsuleId,
                            blobId = readyDeclaration.recognitionBlobId,
                            expectedCiphertextSize = readyDeclaration.expectedSizeBytes,
                            expectedCiphertextSha256 = readyDeclaration.expectedSha256,
                            destination = tempFile,
                        ),
                        networkSession.accessToken,
                    )) {
                        is RecipientBlobDownloadResult.Success -> result
                        is RecipientBlobDownloadResult.Failure -> {
                            if (result.reason == dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH) {
                                if (verifyExactFile(tempPath, readyDeclaration.expectedSizeBytes, readyDeclaration.expectedSha256)) {
                                    RecipientBlobDownloadResult.Success(
                                        ciphertextFile = tempFile,
                                        sizeBytes = readyDeclaration.expectedSizeBytes,
                                    )
                                } else {
                                    // Another first caller may still be
                                    // writing this deterministic path. Keep
                                    // it for the next invocation; never
                                    // discard a possible valid winner.
                                    preserveTemp = true
                                    return@withContext retryable(
                                        IncomingCapsuleAcceptanceRetryReason.DOWNLOAD,
                                    )
                                }
                            } else {
                                return@withContext if (result.retryable) {
                                    retryable(IncomingCapsuleAcceptanceRetryReason.DOWNLOAD)
                                } else {
                                    rejected(IncomingCapsuleAcceptanceRejectionReason.DOWNLOAD_REJECTED)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.DOWNLOAD)
                }
            }

            if (!sameNormalizedPath(downloaded.ciphertextFile.toPath(), tempPath) ||
                downloaded.sizeBytes != readyDeclaration.expectedSizeBytes ||
                !isNoSymlinkPath(tempPath)
            ) {
                return@withContext rejected(IncomingCapsuleAcceptanceRejectionReason.DOWNLOAD_REJECTED)
            }

            // A11b performs its own current-key/account check before and after
            // crypto. This outer check also binds its file input to this live
            // authenticated session before crypto begins.
            when (val checked = sessionFor(request.ownerUserId, initial = false)) {
                is SessionCheck.Ready -> Unit
                is SessionCheck.Failure -> return@withContext checked.result
            }
            val cryptoResult = try {
                controlAcceptance.accept(
                    IncomingControlIndexAcceptanceRequest(
                        ownerUserId = request.ownerUserId,
                        capsuleId = request.capsuleId,
                        recognitionCiphertextFile = tempFile,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.CRYPTO_ACCEPTANCE)
            }
            when (cryptoResult) {
                is IncomingControlIndexAcceptancePortResult.Retryable ->
                    return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.CRYPTO_ACCEPTANCE)
                is IncomingControlIndexAcceptancePortResult.Rejected -> {
                    return@withContext when (cryptoResult.reason) {
                        IncomingAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
                        IncomingAcceptanceRejectionReason.OWNER_MISMATCH,
                        IncomingAcceptanceRejectionReason.ACCOUNT_CHANGED,
                        -> rejected(IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED)
                        else -> rejected(IncomingCapsuleAcceptanceRejectionReason.CRYPTO_REJECTED)
                    }
                }
                is IncomingControlIndexAcceptancePortResult.Verified -> {
                    val persistenceSession = when (
                        val checked = sessionFor(request.ownerUserId, initial = false)
                    ) {
                        is SessionCheck.Ready -> checked.session
                        is SessionCheck.Failure -> return@withContext checked.result
                    }
                    val persistenceResult = try {
                        verifiedControlIndexPersistence.persist(
                            IncomingVerifiedControlIndexPersistenceRequest(
                                ownerUserId = request.ownerUserId,
                                capsuleId = request.capsuleId,
                                verified = cryptoResult.payload,
                            ),
                            authenticatedOwnerUserId = persistenceSession.ownerUserId,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@withContext retryable(
                            IncomingCapsuleAcceptanceRetryReason.VERIFIED_PAYLOAD_PERSISTENCE,
                        )
                    }
                    when (persistenceResult) {
                        IncomingVerifiedControlIndexPersistenceResult.Durable -> Unit
                        is IncomingVerifiedControlIndexPersistenceResult.Retryable ->
                            return@withContext retryable(
                                IncomingCapsuleAcceptanceRetryReason.VERIFIED_PAYLOAD_PERSISTENCE,
                            )
                        is IncomingVerifiedControlIndexPersistenceResult.Rejected -> {
                            return@withContext when (persistenceResult.reason) {
                                IncomingVerifiedControlIndexPersistenceRejectionReason.OWNER_MISMATCH ->
                                    rejected(IncomingCapsuleAcceptanceRejectionReason.OWNER_MISMATCH)
                                IncomingVerifiedControlIndexPersistenceRejectionReason.ACCOUNT_CHANGED ->
                                    rejected(IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED)
                                IncomingVerifiedControlIndexPersistenceRejectionReason.INVALID_VERIFIED_PAYLOAD ->
                                    rejected(IncomingCapsuleAcceptanceRejectionReason.PERSISTENCE_REJECTED)
                                IncomingVerifiedControlIndexPersistenceRejectionReason.LOCAL_CAPABILITY_UNAVAILABLE ->
                                    rejected(IncomingCapsuleAcceptanceRejectionReason.PERSISTENCE_REJECTED)
                            }
                        }
                    }
                }
            }

            // The account is checked again immediately before the file can
            // leave TEMP. A switch during A12 therefore cannot advance a
            // durable owner row.
            when (val checked = sessionFor(request.ownerUserId, initial = false)) {
                is SessionCheck.Ready -> checked.session
                is SessionCheck.Failure -> return@withContext checked.result
            }
            val adopted = try {
                adoption.adopt(
                    IncomingRecognitionCiphertextAdoptionRequest(
                        ownerUserId = request.ownerUserId,
                        capsuleId = request.capsuleId,
                        blobId = readyDeclaration.recognitionBlobId,
                        expectedSizeBytes = readyDeclaration.expectedSizeBytes,
                        expectedSha256 = readyDeclaration.expectedSha256,
                        sourceTempFile = tempFile,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preserveTemp = true
                return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.ADOPTION)
            }
            val adoptedFile = when (adopted) {
                is IncomingRecognitionCiphertextAdoptionResult.Adopted -> adopted.destination
                is IncomingRecognitionCiphertextAdoptionResult.Failure -> {
                    preserveTemp = adopted.retryable
                    return@withContext if (adopted.retryable) {
                        retryable(IncomingCapsuleAcceptanceRetryReason.ADOPTION)
                    } else {
                        rejected(IncomingCapsuleAcceptanceRejectionReason.ADOPTION_REJECTED)
                    }
                }
            }

            // Re-read the live owner after adoption and pass that exact
            // snapshot to A11c2. Its own public boundary checks it again
            // before any file/Room work.
            val commitSession = when (val checked = sessionFor(request.ownerUserId, initial = false)) {
                is SessionCheck.Ready -> checked.session
                is SessionCheck.Failure -> return@withContext checked.result
            }
            val commitResult = try {
                commit.commit(
                    IncomingIndexAcceptanceCommitRequest(
                        ownerUserId = request.ownerUserId,
                        capsuleId = request.capsuleId,
                        recognitionBlobId = readyDeclaration.recognitionBlobId,
                        expectedSizeBytes = readyDeclaration.expectedSizeBytes,
                        expectedSha256 = readyDeclaration.expectedSha256,
                        durableCiphertext = adoptedFile,
                    ),
                    authenticatedOwnerUserId = commitSession.ownerUserId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext retryable(IncomingCapsuleAcceptanceRetryReason.ROOM_COMMIT)
            }
            when (commitResult) {
                IncomingIndexAcceptanceCommitResult.Committed ->
                    IncomingCapsuleAcceptanceResult.Committed
                IncomingIndexAcceptanceCommitResult.IdempotentReplay ->
                    IncomingCapsuleAcceptanceResult.IdempotentReplay
                is IncomingIndexAcceptanceCommitResult.Failure -> {
                    if (commitResult.retryable) {
                        retryable(IncomingCapsuleAcceptanceRetryReason.ROOM_COMMIT)
                    } else {
                        rejected(IncomingCapsuleAcceptanceRejectionReason.ROOM_COMMIT_REJECTED)
                    }
                }
            }
        } finally {
            if (invocationOwnsTemp && !preserveTemp) {
                deleteExactTemp(tempPath, request.ownerUserId)
            }
        }
                        }
                    }
                }
            }
    }

    /**
     * Proves that an already advanced Room state still has its owner-bound
     * encrypted index bundle before acknowledging replay. The snapshot is
     * deliberately close-only at this boundary and is closed on every exit.
     */
    private suspend fun reconcileAlreadyAccepted(
        request: IncomingCapsuleAcceptanceRequest,
    ): IncomingCapsuleAcceptanceResult {
        val beforeInspection = when (
            val checked = sessionFor(request.ownerUserId, initial = false)
        ) {
            is SessionCheck.Ready -> checked.session
            is SessionCheck.Failure -> return checked.result
        }
        val inspection = try {
            senderIndexBundleInspection.inspect(
                IncomingSenderIndexBundleInspectionRequest(
                    authenticatedOwnerUserId = beforeInspection.ownerUserId,
                    ownerUserId = request.ownerUserId,
                    capsuleId = request.capsuleId,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return retryable(IncomingCapsuleAcceptanceRetryReason.VERIFIED_PAYLOAD_PERSISTENCE)
        }

        val snapshot = (inspection as? IncomingSenderIndexBundleInspectionResult.Available)
            ?.snapshot
        return try {
            when (val checked = sessionFor(request.ownerUserId, initial = false)) {
                is SessionCheck.Ready -> Unit
                is SessionCheck.Failure -> return checked.result
            }
            when (inspection) {
                is IncomingSenderIndexBundleInspectionResult.Available ->
                    IncomingCapsuleAcceptanceResult.IdempotentReplay
                IncomingSenderIndexBundleInspectionResult.Missing,
                IncomingSenderIndexBundleInspectionResult.Invalid,
                -> rejected(IncomingCapsuleAcceptanceRejectionReason.DURABLE_STATE_INVALID)
                is IncomingSenderIndexBundleInspectionResult.Unavailable ->
                    retryable(IncomingCapsuleAcceptanceRetryReason.VERIFIED_PAYLOAD_PERSISTENCE)
            }
        } finally {
            snapshot?.close()
        }
    }

    private suspend fun sessionFor(
        owner: UserId,
        initial: Boolean,
    ): SessionCheck {
        val session = try {
            currentSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SessionCheck.Failure(retryable(IncomingCapsuleAcceptanceRetryReason.SESSION_UNAVAILABLE))
        }
        return when {
            session == null && initial ->
                SessionCheck.Failure(rejected(IncomingCapsuleAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER))
            session == null ->
                SessionCheck.Failure(rejected(IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED))
            session.ownerUserId != owner && initial ->
                SessionCheck.Failure(rejected(IncomingCapsuleAcceptanceRejectionReason.OWNER_MISMATCH))
            session.ownerUserId != owner ->
                SessionCheck.Failure(rejected(IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED))
            else -> SessionCheck.Ready(session)
        }
    }

    private suspend fun loadDeclaration(
        request: IncomingCapsuleAcceptanceRequest,
    ): DeclarationLoad {
        val owner = request.ownerUserId.toRestString()
        val capsuleId = request.capsuleId.toRestString()
        val capsule = incomingCapsuleDao.getByCapsuleIdAndOwner(capsuleId, owner)
            ?: return DeclarationLoad.Rejected(
                IncomingCapsuleAcceptanceRejectionReason.CAPSULE_METADATA_MISSING,
            )
        if (capsule.ownerUserId != owner || capsule.capsuleId != capsuleId ||
            capsule.recipientUserId != owner ||
            capsule.protocolVersion != ProtocolV1Limits.PROTOCOL_VERSION ||
            capsule.serverStatus != READY_STATUS
        ) {
            return DeclarationLoad.Rejected(IncomingCapsuleAcceptanceRejectionReason.CAPSULE_STATE_INVALID)
        }

        val envelope = incomingEnvelopeDao.getByCapsuleIdAndOwner(capsuleId, owner)
            ?: return DeclarationLoad.Rejected(
                IncomingCapsuleAcceptanceRejectionReason.ENVELOPE_METADATA_MISSING,
            )
        if (envelope.ownerUserId != owner || envelope.capsuleId != capsuleId ||
            envelope.recipientKeyBundleId != capsule.recipientEncryptionKeyBundleId
        ) {
            return DeclarationLoad.Rejected(IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID)
        }

        val recognitionRows = blobCacheDao.getAllByCapsuleIdAndOwner(capsuleId, owner)
            .filter { it.kind == CapsuleArtifactKind.RECOGNITION_MANIFEST.name }
        if (recognitionRows.size != 1) {
            return DeclarationLoad.Rejected(
                IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
            )
        }
        val recognition = recognitionRows.single()
        if (recognition.ownerUserId != owner || recognition.capsuleId != capsuleId ||
            recognition.ordinal != null ||
            recognition.expectedSizeBytes !in 1L..ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES ||
            recognition.expectedSha256.size != SHA256_BYTES
        ) {
            return DeclarationLoad.Rejected(
                IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
            )
        }
        val recognitionBlobId = try {
            BlobId.parseRest(recognition.blobId)
        } catch (_: IllegalArgumentException) {
            return DeclarationLoad.Rejected(
                IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
            )
        }
        val destination = try {
            derivedIncomingDestination(request.ownerUserId, request.capsuleId, recognitionBlobId)
        } catch (_: UnsafeIncomingPath) {
            return DeclarationLoad.Rejected(IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE)
        }

        val repairedRecognition = if (recognition.localPath != destination.toString()) {
            if (recognition.cacheState != BlobCacheState.DOWNLOADING) {
                return DeclarationLoad.Rejected(
                    IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
                )
            }
            val repaired = blobCacheDao.repairDownloadingRecognitionPathForOwner(
                ownerUserId = owner,
                capsuleId = capsuleId,
                blobId = recognition.blobId,
                expectedSizeBytes = recognition.expectedSizeBytes,
                expectedSha256 = recognition.expectedSha256,
                oldLocalPath = recognition.localPath,
                newLocalPath = destination.toString(),
            )
            if (repaired == 1) {
                recognition.copy(localPath = destination.toString())
            } else {
                // A concurrent repair may have won the exact CAS. Re-read
                // only the owner/capsule scope and accept it only if every
                // immutable binding still matches. The old path is never
                // opened, verified, deleted, or passed to another layer.
                val latestRows = blobCacheDao.getAllByCapsuleIdAndOwner(capsuleId, owner)
                    .filter { it.kind == CapsuleArtifactKind.RECOGNITION_MANIFEST.name }
                if (latestRows.size != 1) {
                    return DeclarationLoad.Rejected(
                        IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
                    )
                }
                latestRows.single()
            }
        } else {
            recognition
        }
        if (!isExactRecognitionBinding(
                repairedRecognition,
                owner = owner,
                capsuleId = capsuleId,
                blobId = recognitionBlobId,
                expectedSizeBytes = recognition.expectedSizeBytes,
                expectedSha256 = recognition.expectedSha256,
            ) || repairedRecognition.localPath != destination.toString()
        ) {
            return DeclarationLoad.Rejected(
                IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
            )
        }

        return when {
            capsule.materialState == LocalMaterialState.DISCOVERED &&
                repairedRecognition.cacheState == BlobCacheState.DOWNLOADING ->
                DeclarationLoad.Ready(
                    RecognitionDeclaration(
                        recognitionBlobId = recognitionBlobId,
                        expectedSizeBytes = repairedRecognition.expectedSizeBytes,
                        expectedSha256 = repairedRecognition.expectedSha256.copyOf(),
                    ),
                )
            capsule.materialState == LocalMaterialState.INDEX_CACHED &&
                repairedRecognition.cacheState == BlobCacheState.CACHED -> {
                if (verifyExactFile(
                        destination,
                        repairedRecognition.expectedSizeBytes,
                        repairedRecognition.expectedSha256,
                    )
                ) {
                    DeclarationLoad.AlreadyAccepted
                } else {
                    DeclarationLoad.Rejected(
                        IncomingCapsuleAcceptanceRejectionReason.DURABLE_STATE_INVALID,
                    )
                }
            }
            else -> DeclarationLoad.Rejected(IncomingCapsuleAcceptanceRejectionReason.CAPSULE_STATE_INVALID)
        }
    }

    private fun prepareRecoveryTempPath(
        owner: UserId,
        capsule: CapsuleId,
        blob: BlobId,
        expectedSize: Long,
        expectedSha256: ByteArray,
    ): TempPreparation {
        val tempRoot = try {
            roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP)
                .toPath().toAbsolutePath().normalize()
        } catch (_: IllegalStateException) {
            // AccountScopedFileRoots rejects an escaping canonical child
            // (including an existing redirected TEMP root). Keep that
            // containment failure in the coordinator's typed safe-rejection
            // contract instead of allowing it to escape as a crash.
            throw UnsafeTempPath()
        }
        val parent = tempRoot.resolve("incoming-recognition")
            .resolve(capsule.toRestString())
            .resolve("blobs")
            .normalize()
        val path = parent.resolve("${blob.toRestString()}.ciphertext.tmp").normalize()
        if (!isContained(parent, tempRoot) || !isContained(path, tempRoot)) {
            throw UnsafeTempPath()
        }

        // Materialise the fixed root before its owned descendants. Each
        // component is created independently after a NOFOLLOW inspection, so
        // a missing descendant is recoverable while an existing symlink or
        // non-directory remains unsafe. Some Android providers report a
        // missing component as a generic IOException from readAttributes;
        // ensureNoSymlinkDirectory treats that as a create candidate and lets
        // the exact create/recheck decide whether it is available.
        ensureNoSymlinkDirectory(tempRoot)
        ensureNoSymlinkDirectory(parent)

        return when (inspectFile(path, expectedSize, expectedSha256, parentPrepared = true)) {
            TempInspection.MISSING -> TempPreparation.Ready(path, existingVerified = false)
            TempInspection.MATCH -> TempPreparation.Ready(path, existingVerified = true)
            TempInspection.MISMATCH -> TempPreparation.Invalid(path)
            TempInspection.UNAVAILABLE -> TempPreparation.Unavailable(path)
            TempInspection.UNSAFE -> throw UnsafeTempPath()
        }
    }

    private fun ensureNoSymlinkDirectory(path: Path) {
        when (inspectDirectory(path)) {
            DirectoryInspection.SAFE -> return
            DirectoryInspection.UNSAFE -> throw UnsafeTempPath()
            DirectoryInspection.MISSING,
            DirectoryInspection.UNAVAILABLE,
            -> Unit
        }

        val parent = path.parent ?: throw UnsafeTempPath()
        if (parent == path) throw UnsafeTempPath()
        ensureNoSymlinkDirectory(parent)

        try {
            Files.createDirectory(path)
        } catch (_: FileAlreadyExistsException) {
            // Re-read below. A concurrent replacement by a symlink or a file
            // must be treated as unsafe, never as a successful mkdir.
        } catch (failure: IOException) {
            when (inspectDirectory(path)) {
                DirectoryInspection.SAFE -> return
                DirectoryInspection.UNSAFE -> throw UnsafeTempPath()
                DirectoryInspection.MISSING,
                DirectoryInspection.UNAVAILABLE,
                -> throw failure
            }
        }

        when (inspectDirectory(path)) {
            DirectoryInspection.SAFE -> Unit
            DirectoryInspection.UNSAFE -> throw UnsafeTempPath()
            DirectoryInspection.MISSING -> throw IOException("owned directory was not created")
            DirectoryInspection.UNAVAILABLE -> throw IOException("owned directory is unavailable")
        }
    }

    private fun deleteExactTemp(path: Path, owner: UserId) {
        val root = runCatching {
            roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP)
                .toPath().toAbsolutePath().normalize()
        }.getOrNull() ?: return
        if (!isContained(path, root)) return
        runCatching { Files.deleteIfExists(path) }
    }

    private fun derivedIncomingDestination(
        owner: UserId,
        capsule: CapsuleId,
        blob: BlobId,
    ): Path {
        val destination = try {
            roots.incomingCiphertextPath(owner, capsule, blob)
        } catch (_: IllegalStateException) {
            throw UnsafeIncomingPath()
        }
        val root = try {
            roots.child(owner, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)
                .toPath().toAbsolutePath().normalize()
        } catch (_: IllegalStateException) {
            throw UnsafeIncomingPath()
        }
        try {
            // Keep the root check ordered and NOFOLLOW: an absent incoming
            // root is materialised for the next recovery step, while an
            // existing symlink/non-directory is rejected before any write.
            ensureNoSymlinkDirectory(root)
        } catch (_: UnsafeTempPath) {
            throw UnsafeIncomingPath()
        }
        return destination
    }

    private fun isExactRecognitionBinding(
        row: BlobCacheEntity,
        owner: String,
        capsuleId: String,
        blobId: BlobId,
        expectedSizeBytes: Long,
        expectedSha256: ByteArray,
    ): Boolean = row.ownerUserId == owner &&
        row.capsuleId == capsuleId &&
        row.blobId == blobId.toRestString() &&
        row.kind == CapsuleArtifactKind.RECOGNITION_MANIFEST.name &&
        row.ordinal == null &&
        row.expectedSizeBytes == expectedSizeBytes &&
        row.expectedSizeBytes in 1L..ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES &&
        row.expectedSha256.contentEquals(expectedSha256) &&
        row.expectedSha256.size == SHA256_BYTES

    private fun verifyExactFile(path: Path, expectedSize: Long, expectedSha256: ByteArray): Boolean {
        return inspectFile(path, expectedSize, expectedSha256) == TempInspection.MATCH
    }

    private fun inspectFile(
        path: Path,
        expectedSize: Long,
        expectedSha256: ByteArray,
        parentPrepared: Boolean = false,
    ): TempInspection {
        // When the exact parent was just created/revalidated above, inspect
        // the leaf directly. This permits a genuinely missing temp file even
        // on providers that report that missing leaf as generic IOException;
        // the NOFOLLOW leaf read below still rejects a symlink/non-regular
        // path. Existing callers retain the full ancestor check.
        if (!parentPrepared && !isNoSymlinkPath(path)) return TempInspection.UNSAFE
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) {
            return TempInspection.MISSING
        } catch (_: IOException) {
            if (parentPrepared && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                // Android providers may report an absent leaf with plain
                // IOException rather than NoSuchFileException. The parent
                // was just verified as a real directory, so this exact
                // no-follow existence check safely distinguishes that case
                // from an unavailable existing entry.
                return TempInspection.MISSING
            }
            return TempInspection.UNAVAILABLE
        } catch (_: SecurityException) {
            return TempInspection.UNAVAILABLE
        }
        if (attributes.isSymbolicLink || !attributes.isRegularFile) return TempInspection.UNSAFE
        if (attributes.size() != expectedSize) return TempInspection.MISMATCH
        return try {
            val digest = MessageDigest.getInstance(SHA256)
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) return TempInspection.MISMATCH
                    total += read
                    if (total > expectedSize) return TempInspection.MISMATCH
                    digest.update(buffer, 0, read)
                }
                if (total != expectedSize) {
                    TempInspection.MISMATCH
                } else if (MessageDigest.isEqual(digest.digest(), expectedSha256)) {
                    TempInspection.MATCH
                } else {
                    TempInspection.MISMATCH
                }
            }
        } catch (_: IOException) {
            TempInspection.UNAVAILABLE
        } catch (_: SecurityException) {
            TempInspection.UNAVAILABLE
        }
    }

    private fun inspectDirectory(path: Path): DirectoryInspection = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            DirectoryInspection.UNSAFE
        } else {
            DirectoryInspection.SAFE
        }
    } catch (_: java.nio.file.NoSuchFileException) {
        DirectoryInspection.MISSING
    } catch (_: IOException) {
        DirectoryInspection.UNAVAILABLE
    } catch (_: SecurityException) {
        DirectoryInspection.UNAVAILABLE
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

    private fun sameNormalizedPath(first: Path, second: Path): Boolean =
        first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()

    private fun isContained(candidate: Path, root: Path): Boolean =
        candidate != root && candidate.startsWith(root)

    private fun stripe(key: String): Int =
        (key.hashCode() and Int.MAX_VALUE) % ATTEMPT_LOCK_STRIPES

    private fun retryable(reason: IncomingCapsuleAcceptanceRetryReason) =
        IncomingCapsuleAcceptanceResult.Retryable(reason)

    private fun rejected(reason: IncomingCapsuleAcceptanceRejectionReason) =
        IncomingCapsuleAcceptanceResult.Rejected(reason)

    private sealed interface SessionCheck {
        data class Ready(val session: IncomingSyncSession) : SessionCheck
        data class Failure(val result: IncomingCapsuleAcceptanceResult) : SessionCheck
    }

    private sealed interface DeclarationLoad {
        data class Ready(val declaration: RecognitionDeclaration) : DeclarationLoad
        data object AlreadyAccepted : DeclarationLoad
        data class Rejected(val reason: IncomingCapsuleAcceptanceRejectionReason) : DeclarationLoad
    }

    private sealed interface TempPreparation {
        data class Ready(val path: Path, val existingVerified: Boolean) : TempPreparation
        data class Invalid(val path: Path) : TempPreparation
        data class Unavailable(val path: Path) : TempPreparation
    }

    private enum class TempInspection {
        MISSING,
        MATCH,
        MISMATCH,
        UNSAFE,
        UNAVAILABLE,
    }

    private enum class DirectoryInspection {
        SAFE,
        MISSING,
        UNSAFE,
        UNAVAILABLE,
    }

    private data class RecognitionDeclaration(
        val recognitionBlobId: BlobId,
        val expectedSizeBytes: Long,
        val expectedSha256: ByteArray,
    )

    private class UnsafeTempPath : IllegalStateException()

    private class UnsafeIncomingPath : IllegalStateException()

    private companion object {
        const val READY_STATUS = "READY"
        const val SHA256_BYTES = 32
        const val SHA256 = "SHA-256"
        const val STREAM_BUFFER_BYTES = 32 * 1024
        const val ATTEMPT_LOCK_STRIPES = 32

        // The outer probe lock makes the declaration lookup itself part of
        // the in-process one-capsule critical section. Once the immutable
        // recognition blob is known, the fixed striped attempt lock covers
        // the rest of the pipeline without retaining one Mutex per ID.
        val capsuleProbeLocks = Array(ATTEMPT_LOCK_STRIPES) { Mutex() }
        val attemptLocks = Array(ATTEMPT_LOCK_STRIPES) { Mutex() }
    }
}
