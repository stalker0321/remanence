package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * M2-P08: app-private persistence for the sender-owned wrapped retry
 * keyset byte stream (the on-disk side of the new
 * `outbox_capsule.sender_retry_keyset_path` column). The store lives
 * on top of [AccountScopedFileRoots] and is the ONLY path through
 * which this material lands on disk: every read, write, and delete
 * is owner-scoped AND capsule-scoped, the persisted file name is
 * derived from the typed [CapsuleId] and never accepted from the
 * caller. Legacy callers use
 * `accounts/<owner-uuid>/retry-material/<capsule-uuid>.pwks`; outbox
 * reservations use the non-reused
 * `<capsule-uuid>-<reservation-uuid>.pwks` form (the `.pwks` extension
 * matches the wrapped-keyset record's domain).
 *
 * Design contract:
 *  - **No crypto**: this layer is bytes-in, bytes-out. It never
 *    parses, validates, or interprets the wrapped-keyset record; a
 *    producer in [dev.hryshyn.remanence.core.crypto] is the only
 *    caller allowed to touch the wrapped format. Error messages
 *    never include the wrapped bytes.
 *  - **No overwrite on first write**: a target that already exists is
 *    either a prior committed record or corrupt residue. A second write
 *    to the same target MUST fail before any byte of the new payload is
 *    written, and the original bytes MUST stay byte-for-byte identical.
 *  - **Per-key serialization**: every write for the same
 *    (owner, capsule) pair is serialized through a per-pair
 *    coroutine [Mutex], so two concurrent writes cannot both pass
 *    the existence check and silently replace each other. Reads
 *    and deletes share the same per-pair lock so a write in flight
 *    is observed as absent by a concurrent read/delete only after
 *    the write returns.
 *  - **Atomic on disk**: writes go to a same-directory temp file
 *    with a random suffix and rename into place; the temp is
 *    removed in a `finally` block so a failure cannot leave residue.
 *  - **Owner + capsule scoped**: every path resolver accepts the same
 *    typed [UserId] and [CapsuleId] as write. Stored row pointers are
 *    accepted only when they resolve to the fixed legacy path or a
 *    reservation-owned path for that pair. A wrong owner or wrong
 *    capsule can neither read nor delete.
 *  - **Canonical containment**: the path returned by [write] is
 *    the canonical path of the persisted file and is always
 *    contained beneath the canonical retry root for [owner].
 *  - **Delete is idempotent**: a missing target is a clean no-op
 *    that returns `false`. A present target is removed and the
 *    function returns `true`; if the platform reports a successful
 *    delete but the file is still present (an adversarial or
 *    partially-failed filesystem), the call fails closed with
 *    [SenderRetryMaterialStorageException] so the lifecycle cannot
 *    silently lose the invariant.
 *  - **Fail-closed on corrupt storage**: an empty persisted file is
 *    a storage-level corruption (writes always refuse empty input)
 *    and surfaces as [SenderRetryMaterialStorageException] from
 *    [read]. A missing file is the normal absent state and returns
 *    `null` from [read]. The store never attempts to interpret the
 *    bytes; a non-empty byte array is the only success shape.
 */
class SenderRetryMaterialStore(
    private val roots: AccountScopedFileRoots,
) {

    /**
     * Writes [bytes] beneath the owner's legacy
     * `accounts/<owner>/retry-material/<capsule>.pwks` file. Refuses
     * empty payloads, refuses to overwrite an existing target, and
     * performs an atomic same-directory write through a temp file
     * whose random suffix is removed in a `finally` block. Returns
     * the canonical persisted path so the caller can persist it as
     * the `outbox_capsule.sender_retry_keyset_path` value.
     *
     * Every (owner, capsule) pair is serialized through a per-pair
     * coroutine mutex; two concurrent writes for the same pair
     * cannot both pass the existence check.
     *
     * @throws IllegalArgumentException if [bytes] is empty.
     * @throws SenderRetryMaterialStorageException if the target
     *   already exists, the on-disk rename failed, or the
     *   resulting file would escape the canonical retry root.
     */
    suspend fun write(
        owner: UserId,
        capsule: CapsuleId,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) {
            "refusing to persist empty sender retry material for $capsule"
        }
        mutexFor(owner, capsule).withLock {
            writeTarget(owner, capsule, expectedPath(owner, capsule), bytes)
        }
    }

    /** Writes a reservation-owned retry target without reusing the legacy path. */
    suspend fun writeForAttempt(
        owner: UserId,
        capsule: CapsuleId,
        attemptId: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) {
            "refusing to persist empty sender retry material for $capsule"
        }
        mutexFor(owner, capsule).withLock {
            writeTarget(owner, capsule, attemptPath(owner, capsule, attemptId), bytes)
        }
    }

    private suspend fun writeTarget(
        owner: UserId,
        capsule: CapsuleId,
        target: File,
        bytes: ByteArray,
    ): String {
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SenderRetryMaterialStorageException(
                "sender retry material already present for $capsule; refusing overwrite",
            )
        }
        val parent = target.parentFile
            ?: throw SenderRetryMaterialStorageException(
                "sender retry material directory resolved to null for $capsule",
            )
        try {
            if (!parent.exists() && !parent.mkdirs() && (!parent.exists() || !parent.isDirectory())) {
                throw SenderRetryMaterialStorageException(
                    "could not prepare sender retry material directory",
                )
            }
        } catch (failure: SecurityException) {
            throw SenderRetryMaterialStorageException(
                "could not prepare sender retry material directory",
            ).initCauseOrThrow(failure)
        }
        val temporary = File(parent, "${target.name}.tmp-${UUID.randomUUID()}")
        return try {
            try {
                temporary.writeBytes(bytes)
            } catch (failure: IOException) {
                throw SenderRetryMaterialStorageException(
                    "could not write sender retry material temp file",
                ).initCauseOrThrow(failure)
            }
            try {
                // No REPLACE_EXISTING: a concurrent winner's reservation-owned
                // target must remain untouched if it appears after preflight.
                Files.move(temporary.toPath(), target.toPath())
            } catch (failure: IOException) {
                throw SenderRetryMaterialStorageException(
                    "could not persist sender retry material",
                ).initCauseOrThrow(failure)
            }
            val canonical = target.canonicalFile
            val canonicalRoot = retryMaterialRoot(owner).canonicalFile
            requireContained(canonical, canonicalRoot, owner, capsule)
            canonical.path
        } finally {
            temporary.delete()
        }
    }

    /**
     * Reads the wrapped retry material for (owner, capsule) if and
     * only if a non-empty target file already exists at the canonical
     * location. A missing file is the normal absent state and
     * returns `null`. An empty file is a storage-level corruption
     * (the store never writes one) and surfaces as
     * [SenderRetryMaterialStorageException]. The returned bytes are
     * exactly the bytes the caller wrote - the store never parses
     * or re-encodes them.
     */
    suspend fun read(
        owner: UserId,
        capsule: CapsuleId,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val mutex = mutexFor(owner, capsule)
        mutex.withLock {
            val target = expectedPath(owner, capsule)
            if (!target.exists()) return@withLock null
            val bytes = try {
                target.readBytes()
            } catch (failure: IOException) {
                throw SenderRetryMaterialStorageException(
                    "could not read sender retry material",
                ).initCauseOrThrow(failure)
            }
            if (bytes.isEmpty()) {
                throw SenderRetryMaterialStorageException(
                    "sender retry material at $target is empty; storage-level corruption",
                )
            }
            bytes
        }
    }

    /** Reads a fixed legacy path or a reservation-owned persisted pointer. */
    suspend fun readAt(
        owner: UserId,
        capsule: CapsuleId,
        storedPath: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        mutexFor(owner, capsule).withLock {
            val target = resolveStoredPath(owner, capsule, storedPath)
                ?: throw SenderRetryMaterialStorageException("sender retry material pointer is not owner-scoped")
            val path = target.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withLock null
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw SenderRetryMaterialStorageException("sender retry material target is not a regular file")
            }
            val bytes = try {
                Files.readAllBytes(path)
            } catch (failure: IOException) {
                throw SenderRetryMaterialStorageException(
                    "could not read sender retry material",
                ).initCauseOrThrow(failure)
            }
            if (bytes.isEmpty()) {
                throw SenderRetryMaterialStorageException("sender retry material is empty")
            }
            bytes
        }
    }

    /** Returns whether a persisted pointer is the fixed legacy or attempt path for this owner/capsule. */
    fun isCanonicalPath(owner: UserId, capsule: CapsuleId, storedPath: String): Boolean =
        resolveStoredPath(owner, capsule, storedPath) != null

    /** Deletes only the exact owner/capsule pointer stored in the committed row. */
    suspend fun deleteAt(
        owner: UserId,
        capsule: CapsuleId,
        storedPath: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutexFor(owner, capsule).withLock {
            val target = resolveStoredPath(owner, capsule, storedPath)
                ?: throw SenderRetryMaterialStorageException("sender retry material pointer is not owner-scoped")
            val path = target.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withLock false
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw SenderRetryMaterialStorageException("sender retry material target is not a regular file")
            }
            try {
                Files.delete(path)
            } catch (failure: IOException) {
                throw SenderRetryMaterialStorageException(
                    "could not delete sender retry material",
                ).initCauseOrThrow(failure)
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw SenderRetryMaterialStorageException("sender retry material target still exists")
            }
            true
        }
    }

    /** Deletes an attempt target only after confirming its exact prepared bytes. */
    suspend fun deleteAttempt(
        owner: UserId,
        capsule: CapsuleId,
        attemptId: String,
        expectedBytes: ByteArray,
        expectedFileKey: Any,
    ): Boolean = withContext(Dispatchers.IO) {
        mutexFor(owner, capsule).withLock {
            val target = attemptPath(owner, capsule, attemptId)
            val path = target.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withLock false
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw SenderRetryMaterialStorageException("sender retry attempt target is not a regular file")
            }
            val fileKey = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                ?: throw SenderRetryMaterialStorageException("sender retry attempt target has no stable file identity")
            if (fileKey != expectedFileKey) {
                throw SenderRetryMaterialStorageException("sender retry attempt target changed")
            }
            if (!Files.readAllBytes(path).contentEquals(expectedBytes)) {
                throw SenderRetryMaterialStorageException("sender retry attempt target changed")
            }
            val finalKey = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                ?: throw SenderRetryMaterialStorageException("sender retry attempt target has no stable file identity")
            if (finalKey != expectedFileKey || !Files.readAllBytes(path).contentEquals(expectedBytes)) {
                throw SenderRetryMaterialStorageException("sender retry attempt target changed")
            }
            Files.delete(path)
            check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                "sender retry attempt target still exists"
            }
            true
        }
    }

    /**
     * Removes the wrapped retry material for (owner, capsule) if
     * present. Idempotent: returns `true` when a file was removed
     * and `false` when no file was present. If the platform reports
     * a successful delete but the file is still present, the call
     * fails closed with [SenderRetryMaterialStorageException] so a
     * silent loss of the invariant cannot survive.
     */
    suspend fun delete(
        owner: UserId,
        capsule: CapsuleId,
    ): Boolean = withContext(Dispatchers.IO) {
        val mutex = mutexFor(owner, capsule)
        mutex.withLock {
            val target = expectedPath(owner, capsule)
            if (!target.exists()) return@withLock false
            val removed = try {
                target.delete()
            } catch (failure: SecurityException) {
                throw SenderRetryMaterialStorageException(
                    "could not delete sender retry material",
                ).initCauseOrThrow(failure)
            }
            // Fail-closed: if the platform lied about the delete,
            // the file is still on disk under the canonical retry
            // root and we MUST surface that, not silently leave the
            // lifecycle believing the row was cleaned.
            if (!removed) {
                if (target.exists()) {
                    throw SenderRetryMaterialStorageException(
                        "delete reported success but target still exists",
                    )
                }
                return@withLock false
            }
            true
        }
    }

    /**
     * Validates, but deliberately does not delete, the legacy fixed
     * owner/capsule retry target left by an interrupted outbox staging attempt.
     * A pathname delete cannot prove that the inode validated here is still
     * the inode at cleanup time. Legacy residue is therefore preserved; new
     * attempts use reservation-owned paths and never reuse this name.
     */
    suspend fun reconcileOrphan(
        owner: UserId,
        capsule: CapsuleId,
        expectedBytes: ByteArray?,
    ): Boolean = withContext(Dispatchers.IO) {
        mutexFor(owner, capsule).withLock {
            val target = expectedPath(owner, capsule)
            val path = target.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withLock false
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw SenderRetryMaterialStorageException(
                    "canonical sender retry target is not a regular file",
                )
            }
            val expected = expectedBytes ?: throw SenderRetryMaterialStorageException(
                "unexpected canonical sender retry target",
            )
            val actual = try {
                Files.readAllBytes(path)
            } catch (failure: IOException) {
                throw SenderRetryMaterialStorageException(
                    "could not inspect canonical sender retry target",
                ).initCauseOrThrow(failure)
            }
            if (!actual.contentEquals(expected)) {
                throw SenderRetryMaterialStorageException(
                    "canonical sender retry target is ambiguous",
                )
            }
            true
        }
    }

    /** Reconciles exact reservation-owned retry orphans; paths are never reused by a winner. */
    suspend fun reconcileOrphanAttempts(
        owner: UserId,
        capsule: CapsuleId,
        expectedBytes: ByteArray?,
        beforeDelete: (() -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        mutexFor(owner, capsule).withLock {
            val root = retryMaterialRoot(owner)
            val candidates = root.listFiles().orEmpty().filter { file ->
                val name = file.name
                name.startsWith("${capsule.toRestString()}-") && name.endsWith(EXTENSION) &&
                    runCatching {
                        UUID.fromString(name.removePrefix("${capsule.toRestString()}-").removeSuffix(EXTENSION))
                    }.isSuccess
            }
            if (candidates.isEmpty()) return@withLock 0
            val expected = expectedBytes ?: throw SenderRetryMaterialStorageException(
                "unexpected reservation-owned sender retry target",
            )
            val fileKeys = candidates.associateWith { target ->
                val path = target.toPath()
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw SenderRetryMaterialStorageException("reservation-owned retry target is not a regular file")
                }
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                    ?: throw SenderRetryMaterialStorageException("reservation-owned retry target has no stable file identity")
            }
            candidates.forEach { target ->
                val path = target.toPath()
                if (!Files.readAllBytes(path).contentEquals(expected)) {
                    throw SenderRetryMaterialStorageException("reservation-owned retry target is ambiguous")
                }
            }
            beforeDelete?.invoke()
            candidates.forEach { target ->
                val path = target.toPath()
                if (
                    Files.isSymbolicLink(path) ||
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() != fileKeys[target] ||
                    !Files.readAllBytes(path).contentEquals(expected)
                ) {
                    throw SenderRetryMaterialStorageException("reservation-owned retry target changed")
                }
                Files.delete(path)
                check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    "reservation-owned retry target still exists"
                }
            }
            candidates.size
        }
    }

    /**
     * Derives the legacy fixed path for (owner, capsule) without creating
     * any directory or file. New outbox rows persist [attemptPath] instead.
     */
    fun expectedPath(owner: UserId, capsule: CapsuleId): File {
        val dir = retryMaterialRoot(owner)
        return File(dir, "${capsule.toRestString()}$EXTENSION")
    }

    /** Derives the non-reused retry target owned by one staging reservation. */
    fun attemptPath(owner: UserId, capsule: CapsuleId, attemptId: String): File {
        val canonicalAttempt = runCatching { UUID.fromString(attemptId) }.getOrNull()
            ?.takeIf { it.toString() == attemptId }
            ?: throw SenderRetryMaterialStorageException("sender retry attempt id is not canonical")
        val dir = retryMaterialRoot(owner)
        val target = File(dir, "${capsule.toRestString()}-$canonicalAttempt$EXTENSION")
        requireContained(target, dir.canonicalFile, owner, capsule)
        return target
    }

    private fun resolveStoredPath(owner: UserId, capsule: CapsuleId, storedPath: String): File? {
        val target = runCatching { File(storedPath).canonicalFile }.getOrNull() ?: return null
        val rawPath = runCatching { File(storedPath).toPath() }.getOrNull() ?: return null
        if (Files.isSymbolicLink(rawPath)) return null
        val fixed = expectedPath(owner, capsule).canonicalFile
        if (target == fixed) return target
        val root = retryMaterialRoot(owner).canonicalFile
        val prefix = "${capsule.toRestString()}-"
        val suffix = EXTENSION
        if (target.parentFile?.canonicalFile != root ||
            !target.name.startsWith(prefix) ||
            !target.name.endsWith(suffix)
        ) return null
        val attemptId = target.name.removePrefix(prefix).removeSuffix(suffix)
        return runCatching { UUID.fromString(attemptId) }
            .getOrNull()
            ?.takeIf { it.toString() == attemptId }
            ?.let { target }
    }

    /**
     * The canonical retry root for [owner] - the directory beneath
     * `accounts/<owner>/retry-material/`. Returned for containment
     * checks; the directory is not created by this call.
     */
    private fun retryMaterialRoot(owner: UserId): File =
        roots.child(owner, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL)

    /**
     * Asserts the persisted file's canonical path lives under the
     * canonical retry root. The boundary check is deliberately
     * performed against canonical paths so symlinks and `..`
     * segments inside the writer cannot escape the accounts root.
     */
    private fun requireContained(
        candidateCanonical: File,
        rootCanonical: File,
        owner: UserId,
        capsule: CapsuleId,
    ) {
        val rootPath = rootCanonical.path
        val requiredPrefix = if (rootPath.endsWith(File.separator)) {
            rootPath
        } else {
            "$rootPath${File.separator}"
        }
        val candidatePath = candidateCanonical.path
        val contained = candidatePath == rootPath || candidatePath.startsWith(requiredPrefix)
        if (!contained) {
            throw SenderRetryMaterialStorageException(
                "persisted sender retry material escaped the canonical retry root",
            )
        }
    }

    /**
     * Per-(owner, capsule) coroutine mutex. The map is a process
     * cache; the lifecycle never sees the key. Same-pair writes
     * serialize here, and reads/deletes share the same lock so a
     * writer's rename is observed atomically by any reader that
     * takes the lock after the writer releases it.
     */
    private fun mutexFor(owner: UserId, capsule: CapsuleId): Mutex {
        val key = Pair(owner, capsule)
        return mutexes.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Re-throws if [Throwable.initCause] is unsupported on the JVM
     * (it is on every supported JVM but the API is checked). Returns
     * the receiver so the throw site stays one expression.
     */
    private fun Throwable.initCauseOrThrow(cause: Throwable): Throwable {
        try {
            initCause(cause)
        } catch (_: IllegalStateException) {
            // Already initialised; preserve the original cause and
            // continue so the caller still sees a typed failure.
        } catch (_: IllegalArgumentException) {
            // initCause(self) - impossible here; ignore.
        }
        return this
    }

    private companion object {
        /** Filename extension: matches the wrapped-keyset domain. */
        const val EXTENSION: String = ".pwks"

        val mutexes: ConcurrentHashMap<Pair<UserId, CapsuleId>, Mutex> = ConcurrentHashMap()
    }
}

/**
 * M2-P08: a typed failure surfaced by [SenderRetryMaterialStore]
 * for any storage-level problem the store refuses to silently mask:
 * overwrite refusal, corrupt empty file, I/O failure, rename
 * failure, canonical containment violation, or a delete that
 * reported success but left the file on disk. Callers should map
 * this to their own retry-or-fail surface; the store never logs the
 * wrapped bytes.
 */
class SenderRetryMaterialStorageException(message: String) : RuntimeException(message)
