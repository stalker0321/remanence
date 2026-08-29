package dev.hryshyn.remanence.index

import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    DEPENDENCY_UNAVAILABLE,
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
    private val output: OutputStream,
) {
    private var closed = false

    internal fun write(bytes: ByteArray) = output.write(bytes)

    internal fun flush() = output.flush()

    internal fun close() {
        if (closed) return
        try {
            output.close()
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
                        SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE,
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
                    SemanticInspection.INVALID -> return@withLock failure(
                        SenderIndexBundleStageFailure.LOCAL_STORAGE,
                        true,
                    )
                    SemanticInspection.UNAVAILABLE -> return@withLock failure(
                        SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE,
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

                try {
                    fileSystem.atomicNoReplaceLink(paths.temporary, paths.destination)
                } catch (_: FileAlreadyExistsException) {
                    return@withLock reconcileExistingDestination(request, paths, plaintext!!, aad!!)
                } catch (_: UnsupportedOperationException) {
                    return@withLock failure(
                        SenderIndexBundleStageFailure.ATOMIC_MOVE_UNAVAILABLE,
                        false,
                    )
                } catch (_: SecurityException) {
                    return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                } catch (_: IOException) {
                    return@withLock reconcileExistingDestination(request, paths, plaintext!!, aad!!)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }

                // Link success means the destination now owns these exact
                // bytes. From here on, no cleanup failure may delete it.
                return@withLock finishDurableDestination(
                    request = request,
                    paths = paths,
                    expectedPlaintext = plaintext!!,
                    aad = aad!!,
                    replayed = false,
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
        var ownedPart: Path? = null
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
            ownedPart = normalizedPart
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
            try {
                freshPart.write(sealed!!)
                freshPart.flush()
                freshPart.close()
                forceFile(normalizedPart)?.let { return it }
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

            try {
                fileSystem.atomicNoReplaceLink(normalizedPart, paths.temporary)
            } catch (_: FileAlreadyExistsException) {
                return reconcileTemporaryAfterLinkFailure(
                    paths,
                    normalizedPart,
                    expectedPlaintext,
                    aad,
                ) { ownedPart = null }
            } catch (_: UnsupportedOperationException) {
                return failure(SenderIndexBundleStageFailure.ATOMIC_MOVE_UNAVAILABLE, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            } catch (_: IOException) {
                return reconcileTemporaryAfterLinkFailure(
                    paths,
                    normalizedPart,
                    expectedPlaintext,
                    aad,
                ) { ownedPart = null }
            } catch (_: RuntimeException) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }

            return completeTemporaryPublication(paths, normalizedPart, expectedPlaintext, aad) {
                ownedPart = null
            }
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
        ownedPart: Path,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
        onPartConsumed: () -> Unit = {},
    ): SenderIndexBundleStageResult.Failure? = when (
        inspectSemantic(paths.temporary, expectedPlaintext, aad)
    ) {
        SemanticInspection.MATCH -> completeTemporaryPublication(
            paths,
            ownedPart,
            expectedPlaintext,
            aad,
            onPartConsumed,
        )
        SemanticInspection.MISMATCH,
        SemanticInspection.UNSAFE,
        -> failure(SenderIndexBundleStageFailure.DESTINATION_CONFLICT, false)
        SemanticInspection.UNAVAILABLE ->
            failure(SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE, true)
        else -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
    }

    private suspend fun completeTemporaryPublication(
        paths: StagePaths,
        ownedPart: Path,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
        onPartConsumed: () -> Unit,
    ): SenderIndexBundleStageResult.Failure? {
        forceFile(paths.temporary)?.let { return it }
        if (!forceDirectoryBestEffort(paths.temporary.parent!!)) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        try {
            if (!deleteOwnedPart(ownedPart)) {
                return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            }
            onPartConsumed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        if (!forceDirectoryBestEffort(ownedPart.parent!!)) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
        return when (inspectSemantic(paths.temporary, expectedPlaintext, aad)) {
            SemanticInspection.MATCH -> null
            SemanticInspection.MISMATCH,
            SemanticInspection.UNSAFE,
            -> failure(SenderIndexBundleStageFailure.DESTINATION_CONFLICT, false)
            SemanticInspection.UNAVAILABLE ->
                failure(SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE, true)
            else -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }
    }

    /** Delete only this invocation's no-follow regular part, then persist the unlink. */
    private fun deleteOwnedPart(path: Path): Boolean {
        val attributes = try {
            fileSystem.attributes(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (attributes == null) return forceDirectoryBestEffort(path.parent!!)
        if (attributes.isSymbolicLink || !attributes.isRegularFile) return false
        return try {
            fileSystem.deleteIfExists(path)
            forceDirectoryBestEffort(path.parent!!)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupOwnedPartBestEffort(path: Path) {
        val attributes = try {
            fileSystem.attributes(path)
        } catch (_: Exception) {
            return
        }
        if (attributes?.isSymbolicLink == true || attributes?.isRegularFile == false) return
        try {
            fileSystem.deleteIfExists(path)
        } catch (_: Exception) {
            return
        }
        try {
            forceDirectoryBestEffort(path.parent!!)
        } catch (_: Exception) {
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

    private suspend fun finishDurableDestination(
        request: SenderIndexBundleStageRequest,
        paths: StagePaths,
        expectedPlaintext: ByteArray,
        aad: ByteArray,
        replayed: Boolean,
    ): SenderIndexBundleStageResult {
        forceFile(paths.destination)?.let { return it }
        if (!forceDirectoryBestEffort(paths.destination.parent!!)) {
            return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
        }

        when (inspectSemantic(paths.temporary, expectedPlaintext, aad)) {
            SemanticInspection.MATCH -> {
                try {
                    fileSystem.deleteIfExists(paths.temporary)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                }
                if (!forceDirectoryBestEffort(paths.temporary.parent!!)) {
                    return failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
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
                return failure(SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE, true)
            else -> Unit // keep an unknown orphan; never risk the winner
        }

        val verified = when (
            val verification = verifyDestination(paths.destination, expectedPlaintext, aad)
        ) {
            is DestinationVerification.Verified -> verification.bytes
            is DestinationVerification.Failure -> return failure(
                if (verification.inspection == SemanticInspection.UNAVAILABLE) {
                    SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE
                } else {
                    SenderIndexBundleStageFailure.LOCAL_STORAGE
                },
                true,
            )
        }
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
    ): DestinationVerification {
        val read = readBounded(path)
        if (read !is ReadResult.Bytes) {
            return DestinationVerification.Failure(
                (read as ReadResult.Failure).inspection,
            )
        }
        return try {
            val inspection = inspectSemantic(path, expectedPlaintext, aad)
            if (inspection == SemanticInspection.MATCH) {
                // VerifiedBytes owns this digest until finish copies it into
                // the opaque capability.
                DestinationVerification.Verified(
                    VerifiedBytes(
                        sha256 = MessageDigest.getInstance("SHA-256").digest(read.bytes),
                        sizeBytes = read.bytes.size.toLong(),
                    ),
                )
            } else {
                DestinationVerification.Failure(inspection)
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
    ): SenderIndexBundleStageResult {
        return when (inspectSemantic(paths.destination, expectedPlaintext, aad)) {
            SemanticInspection.MATCH -> finishDurableDestination(
                request,
                paths,
                expectedPlaintext,
                aad,
                replayed = true,
            )
            SemanticInspection.MISSING,
            SemanticInspection.READ_FAILURE -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            SemanticInspection.UNAVAILABLE ->
                failure(SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE, true)
            SemanticInspection.MISMATCH,
            SemanticInspection.UNSAFE,
            SemanticInspection.INVALID,
            -> failure(SenderIndexBundleStageFailure.DESTINATION_CONFLICT, false)
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

    private sealed interface DestinationVerification {
        data class Verified(val bytes: VerifiedBytes) : DestinationVerification
        data class Failure(val inspection: SemanticInspection) : DestinationVerification
    }

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
    override fun attributes(path: Path): SenderIndexBundleFileAttributes? = try {
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
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
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
        repeat(MAX_PART_CREATE_ATTEMPTS) {
            val path = parent.resolve("$prefix${UUID.randomUUID()}$suffix")
            try {
                val output = Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                return SenderIndexBundleOwnedPart(path, output)
            } catch (_: FileAlreadyExistsException) {
                // This candidate was not owned; choose another fresh name.
            }
        }
        throw IOException("fresh part creation exhausted")
    }

    override fun atomicNoReplaceLink(source: Path, destination: Path) {
        Files.createLink(destination, source)
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    override fun forceFile(path: Path) {
        java.nio.channels.FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { it.force(true) }
    }

    override fun forceDirectory(path: Path) {
        java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private const val MAX_PART_CREATE_ATTEMPTS = 16
}
