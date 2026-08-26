package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.UserId
import java.io.File

/**
 * M2-P04: account-scoped capsule file root resolver.
 *
 * All durable app-private material for an authenticated local account lives
 * beneath `filesDir/accounts/<canonical-owner-uuid>/`. The five fixed child
 * roots are exhaustive; no operation may enumerate, return, or delete
 * another account root.
 *
 * The resolver is pure: it does not create, modify, or delete any directory
 * or file. It only derives [File] handles from a [UserId] and returns them
 * for callers that materialize the on-disk state explicitly. Path segments
 * are derived from [UserId.toRestString] which round-trips through the
 * protocol-canonical 8-4-4-4-12 lowercase UUID form validated by
 * `:core:model`; a non-canonical owner UUID cannot be constructed.
 *
 * Every exposed root is verified - through canonical path resolution - to
 * be contained beneath [filesDir]/accounts/ and inside the resolved owner
 * directory so a malformed or hostile owner UUID cannot escape the
 * boundary. The resolver never enumerates the contents of the accounts
 * root and only returns the directory of the owner passed in.
 */
class AccountScopedFileRoots(
    private val filesDir: File,
) {
    init {
        require(filesDir.path.isNotEmpty()) { "filesDir must be resolvable" }
    }

    /**
     * The fixed set of child roots the MVP defines for every authenticated
     * account. The set is closed: any other name is rejected at the API
     * surface so no caller can invent a sixth root.
     */
    enum class ChildRoot(val directoryName: String) {
        FINGERPRINTS("fingerprints"),
        OUTBOX_CIPHERTEXT("outbox-ciphertext"),
        INCOMING_CIPHERTEXT("incoming-ciphertext"),
        RETRY_MATERIAL("retry-material"),
        TEMP("temp"),
    }

    /**
     * Resolves the fixed child root of [owner] named [root] beneath
     * `filesDir/accounts/<owner>/<root>/`. The returned [File] is
     * guaranteed to be contained beneath the owner directory and beneath
     * the accounts root. The directory is not created by this call.
     */
    fun child(owner: UserId, root: ChildRoot): File {
        val ownerPath = ownerDirectory(owner)
        val child = File(ownerPath, root.directoryName)
        requireContained(child, ownerPath, owner, root)
        return child
    }

    /**
     * Returns the canonical owner directory `filesDir/accounts/<owner>/`
     * for retention/purge callers that operate on the whole account root
     * at once. The directory is not created by this call.
     */
    fun accountDirectory(owner: UserId): File {
        val ownerPath = ownerDirectory(owner)
        requireContained(ownerPath, accountsRoot(), owner, null)
        return ownerPath
    }

    private fun accountsRoot(): File = File(filesDir, ACCOUNTS_DIR)

    private fun ownerDirectory(owner: UserId): File =
        File(accountsRoot(), owner.toRestString())

    private fun requireContained(
        candidate: File,
        expectedParent: File,
        owner: UserId,
        root: ChildRoot?,
    ) {
        val parentCanonical = expectedParent.canonicalFile.path
        val candidateCanonical = candidate.canonicalFile.path
        val requiredPrefix = if (parentCanonical.endsWith(File.separator)) {
            parentCanonical
        } else {
            "$parentCanonical${File.separator}"
        }
        val contained = candidateCanonical == parentCanonical ||
            candidateCanonical.startsWith(requiredPrefix)
        check(contained) {
            val label = root?.directoryName ?: "account"
            "resolved $label root for ${owner.toRestString()} escapes accounts boundary"
        }
    }

    private companion object {
        const val ACCOUNTS_DIR = "accounts"
    }
}
