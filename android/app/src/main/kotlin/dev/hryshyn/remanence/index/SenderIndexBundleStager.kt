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
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
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
                    SemanticInspection.MISMATCH,
                    SemanticInspection.UNSAFE,
                    SemanticInspection.INVALID,
                    -> return@withLock failure(
                        SenderIndexBundleStageFailure.DESTINATION_CONFLICT,
                        false,
                    )
                }

                when (val staged = inspectSemantic(paths.temporary, plaintext!!, aad!!)) {
                    SemanticInspection.MATCH -> Unit
                    SemanticInspection.MISSING -> {
                        val sealed = try {
                            sealer.seal(plaintext!!, aad!!)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            return@withLock failure(
                                SenderIndexBundleStageFailure.SEALING_FAILED,
                                true,
                            )
                        }
                        try {
                            if (sealed.isEmpty() || sealed.size > MAX_CIPHERTEXT_BYTES) {
                                return@withLock failure(
                                    SenderIndexBundleStageFailure.SEALING_FAILED,
                                    retryable = false,
                                )
                            }
                            writeFresh(paths.temporary, sealed)
                            forceFile(paths.temporary)?.let { return@withLock it }
                            if (!forceDirectoryBestEffort(paths.temporary.parent!!)) {
                                return@withLock failure(
                                    SenderIndexBundleStageFailure.LOCAL_STORAGE,
                                    true,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: FileAlreadyExistsException) {
                            when (inspectSemantic(paths.temporary, plaintext!!, aad!!)) {
                                SemanticInspection.MATCH -> Unit
                                SemanticInspection.READ_FAILURE -> return@withLock failure(
                                    SenderIndexBundleStageFailure.LOCAL_STORAGE,
                                    true,
                                )
                                else -> return@withLock failure(
                                    SenderIndexBundleStageFailure.DESTINATION_CONFLICT,
                                    false,
                                )
                            }
                        } catch (_: SecurityException) {
                            return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                        } catch (_: IOException) {
                            return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                        } catch (_: UnsupportedOperationException) {
                            return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                        } catch (_: RuntimeException) {
                            return@withLock failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
                        } finally {
                            wipe(sealed)
                        }
                        when (inspectSemantic(paths.temporary, plaintext!!, aad!!)) {
                            SemanticInspection.MATCH -> Unit
                            SemanticInspection.READ_FAILURE -> return@withLock failure(
                                SenderIndexBundleStageFailure.LOCAL_STORAGE,
                                true,
                            )
                            else -> return@withLock failure(
                                SenderIndexBundleStageFailure.DESTINATION_CONFLICT,
                                false,
                            )
                        }
                    }
                    SemanticInspection.READ_FAILURE -> return@withLock failure(
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

                try {
                    fileSystem.atomicNoReplaceLink(paths.temporary, paths.destination)
                } catch (_: FileAlreadyExistsException) {
                    return@withLock reconcileExistingDestination(request, paths, plaintext!!, aad!!)
                } catch (_: FileSystemException) {
                    return@withLock failure(
                        SenderIndexBundleStageFailure.ATOMIC_MOVE_UNAVAILABLE,
                        false,
                    )
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
        return StagePaths(
            root = root,
            parent = parent,
            destination = parent.resolve("${capsule.toRestString()}.index.bundle").normalize(),
            temporary = parent.resolve("${capsule.toRestString()}.index.bundle.tmp").normalize(),
        )
    }

    private fun ensureDestinationParent(paths: StagePaths) {
        if (!isContained(paths.parent, paths.root) || !isSafePath(paths.root) || !isSafePath(paths.parent)) {
            throw PathUnsafe()
        }
        fileSystem.makeDirectories(paths.parent)
        if (!isDirectoryChainSafe(paths.root, paths.parent)) throw PathUnsafe()
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
                return SemanticInspection.INVALID
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

    private fun writeFresh(path: Path, sealed: ByteArray) {
        fileSystem.openWriteNew(path).use { output ->
            output.write(sealed)
            output.flush()
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
    ): SenderIndexBundleStageResult {
        return when (inspectSemantic(paths.destination, expectedPlaintext, aad)) {
            SemanticInspection.MATCH -> finishDurableDestination(
                request,
                paths,
                expectedPlaintext,
                aad,
                replayed = true,
            )
            SemanticInspection.READ_FAILURE -> failure(SenderIndexBundleStageFailure.LOCAL_STORAGE, true)
            else -> failure(SenderIndexBundleStageFailure.DESTINATION_CONFLICT, false)
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
    fun openWriteNew(path: Path): OutputStream
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

    override fun openWriteNew(path: Path): OutputStream =
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

    override fun atomicNoReplaceLink(source: Path, destination: Path) {
        Files.createLink(destination, source)
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    override fun forceFile(path: Path) {
        java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    override fun forceDirectory(path: Path) {
        java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }
}
