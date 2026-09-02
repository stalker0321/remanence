package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * The account/capsule/blob file adoption request shared by recognition,
 * content-manifest, and photo ciphertext.
 *
 * Callers must provide the artifact kind and, for photos, the canonical
 * ordinal. Recognition callers use [IncomingRecognitionCiphertextAdoptionRequest]
 * so that the legacy entry point cannot be widened to another artifact kind.
 */
class IncomingCiphertextAdoptionRequest(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val blobId: BlobId,
    val expectedSizeBytes: Long,
    expectedSha256: ByteArray,
    sourceTempFile: File,
    val artifactKind: CapsuleArtifactKind,
    val ordinal: Int,
) {
    private val expectedSha256Snapshot = expectedSha256.copyOf()

    /** A caller-owned source handle; the adopter never accepts a destination handle. */
    val sourceTempFile: File = sourceTempFile

    val expectedSha256: ByteArray
        get() = expectedSha256Snapshot.copyOf()

    init {
        require(expectedSizeBytes in 1L..maxCiphertextBytes(artifactKind)) {
            "ciphertext size exceeds the artifact-kind protocol limit"
        }
        require(
            when (artifactKind) {
                CapsuleArtifactKind.PHOTO ->
                    ordinal in ProtocolV1Limits.PHOTO_ORDINAL_MIN..ProtocolV1Limits.PHOTO_ORDINAL_MAX
                CapsuleArtifactKind.RECOGNITION_MANIFEST,
                CapsuleArtifactKind.CONTENT_MANIFEST,
                -> ordinal == ProtocolV1Limits.NON_PHOTO_ORDINAL
            },
        ) { "artifact ordinal is invalid" }
        require(expectedSha256Snapshot.size == SHA256_BYTES) {
            "ciphertext hash must be SHA-256"
        }
        require(sourceTempFile.path.isNotEmpty()) { "source file must be resolvable" }
        require(sourceTempFile.isAbsolute) { "source file must be absolute" }
    }

    override fun toString(): String =
        "IncomingCiphertextAdoptionRequest(<redacted>)"

    private companion object {
        const val SHA256_BYTES = 32

        fun maxCiphertextBytes(
            artifactKind: CapsuleArtifactKind,
        ): Long = when (artifactKind) {
            CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.CONTENT_MANIFEST ->
                ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.PHOTO ->
                ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES
        }
    }
}

/** Generic failure vocabulary shared by every incoming ciphertext kind. */
enum class IncomingCiphertextAdoptionFailure {
    SOURCE_OUTSIDE_OWNER_TEMP,
    SOURCE_PATH_UNSAFE,
    SOURCE_MISSING,
    SOURCE_NOT_REGULAR,
    SOURCE_INTEGRITY_FAILED,
    DESTINATION_PATH_UNSAFE,
    DESTINATION_CONFLICT,
    ATOMIC_MOVE_UNAVAILABLE,
    DURABILITY_UNAVAILABLE,
    LOCAL_STORAGE,
}

/** Compatibility result retained by the accepted A11 API. */
sealed interface IncomingRecognitionCiphertextAdoptionResult {
    /** The verified durable destination capability to be consumed by A11c2. */
    class Adopted internal constructor(
        val destination: DurableIncomingCiphertextFile,
    ) : IncomingRecognitionCiphertextAdoptionResult {
        override fun toString(): String =
            "IncomingRecognitionCiphertextAdoptionResult.Adopted(<redacted>)"
    }

    data class Failure(
        val reason: IncomingCiphertextAdoptionFailure,
        val retryable: Boolean,
    ) : IncomingRecognitionCiphertextAdoptionResult
}

/**
 * The fixed-shape A11 recognition request. It intentionally has no artifact
 * kind or ordinal input: the legacy entry point always adopts the
 * RECOGNITION_MANIFEST blob at the non-photo ordinal and enforces its smaller
 * protocol limit before reaching the shared filesystem algorithm.
 */
