package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.UserId
import java.io.File

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
        deleteRecursivelyIfExists(tempRoot)
    }

    /**
     * Explicit local-account purge: removes the entire account directory of
     * [owner] (all five fixed child roots). Other accounts are never
     * touched and the operation refuses if the resolved account directory
     * is not contained beneath the accounts root.
     */
    fun purgeAccount(owner: UserId) {
        val accountDir = roots.accountDirectory(owner)
        deleteRecursivelyIfExists(accountDir)
    }

    private fun deleteRecursivelyIfExists(directory: File) {
        if (!directory.exists()) return
        if (!directory.isDirectory) {
            // Defensive: an unexpected non-directory should not block a
            // bounded account-scoped operation; remove the entry and move
            // on. The resolver already proved containment.
            directory.delete()
            return
        }
        directory.deleteRecursively()
    }
}
