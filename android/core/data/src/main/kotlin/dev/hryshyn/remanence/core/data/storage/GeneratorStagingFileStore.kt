package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
 *   canonical ordinals `0..MAX_LEASES_PER_SESSION-1` are accepted,
 *   anything else fails fast (leading zeros, signs and huge numbers
 *   included).
 * - Every resolution is containment-checked
 *   ([AccountScopedFileRoots.isContainedPath]) and every access uses
 *   NOFOLLOW semantics; symlinks are unlinked, never followed, via
 *   [AccountStorageRetention.deleteNoFollow].
 * - Writes follow CREATE_NEW doctrine: an existing target is refused
 *   before temp creation and rechecked immediately before rename, so a
 *   replay or concurrent writer can never replace a committed blob or
 *   manifest. G4 ordering note: restart recovery must sweep before
 *   re-staging, or a legitimate post-crash re-stage hits this refusal.
 * - Writes are atomic temp-write + fsync + atomic rename; a failed write
 *   leaves no partial entry behind. Temp files are session-bound
 *   (`<name>.tmp-<nano>`), at most one residue per dead write, swept
 *   with the session.
 * - Reads and writes are bounded at 32 MiB (raw-source limit, same value
 *   as `PhotoStagingPipeline.MAX_SOURCE_BYTES`); reads use a local
 *   capped loop, never an unbounded pre-sized read.
 * - Directory-entry durability is best-effort via [syncDirectory]: where
 *   the filesystem refuses directory fsync, the result is explicit
 *   [DirSyncResult.UNSUPPORTED] and full rename durability is NOT
 *   claimed; recovery then relies on manifest presence plus orphan sweep
 *   (a missing blob renders its session unusable and swept).
 * - Hardlinks are treated as regular entries: content is authenticated
 *   downstream by hash (foreign bytes fail closed unless byte-identical,
 *   in which case harmless), and cleanup unlinks names only without
 *   following.
 * - Recovery contract (G3 `BlobStore` seam): each session directory
 *   carries a versioned `manifest` file (format/session/owner/epoch/
 *   revision/createdAt, exact grammar enforced). [sweepOwner] deletes
 *   session directories absent from the caller-given live set plus
 *   unknown entries, strictly inside this owner's namespace; foreign
 *   owners are never enumerated or touched. A wrong live set is
 *   destructive by design — live-set correctness is a G4-caller
 *   obligation.
 * - Lifecycle hooks at the storage boundary: [deleteSession] backs
 *   revoke/close, [onLogout] delegates to
 *   [AccountStorageRetention.onLogout] (purges the whole TEMP root,
 *   durable ciphertext untouched).
 *
 * Throws contract: [IllegalArgumentException] for malformed keys,
 * over-bound or empty blobs and bad session ids (caller bugs,
 * fail-fast); [AccountStorageCleanupException] for IO/safety failures
 * (callers map it to sealed rejections; G3 already does).
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

        /** Manifest format tag pinned by the grammar below. */
        const val MANIFEST_FORMAT = "gen-staging-manifest/1"

        /** Max manifest bytes (memory safety). */
        const val MAX_MANIFEST_BYTES = 4096

        /** Max chars per manifest line (memory safety). */
        const val MAX_MANIFEST_LINE_CHARS = 512

        private val SESSION_ID = Regex("^stg-[0-9a-f]{16}$")
    }

    /** Directory-entry durability outcome (explicit, never overstated). */
    enum class DirSyncResult { SYNCED, UNSUPPORTED }

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

    private fun canonicalOrdinal(raw: String): Int? {
        val value = raw.toLongOrNull() ?: return null
        if (value < 0 || value > GeneratorStaging.MAX_LEASES_PER_SESSION - 1) return null
        if (raw != value.toString()) return null
        return value.toInt()
    }

    private fun splitKey(key: String): Pair<String, Int> {
        val parts = key.split("/")
        if (parts.size != 2 || !SESSION_ID.matches(parts[0])) {
            throw IllegalArgumentException("opaque staging key rejected: not a session/ordinal token")
        }
        val ordinal = canonicalOrdinal(parts[1])
            ?: throw IllegalArgumentException("opaque staging key rejected: ordinal out of range")
        return parts[0] to ordinal
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

    /**
     * Post-materialization recheck: re-runs containment + symlink + root
     * safety on the materialized chain, matching the
     * `AccountScopedFileRoots` convention (resolve → materialise →
     * re-verify before use).
     */
    internal fun recheckMaterialized(sessionId: String, name: String) {
        val namespace = namespaceRoot().toPath().toAbsolutePath().normalize()
        val sessionDir = namespace.resolve(sessionId).normalize()
        val target = sessionDir.resolve(name).normalize()
        if (!roots.isContainedPath(target, namespace)) {
            throw AccountStorageCleanupException("staging path escapes namespace for $owner")
        }
        if (roots.trustedPathSafety(sessionDir) != TrustedPathSafety.SAFE) {
            throw AccountStorageCleanupException("staging session chain unsafe for $owner")
        }
        if (Files.isSymbolicLink(sessionDir) || Files.isSymbolicLink(target)) {
            throw AccountStorageCleanupException("staging symlink refused for $owner")
        }
    }

    override fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("staging blob must not be empty")
        }
        if (bytes.size > MAX_BLOB_BYTES) {
            throw IllegalArgumentException("staging blob over 32 MiB bound")
        }
        val (sessionId, ordinal) = splitKey(key)
        writeBytes(sessionId, ordinal.toString(), bytes)
    }

    private fun writeBytes(sessionId: String, name: String, bytes: ByteArray) {
        val target = resolve(sessionId, name)
        target.parentFile?.let {
            if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) {
                throw AccountStorageCleanupException("cannot materialise staging session for $owner")
            }
        }
        recheckMaterialized(sessionId, name)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw AccountStorageCleanupException("staging target exists, refusing overwrite for $owner")
        }
        val tmp = File(target.parent, "${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                tmp.delete()
                throw AccountStorageCleanupException("staging target appeared, refusing overwrite for $owner")
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
            syncDirectory(target.parentFile)
        } catch (e: AccountStorageCleanupException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            throw AccountStorageCleanupException("staging write failed for $owner: ${e.message}", e)
        }
    }

    /**
     * Best-effort parent-directory durability seam: [DirSyncResult.SYNCED]
     * when the filesystem honors directory fsync, [DirSyncResult.UNSUPPORTED]
     * otherwise (never silently treated as durable).
     */
    internal fun syncDirectory(dir: File?): DirSyncResult {
        if (dir == null) return DirSyncResult.UNSUPPORTED
        return try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
            DirSyncResult.SYNCED
        } catch (_: IOException) {
            DirSyncResult.UNSUPPORTED
        } catch (_: UnsupportedOperationException) {
            DirSyncResult.UNSUPPORTED
        }
    }

    override fun get(key: String): ByteArray? {
        val (sessionId, ordinal) = splitKey(key)
        val target = resolve(sessionId, ordinal.toString())
        if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return readBounded(target.toPath())
    }

    /**
     * Local capped read loop (no `core/data→core/crypto` edge): fails
     * before the accumulated bytes exceed [MAX_BLOB_BYTES], so a growing
     * file can never force an oversized allocation.
     */
    private fun readBounded(path: java.nio.file.Path): ByteArray {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(MAX_BLOB_BYTES, 64 * 1024))
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read == 0) throw IOException("zero-length read")
                total += read
                if (total > MAX_BLOB_BYTES) {
                    throw AccountStorageCleanupException("staging blob over 32 MiB bound for $owner")
                }
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }
    }

    override fun delete(key: String): Boolean {
        val (sessionId, ordinal) = splitKey(key)
        val target = resolve(sessionId, ordinal.toString())
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
                if (canonicalOrdinal(blob.name) == null) continue
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

    /**
     * Reads a session manifest under the bounded versioned grammar:
     * exactly `format/sessionId/owner/epoch/revision/createdAt`, each
     * once, bounded non-negative numerics, `format` pinned, identity
     * bound to path + owner. Anything else yields null (fail closed).
     */
    fun readManifest(sessionId: String): Map<String, String>? {
        if (!SESSION_ID.matches(sessionId)) return null
        val target = try {
            resolve(sessionId, MANIFEST_NAME)
        } catch (_: AccountStorageCleanupException) {
            return null
        }
        if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            parseManifest(readBounded(target.toPath()), sessionId)
        } catch (_: IOException) {
            null
        } catch (_: AccountStorageCleanupException) {
            null
        }
    }

    internal fun parseManifest(bytes: ByteArray, sessionId: String): Map<String, String>? {
        if (bytes.size > MAX_MANIFEST_BYTES) return null
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val seen = mutableMapOf<String, String>()
        for (rawLine in text.split("\n")) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) continue
            if (line.length > MAX_MANIFEST_LINE_CHARS) return null
            val index = line.indexOf('=')
            if (index <= 0) return null
            val field = line.substring(0, index)
            val value = line.substring(index + 1)
            if (field in seen) return null
            seen[field] = value
        }
        if (seen.keys != setOf("format", "sessionId", "owner", "epoch", "revision", "createdAt")) return null
        if (seen["format"] != MANIFEST_FORMAT) return null
        if (seen["sessionId"] != sessionId) return null
        if (seen["owner"] != owner.toRestString()) return null
        for (field in listOf("epoch", "revision", "createdAt")) {
            val number = seen[field]?.toLongOrNull() ?: return null
            if (number < 0) return null
        }
        return seen.toMap()
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
            appendLine("format=$MANIFEST_FORMAT")
            appendLine("sessionId=$sessionId")
            appendLine("owner=${owner.toRestString()}")
            appendLine("epoch=$epoch")
            appendLine("revision=$revision")
            appendLine("createdAt=$createdAtMillis")
        }.toByteArray(Charsets.UTF_8)
}
