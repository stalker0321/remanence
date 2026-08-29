package dev.hryshyn.remanence.index

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** One authenticated owner and one immutable verified A11b recognition payload. */
class SenderIndexBundleStageRequest(
    val authenticatedOwnerUserId: UserId?,
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val verifiedRecognition: RecognitionManifestContent,
) {
    override fun toString(): String = "SenderIndexBundleStageRequest(<redacted>)"
}

enum class SenderIndexBundleStageFailure {
    NO_AUTHENTICATED_OWNER,
    OWNER_MISMATCH,
    INVALID_VERIFIED_RECOGNITION,
    PATH_UNSAFE,
    DESTINATION_CONFLICT,
    SEALING_FAILED,
    LOCAL_STORAGE,
    ATOMIC_MOVE_UNAVAILABLE,
    DURABILITY_UNAVAILABLE,
}

sealed interface SenderIndexBundleStageResult {
    class Staged(
        val durable: DurableSenderIndexBundle,
        val replayed: Boolean,
    ) : SenderIndexBundleStageResult {
        override fun toString(): String = "SenderIndexBundleStageResult.Staged(<redacted>)"
    }

    data class Failure(
        val reason: SenderIndexBundleStageFailure,
        val retryable: Boolean,
    ) : SenderIndexBundleStageResult
}

/**
 * Opaque A12b capability for the deterministic account/capsule destination.
 * It contains no plaintext accessor and never renders the path or hashes.
 */
class DurableSenderIndexBundle internal constructor(
    internal val ownerUserId: UserId,
    internal val capsuleId: CapsuleId,
    private val destinationFile: File,
    ciphertextSha256: ByteArray,
    internal val ciphertextSizeBytes: Long,
) {
    private val ciphertextSha256Snapshot = ciphertextSha256.copyOf()

    internal val ciphertextSha256: ByteArray
        get() = ciphertextSha256Snapshot.copyOf()

    /** The destination capability consumed by A12b; callers cannot choose it. */
    internal fun asFile(): File = destinationFile

    override fun toString(): String = "DurableSenderIndexBundle(<redacted>)"
}

/**
 * The only write capability returned for a newly created staging part. Its
 * stream is opened by the exclusive create operation and has no path
 * parameter, so staging cannot open TEMP, destination, or another file.
 */
internal class SenderIndexBundleOwnedPart internal constructor(
    internal val path: Path,
    internal val identity: SenderIndexBundleFileIdentity,
    private val io: SenderIndexBundleOwnedPartIo,
) {
    private var closed = false

    internal fun write(bytes: ByteArray) = io.write(bytes)

    /** Force the already-open exclusive-create channel; never reopen [path]. */
    internal fun force() = io.force()

    internal fun close() {
        if (closed) return
        try {
            io.close()
        } finally {
            closed = true
        }
    }

    internal fun closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
            // Cleanup must not mask the original result or cancellation.
        }
    }

    override fun toString(): String = "SenderIndexBundleOwnedPart(<redacted>)"
}

/** The only operations exposed by an owned part's already-open write handle. */
internal interface SenderIndexBundleOwnedPartIo {
    fun write(bytes: ByteArray)
    fun force()
    fun close()
}

/** Opaque provider identity used only for ownership checks, never for diagnostics. */
internal class SenderIndexBundleFileIdentity internal constructor(
    private val providerKey: Any,
) {
    override fun equals(other: Any?): Boolean =
        other is SenderIndexBundleFileIdentity && providerKey == other.providerKey

    override fun hashCode(): Int = providerKey.hashCode()

    override fun toString(): String = "SenderIndexBundleFileIdentity(<redacted>)"
}

/**
 * A12a account-scoped local index staging. It turns one already verified A11b
 * recognition result into one sealed, deterministic local file. It does not
 * know Room states and does not activate an incoming capsule.
 */
