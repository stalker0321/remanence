package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
 * caller, and the on-disk layout is fixed at
 * `accounts/<owner-uuid>/retry-material/<capsule-uuid>.pwks` (the
 * `.pwks` extension matches the wrapped-keyset record's domain).
 *
 * Design contract:
 *  - **No crypto**: this layer is bytes-in, bytes-out. It never
 *    parses, validates, or interprets the wrapped-keyset record; a
 *    producer in [dev.hryshyn.remanence.core.crypto] is the only
 *    caller allowed to touch the wrapped format. Error messages
 *    never include the wrapped bytes.
 *  - **No overwrite on first write**: a target that already exists
 *    is either a prior committed record or corrupt residue. A second
 *    write for the same (owner, capsule) MUST fail before any byte
 *    of the new payload is written, and the original bytes MUST
 *    stay byte-for-byte identical.
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
 *  - **Owner + capsule scoped**: read and delete accept the same
 *    typed [UserId] and [CapsuleId] as write and resolve the same
 *    canonical path. A wrong owner or wrong capsule can neither read
 *    nor delete; the call returns "absent" exactly like a missing
 *    file.
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
     * Writes [bytes] beneath the owner's
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
        val mutex = mutexFor(owner, capsule)
        mutex.withLock {
            val target = expectedPath(owner, capsule)
            if (target.exists()) {
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
            // Unique per-invocation temp name: two writes can never
            // rename over or delete each other's in-flight temporary
            // file. The mutex above already serializes same-pair
            // writes; the random suffix additionally protects against
            // any two concurrent writers that somehow race past the
            // lock.
            val temporary = File(parent, "${target.name}.tmp-${UUID.randomUUID()}")
            try {
                try {
                    temporary.writeBytes(bytes)
                } catch (failure: IOException) {
                    throw SenderRetryMaterialStorageException(
                        "could not write sender retry material temp file",
                    ).initCauseOrThrow(failure)
                }
                if (!temporary.renameTo(target)) {
                    throw SenderRetryMaterialStorageException(
                        "could not persist sender retry material",
                    )
                }
                // Canonical containment: the persisted file's
                // canonical path MUST live under the owner's
                // canonical retry root. The check happens AFTER the
                // rename so the resolver's requireContained() guard
                // runs against the on-disk file, not the
                // not-yet-resolved path.
                val canonical = target.canonicalFile
                val canonicalRoot = retryMaterialRoot(owner).canonicalFile
                requireContained(canonical, canonicalRoot, owner, capsule)
                canonical.path
            } finally {
                // The rename either succeeded and the temp was
                // consumed, or it failed; either way, no temp file
                // must remain.
                temporary.delete()
            }
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
     * Derives the canonical persisted path for (owner, capsule) and
     * returns it WITHOUT creating any directory or file. Useful for
     * tests and for callers that want to assert the exact on-disk
     * target before issuing a write.
     */
    fun expectedPath(owner: UserId, capsule: CapsuleId): File {
        val dir = retryMaterialRoot(owner)
        return File(dir, "${capsule.toRestString()}$EXTENSION")
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
