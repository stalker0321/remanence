package dev.hryshyn.remanence.index

import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The owner-bound, path-free input to one durable sender-index inspection. */
class SenderIndexBundleReadRequest(
    val authenticatedOwnerUserId: UserId?,
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
) {
    override fun toString(): String = "SenderIndexBundleReadRequest(<redacted>)"
}

enum class SenderIndexBundleReadCorruptReason {
    NO_AUTHENTICATED_OWNER,
    OWNER_MISMATCH,
    PATH_UNSAFE,
    NON_REGULAR_FILE,
    CIPHERTEXT_EMPTY,
    CIPHERTEXT_TOO_LARGE,
    CIPHERTEXT_SIZE_CHANGED,
    CIPHERTEXT_TRUNCATED,
    PLAINTEXT_TOO_LARGE,
    PLAINTEXT_MALFORMED,
    CAPSULE_MISMATCH,
}

enum class SenderIndexBundleReadUnavailableReason {
    LOCAL_STORAGE,
    SEALER_UNAVAILABLE,
}

/** Redacted result of inspecting one owner/capsule sender-index bundle. */
sealed interface SenderIndexBundleReadResult {
    class Available(
        val snapshot: SenderIndexBundleInspectionSnapshot,
    ) : SenderIndexBundleReadResult {
        override fun toString(): String = "SenderIndexBundleReadResult.Available(<redacted>)"
    }

    data object Missing : SenderIndexBundleReadResult

    data class Corrupt(
        val reason: SenderIndexBundleReadCorruptReason,
    ) : SenderIndexBundleReadResult

    data class Unavailable(
        val reason: SenderIndexBundleReadUnavailableReason,
    ) : SenderIndexBundleReadResult
}

/**
 * Wipeable in-memory view for a later account-scoped candidate reader. It has
 * no file, ciphertext, private-key, or path capability and never renders
 * payload fields. It may carry the v2 public sender verification key; v1
 * snapshots intentionally carry none. Callers must close it when loading is
 * complete.
 *
 * Getter results are caller-owned JVM values. An immutable String obtained
 * before close cannot be erased by this class; close wipes the snapshot's
 * internal mutable copies and rejects all later getter access.
 */
class SenderIndexBundleInspectionSnapshot internal constructor(
    localFormatVersion: Int,
    capsuleId: CapsuleId,
    senderHandleSnapshot: String,
    createdAtEpochSeconds: Long,
    placeLabel: String?,
    frontFingerprint: ByteArray,
    backFingerprint: ByteArray,
    senderVerification: SenderIndexBundleSenderVerification?,
    private val wipeBytes: (ByteArray) -> Unit,
    private val wipeChars: (CharArray) -> Unit,
) : AutoCloseable {
    private val localFormatVersionValue = localFormatVersion
    private val capsuleIdValue = capsuleId
    private val senderHandleChars = senderHandleSnapshot.toCharArray()
    private val createdAtEpochSecondsValue = createdAtEpochSeconds
    private val placeLabelChars = placeLabel?.toCharArray()
    private val frontFingerprintBytes = frontFingerprint.copyOf()
    private val backFingerprintBytes = backFingerprint.copyOf()
    private val senderVerificationValue = senderVerification?.copyForHandoff()
    private var closed = false

    val localFormatVersion: Int
        get() {
            checkOpen()
            return localFormatVersionValue
        }

    val capsuleId: CapsuleId
        get() {
            checkOpen()
            return capsuleIdValue
        }

    val senderHandleSnapshot: String
        get() {
            checkOpen()
            return String(senderHandleChars)
        }

    val createdAtEpochSeconds: Long
        get() {
            checkOpen()
            return createdAtEpochSecondsValue
        }

    val placeLabel: String?
        get() {
            checkOpen()
            return placeLabelChars?.let(::String)
        }

    val frontFingerprint: ByteArray
        get() = openCopy(frontFingerprintBytes)

    val backFingerprint: ByteArray
        get() = openCopy(backFingerprintBytes)

    /** Public sender verification material, absent only on legacy v1 bundles. */
    internal val senderVerification: SenderIndexBundleSenderVerification?
        get() {
            checkOpen()
            return senderVerificationValue?.copyForHandoff()
        }

    override fun close() {
        if (closed) return
        closed = true
        try {
            frontFingerprintBytes.fill(0)
            wipeBytes(frontFingerprintBytes)
        } finally {
            try {
                backFingerprintBytes.fill(0)
                wipeBytes(backFingerprintBytes)
            } finally {
                try {
                    senderHandleChars.fill('\u0000')
                    wipeChars(senderHandleChars)
                } finally {
                    placeLabelChars?.let { chars ->
                        chars.fill('\u0000')
                        wipeChars(chars)
                    }
                    senderVerificationValue?.wipe()
                }
            }
        }
    }

    override fun toString(): String = "SenderIndexBundleInspectionSnapshot(<redacted>)"

    private fun openCopy(bytes: ByteArray): ByteArray {
        checkOpen()
        return bytes.copyOf()
    }

    private fun checkOpen() {
        check(!closed) { "sender index snapshot is closed" }
    }
}

