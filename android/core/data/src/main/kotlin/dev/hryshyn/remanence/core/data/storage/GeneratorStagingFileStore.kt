package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * G4-A — production owner-scoped filesystem [GeneratorStaging.BlobStore]
 * plus retention/recovery integration for G3 staging leases.
 *
 * Layout (all beneath the account-owned TEMP root, never elsewhere):
 * ```
 * temp/create/gen-staging/<owner-uuid>/<sessionId>/manifest
 * temp/create/gen-staging/<owner-uuid>/<sessionId>/<ordinal>
 * ```
 * - One store instance is bound to exactly one [owner]; [keys] enumerates
 *   only that owner's namespace, never across accounts.
 * - G3 keys (`<sessionId>/<ordinal>`) are treated as OPAQUE authenticated
 *   tokens, never as paths: only `stg-[0-9a-f]{16}` session ids and
 *   numeric ordinals are accepted, anything else fails fast.
 * - Every resolution is containment-checked
 *   ([AccountScopedFileRoots.isContainedPath]) and every access uses
 *   NOFOLLOW semantics; symlinks are unlinked, never followed, via
 *   [AccountStorageRetention.deleteNoFollow].
 * - Writes are atomic temp-write + fsync + atomic rename; a failed write
 *   leaves no partial entry behind.
 * - Reads and writes are bounded at 32 MiB (raw-source limit, same value
 *   as `PhotoStagingPipeline.MAX_SOURCE_BYTES`).
 * - Recovery contract (G3 `BlobStore` seam): each session directory
 *   carries a `manifest` file (session/owner/epoch/revision/createdAt).
 *   [sweepOwner] deletes session directories absent from the caller-given
 *   live set plus unknown entries, strictly inside this owner's
 *   namespace; foreign owners are never enumerated or touched.
 * - Lifecycle hooks at the storage boundary: [deleteSession] backs
 *   revoke/close, [onLogout] delegates to
 *   [AccountStorageRetention.onLogout] (purges the whole TEMP root,
 *   durable ciphertext untouched).
 *
 * Out of scope: URI adaptation, normalization, Create bridge, renderers,
 * publishing, receive presentation, encryption transport, M4.
 */