class IncomingRecognitionCiphertextAdoptionRequest(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val blobId: BlobId,
    val expectedSizeBytes: Long,
    expectedSha256: ByteArray,
    sourceTempFile: File,
) {
    private val expectedSha256Snapshot = expectedSha256.copyOf()

    val sourceTempFile: File = sourceTempFile

    val expectedSha256: ByteArray
        get() = expectedSha256Snapshot.copyOf()

    init {
        require(expectedSizeBytes in 1L..ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES) {
            "recognition ciphertext size exceeds the protocol limit"
        }
        require(expectedSha256Snapshot.size == SHA256_BYTES) {
            "ciphertext hash must be SHA-256"
        }
        require(sourceTempFile.path.isNotEmpty()) { "source file must be resolvable" }
        require(sourceTempFile.isAbsolute) { "source file must be absolute" }
    }

    internal fun asGeneric(): IncomingCiphertextAdoptionRequest = IncomingCiphertextAdoptionRequest(
        ownerUserId = ownerUserId,
        capsuleId = capsuleId,
        blobId = blobId,
        expectedSizeBytes = expectedSizeBytes,
        expectedSha256 = expectedSha256Snapshot,
        sourceTempFile = sourceTempFile,
        artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
        ordinal = ProtocolV1Limits.NON_PHOTO_ORDINAL,
    )

    override fun toString(): String =
        "IncomingRecognitionCiphertextAdoptionRequest(<redacted>)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/** Source-compatible name for the accepted A11 recognition failure enum. */
typealias IncomingRecognitionCiphertextAdoptionFailure = IncomingCiphertextAdoptionFailure

/** Redacted generic result for every incoming ciphertext artifact kind. */
sealed interface IncomingCiphertextAdoptionResult {
    class Adopted internal constructor(
        val destination: DurableIncomingCiphertextFile,
    ) : IncomingCiphertextAdoptionResult {
        override fun toString(): String =
            "IncomingCiphertextAdoptionResult.Adopted(<redacted>)"
    }

    data class Failure(
        val reason: IncomingCiphertextAdoptionFailure,
        val retryable: Boolean,
    ) : IncomingCiphertextAdoptionResult
}

/**
 * An owner/capsule/blob-bound capability for the ciphertext file installed by
 * [IncomingRecognitionCiphertextAdopter]. The path is derived by the adopter;
 * callers cannot select or replace the durable destination.
 */
class DurableIncomingCiphertextFile internal constructor(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val blobId: BlobId,
    private val file: File,
) {
    /** The verified, account-scoped file capability needed by A11c2. */
    fun asFile(): File = file

    override fun toString(): String = "DurableIncomingCiphertextFile(<redacted>)"
}

/**
 * Crash-safe adoption of one already verified incoming ciphertext.
 *
 * The boundary deliberately has no Room or material-state dependency. It
 * verifies the source immediately before adoption, installs only the fixed
 * owner/capsule/blob-derived destination, and never falls back to copying a
 * file when an atomic no-replace link is unavailable. A process retry treats an already
 * installed, independently re-verified destination as an idempotent success.
 */
class IncomingRecognitionCiphertextAdopter internal constructor(
    private val roots: AccountScopedFileRoots,
    private val fileSystem: IncomingCiphertextFileSystem,
) {
    constructor(roots: AccountScopedFileRoots) : this(roots, RealIncomingCiphertextFileSystem)

    /**
     * Adopts [request.sourceTempFile] into the fixed incoming-ciphertext
     * layout. File work runs on IO, while cancellation is checked between
     * bounded read chunks and is never converted into a failure result.
     */
    suspend fun adopt(
        request: IncomingRecognitionCiphertextAdoptionRequest,
    ): IncomingRecognitionCiphertextAdoptionResult = adopt(request.asGeneric())

    suspend fun adopt(
        request: IncomingCiphertextAdoptionRequest,
    ): IncomingRecognitionCiphertextAdoptionResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        val paths = try {
            resolvePaths(request)
        } catch (_: SecurityException) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.SOURCE_PATH_UNSAFE,
                retryable = false,
            )
        } catch (_: IllegalStateException) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.SOURCE_PATH_UNSAFE,
                retryable = false,
            )
        }

        if (!isSafePath(paths.tempRoot) || !isSafePath(paths.source)) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.SOURCE_PATH_UNSAFE,
                retryable = false,
            )
        }
        if (!isContained(paths.source, paths.tempRoot) || paths.source == paths.tempRoot) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.SOURCE_OUTSIDE_OWNER_TEMP,
                retryable = false,
            )
        }
        if (!isSafePath(paths.incomingRoot) || !isSafePath(paths.destination)) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
                retryable = false,
            )
        }
        if (!isContained(paths.destination, paths.incomingRoot)) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
                retryable = false,
            )
        }

        try {
            ensureDestinationParent(paths.incomingRoot, paths.destination.parent!!)
        } catch (failure: PathFailure) {
            return@withContext failure(failure.reason, retryable = false)
        } catch (failure: IOException) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                retryable = true,
            )
        } catch (_: SecurityException) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                retryable = true,
            )
        } catch (_: UnsupportedOperationException) {
            return@withContext failure(
                IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                retryable = true,
            )
        }

        // The monitor closes the check/link race between concurrent adopters
        // in this Android process. createLink is itself atomic and refuses an
        // existing destination; no copy or replacement fallback is permitted.
        val operationContext = coroutineContext
        synchronized(destinationLock(paths.destination)) {
            operationContext.ensureActive()
            val checkCancellation = { operationContext.ensureActive() }

            when (verifyFile(paths.destination, request, checkCancellation)) {
                FileVerification.MISSING -> Unit
                FileVerification.MATCH -> {
                    return@withContext finishExistingDestination(
                        request,
                        paths,
                        checkCancellation,
                    )
                }
                FileVerification.SYMLINK,
                FileVerification.NOT_REGULAR,
                FileVerification.MISMATCH,
                -> {
                    return@withContext failure(
                        IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_CONFLICT,
                        retryable = false,
                    )
                }
                FileVerification.READ_FAILURE -> {
                    return@withContext failure(
                        IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                        retryable = true,
                    )
                }
            }

            when (val sourceState = verifyFile(paths.source, request, checkCancellation)) {
                FileVerification.MISSING -> {
                    return@withContext failure(
                        IncomingRecognitionCiphertextAdoptionFailure.SOURCE_MISSING,
                        retryable = true,
                    )
                }
                FileVerification.SYMLINK -> {
                    return@withContext failure(
                        IncomingRecognitionCiphertextAdoptionFailure.SOURCE_PATH_UNSAFE,
                        retryable = false,
                    )
                }
                FileVerification.NOT_REGULAR -> {
                    return@withContext failure(
                        IncomingRecognitionCiphertextAdoptionFailure.SOURCE_NOT_REGULAR,
                        retryable = false,
                    )
                }
                FileVerification.MISMATCH -> {
                    return@withContext failure(
                        IncomingRecognitionCiphertextAdoptionFailure.SOURCE_INTEGRITY_FAILED,
                        retryable = false,
                    )
                }
                FileVerification.READ_FAILURE -> {
                    return@withContext failure(
                        IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                        retryable = true,
                    )
                }
                FileVerification.MATCH -> Unit
            }

            try {
                fileSystem.atomicNoReplaceLink(paths.source, paths.destination)
            } catch (_: FileAlreadyExistsException) {
                return@withContext reconcileConcurrentWinner(
                    request,
                    paths,
                    paths.destination,
                    checkCancellation,
                )
            } catch (_: FileSystemException) {
                return@withContext failure(
                    IncomingRecognitionCiphertextAdoptionFailure.ATOMIC_MOVE_UNAVAILABLE,
                    retryable = false,
                )
            } catch (_: SecurityException) {
                return@withContext failure(
                    IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                    retryable = true,
                )
            } catch (_: IOException) {
                // Some providers report a concurrent target appearance as a
                // generic IOException. Re-read it before choosing a result.
                return@withContext reconcileConcurrentWinnerOrStorageFailure(
                    request,
                    paths,
                    paths.destination,
                    checkCancellation,
                )
            } catch (_: UnsupportedOperationException) {
                return@withContext failure(
                    IncomingRecognitionCiphertextAdoptionFailure.ATOMIC_MOVE_UNAVAILABLE,
                    retryable = false,
                )
            }

            return@withContext finishInstalledDestination(
                request,
                paths,
                checkCancellation,
            )
        }
    }

    private fun resolvePaths(
        request: IncomingCiphertextAdoptionRequest,
    ): AdoptionPaths {
        val tempRoot = roots.child(
            request.ownerUserId,
            AccountScopedFileRoots.ChildRoot.TEMP,
        ).toPath().toAbsolutePath().normalize()
        val incomingRoot = roots.child(
            request.ownerUserId,
            AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT,
        ).toPath().toAbsolutePath().normalize()
        val source = request.sourceTempFile.toPath().toAbsolutePath().normalize()
        val destination = roots.incomingCiphertextPath(
            owner = request.ownerUserId,
            capsule = request.capsuleId,
            blob = request.blobId,
        )
        return AdoptionPaths(tempRoot, incomingRoot, source, destination)
    }

    private fun ensureDestinationParent(
        incomingRoot: Path,
        destinationParent: Path,
    ) {
        if (!isContained(destinationParent, incomingRoot)) {
            throw PathFailure(
                IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
            )
        }
        if (!isSafePath(incomingRoot) || !isSafePath(destinationParent)) {
            throw PathFailure(
                IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
            )
        }
        fileSystem.makeDirectories(destinationParent)
        if (!isDirectoryChainSafe(incomingRoot, destinationParent)) {
            throw PathFailure(
                IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
            )
        }
    }

    private fun isDirectoryChainSafe(root: Path, leaf: Path): Boolean {
        if (!isContained(leaf, root)) return false
        var current = root
        if (!directoryIsSafe(current)) return false
        for (segment in root.relativize(leaf)) {
            current = current.resolve(segment)
            if (!directoryIsSafe(current)) return false
        }
        return true
    }

    private fun directoryIsSafe(path: Path): Boolean {
        val attrs = fileSystem.attributes(path) ?: return false
        return !attrs.isSymbolicLink && attrs.isDirectory && isSafePath(path)
    }

    private fun verifyFile(
        path: Path,
        request: IncomingCiphertextAdoptionRequest,
        checkCancellation: () -> Unit,
    ): FileVerification {
        checkCancellation()
        val attrs = try {
            fileSystem.attributes(path)
        } catch (_: SecurityException) {
            return FileVerification.READ_FAILURE
        } catch (_: IOException) {
            return FileVerification.READ_FAILURE
        }
        if (attrs == null) return FileVerification.MISSING
        if (attrs.isSymbolicLink) return FileVerification.SYMLINK
        if (!attrs.isRegularFile) return FileVerification.NOT_REGULAR
        if (attrs.size() != request.expectedSizeBytes) return FileVerification.MISMATCH

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        try {
            fileSystem.openRead(path).use { input ->
                while (true) {
                    checkCancellation()
                    val remaining = request.expectedSizeBytes - total
                    val readLimit = minOf(buffer.size.toLong(), remaining + 1L).toInt()
                    val read = input.read(buffer, 0, readLimit)
                    if (read < 0) break
                    if (read == 0 || read.toLong() > remaining) return FileVerification.MISMATCH
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return FileVerification.READ_FAILURE
        } catch (_: SecurityException) {
            return FileVerification.READ_FAILURE
        }
        if (total != request.expectedSizeBytes) return FileVerification.MISMATCH
        return if (MessageDigest.isEqual(digest.digest(), request.expectedSha256)) {
            FileVerification.MATCH
        } else {
            FileVerification.MISMATCH
        }
    }

    private fun finishExistingDestination(
        request: IncomingCiphertextAdoptionRequest,
        paths: AdoptionPaths,
        checkCancellation: () -> Unit,
    ): IncomingRecognitionCiphertextAdoptionResult =
        finishDurableDestination(request, paths, checkCancellation)

    private fun finishInstalledDestination(
        request: IncomingCiphertextAdoptionRequest,
        paths: AdoptionPaths,
        checkCancellation: () -> Unit,
    ): IncomingRecognitionCiphertextAdoptionResult {
        return when (verifyFile(paths.destination, request, checkCancellation)) {
            FileVerification.MATCH -> finishDurableDestination(request, paths, checkCancellation)
            FileVerification.MISSING,
            FileVerification.READ_FAILURE,
            -> failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
            FileVerification.SYMLINK,
            FileVerification.NOT_REGULAR,
            FileVerification.MISMATCH,
            -> failure(
                IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_CONFLICT,
                retryable = false,
            )
        }
    }

    private fun finishDurableDestination(
        request: IncomingCiphertextAdoptionRequest,
        paths: AdoptionPaths,
        checkCancellation: () -> Unit,
    ): IncomingRecognitionCiphertextAdoptionResult {
        checkCancellation()
        try {
            // This force is mandatory. Unsupported mandatory durability is a
            // fail-closed result, never a successful adoption.
            fileSystem.forceFile(paths.destination)
        } catch (_: UnsupportedOperationException) {
            return failure(
                IncomingRecognitionCiphertextAdoptionFailure.DURABILITY_UNAVAILABLE,
                retryable = false,
            )
        } catch (_: SecurityException) {
            return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
        } catch (_: IOException) {
            return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
        }

        // Directory force is best effort. If it fails, preserve the verified
        // source so a retry can repeat durability and cleanup safely.
        if (!forceDirectoryBestEffort(paths.destination.parent!!)) {
            return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
        }

        when (verifyFile(paths.source, request, checkCancellation)) {
            FileVerification.MATCH -> {
                try {
                    fileSystem.deleteIfExists(paths.source)
                } catch (_: SecurityException) {
                    return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
                } catch (_: IOException) {
                    return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
                }
                // The destination is already durable. The source's parent,
                // not the incoming destination parent, records this unlink.
                if (!forceDirectoryBestEffort(paths.source.parent!!)) {
                    return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
                }
            }
            FileVerification.MISSING -> {
                // A prior attempt may have unlinked the source and then
                // failed to persist that unlink. Close that durability gap
                // on replay without requiring the source to reappear.
                if (!forceDirectoryBestEffort(paths.source.parent!!)) {
                    return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
                }
            }
            FileVerification.SYMLINK,
            FileVerification.NOT_REGULAR,
            FileVerification.MISMATCH,
            -> Unit
            FileVerification.READ_FAILURE -> {
                return failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
            }
        }

        return IncomingRecognitionCiphertextAdoptionResult.Adopted(
            destinationCapability(request, paths.destination),
        )
    }

    private fun forceDirectoryBestEffort(directory: Path): Boolean = try {
        fileSystem.forceDirectory(directory)
        true
    } catch (_: UnsupportedOperationException) {
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun reconcileConcurrentWinner(
        request: IncomingCiphertextAdoptionRequest,
        paths: AdoptionPaths,
        destination: Path,
        checkCancellation: () -> Unit,
    ): IncomingRecognitionCiphertextAdoptionResult =
        when (verifyFile(destination, request, checkCancellation)) {
            FileVerification.MATCH -> finishDurableDestination(request, paths, checkCancellation)
            FileVerification.MISSING,
            FileVerification.READ_FAILURE,
            -> failure(IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE, retryable = true)
            FileVerification.SYMLINK,
            FileVerification.NOT_REGULAR,
            FileVerification.MISMATCH,
            -> failure(
                IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_CONFLICT,
                retryable = false,
            )
        }

    private fun reconcileConcurrentWinnerOrStorageFailure(
        request: IncomingCiphertextAdoptionRequest,
        paths: AdoptionPaths,
        destination: Path,
        checkCancellation: () -> Unit,
    ): IncomingRecognitionCiphertextAdoptionResult {
        val state = verifyFile(destination, request, checkCancellation)
        return if (state == FileVerification.MATCH) {
            finishDurableDestination(request, paths, checkCancellation)
        } else {
            reconcileConcurrentWinner(request, paths, destination, checkCancellation)
        }
    }

    private fun destinationCapability(
        request: IncomingCiphertextAdoptionRequest,
        destination: Path,
    ): DurableIncomingCiphertextFile = DurableIncomingCiphertextFile(
        ownerUserId = request.ownerUserId,
        capsuleId = request.capsuleId,
        blobId = request.blobId,
        file = destination.toFile(),
    )

    private fun failure(
        reason: IncomingRecognitionCiphertextAdoptionFailure,
        retryable: Boolean,
    ): IncomingRecognitionCiphertextAdoptionResult.Failure =
        IncomingRecognitionCiphertextAdoptionResult.Failure(reason, retryable)

    private fun isSafePath(path: Path): Boolean {
        var current: Path? = path
        return try {
            while (current != null) {
                val examined = current
                val attrs = fileSystem.attributes(examined) ?: run {
                    current = examined.parent
                    continue
                }
                if (attrs.isSymbolicLink) return false
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

    private data class AdoptionPaths(
        val tempRoot: Path,
        val incomingRoot: Path,
        val source: Path,
        val destination: Path,
    )

    private class PathFailure(
        val reason: IncomingRecognitionCiphertextAdoptionFailure,
    ) : IllegalStateException()

    private enum class FileVerification {
        MISSING,
        MATCH,
        SYMLINK,
        NOT_REGULAR,
        MISMATCH,
        READ_FAILURE,
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 32 * 1024
        private const val DESTINATION_LOCK_STRIPES = 32
        private val destinationLocks = Array(DESTINATION_LOCK_STRIPES) { Any() }

        fun destinationLock(destination: Path): Any =
            destinationLocks[(destination.toString().hashCode() and Int.MAX_VALUE) % DESTINATION_LOCK_STRIPES]
    }
}

/**
 * Thin generic adapter over the accepted A11 filesystem algorithm. It maps
 * only the typed result vocabulary; recognition, content, and photo adoption
 * all execute the same implementation.
 */
class IncomingCiphertextAdopter internal constructor(
    roots: AccountScopedFileRoots,
    fileSystem: IncomingCiphertextFileSystem,
) {
    constructor(roots: AccountScopedFileRoots) :
        this(roots, RealIncomingCiphertextFileSystem)

    private val delegate = IncomingRecognitionCiphertextAdopter(roots, fileSystem)

    suspend fun adopt(
        request: IncomingCiphertextAdoptionRequest,
    ): IncomingCiphertextAdoptionResult = when (val result = delegate.adopt(request)) {
        is IncomingRecognitionCiphertextAdoptionResult.Adopted ->
            IncomingCiphertextAdoptionResult.Adopted(result.destination)
        is IncomingRecognitionCiphertextAdoptionResult.Failure ->
            IncomingCiphertextAdoptionResult.Failure(
                reason = result.reason,
                retryable = result.retryable,
            )
    }
}

internal data class IncomingFileAttributes(
    val isSymbolicLink: Boolean,
    val isRegularFile: Boolean,
    val isDirectory: Boolean,
    private val byteSize: Long,
) {
    fun size(): Long = byteSize
}

/** Small injectable filesystem seam for deterministic storage failure tests. */
internal interface IncomingCiphertextFileSystem {
    fun attributes(path: Path): IncomingFileAttributes?

    fun makeDirectories(path: Path)

    fun openRead(path: Path): InputStream

    fun atomicNoReplaceLink(source: Path, destination: Path)

    fun deleteIfExists(path: Path): Boolean

    fun forceFile(path: Path)

    fun forceDirectory(path: Path)
}

private object RealIncomingCiphertextFileSystem : IncomingCiphertextFileSystem {
    override fun attributes(path: Path): IncomingFileAttributes? = try {
        val attrs = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        IncomingFileAttributes(
            isSymbolicLink = attrs.isSymbolicLink,
            isRegularFile = attrs.isRegularFile,
            isDirectory = attrs.isDirectory,
            byteSize = attrs.size(),
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    override fun makeDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun openRead(path: Path): InputStream =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

    override fun atomicNoReplaceLink(source: Path, destination: Path) {
        // createLink is an atomic same-filesystem no-replace install. If the
        // provider cannot hard-link, adoption fails closed; copying or
        // ATOMIC_MOVE (whose existing-target behavior is unspecified) is not
        // a safe substitute.
        Files.createLink(destination, source)
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    override fun forceFile(path: Path) {
        java.nio.channels.FileChannel.open(
            path,
            StandardOpenOption.WRITE,
        ).use { channel -> channel.force(true) }
    }

    override fun forceDirectory(path: Path) {
        java.nio.channels.FileChannel.open(
            path,
            StandardOpenOption.READ,
        ).use { channel -> channel.force(true) }
    }
}