/**
 * Read-only A12b2 inspection of the deterministic A12a destination. The
 * reader accepts both the current v2 bundle and the canonical v1 recognition
 * bundle using the matching versioned AAD. v1 remains useful to recognition,
 * but has no sender verification key for offline presentation.
 * SecretSealer contract does not distinguish authentication failure from
 * provider/Keystore unavailability, so every unseal exception is conservatively
 * reported as [SenderIndexBundleReadResult.Unavailable] and the file is kept.
 */
class SenderIndexBundleReader internal constructor(
    private val roots: AccountScopedFileRoots,
    private val sealer: SecretSealer,
    private val codec: SenderIndexBundleCodec,
    private val fileSystem: SenderIndexBundleReaderFileSystem,
    private val wipe: (ByteArray) -> Unit,
    private val wipeChars: (CharArray) -> Unit = { it.fill('\u0000') },
) {

    constructor(
        roots: AccountScopedFileRoots,
        sealer: SecretSealer,
    ) : this(
        roots = roots,
        sealer = sealer,
        codec = SenderIndexBundleCodec(),
        fileSystem = RealSenderIndexBundleReaderFileSystem,
        wipe = { it.fill(0) },
        wipeChars = { it.fill('\u0000') },
    )

    /** Inspects only the authenticated owner's deterministic bundle path. */
    suspend fun inspect(
        request: SenderIndexBundleReadRequest,
    ): SenderIndexBundleReadResult {
        coroutineContext.ensureActive()
        val authenticatedOwner = request.authenticatedOwnerUserId
            ?: return SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.NO_AUTHENTICATED_OWNER,
            )
        if (authenticatedOwner != request.ownerUserId) {
            return SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.OWNER_MISMATCH,
            )
        }
        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            inspectOnIo(request)
        }
    }

    private suspend fun inspectOnIo(
        request: SenderIndexBundleReadRequest,
    ): SenderIndexBundleReadResult {
        val paths = try {
            resolvePaths(request.ownerUserId, request.capsuleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.PATH_UNSAFE,
            )
        }

        when (directoryChainState(paths.root, paths.parent)) {
            DirectoryChainState.MISSING -> return SenderIndexBundleReadResult.Missing
            DirectoryChainState.UNSAFE -> return SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.PATH_UNSAFE,
            )
            DirectoryChainState.UNAVAILABLE -> return unavailable(
                SenderIndexBundleReadUnavailableReason.LOCAL_STORAGE,
            )
            DirectoryChainState.SAFE -> Unit
        }

        val destinationAttributes = try {
            fileSystem.attributes(paths.destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(SenderIndexBundleReadUnavailableReason.LOCAL_STORAGE)
        }
        if (destinationAttributes == null) return SenderIndexBundleReadResult.Missing
        if (destinationAttributes.isSymbolicLink) {
            return corrupt(SenderIndexBundleReadCorruptReason.PATH_UNSAFE)
        }
        if (!destinationAttributes.isRegularFile || destinationAttributes.isDirectory) {
            return corrupt(SenderIndexBundleReadCorruptReason.NON_REGULAR_FILE)
        }
        if (destinationAttributes.size <= 0L) {
            return corrupt(SenderIndexBundleReadCorruptReason.CIPHERTEXT_EMPTY)
        }
        if (destinationAttributes.size > MAX_CIPHERTEXT_BYTES) {
            return corrupt(SenderIndexBundleReadCorruptReason.CIPHERTEXT_TOO_LARGE)
        }

        val read = readBounded(paths.destination, destinationAttributes.size)
        if (read !is ReadResult.Bytes) {
            return when (read) {
                ReadResult.Missing -> SenderIndexBundleReadResult.Missing
                ReadResult.SizeChanged -> corrupt(
                    SenderIndexBundleReadCorruptReason.CIPHERTEXT_SIZE_CHANGED,
                )
                ReadResult.Truncated -> corrupt(
                    SenderIndexBundleReadCorruptReason.CIPHERTEXT_TRUNCATED,
                )
                ReadResult.ReadFailure -> unavailable(
                    SenderIndexBundleReadUnavailableReason.LOCAL_STORAGE,
                )
            }
        }

        val ciphertext = read.bytes
        var aad: ByteArray? = null
        var opened: ByteArray? = null
        var decoded: SenderIndexBundlePlaintext? = null
        return try {
            for (formatVersion in listOf(
                SenderIndexBundleCodec.FORMAT_VERSION,
                SenderIndexBundleCodec.LEGACY_FORMAT_VERSION,
            )) {
                val candidateAad = SenderIndexBundleAad.encode(
                    request.ownerUserId,
                    request.capsuleId,
                    formatVersion,
                )
                try {
                    opened = sealer.unseal(ciphertext, candidateAad)
                    aad = candidateAad
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    wipe(candidateAad)
                }
            }
            if (opened == null) {
                // SecretSealer intentionally has no trustworthy corruption-vs-
                // provider distinction. Preserve the file and retry safely.
                return unavailable(SenderIndexBundleReadUnavailableReason.SEALER_UNAVAILABLE)
            }
            if (opened!!.isEmpty() || opened!!.size > SenderIndexBundleCodec.MAX_PLAINTEXT_BYTES) {
                return corrupt(SenderIndexBundleReadCorruptReason.PLAINTEXT_TOO_LARGE)
            }
            decoded = try {
                codec.decode(opened!!)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return corrupt(SenderIndexBundleReadCorruptReason.PLAINTEXT_MALFORMED)
            }
            if (decoded!!.capsuleId != request.capsuleId) {
                return corrupt(SenderIndexBundleReadCorruptReason.CAPSULE_MISMATCH)
            }
            SenderIndexBundleReadResult.Available(
                SenderIndexBundleInspectionSnapshot(
                    localFormatVersion = decoded!!.localFormatVersion,
                    capsuleId = decoded!!.capsuleId,
                    senderHandleSnapshot = decoded!!.senderHandleSnapshot,
                    createdAtEpochSeconds = decoded!!.createdAtEpochSeconds,
                    placeLabel = decoded!!.placeLabel,
                    frontFingerprint = decoded!!.frontFingerprint,
                    backFingerprint = decoded!!.backFingerprint,
                    senderVerification = decoded!!.senderVerification,
                    wipeBytes = wipe,
                    wipeChars = wipeChars,
                ),
            )
        } finally {
            wipe(ciphertext)
            aad?.let(wipe)
            opened?.let(wipe)
            decoded?.wipe()
        }
    }

    private fun resolvePaths(owner: UserId, capsule: CapsuleId): ReaderPaths {
        val root = roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
            .toPath().toAbsolutePath().normalize()
        val parent = root.resolve("capsules").normalize()
        val destination = parent.resolve("${capsule.toRestString()}.index.bundle").normalize()
        check(parent != root && parent.startsWith(root) && destination.startsWith(root)) {
            "sender index path escapes owner root"
        }
        return ReaderPaths(root, parent, destination)
    }

    private fun directoryChainState(root: Path, parent: Path): DirectoryChainState {
        if (!parent.startsWith(root) || parent == root) return DirectoryChainState.UNSAFE
        val filesystemRoot = root.root ?: return DirectoryChainState.UNSAFE
        var current = filesystemRoot
        return try {
            for (segment in filesystemRoot.relativize(parent)) {
                current = current.resolve(segment)
                val attributes = fileSystem.attributes(current)
                    ?: return DirectoryChainState.MISSING
                if (attributes.isSymbolicLink || !attributes.isDirectory) {
                    return DirectoryChainState.UNSAFE
                }
            }
            DirectoryChainState.SAFE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            DirectoryChainState.UNAVAILABLE
        } catch (_: SecurityException) {
            DirectoryChainState.UNAVAILABLE
        } catch (_: UnsupportedOperationException) {
            DirectoryChainState.UNAVAILABLE
        } catch (_: RuntimeException) {
            DirectoryChainState.UNAVAILABLE
        }
    }

    private suspend fun readBounded(path: Path, expectedSize: Long): ReadResult {
        val output = ByteArray(expectedSize.toInt())
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0L
        var returned = false
        try {
            fileSystem.openRead(path).use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) return ReadResult.ReadFailure
                    if (count.toLong() > expectedSize - total) {
                        return ReadResult.SizeChanged
                    }
                    buffer.copyInto(
                        output,
                        destinationOffset = total.toInt(),
                        endIndex = count,
                    )
                    total += count
                }
            }
            if (total != expectedSize) return ReadResult.Truncated
            returned = true
            return ReadResult.Bytes(output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.nio.file.NoSuchFileException) {
            return ReadResult.Missing
        } catch (_: IOException) {
            return ReadResult.ReadFailure
        } catch (_: SecurityException) {
            return ReadResult.ReadFailure
        } catch (_: UnsupportedOperationException) {
            return ReadResult.ReadFailure
        } catch (_: RuntimeException) {
            return ReadResult.ReadFailure
        } finally {
            wipe(buffer)
            if (!returned) wipe(output)
        }
    }

    private fun corrupt(reason: SenderIndexBundleReadCorruptReason) =
        SenderIndexBundleReadResult.Corrupt(reason)

    private fun unavailable(reason: SenderIndexBundleReadUnavailableReason) =
        SenderIndexBundleReadResult.Unavailable(reason)

    private data class ReaderPaths(
        val root: Path,
        val parent: Path,
        val destination: Path,
    )

    private enum class DirectoryChainState {
        SAFE,
        MISSING,
        UNSAFE,
        UNAVAILABLE,
    }

    private sealed interface ReadResult {
        data class Bytes(val bytes: ByteArray) : ReadResult
        data object Missing : ReadResult
        data object SizeChanged : ReadResult
        data object Truncated : ReadResult
        data object ReadFailure : ReadResult
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 32 * 1024
        const val MAX_CIPHERTEXT_BYTES = SenderIndexBundleCodec.MAX_PLAINTEXT_BYTES +
            ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES.toInt()
    }
}

internal interface SenderIndexBundleReaderFileSystem {
    fun attributes(path: Path): SenderIndexBundleFileAttributes?
    fun openRead(path: Path): InputStream
}

private object RealSenderIndexBundleReaderFileSystem : SenderIndexBundleReaderFileSystem {
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

    override fun openRead(path: Path): InputStream =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
}