class SenderIndexBundleStager internal constructor(
    private val roots: AccountScopedFileRoots,
    private val sealer: SecretSealer,
    private val codec: SenderIndexBundleCodec,
    private val fileSystem: SenderIndexBundleFileSystem,
    private val wipe: (ByteArray) -> Unit,
) {

    constructor(
        roots: AccountScopedFileRoots,
        sealer: SecretSealer,
    ) : this(
        roots = roots,
        sealer = sealer,
        codec = SenderIndexBundleCodec(),
        fileSystem = RealSenderIndexBundleFileSystem,
        wipe = { it.fill(0) },
    )

    /** Stages or semantically replays one immutable owner/capsule bundle. */
    suspend fun stage(
        request: SenderIndexBundleStageRequest,
    ): SenderIndexBundleStageResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (request.authenticatedOwnerUserId == null) {
            return@withContext failure(SenderIndexBundleStageFailure.NO_AUTHENTICATED_OWNER, false)
        }
        if (request.authenticatedOwnerUserId != request.ownerUserId) {
            return@withContext failure(SenderIndexBundleStageFailure.OWNER_MISMATCH, false)
        }

        val lock = SenderIndexBundleLocks.forKey(
            request.ownerUserId.toRestString() + "|" + request.capsuleId.toRestString(),
        )
        lock.withLock {
            coroutineContext.ensureActive()
            val bundle = try {
                SenderIndexBundlePlaintext.fromVerifiedRecognition(
                    expectedCapsuleId = request.capsuleId,
                    recognition = request.verifiedRecognition,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock failure(
                    SenderIndexBundleStageFailure.INVALID_VERIFIED_RECOGNITION,
                    retryable = false,
                )
            }

            var plaintext: ByteArray? = null
            var aad: ByteArray? = null
            try {
                plaintext = codec.encode(bundle)
                aad = aadFor(request.ownerUserId, request.capsuleId, bundle.localFormatVersion)
                val paths = try {
                    resolvePaths(request.ownerUserId, request.capsuleId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withLock failure(SenderIndexBundleStageFailure.PATH_UNSAFE, false)
                }
                if (!paths.areSafe(fileSystem)) {
                    return@withLock failure(SenderIndexBundleStageFailure.PATH_UNSAFE, false)
                }
                try {
                    ensureDestinationParent(paths)
                } catch (_: PathUnsafe) {
                    return@withLock failure(SenderIndexBundleStageFailure.PATH_UNSAFE, false)
                } catch (_: UnsupportedOperationException) {
                    return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                } catch (_: SecurityException) {
                    return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                } catch (_: IOException) {
                    return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }

                when (val existing = inspectSemantic(paths.destination, plaintext!!, aad!!)) {
                    SemanticInspection.MISSING -> Unit
                    SemanticInspection.MATCH -> {
                        return@withLock finishDurableDestination(
                            request = request,
                            paths = paths,
                            expectedPlaintext = plaintext!!,
                            aad = aad!!,
                            replayed = true,
                        )
                    }
                    SemanticInspection.READ_FAILURE -> return@withLock failure(
                        SenderIndexBundleStageFailure.LOCAL_STORAGE,
                        true,
                    )
                    SemanticInspection.UNAVAILABLE -> return@withLock failure(
                        SenderIndexBundleStageFailure.LOCAL_STORAGE,
                        true,
                    )
                    SemanticInspection.MISMATCH,
                    SemanticInspection.UNSAFE,
                    SemanticInspection.INVALID,
                    -> return@withLock failure(
                        SenderIndexBundleStageFailure.DESTINATION_CONFLICT,
                        false,
                    )
                }

                when (inspectSemantic(paths.temporary, plaintext!!, aad!!)) {
                    SemanticInspection.MATCH -> Unit
                    SemanticInspection.MISSING -> ensureTemporary(paths, plaintext!!, aad!!)?.let { return@withLock it }
                    // The canonical TEMP is never written incrementally. Any
                    // existing unreadable/unavailable TEMP is therefore
                    // preserved and retried conservatively; only a fresh
                    // unique .part is disposable by its creating invocation.
                    SemanticInspection.INVALID,
                    SemanticInspection.UNAVAILABLE -> return@withLock failure(
                        SenderIndexBundleStageFailure.LOCAL_STORAGE,
                        true,
                    )
                    SemanticInspection.READ_FAILURE -> return@withLock failure(
                        SenderIndexBundleStageFailure.LOCAL_STORAGE,
                        true,
                    )
                    SemanticInspection.MISMATCH,
                    SemanticInspection.UNSAFE,
                    -> return@withLock failure(
                        SenderIndexBundleStageFailure.DESTINATION_CONFLICT,
                        false,
                    )
                }

                val temporaryIdentity = currentIdentity(paths.temporary)
                    ?: return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                try {
                    fileSystem.atomicNoReplaceLink(paths.temporary, paths.destination)
                } catch (_: FileAlreadyExistsException) {
                    return@withLock reconcileExistingDestination(
                        request,
                        paths,
                        plaintext!!,
                        aad!!,
                        temporaryIdentity,
                    )
                } catch (_: UnsupportedOperationException) {
                    return@withLock failure(
                        SenderIndexBundleStageFailure.ATOMIC_MOVE_UNAVAILABLE,
                        false,
                    )
                } catch (_: SecurityException) {
                    return@withLock reconcileExistingDestination(
                        request,
                        paths,
                        plaintext!!,
                        aad!!,
                        temporaryIdentity,
                    )
                } catch (_: IOException) {
                    return@withLock reconcileExistingDestination(
                        request,
                        paths,
                        plaintext!!,
                        aad!!,
                        temporaryIdentity,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    return@withLock reconcileExistingDestination(
                        request,
                        paths,
                        plaintext!!,
                        aad!!,
                        temporaryIdentity,
                    )
                }

                // Link success means the destination now owns these exact
                // bytes. From here on, no cleanup failure may delete it.
                return@withLock finishDurableDestination(
                    request = request,
                    paths = paths,
                    expectedPlaintext = plaintext!!,
                    aad = aad!!,
                    replayed = false,
                    expectedTemporaryIdentity = temporaryIdentity,
                )
            } finally {
                plaintext?.let(wipe)
                aad?.let(wipe)
                bundle.wipe()
            }
        }
    }

    private fun resolvePaths(owner: UserId, capsule: CapsuleId): StagePaths {
        val root = roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
            .toPath().toAbsolutePath().normalize()
        val parent = root.resolve("capsules").normalize()
        val baseName = "${capsule.toRestString()}.index.bundle"
        return StagePaths(
            root = root,
            parent = parent,
            destination = parent.resolve(baseName).normalize(),
            temporary = parent.resolve("$baseName.tmp").normalize(),
            partPrefix = "$baseName.",
            partSuffix = ".part",
        )
    }

    private fun ensureDestinationParent(paths: StagePaths) {
        if (!isContained(paths.parent, paths.root) || !isSafePath(paths.root) || !isSafePath(paths.parent)) {
            throw PathUnsafe()
        }
        fileSystem.makeDirectories(paths.parent)
        if (!isDirectoryChainSafe(paths.root, paths.parent)) throw PathUnsafe()
    }

    /**
     * Build the canonical TEMP through a unique same-directory part file.
     * The deterministic TEMP is never exposed to an incremental write: a
     * crash can leave only an invocation-specific .part, which a later
     * invocation ignores while it creates its own fresh part. The fixed
     * in-process stripe lock serializes same-capsule callers.
     */
    private suspend fun ensureTemporary(
        paths: StagePaths,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
    ): SenderIndexBundleStageResult.Failure? {
        var ownedPart: SenderIndexBundleOwnedPart? = null
        var returnedPart: SenderIndexBundleOwnedPart? = null
        var sealed: ByteArray? = null
        try {
            sealed = try {
                sealer.seal(expectedPlaintext, aad)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failure(SenderIndexBundleStageFailure.SEALING_FAILED, true)
            }
            if (sealed!!.isEmpty() || sealed!!.size > MAX_CIPHERTEXT_BYTES) {
                return failure(SenderIndexBundleStageFailure.SEALING_FAILED, false)
            }

            returnedPart = try {
                fileSystem.createFreshPart(
                    parent = paths.parent,
                    prefix = paths.partPrefix,
                    suffix = paths.partSuffix,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }
            val freshPart = returnedPart
                ?: return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            val normalizedPart = try {
                val normalized = freshPart.path.toAbsolutePath().normalize()
                if (freshPart.path != normalized ||
                    normalized.parent != paths.parent ||
                    !normalized.startsWith(paths.root) ||
                    normalized.fileName.toString().length <=
                        paths.partPrefix.length + paths.partSuffix.length ||
                    !normalized.fileName.toString().startsWith(paths.partPrefix) ||
                    !normalized.fileName.toString().endsWith(paths.partSuffix)
                ) {
                    return failure(SenderIndexBundleStageFailure.PATH_UNSAFE, false)
                }
                normalized
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failure(SenderIndexBundleStageFailure.PATH_UNSAFE, false)
            }
            // Shape validation proves this exact path is inside the canonical
            // parent before ownership is retained for cleanup.
            ownedPart = freshPart
            val partAttributes = try {
                fileSystem.attributes(normalizedPart)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }
            if (partAttributes == null ||
                partAttributes.isSymbolicLink ||
                !partAttributes.isRegularFile
            ) {
                ownedPart = null
                return failure(SenderIndexBundleStageFailure.PATH_UNSAFE, false)
            }
            if (partAttributes.fileIdentity != freshPart.identity) {
                // A provider replaced the returned name before the first
                // write. The name is no longer ours; preserve it and fail
                // closed rather than unlinking another inode.
                ownedPart = null
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }
            try {
                freshPart.write(sealed!!)
                // The exclusive-create channel remains the sole force
                // capability for this part. Never reopen the pathname.
                freshPart.force()
                freshPart.close()
                if (!forceDirectoryBestEffort(normalizedPart.parent!!)) {
                    return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FileAlreadyExistsException) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            } catch (_: SecurityException) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            } catch (_: IOException) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            } catch (_: UnsupportedOperationException) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            } catch (_: RuntimeException) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }

            if (!isOwnedPath(normalizedPart, freshPart.identity)) {
                // The pathname was substituted after capability creation.
                // Do not hand the substituted inode to a hard-link operation.
                ownedPart = null
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }
            try {
                fileSystem.atomicNoReplaceLink(normalizedPart, paths.temporary)
            } catch (_: FileAlreadyExistsException) {
                return reconcileTemporaryAfterLinkFailure(
                    paths,
                    freshPart,
                    expectedPlaintext,
                    aad,
                    onPartConsumed = { ownedPart = null },
                    onPartOwnershipUncertain = { ownedPart = null },
                )
            } catch (_: UnsupportedOperationException) {
                return failure(SenderIndexBundleStageFailure.ATOMIC_MOVE_UNAVAILABLE, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return reconcileTemporaryAfterLinkFailure(
                    paths,
                    freshPart,
                    expectedPlaintext,
                    aad,
                    onPartConsumed = { ownedPart = null },
                    onPartOwnershipUncertain = { ownedPart = null },
                )
            } catch (_: IOException) {
                return reconcileTemporaryAfterLinkFailure(
                    paths,
                    freshPart,
                    expectedPlaintext,
                    aad,
                    onPartConsumed = { ownedPart = null },
                    onPartOwnershipUncertain = { ownedPart = null },
                )
            } catch (_: RuntimeException) {
                return reconcileTemporaryAfterLinkFailure(
                    paths,
                    freshPart,
                    expectedPlaintext,
                    aad,
                    onPartConsumed = { ownedPart = null },
                    onPartOwnershipUncertain = { ownedPart = null },
                )
            }

            return completeTemporaryPublication(
                paths,
                freshPart,
                expectedPlaintext,
                aad,
                onPartConsumed = { ownedPart = null },
                onPartOwnershipUncertain = { ownedPart = null },
            )
        } finally {
            returnedPart?.closeQuietly()
            sealed?.let(wipe)
            ownedPart?.let { part ->
                withContext(NonCancellable) {
                    cleanupOwnedPartBestEffort(part)
                }
            }
        }
    }

    private suspend fun reconcileTemporaryAfterLinkFailure(
        paths: StagePaths,
        ownedPart: SenderIndexBundleOwnedPart,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
        onPartConsumed: () -> Unit = {},
        onPartOwnershipUncertain: () -> Unit = {},
    ): SenderIndexBundleStageResult.Failure? {
        if (!isOwnedPath(ownedPart.path, ownedPart.identity)) {
            onPartOwnershipUncertain()
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        val temporaryIdentity = currentIdentity(paths.temporary)
        if (temporaryIdentity != ownedPart.identity) {
            onPartOwnershipUncertain()
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        val inspection = inspectSemantic(paths.temporary, expectedPlaintext, aad)
        if (!isOwnedPath(ownedPart.path, ownedPart.identity) ||
            currentIdentity(paths.temporary) != ownedPart.identity
        ) {
            onPartOwnershipUncertain()
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        return when (inspection) {
            SemanticInspection.MATCH -> completeTemporaryPublication(
                paths,
                ownedPart,
                expectedPlaintext,
                aad,
                onPartConsumed,
                onPartOwnershipUncertain,
            )
            SemanticInspection.MISMATCH,
            SemanticInspection.UNSAFE,
            -> failure(SenderIndexBundleStageFailure.DESTINATION_CONFLICT, false)
            else -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
    }

    private suspend fun completeTemporaryPublication(
        paths: StagePaths,
        ownedPart: SenderIndexBundleOwnedPart,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
        onPartConsumed: () -> Unit,
        onPartOwnershipUncertain: () -> Unit,
    ): SenderIndexBundleStageResult.Failure? {
        if (!isOwnedPath(ownedPart.path, ownedPart.identity) ||
            currentIdentity(paths.temporary) != ownedPart.identity
        ) {
            onPartOwnershipUncertain()
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        forceFile(paths.temporary)?.let { return it }
        if (!isOwnedPath(ownedPart.path, ownedPart.identity) ||
            currentIdentity(paths.temporary) != ownedPart.identity
        ) {
            onPartOwnershipUncertain()
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        if (!forceDirectoryBestEffort(paths.temporary.parent!!)) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        try {
            if (!deleteOwnedPart(ownedPart)) {
                onPartOwnershipUncertain()
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }
            onPartConsumed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        if (!forceDirectoryBestEffort(ownedPart.path.parent!!)) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        if (currentIdentity(paths.temporary) != ownedPart.identity) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        return when (inspectSemantic(paths.temporary, expectedPlaintext, aad)) {
            SemanticInspection.MATCH -> if (currentIdentity(paths.temporary) == ownedPart.identity) {
                null
            } else {
                failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }
            SemanticInspection.MISMATCH,
            SemanticInspection.UNSAFE,
            -> failure(SenderIndexBundleStageFailure.DESTINATION_CONFLICT, false)
            else -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
    }

    /** Delete only this invocation's no-follow regular part, then persist the unlink. */
    private fun deleteOwnedPart(part: SenderIndexBundleOwnedPart): Boolean {
        if (!isOwnedPath(part.path, part.identity)) return false
        val attributes = try {
            fileSystem.attributes(part.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (attributes == null ||
            attributes.fileIdentity != part.identity ||
            attributes.isSymbolicLink ||
            !attributes.isRegularFile
        ) return false
        return try {
            fileSystem.deleteIfExists(part.path)
            currentIdentity(part.path) == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupOwnedPartBestEffort(part: SenderIndexBundleOwnedPart) {
        val attributes = try {
            fileSystem.attributes(part.path)
        } catch (_: Throwable) {
            return
        }
        if (attributes == null ||
            attributes.fileIdentity != part.identity ||
            attributes.isSymbolicLink ||
            !attributes.isRegularFile
        ) return
        try {
            fileSystem.deleteIfExists(part.path)
            if (currentIdentity(part.path) != null) return
        } catch (_: Throwable) {
            return
        }
        try {
            forceDirectoryBestEffort(part.path.parent!!)
        } catch (_: Throwable) {
            // This helper runs from finally and must never mask the original
            // result or cancellation, including an explicit provider-thrown
            // CancellationException.
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
        val attributes = try {
            fileSystem.attributes(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return attributes != null && attributes.isDirectory && !attributes.isSymbolicLink && isSafePath(path)
    }

    private fun isSafePath(path: Path): Boolean {
        var current: Path? = path
        return try {
            while (current != null) {
                val examined = current
                val attributes = fileSystem.attributes(examined)
                if (attributes?.isSymbolicLink == true) return false
                current = if (attributes == null) examined.parent else examined.parent
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun isContained(candidate: Path, root: Path): Boolean =
        candidate != root && candidate.startsWith(root)

    private suspend fun inspectSemantic(
        path: Path,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
    ): SemanticInspection {
        val read = readBounded(path)
        if (read is ReadResult.Failure) return read.inspection
        read as ReadResult.Bytes
        val ciphertext = read.bytes
        var opened: ByteArray? = null
        var canonicalDecoded: ByteArray? = null
        return try {
            opened = try {
                sealer.unseal(ciphertext, aad)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // SecretSealer deliberately does not promise an
                // authenticity-vs-provider exception distinction. Preserve
                // the file and retry conservatively instead of turning a
                // possible transient Keystore/provider failure into a
                // permanent conflict.
                return SemanticInspection.UNAVAILABLE
            }
            if (opened!!.size > SenderIndexBundleCodec.MAX_PLAINTEXT_BYTES) {
                return SemanticInspection.INVALID
            }
            val decoded = try {
                codec.decode(opened!!)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return SemanticInspection.INVALID
            }
            try {
                canonicalDecoded = codec.encode(decoded)
                if (MessageDigest.isEqual(canonicalDecoded, expectedPlaintext)) {
                    SemanticInspection.MATCH
                } else {
                    SemanticInspection.MISMATCH
                }
            } finally {
                decoded.wipe()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SemanticInspection.READ_FAILURE
        } finally {
            wipe(ciphertext)
            opened?.let(wipe)
            canonicalDecoded?.let(wipe)
        }
    }

    private suspend fun readBounded(path: Path): ReadResult {
        val attributes = try {
            fileSystem.attributes(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        } catch (_: SecurityException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        } catch (_: UnsupportedOperationException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        } catch (_: RuntimeException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        }
        if (attributes == null) return ReadResult.Failure(SemanticInspection.MISSING)
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            return ReadResult.Failure(SemanticInspection.UNSAFE)
        }
        if (attributes.size > MAX_CIPHERTEXT_BYTES) {
            return ReadResult.Failure(SemanticInspection.INVALID)
        }
        val output = ByteArray(attributes.size.toInt())
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0L
        var returnedBytes = false
        try {
            fileSystem.openRead(path).use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) return ReadResult.Failure(SemanticInspection.READ_FAILURE)
                    val offset = total.toInt()
                    total += count
                    if (total > MAX_CIPHERTEXT_BYTES) {
                        wipe(output)
                        return ReadResult.Failure(SemanticInspection.INVALID)
                    }
                    if (total > attributes.size) {
                        wipe(output)
                        return ReadResult.Failure(SemanticInspection.INVALID)
                    }
                    buffer.copyInto(output, destinationOffset = offset, endIndex = count)
                }
                if (total == attributes.size) {
                    returnedBytes = true
                    return ReadResult.Bytes(output)
                }
                return ReadResult.Failure(SemanticInspection.INVALID)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        } catch (_: SecurityException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        } catch (_: UnsupportedOperationException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        } catch (_: RuntimeException) {
            return ReadResult.Failure(SemanticInspection.READ_FAILURE)
        } finally {
            wipe(buffer)
            if (!returnedBytes) wipe(output)
        }
    }

    private fun forceFile(path: Path): SenderIndexBundleStageResult.Failure? = try {
        fileSystem.forceFile(path)
        null
    } catch (_: UnsupportedOperationException) {
        failure(SenderIndexBundleStageFailure.DURABILITY_UNAVAILABLE, false)
    } catch (_: SecurityException) {
        failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
    } catch (_: IOException) {
        failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
    }

    private fun currentIdentity(path: Path): SenderIndexBundleFileIdentity? = try {
        fileSystem.attributes(path)?.fileIdentity
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun isOwnedPath(path: Path, identity: SenderIndexBundleFileIdentity): Boolean =
        currentIdentity(path) == identity

    private suspend fun finishDurableDestination(
        request: SenderIndexBundleStageRequest,
        paths: StagePaths,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
        replayed: Boolean,
        expectedTemporaryIdentity: SenderIndexBundleFileIdentity? = null,
    ): SenderIndexBundleStageResult {
        val destinationIdentity = currentIdentity(paths.destination)
            ?: return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        if (expectedTemporaryIdentity != null &&
            destinationIdentity != expectedTemporaryIdentity
        ) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        forceFile(paths.destination)?.let { return it }
        if (currentIdentity(paths.destination) != destinationIdentity) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        if (!forceDirectoryBestEffort(paths.destination.parent!!)) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }

        val temporaryInspection = inspectSemantic(paths.temporary, expectedPlaintext, aad)
        if (expectedTemporaryIdentity != null &&
            currentIdentity(paths.temporary) != expectedTemporaryIdentity
        ) {
            // A missing or substituted TEMP after publication is ambiguous;
            // never unlink or otherwise claim that pathname.
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        when (temporaryInspection) {
            SemanticInspection.MATCH -> {
                val temporaryIdentity = currentIdentity(paths.temporary)
                if (temporaryIdentity == null) {
                    return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }
                if (expectedTemporaryIdentity != null &&
                    temporaryIdentity != expectedTemporaryIdentity
                ) {
                    // The path was replaced after the link/reconciliation
                    // boundary. Preserve both names; neither is ours now.
                    return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }
                // On replay there is no retained capability. It is safe to
                // unlink only a temp that is the exact destination inode;
                // unrelated semantic orphans remain untouched.
                if (temporaryIdentity == destinationIdentity ||
                    temporaryIdentity == expectedTemporaryIdentity
                ) {
                    if (!deletePathIfExact(paths.temporary, temporaryIdentity)) {
                        return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                    }
                    if (!forceDirectoryBestEffort(paths.temporary.parent!!)) {
                        return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                    }
                }
            }
            SemanticInspection.MISSING -> {
                // A previous attempt may have unlinked the temp and failed to
                // persist that unlink. Close that directory-durability gap.
                if (!forceDirectoryBestEffort(paths.temporary.parent!!)) {
                    return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }
            }
            SemanticInspection.READ_FAILURE ->
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            SemanticInspection.UNAVAILABLE ->
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            else -> Unit // keep an unknown orphan; never risk the winner
        }

        val verified = verifyDestination(paths.destination, expectedPlaintext, aad)
            ?: return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        return try {
            SenderIndexBundleStageResult.Staged(
                durable = DurableSenderIndexBundle(
                    ownerUserId = request.ownerUserId,
                    capsuleId = request.capsuleId,
                    destinationFile = paths.destination.toFile(),
                    ciphertextSha256 = verified.sha256,
                    ciphertextSizeBytes = verified.sizeBytes,
                ),
                replayed = replayed,
            )
        } finally {
            // DurableSenderIndexBundle copies the digest before ownership is
            // returned to A12b.
            wipe(verified.sha256)
        }
    }

    private suspend fun verifyDestination(
        path: Path,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
    ): VerifiedBytes? {
        val read = readBounded(path)
        if (read !is ReadResult.Bytes) return null
        return try {
            if (inspectSemantic(path, expectedPlaintext, aad) == SemanticInspection.MATCH) {
                // VerifiedBytes owns this digest until finish copies it into
                // the opaque capability.
                VerifiedBytes(
                    sha256 = MessageDigest.getInstance("SHA-256").digest(read.bytes),
                    sizeBytes = read.bytes.size.toLong(),
                )
            } else {
                null
            }
        } finally {
            // This complete ciphertext buffer is never allowed to escape.
            wipe(read.bytes)
        }
    }

    private suspend fun reconcileExistingDestination(
        request: SenderIndexBundleStageRequest,
        paths: StagePaths,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
        temporaryIdentity: SenderIndexBundleFileIdentity,
    ): SenderIndexBundleStageResult {
        if (currentIdentity(paths.temporary) != temporaryIdentity) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        val inspection = inspectSemantic(paths.destination, expectedPlaintext, aad)
        if (currentIdentity(paths.temporary) != temporaryIdentity) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        return when (inspection) {
            SemanticInspection.MATCH -> {
                if (currentIdentity(paths.destination) != temporaryIdentity) {
                    return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }
                finishDurableDestination(
                    request,
                    paths,
                    expectedPlaintext,
                    aad,
                    replayed = true,
                    expectedTemporaryIdentity = temporaryIdentity,
                )
            }
            SemanticInspection.MISSING,
            SemanticInspection.READ_FAILURE -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            SemanticInspection.UNAVAILABLE -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            SemanticInspection.MISMATCH,
            SemanticInspection.UNSAFE,
            SemanticInspection.INVALID,
            -> failure(SenderIndexBundleStageFailure.DESTINATION_CONFLICT, false)
        }
    }

    private fun deletePathIfExact(
        path: Path,
        expectedIdentity: SenderIndexBundleFileIdentity,
    ): Boolean {
        if (currentIdentity(path) != expectedIdentity) return false
        return try {
            fileSystem.deleteIfExists(path)
            currentIdentity(path) == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun aadFor(owner: UserId, capsule: CapsuleId, formatVersion: Int): ByteArray =
        SenderIndexBundleAad.encode(owner, capsule, formatVersion)

    private fun forceDirectoryBestEffort(directory: Path): Boolean = try {
        fileSystem.forceDirectory(directory)
        true
    } catch (_: UnsupportedOperationException) {
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IOException) {
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }

    private fun failure(
        reason: SenderIndexBundleStageFailure,
        retryable: Boolean,
    ) = SenderIndexBundleStageResult.Failure(reason, retryable)

    private data class StagePaths(
        val root: Path,
        val parent: Path,
        val destination: Path,
        val temporary: Path,
        val partPrefix: String,
        val partSuffix: String,
    ) {
        fun areSafe(fileSystem: SenderIndexBundleFileSystem): Boolean =
            isSafe(fileSystem, root) && isSafe(fileSystem, parent) &&
                isSafe(fileSystem, destination) && isSafe(fileSystem, temporary) &&
                destination.startsWith(root) && temporary.startsWith(root)

        private fun isSafe(fileSystem: SenderIndexBundleFileSystem, path: Path): Boolean {
            var current: Path? = path
            return try {
                while (current != null) {
                    val attributes = fileSystem.attributes(current!!)
                    if (attributes?.isSymbolicLink == true) return false
                    current = current!!.parent
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    private data class VerifiedBytes(val sha256: ByteArray, val sizeBytes: Long)

    private sealed interface ReadResult {
        data class Bytes(val bytes: ByteArray) : ReadResult
        data class Failure(val inspection: SemanticInspection) : ReadResult
    }

    private enum class SemanticInspection {
        MISSING,
        MATCH,
        MISMATCH,
        INVALID,
        UNAVAILABLE,
        UNSAFE,
        READ_FAILURE,
    }

    private class PathUnsafe : IllegalStateException()

    private companion object {
        const val STREAM_BUFFER_BYTES = 32 * 1024
        // Established AES-GCM/Tink framing is plaintext + 33 bytes: a
        // 5-byte Tink prefix, 12-byte nonce, and 16-byte authentication tag.
        // The Keystore-backed local Aead is no larger than this bound.
        const val MAX_CIPHERTEXT_BYTES = SenderIndexBundleCodec.MAX_PLAINTEXT_BYTES +
            ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES.toInt()
    }
}

private object SenderIndexBundleLocks {
    private const val STRIPES = 32
    private val locks = Array(STRIPES) { Mutex() }

    fun forKey(key: String): Mutex = locks[(key.hashCode() and Int.MAX_VALUE) % STRIPES]
}

internal data class SenderIndexBundleFileAttributes(
    val isSymbolicLink: Boolean,
    val isRegularFile: Boolean,
    val isDirectory: Boolean,
    val size: Long,
    val fileIdentity: SenderIndexBundleFileIdentity?,
)

internal interface SenderIndexBundleFileSystem {
    fun attributes(path: Path): SenderIndexBundleFileAttributes?
    fun makeDirectories(path: Path)
    fun openRead(path: Path): InputStream
    fun createFreshPart(parent: Path, prefix: String, suffix: String): SenderIndexBundleOwnedPart
    fun atomicNoReplaceLink(source: Path, destination: Path)
    fun deleteIfExists(path: Path): Boolean
    fun forceFile(path: Path)
    fun forceDirectory(path: Path)
}

private object RealSenderIndexBundleFileSystem : SenderIndexBundleFileSystem {
    private val useAndroidNativeFilesystem =
        System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true

    override fun attributes(path: Path): SenderIndexBundleFileAttributes? =
        if (useAndroidNativeFilesystem) androidAttributes(path) else jvmAttributes(path)

    private fun jvmAttributes(path: Path): SenderIndexBundleFileAttributes? = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        SenderIndexBundleFileAttributes(
            isSymbolicLink = attributes.isSymbolicLink,
            isRegularFile = attributes.isRegularFile,
            isDirectory = attributes.isDirectory,
            size = attributes.size(),
            fileIdentity = attributes.fileKey()?.let(::SenderIndexBundleFileIdentity),
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    private fun androidAttributes(path: Path): SenderIndexBundleFileAttributes? = try {
        val stat = Os.lstat(path.toString())
        SenderIndexBundleFileAttributes(
            isSymbolicLink = OsConstants.S_ISLNK(stat.st_mode),
            isRegularFile = OsConstants.S_ISREG(stat.st_mode),
            isDirectory = OsConstants.S_ISDIR(stat.st_mode),
            size = stat.st_size,
            fileIdentity = SenderIndexBundleFileIdentity(
                AndroidFileIdentity(stat.st_dev, stat.st_ino),
            ),
        )
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error.rethrowAsIOException()
    }

    override fun makeDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun openRead(path: Path): InputStream =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

    override fun createFreshPart(
        parent: Path,
        prefix: String,
        suffix: String,
    ): SenderIndexBundleOwnedPart {
        if (useAndroidNativeFilesystem) {
            return createAndroidFreshPart(parent, prefix, suffix)
        }
        repeat(MAX_PART_CREATE_ATTEMPTS) {
            val path = parent.resolve("$prefix${UUID.randomUUID()}$suffix")
            try {
                val channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                var handedOff = false
                try {
                    val fileKey = Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    ).fileKey() ?: throw UnsupportedOperationException(
                        "stable file identity unavailable",
                    )
                    val owned = SenderIndexBundleOwnedPart(
                        path = path,
                        identity = SenderIndexBundleFileIdentity(fileKey),
                        io = FileChannelOwnedPartIo(channel),
                    )
                    handedOff = true
                    return owned
                } finally {
                    if (!handedOff) channel.close()
                }
            } catch (_: FileAlreadyExistsException) {
                // This candidate was not owned; choose another fresh name.
            }
        }
        throw IOException("fresh part creation exhausted")
    }

    override fun atomicNoReplaceLink(source: Path, destination: Path) {
        if (useAndroidNativeFilesystem) {
            Os.link(source.toString(), destination.toString())
        } else {
            Files.createLink(destination, source)
        }
    }

    override fun deleteIfExists(path: Path): Boolean = if (useAndroidNativeFilesystem) {
        try {
            Os.remove(path.toString())
            true
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) false else throw error.rethrowAsIOException()
        }
    } else {
        Files.deleteIfExists(path)
    }

    override fun forceFile(path: Path) {
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { it.force(true) }
    }

    override fun forceDirectory(path: Path) {
        java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun createAndroidFreshPart(
        parent: Path,
        prefix: String,
        suffix: String,
    ): SenderIndexBundleOwnedPart {
        repeat(MAX_PART_CREATE_ATTEMPTS) {
            val path = parent.resolve("$prefix${UUID.randomUUID()}$suffix")
            var descriptor: FileDescriptor? = null
            try {
                descriptor = Os.open(
                    path.toString(),
                    OsConstants.O_CREAT or
                        OsConstants.O_EXCL or
                        OsConstants.O_WRONLY or
                        OsConstants.O_NOFOLLOW or
                        OsConstants.O_CLOEXEC,
                    OsConstants.S_IRUSR or OsConstants.S_IWUSR,
                )
                val stat = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw UnsupportedOperationException("fresh part is not regular")
                }
                val stream = FileOutputStream(descriptor)
                descriptor = null // FileOutputStream now owns the descriptor.
                return SenderIndexBundleOwnedPart(
                    path = path,
                    identity = SenderIndexBundleFileIdentity(
                        AndroidFileIdentity(stat.st_dev, stat.st_ino),
                    ),
                    io = AndroidOwnedPartIo(stream),
                )
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error.rethrowAsIOException()
            } finally {
                descriptor?.let { openDescriptor ->
                    try {
                        Os.close(openDescriptor)
                    } catch (_: Exception) {
                        // No capability was returned, so the descriptor is
                        // never exposed to the caller.
                    }
                }
            }
        }
        throw IOException("fresh part creation exhausted")
    }

    private data class AndroidFileIdentity(val device: Long, val inode: Long)

    private const val MAX_PART_CREATE_ATTEMPTS = 16

    private class FileChannelOwnedPartIo(
        private val channel: FileChannel,
    ) : SenderIndexBundleOwnedPartIo {
        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun force() {
            channel.force(true)
        }

        override fun close() {
            channel.close()
        }
    }

    private class AndroidOwnedPartIo(
        private val stream: FileOutputStream,
    ) : SenderIndexBundleOwnedPartIo {
        private val channel = stream.channel

        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun force() {
            channel.force(true)
        }

        override fun close() {
            stream.close()
        }
    }
}
