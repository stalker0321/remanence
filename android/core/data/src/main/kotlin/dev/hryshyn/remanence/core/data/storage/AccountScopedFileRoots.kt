package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/** The fail-closed result of inspecting a path under the trusted app root. */
enum class TrustedPathSafety {
    SAFE,
    UNSAFE,
    UNAVAILABLE,
}

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
 * for callers that materialise the on-disk state explicitly. Path segments
 * are derived from [UserId.toRestString] which round-trips through the
 * protocol-canonical 8-4-4-4-12 lowercase UUID form validated by
 * `:core:model`; a non-canonical owner UUID cannot be constructed.
 *
 * [filesDir] and its canonical target are one trusted root identity because
 * Android may expose the same app-private directory through a system alias.
 * Symlinks at or above that boundary are permitted; every controlled
 * component below it is still inspected with NOFOLLOW_LINKS.
 */
class AccountScopedFileRoots(
    private val filesDir: File,
) {
    private val rawFilesRoot: Path = filesDir.toPath().toAbsolutePath().normalize()

    init {
        require(filesDir.path.isNotEmpty()) { "filesDir must be resolvable" }
    }

    /** The fixed set of child roots for every authenticated account. */
    enum class ChildRoot(val directoryName: String) {
        FINGERPRINTS("fingerprints"),
        OUTBOX_CIPHERTEXT("outbox-ciphertext"),
        INCOMING_CIPHERTEXT("incoming-ciphertext"),
        RETRY_MATERIAL("retry-material"),
        TEMP("temp"),
    }

    /**
     * Resolves the fixed child root of [owner] beneath
     * `filesDir/accounts/<owner>/<root>/`. The directory is not created.
     */
    fun child(owner: UserId, root: ChildRoot): File = resolveTrustedRelative(
        filesDir.toPath(),
        ACCOUNTS_DIR,
        owner.toRestString(),
        root.directoryName,
    ).toFile()

    /**
     * Resolves the deterministic incoming ciphertext destination used by
     * sync and acceptance. This is fixed-path derivation only; callers must
     * apply [trustedPathSafety] before materialising or consuming it.
     */
    fun incomingCiphertextPath(owner: UserId, capsule: CapsuleId, blob: BlobId): Path {
        val root = child(owner, ChildRoot.INCOMING_CIPHERTEXT)
            .toPath()
            .toAbsolutePath()
            .normalize()
        // Keep derivation separate from the caller's NOFOLLOW safety gate so
        // destination failures retain their existing typed classification.
        // The returned path is never a trusted capability until the caller
        // applies trustedPathSafety.
        return resolveFixedRelative(
            root,
            "capsules",
            capsule.toRestString(),
            "blobs",
            "${blob.toRestString()}.ciphertext",
        )
    }

    /** Resolves `filesDir/accounts/<owner>/temp/create/`; it does not create it. */
    fun createStagingRoot(owner: UserId): File = resolveTrustedRelative(
        child(owner, ChildRoot.TEMP).toPath(),
        CREATE_STAGING_DIR,
    ).toFile()

    /** Returns the owner directory for retention/purge callers. */
    fun accountDirectory(owner: UserId): File = resolveTrustedRelative(
        filesDir.toPath(),
        ACCOUNTS_DIR,
        owner.toRestString(),
    ).toFile()

    /**
     * Returns the fixed owner spelling for a NOFOLLOW cleanup inspection.
     * The caller must inspect [trustedPathSafety] before enumerating it; this
     * deliberately preserves cleanup's ability to observe and reject an
     * already-materialised unsafe owner entry without following it.
     */
    internal fun accountDirectoryForInspection(owner: UserId): File = resolveFixedRelative(
        filesDir.toPath(),
        ACCOUNTS_DIR,
        owner.toRestString(),
    ).toFile()

    /** Returns whether [path] is the raw or canonical spelling of this root. */
    fun isTrustedRoot(path: Path): Boolean = try {
        val normalized = path.toAbsolutePath().normalize()
        normalized == rawFilesRoot || normalized == canonicalFilesRoot()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Checks the raw/canonical trusted-root identity and rejects every
     * existing symlink below that identity. Missing descendants remain safe
     * to derive; callers re-check each directory after materialising it.
     */
    fun trustedPathSafety(path: Path): TrustedPathSafety {
        val normalized = try {
            path.toAbsolutePath().normalize()
        } catch (_: SecurityException) {
            return TrustedPathSafety.UNAVAILABLE
        }
        val canonicalRoot = try {
            canonicalFilesRoot()
        } catch (_: IOException) {
            return TrustedPathSafety.UNAVAILABLE
        } catch (_: SecurityException) {
            return TrustedPathSafety.UNAVAILABLE
        }
        val canonical = try {
            normalized.toFile().canonicalFile.toPath().toAbsolutePath().normalize()
        } catch (_: IOException) {
            return TrustedPathSafety.UNAVAILABLE
        } catch (_: SecurityException) {
            return TrustedPathSafety.UNAVAILABLE
        }
        if (!isEqualOrDescendant(canonical, canonicalRoot)) {
            return TrustedPathSafety.UNSAFE
        }

        val inspection = when {
            isEqualOrDescendant(normalized, rawFilesRoot) ->
                normalized to rawFilesRoot
            isEqualOrDescendant(normalized, canonicalRoot) ->
                normalized to canonicalRoot
            else -> return TrustedPathSafety.UNSAFE
        }
        return inspectControlledComponents(inspection.first, inspection.second)
    }

    /** Compares raw and canonical spellings without weakening path safety. */
    fun sameTrustedPath(first: Path, second: Path): Boolean {
        val firstNormalized = try {
            first.toAbsolutePath().normalize()
        } catch (_: SecurityException) {
            return false
        }
        val secondNormalized = try {
            second.toAbsolutePath().normalize()
        } catch (_: SecurityException) {
            return false
        }
        if (firstNormalized == secondNormalized) return true
        return try {
            firstNormalized.toFile().canonicalFile.toPath().toAbsolutePath().normalize() ==
                secondNormalized.toFile().canonicalFile.toPath().toAbsolutePath().normalize()
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Segment-aware containment for raw or canonical spellings. */
    fun isContainedPath(candidate: Path, root: Path): Boolean {
        val candidateNormalized = try {
            candidate.toAbsolutePath().normalize()
        } catch (_: SecurityException) {
            return false
        }
        val rootNormalized = try {
            root.toAbsolutePath().normalize()
        } catch (_: SecurityException) {
            return false
        }
        if (candidateNormalized == rootNormalized) return false
        if (isStrictDescendant(candidateNormalized, rootNormalized)) return true
        return try {
            val candidateCanonical = candidateNormalized.toFile().canonicalFile.toPath()
                .toAbsolutePath().normalize()
            val rootCanonical = rootNormalized.toFile().canonicalFile.toPath()
                .toAbsolutePath().normalize()
            isStrictDescendant(candidateCanonical, rootCanonical)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Resolves only fixed, single path segments and validates the result. */
    fun resolveTrustedRelative(root: Path, vararg components: String): Path {
        var resolved = root.toAbsolutePath().normalize()
        val normalizedRoot = resolved
        for (component in components) {
            require(isSingleSafeSegment(component)) { "path component is not fixed and relative" }
            resolved = resolved.resolve(component).normalize()
        }
        check(resolved != normalizedRoot && isContainedPath(resolved, normalizedRoot)) {
            "resolved path escaped trusted root"
        }
        check(trustedPathSafety(resolved) == TrustedPathSafety.SAFE) {
            "resolved path is not safe"
        }
        return resolved
    }

    private fun resolveFixedRelative(root: Path, vararg components: String): Path {
        var resolved = root.toAbsolutePath().normalize()
        val normalizedRoot = resolved
        for (component in components) {
            require(isSingleSafeSegment(component)) { "path component is not fixed and relative" }
            resolved = resolved.resolve(component).normalize()
        }
        require(resolved != normalizedRoot && isStrictDescendant(resolved, normalizedRoot)) {
            "resolved path escaped lexical root"
        }
        return resolved
    }

    private fun canonicalFilesRoot(): Path =
        filesDir.canonicalFile.toPath().toAbsolutePath().normalize()

    private fun inspectControlledComponents(path: Path, boundary: Path): TrustedPathSafety {
        var current = path
        return try {
            while (current != boundary) {
                val attributes = try {
                    Files.readAttributes(
                        current,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: java.nio.file.NoSuchFileException) {
                    current = current.parent ?: return TrustedPathSafety.UNSAFE
                    continue
                }
                if (attributes.isSymbolicLink) return TrustedPathSafety.UNSAFE
                current = current.parent ?: return TrustedPathSafety.UNSAFE
            }
            TrustedPathSafety.SAFE
        } catch (_: IOException) {
            TrustedPathSafety.UNAVAILABLE
        } catch (_: SecurityException) {
            TrustedPathSafety.UNAVAILABLE
        }
    }

    private fun isSingleSafeSegment(component: String): Boolean {
        if (component.isEmpty() || component == "." || component == ".." ||
            component.contains('/') || component.contains('\\')
        ) return false
        val parsed = try {
            Paths.get(component)
        } catch (_: RuntimeException) {
            return false
        }
        return !parsed.isAbsolute && parsed.nameCount == 1 && parsed.fileName.toString() == component
    }

    private fun isEqualOrDescendant(path: Path, root: Path): Boolean =
        path == root || isStrictDescendant(path, root)

    private fun isStrictDescendant(path: Path, root: Path): Boolean =
        path != root && path.startsWith(root)

    private companion object {
        const val ACCOUNTS_DIR = "accounts"
        const val CREATE_STAGING_DIR = "create"
    }
}