class GeneratorStagingFileStore(
    private val roots: AccountScopedFileRoots,
    private val retention: AccountStorageRetention,
    private val owner: UserId,
) : GeneratorStaging.BlobStore {

    companion object {
        const val NAMESPACE_DIR = "gen-staging"
        const val MANIFEST_NAME = "manifest"

        /** Raw-source byte bound shared with the staging pipeline. */
        const val MAX_BLOB_BYTES = 32 * 1024 * 1024

        private val SESSION_ID = Regex("^stg-[0-9a-f]{16}$")
        private val ORDINAL = Regex("^[0-9]+$")
    }

    private fun namespaceRoot(): File {
        val root = File(
            roots.createStagingRoot(owner),
            NAMESPACE_DIR,
        )
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw AccountStorageCleanupException("cannot materialise staging namespace for $owner")
        }
        val safety = roots.trustedPathSafety(root.toPath())
        if (safety != TrustedPathSafety.SAFE) {
            throw AccountStorageCleanupException("staging namespace unsafe for $owner: $safety")
        }
        return root
    }

    private fun splitKey(key: String): Pair<String, String> {
        val parts = key.split("/")
        if (parts.size != 2 || !SESSION_ID.matches(parts[0]) || !ORDINAL.matches(parts[1])) {
            throw IllegalArgumentException("opaque staging key rejected: not a session/ordinal token")
        }
        return parts[0] to parts[1]
    }

    private fun resolve(sessionId: String, name: String): File {
        val namespace = namespaceRoot().toPath().toAbsolutePath().normalize()
        val sessionDir = namespace.resolve(sessionId).normalize()
        val target = sessionDir.resolve(name).normalize()
        if (!roots.isContainedPath(target, namespace)) {
            throw AccountStorageCleanupException("staging path escapes namespace for $owner")
        }
        if (Files.isSymbolicLink(sessionDir) || Files.isSymbolicLink(target)) {
            throw AccountStorageCleanupException("staging symlink refused for $owner")
        }
        return target.toFile()
    }

    override fun put(key: String, bytes: ByteArray) {
        if (bytes.size > MAX_BLOB_BYTES) {
            throw IllegalArgumentException("staging blob over 32 MiB bound")
        }
        val (sessionId, ordinal) = splitKey(key)
        writeBytes(sessionId, ordinal, bytes)
    }

    private fun writeBytes(sessionId: String, name: String, bytes: ByteArray) {
        val target = resolve(sessionId, name)
        target.parentFile?.let {
            if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) {
                throw AccountStorageCleanupException("cannot materialise staging session for $owner")
            }
        }
        val tmp = File(target.parent, "${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: IOException) {
                tmp.delete()
                throw AccountStorageCleanupException("staging atomic write failed for $owner: ${e.message}", e)
            }
        } catch (e: AccountStorageCleanupException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            throw AccountStorageCleanupException("staging write failed for $owner: ${e.message}", e)
        }
    }

    override fun get(key: String): ByteArray? {
        val (sessionId, ordinal) = splitKey(key)
        val target = resolve(sessionId, ordinal)
        if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(target.toPath()) > MAX_BLOB_BYTES) {
            throw AccountStorageCleanupException("staging blob over 32 MiB bound for $owner")
        }
        return Files.readAllBytes(target.toPath())
    }

    override fun delete(key: String): Boolean {
        val (sessionId, ordinal) = splitKey(key)
        val target = resolve(sessionId, ordinal)
        return try {
            Files.deleteIfExists(target.toPath())
        } catch (e: IOException) {
            false
        }
    }

    override fun keys(): Set<String> {
        val namespace = namespaceRoot()
        val found = mutableSetOf<String>()
        val sessions = namespace.listFiles() ?: return emptySet()
        for (sessionDir in sessions) {
            if (!SESSION_ID.matches(sessionDir.name)) continue
            if (Files.isSymbolicLink(sessionDir.toPath())) continue
            val blobs = try {
                sessionDir.listFiles()
            } catch (_: SecurityException) {
                continue
            } ?: continue
            for (blob in blobs) {
                if (!ORDINAL.matches(blob.name)) continue
                if (Files.isSymbolicLink(blob.toPath())) continue
                if (roots.isContainedPath(
                        blob.toPath().toAbsolutePath().normalize(),
                        namespace.toPath().toAbsolutePath().normalize(),
                    )
                ) {
                    found += "${sessionDir.name}/${blob.name}"
                }
            }
        }
        return found
    }

    /** Writes the session recovery manifest (owner/session/epoch/revision). */
    fun writeManifest(sessionId: String, epoch: Long, revision: Long, createdAtMillis: Long) {
        require(SESSION_ID.matches(sessionId)) { "opaque session id rejected" }
        val target = resolve(sessionId, MANIFEST_NAME)
        target.parentFile?.mkdirs()
        writeBytes(sessionId, MANIFEST_NAME, manifestBytes(sessionId, epoch, revision, createdAtMillis))
    }

    /** Reads a session manifest, or null when absent/unreadable/foreign. */
    fun readManifest(sessionId: String): Map<String, String>? {
        if (!SESSION_ID.matches(sessionId)) return null
        val target = try {
            resolve(sessionId, MANIFEST_NAME)
        } catch (_: AccountStorageCleanupException) {
            return null
        }
        if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            target.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap().takeIf { it["sessionId"] == sessionId && it["owner"] == owner.toRestString() }
        } catch (_: IOException) {
            null
        }
    }

    /** Deletes one session directory (revoke/close hook). Best-effort. */
    fun deleteSession(sessionId: String): Boolean {
        if (!SESSION_ID.matches(sessionId)) return false
        val namespace = namespaceRoot().toPath().toAbsolutePath().normalize()
        val dir = namespace.resolve(sessionId).normalize()
        if (!roots.isContainedPath(dir, namespace)) return false
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            retention.deleteNoFollow(dir.toFile())
            true
        } catch (_: AccountStorageCleanupException) {
            false
        }
    }

    /**
     * Restart orphan sweep for exactly this [owner]: deletes session
     * directories absent from [liveSessionIds] plus unknown entries,
     * strictly inside the owner's namespace. Foreign owners are never
     * enumerated. Returns the number of removed top-level entries.
     */
    fun sweepOwner(liveSessionIds: Set<String>): Int {
        val namespace = namespaceRoot()
        var removed = 0
        val entries = namespace.listFiles() ?: return 0
        for (entry in entries) {
            val knownLive = SESSION_ID.matches(entry.name) && liveSessionIds.contains(entry.name)
            if (knownLive) continue
            if (!SESSION_ID.matches(entry.name)) {
                // Unknown entry inside OUR namespace only: unlink, never follow.
                try {
                    retention.deleteNoFollow(entry)
                    removed++
                } catch (_: AccountStorageCleanupException) {
                    continue
                }
                continue
            }
            try {
                retention.deleteNoFollow(entry)
                removed++
            } catch (_: AccountStorageCleanupException) {
                continue
            }
        }
        return removed
    }

    /** Logout hook: purges the whole TEMP root via retention (durable kept). */
    fun onLogout() {
        retention.onLogout(owner)
    }

    private fun manifestBytes(sessionId: String, epoch: Long, revision: Long, createdAtMillis: Long): ByteArray =
        buildString {
            appendLine("sessionId=$sessionId")
            appendLine("owner=${owner.toRestString()}")
            appendLine("epoch=$epoch")
            appendLine("revision=$revision")
            appendLine("createdAt=$createdAtMillis")
        }.toByteArray(Charsets.UTF_8)
}
