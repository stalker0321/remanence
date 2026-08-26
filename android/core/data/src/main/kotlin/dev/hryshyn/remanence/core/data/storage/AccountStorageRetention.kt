package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Raised when the cleanup boundary could not remove an in-scope entry.
 * Other accounts' material is never affected; the path reported here is
 * always beneath the targeted account root, so the caller knows the
 * failure is local to that account.
 */
class AccountStorageCleanupException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * M2-P04: account-scoped retention and purge boundary.
 *
 * Two operations, both strictly account-scoped through [AccountScopedFileRoots]:
 *
 *  - [onLogout] retains the durable encrypted material for the same account
 *    and purges only the temp root of that account. It is the operation
 *    every normal logout runs; durable ciphertext (fingerprints,
 *    outbox-ciphertext, incoming-ciphertext, retry-material) is left in
 *    place so the same account may log back in and resume.
 *  - [purgeAccount] removes the entire account root (all five child roots
 *    included) for the explicit "remove local data" flow. No other
 *    account's root is touched, and the operation refuses any owner whose
 *    resolved path escapes the accounts boundary.
 *
 * Cleanup safety: every recursive deletion is bounded to the directory the
 * caller hands in. Symlinks encountered during traversal are NEVER
 * followed - the symlink entry itself is unlinked, its target is left
 * untouched, and a symlink may not be used to delete material that lives
 * outside the targeted account. If an in-scope entry cannot be removed for
 * any reason the routine fails explicitly with [AccountStorageCleanupException]
 * carrying the offending path, so the caller never sees a silent
 * half-deleted state.
 *
 * No method ever enumerates the contents of the accounts root: callers
 * pass the explicit [UserId] of the account they intend to act on, and
 * the boundary deletes nothing but that account's own directories.
 */
class AccountStorageRetention(
    private val roots: AccountScopedFileRoots,
) {

    /**
     * Normal logout retention policy: durable encrypted material for [owner]
     * is preserved; only the [AccountScopedFileRoots.ChildRoot.TEMP] child
     * of that account is purged. Other accounts are never touched.
     */
    fun onLogout(owner: UserId) {
        val tempRoot = roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP)
        deleteNoFollow(tempRoot)
    }

    /**
     * Explicit local-account purge: removes the entire account directory of
     * [owner] (all five fixed child roots). Other accounts are never
     * touched and the operation refuses if the resolved account directory
     * is not contained beneath the accounts root.
     */
    fun purgeAccount(owner: UserId) {
        val accountDir = roots.accountDirectory(owner)
        deleteNoFollow(accountDir)
    }

    /**
     * Recursively deletes [root] without ever following symbolic links. A
     * missing [root] is a no-op. Any in-scope entry that cannot be removed
     * raises [AccountStorageCleanupException] with the offending path.
     *
     * Implementation notes:
     *  - The deletion is driven by [java.nio.file.Files.walkFileTree] with a
     *    [NoFollowSymbolicLinks] visitor. The visitor inspects the
     *    [BasicFileAttributes.isSymbolicLink] flag (resolved without
     *    following) for every entry it sees and only descends into real
     *    directories; a symlink is unlinked at the [visitFile] step and is
     *    never recursed into. This is the standard, Android-API-26+ safe
     *    pattern for the "rm -r without following links" semantics.
     *  - The root itself is deleted in a `try/finally` after the walk so
     *    failure to walk still leaves a clean error and never silently
     *    leaves a partially-deleted state behind for the next caller.
     *  - The traversal never reads from the symlink target, so material
     *    that lives outside the targeted account root cannot be observed,
     *    modified, or deleted.
     */
    private fun deleteNoFollow(root: File) {
        if (!root.exists()) return
        val rootPath: Path = root.toPath()
        val visitor = NoFollowSymbolicLinks()
        try {
            Files.walkFileTree(rootPath, visitor)
        } catch (failure: IOException) {
            throw AccountStorageCleanupException(
                "could not complete account-scoped cleanup of $root: ${failure.message}",
                failure,
            )
        }
        try {
            Files.deleteIfExists(rootPath)
        } catch (failure: IOException) {
            throw AccountStorageCleanupException(
                "could not remove account-scoped root $root: ${failure.message}",
                failure,
            )
        }
    }

    private class NoFollowSymbolicLinks : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            // If the directory entry is itself a symlink (rare, but possible
            // on POSIX filesystems), refuse to descend: the symlink will be
            // unlinked at the visitFile step by the parent walker.
            if (attrs.isSymbolicLink) {
                return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            // The walker delivers both real files and symlinks here. A
            // symlink is unlinked directly - we never touch its target.
            return try {
                Files.delete(file)
                FileVisitResult.CONTINUE
            } catch (missing: NoSuchFileException) {
                // Already gone - treat as success so a concurrent cleanup
                // does not mask an otherwise-clean sweep.
                FileVisitResult.CONTINUE
            } catch (failure: IOException) {
                throw AccountStorageCleanupException(
                    "could not remove in-scope entry $file: ${failure.message}",
                    failure,
                )
            }
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            // By the time this fires the walker has already visited every
            // descendant. The directory is now empty (or never had any
            // children), so it is safe to unlink. If a sub-entry refused
            // to be removed the IOException argument carries that failure
            // and we must not paper over it.
            if (exc != null) {
                throw AccountStorageCleanupException(
                    "could not complete traversal beneath $dir: ${exc.message}",
                    exc,
                )
            }
            return try {
                Files.delete(dir)
                FileVisitResult.CONTINUE
            } catch (missing: NoSuchFileException) {
                FileVisitResult.CONTINUE
            } catch (failure: IOException) {
                throw AccountStorageCleanupException(
                    "could not remove in-scope directory $dir: ${failure.message}",
                    failure,
                )
            }
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            // Anything other than a benign "not found" is fatal: the
            // caller asked for a bounded cleanup, and a silent skip would
            // leave an in-scope entry undeleted.
            if (exc is NoSuchFileException) return FileVisitResult.CONTINUE
            throw AccountStorageCleanupException(
                "could not traverse in-scope entry $file: ${exc.message}",
                exc,
            )
        }
    }
}
