package dev.hryshyn.remanence.wiring

import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.crypto.KeysetKekWrapper
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owner-scoped identity filesystem over the UNCHANGED
 * [IdentityBundleRepository] (per-directory instances; its
 * refuse-to-overwrite kernel is reused, never reimplemented).
 *
 * Layout under `filesDir` (all names fixed or derived from canonical
 * UUIDs — never email/handle paths):
 * - `identity/` — the legacy home, byte-identical. Files keep their
 *   exact names and formats; this class never writes anything except
 *   a legacy-claim `owner.json` marker.
 * - `identity/<ownerUuid>/` — one directory per subsequently
 *   registered owner, same three sealed filenames, plus
 *   `owner.json` binding it to `{userId, keyBundleId}`.
 * - `identity-staging/<bundleUuid>/` — durable flow candidates
 *   (same sealed filenames), indexed by exact derived bundle so a
 *   later authenticated login can recover the matching one.
 * - `identity-staging/flows/<sha256(flowKey)>.json` — which staged
 *   bundle a flow key currently owns (same flow/target retry reuses
 *   it; a new flow/target mints independent keys).
 *
 * Resolution never guesses ownership: private handles open only for
 * an explicitly requested owner+bundle that verifies exactly (owner
 * dir first, then legacy with proof). A mismatch to another owner
 * never blocks that owner's independent store — it resolves
 * `RecoveryRequired` instead. Staging is never consulted by
 * resolution (unbound keys are not readiness); archival moves staged
 * keys into the owner dir. There is deliberately no delete, sweep,
 * or purge API: retention beyond that is a product choice.
 */
class OwnerScopedIdentityStore(
    private val identityRoot: File,
    private val stagingRoot: File,
    private val wrapper: KeysetKekWrapper,
) {

    /** Public binding claim: no secrets, owner + derived bundle only. */
    data class OwnerClaim(val ownerUserId: String, val keyBundleId: String)

    /** Per-directory engine reusing the legacy formats and guards. */
    fun repositoryFor(ownerUserId: String): IdentityBundleRepository {
        val owner = canonicalUuid(ownerUserId)
            ?: throw IllegalArgumentException("owner id must be a canonical UUID")
        return IdentityBundleRepository(ownerDir(owner), wrapper)
    }

    /** The untouched legacy home. */
    fun legacyRepository(): IdentityBundleRepository =
        IdentityBundleRepository(identityRoot, wrapper)

    /**
     * Verified private-handle load for an explicitly requested
     * owner+bundle: the owner dir when it verifies, else the legacy
     * home ONLY with an explicit verified-owner binding (a marker
     * claim for THIS owner plus exact verify), else
     * `RecoveryRequired`. Unclaimed legacy never yields private
     * handles for any owner, whatever the bundle match — B asking for
     * A's bundle gets `RecoveryRequired`, never A's keys.
     */
    @Synchronized
    fun resolve(ownerUserId: String, expectedBundleId: String): IdentityBundleRepository.LoadResult {
        val bundle = canonicalUuid(expectedBundleId)
            ?: return IdentityBundleRepository.LoadResult.RecoveryRequired
        // repositoryFor validates the owner form and throws on misuse.
        val owner = canonicalUuid(ownerUserId)
            ?: return IdentityBundleRepository.LoadResult.RecoveryRequired
        if (verifyDirBundle(ownerDir(owner), bundle)) {
            val loaded = repositoryFor(owner).load()
            if (loaded is IdentityBundleRepository.LoadResult.Available) return loaded
        }
        val claim = readClaim(identityRoot)
        if (claim != null && claim.ownerUserId == owner && claim.keyBundleId == bundle &&
            verifyDirBundle(identityRoot, bundle)
        ) {
            val legacy = legacyRepository().load()
            if (legacy is IdentityBundleRepository.LoadResult.Available) return legacy
        }
        return IdentityBundleRepository.LoadResult.RecoveryRequired
    }

    /**
     * Boolean availability for a bundle id, without exposing handles.
     * Markers are hints only and NEVER decide: every positive below is
     * proven by unwrapping the actual stored keys and deriving the exact
     * bundle (forged/stale markers return false). Staging never counts:
     * unbound keys are not readiness.
     */
    @Synchronized
    fun hasIdentityFor(bundleId: String): Boolean {
        val bundle = canonicalUuid(bundleId) ?: return false
        if (verifyDirBundle(identityRoot, bundle)) return true
        listOwnerDirs().forEach { dir ->
            if (verifyDirBundle(dir, bundle)) return true
        }
        return false
    }

    /** Reads the binding claim of a directory, or null when absent/malformed. */
    fun readClaim(directory: File): OwnerClaim? {
        val file = File(directory, OWNER_FILE_NAME)
        if (!file.isFile) return null
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText(Charsets.UTF_8))
            if (root !is JsonObject) return null
            val owner = root["userId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
            val bundle = root["keyBundleId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
            // Normalize: malformed values fail closed, and stored claims
            // always compare in canonical form downstream.
            val ownerNorm = canonicalUuid(owner) ?: return null
            val bundleNorm = canonicalUuid(bundle) ?: return null
            OwnerClaim(ownerNorm, bundleNorm)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Claims the legacy home for an owner after proof (the login's
     * userId+bundleId equals the legacy exports). Refuses when the
     * legacy material does not match, or when a conflicting claim
     * already exists. Best-effort boolean — callers proceed regardless;
     * [resolve] serves the legacy home only when this claim binds the
     * requested owner+bundle, and fails closed otherwise.
     */
    @Synchronized
    fun claimLegacyIfMatching(ownerUserId: String, bundleId: String): Boolean {
        val owner = canonicalUuid(ownerUserId) ?: return false
        val bundle = canonicalUuid(bundleId) ?: return false
        if (!verifyDirBundle(identityRoot, bundle)) return false
        return writeClaim(identityRoot, OwnerClaim(owner, bundle))
    }

    /**
     * Flow-scoped provisional creation: stable per flow key (same
     * flow/target retry reuses the staged candidate), independent keys
     * per new flow key. Durable before any network so timeouts, 409s,
     * cancels, and process death never lose a possibly-committed
     * account's keys. Throws only when the flow's staged material
     * exists but is unusable (callers map this to explicit recovery).
     */
    @Synchronized
    fun prepareProvisional(flowKey: String, kekAlias: String): PreparedIdentity {
        require(flowKey.isNotBlank()) { "flow key must not be blank" }
        if (flowFile(flowKey).exists()) {
            // An established flow never resets implicitly: a malformed
            // mapping, missing material, or unusable staged keys all fail
            // closed (possibly-server-committed state must not be silently
            // replaced). Fresh keys happen only below, for a new flow.
            val bound = boundBundle(flowKey)
                ?: throw java.security.GeneralSecurityException(
                    "flow binding unreadable; refusing silent reset",
                )
            if (!stagedDir(bound).exists()) {
                throw java.security.GeneralSecurityException(
                    "flow binding points at missing material; refusing silent reset",
                )
            }
            val staged = try {
                stagedRepository(bound).loadPublicExports()
            } catch (_: Exception) {
                null
            }
            if (staged is IdentityBundleRepository.PublicExportsResult.Available) {
                return toPrepared(staged)
            }
            throw java.security.GeneralSecurityException(
                "staged candidate for this flow is unusable; start a new flow",
            )
        }
        val generated = AccountIdentityGenerator().generate()
        val bundle = deriveKeyBundleId(generated.encryptionPublicKeyset)
        IdentityBundleRepository(stagedDir(bundle), wrapper)
            .importHandles(kekAlias, generated.encryptionPrivateHandle, generated.signingPrivateHandle)
        writeFlowBinding(flowKey, bundle)
        return PreparedIdentity(
            keyBundleId = bundle,
            encryptionPublicKeysetB64Url = encodeBase64Url(generated.encryptionPublicKeyset),
            signingPublicKeysetB64Url = encodeBase64Url(generated.signingPublicKeyset),
        )
    }

    /**
     * Recovers a staged candidate by exact derived bundle id (for a
     * later authenticated login after a lost ACK), or null when no
     * usable staged material matches. Never generates.
     */
    @Synchronized
    fun resolveStaged(bundleId: String): PreparedIdentity? {
        val bundle = canonicalUuid(bundleId) ?: return null
        return try {
            val staged = IdentityBundleRepository(stagedDir(bundle), wrapper).loadPublicExports()
            if (staged is IdentityBundleRepository.PublicExportsResult.Available) toPrepared(staged)
            else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Idempotent verified archival of a staged candidate into its
     * owner's directory. COPIES (never moves) the sealed files, so there
     * is no torn-move window and a post-success retry of the same flow
     * reuses instead of regenerating. Every write is provenance-proven:
     * an existing destination file must be byte-identical to the staged
     * source (our own prior copy); anything foreign or corrupt refuses
     * with the old bytes preserved. A recovered record takes priority
     * and is never mutated by adoption: adopted-same succeeds, anything
     * else refuses. No plaintext crosses. Returns false instead of
     * throwing on any integrity problem; the staged copy is retained.
     */
    @Synchronized
    fun adoptStaged(bundleId: String, ownerUserId: String): Boolean {
        val bundle = canonicalUuid(bundleId) ?: return false
        val owner = canonicalUuid(ownerUserId) ?: return false
        val destination = ownerDir(owner)
        val staged = try {
            IdentityBundleRepository(stagedDir(bundle), wrapper).loadPublicExports()
        } catch (_: Exception) {
            null
        }
        if (staged is IdentityBundleRepository.PublicExportsResult.Available &&
            deriveKeyBundleId(staged.encryptionPublicKeyset) == bundle
        ) {
            if (!copyRecordsInto(stagedDir(bundle), destination, staged)) return false
        } else if (!destination.exists()) {
            return false
        }
        val current = try {
            IdentityBundleRepository(destination, wrapper).load()
        } catch (_: Exception) {
            null
        }
        if (current !is IdentityBundleRepository.LoadResult.Available) return false
        if (staged is IdentityBundleRepository.PublicExportsResult.Available) {
            // Full-identity equality (HPKE AND signing) against the proven
            // staged source — same HPKE with different Ed25519 is NOT the
            // same identity.
            if (!identitiesEqual(current, staged)) return false
        } else {
            // No staged reference (already-adopted convergence): the
            // destination must still derive exactly the bundle.
            val currentBundle = try {
                deriveLoadedBundle(current)
            } catch (_: Exception) {
                null
            } ?: return false
            if (currentBundle != bundle) return false
        }
        writeClaim(destination, OwnerClaim(owner, bundle))
        return true
    }

    /**
     * Converges a destination toward staged source records with proven
     * provenance, in two strict phases. Phase one (preflight) reads and
     * validates EVERYTHING — recovered-record priority, source
     * readability, and each existing destination record against the
     * staged source — without writing anything. Phase two copies only
     * the records proven absent. A recovered record is authoritative
     * and never mutated: adopted-same succeeds, anything else refuses.
     * Otherwise each sealed record either already matches byte-for-byte
     * (our own prior copy — skipped), is absent (copied via tmp+rename),
     * or differs (foreign/corrupt — refuse with old bytes preserved).
     * A rejection therefore never changes the tree.
     */
    private fun copyRecordsInto(
        source: File,
        destination: File,
        staged: IdentityBundleRepository.PublicExportsResult.Available,
    ): Boolean {
        return try {
            if (File(destination, IdentityBundleRepository.RECOVERED_FILE_NAME).exists()) {
                val current = try {
                    IdentityBundleRepository(destination, wrapper).load()
                } catch (_: Exception) {
                    null
                }
                if (current !is IdentityBundleRepository.LoadResult.Available) return false
                return identitiesEqual(current, staged)
            }
            val names = listOf(
                IdentityBundleRepository.ENCRYPTION_FILE_NAME,
                IdentityBundleRepository.SIGNING_FILE_NAME,
            )
            // Phase one: preflight every record before any write.
            val expected = names.map { name ->
                try {
                    File(source, name).readBytes()
                } catch (_: Exception) {
                    null
                } ?: return false
            }
            val missing = names.mapIndexedNotNull { index, name ->
                val target = File(destination, name)
                if (!target.exists()) name
                else {
                    val existing = try {
                        target.readBytes()
                    } catch (_: Exception) {
                        null
                    }
                    if (existing == null || !expected[index].contentEquals(existing)) return false
                    null
                }
            }
            // Phase two: only proven-absent records are written.
            destination.mkdirs()
            missing.forEach { name -> copyRecord(source, destination, name) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Full-identity equality on public exports (HPKE AND signing). */
    private fun identitiesEqual(
        loaded: IdentityBundleRepository.LoadResult.Available,
        staged: IdentityBundleRepository.PublicExportsResult.Available,
    ): Boolean {
        return try {
            val loadedEncryption = publicBytes(loaded.encryptionHandle)
            val loadedSigning = publicBytes(loaded.signingHandle)
            try {
                loadedEncryption.contentEquals(staged.encryptionPublicKeyset) &&
                    loadedSigning.contentEquals(staged.signingPublicKeyset)
            } finally {
                loadedEncryption.fill(0)
                loadedSigning.fill(0)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun publicBytes(handle: com.google.crypto.tink.KeysetHandle): ByteArray =
        com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            handle.publicKeysetHandle,
        )

    private fun copyRecord(sourceDir: File, destinationDir: File, name: String) {
        val bytes = File(sourceDir, name).readBytes()
        val temporary = File(destinationDir, "$name.tmp")
        temporary.writeBytes(bytes)
        bytes.fill(0)
        if (!temporary.renameTo(File(destinationDir, name))) {
            temporary.delete()
            throw java.security.GeneralSecurityException("could not stage $name")
        }
    }

    /**
     * Owner-scoped recovery install (same refuse-to-replace kernel, per
     * dir). Synchronized on the same monitor as [adoptStaged] so a
     * competing recovery write cannot invalidate an adoption preflight
     * unnoticed; the kernel still refuses replacement either way.
     */
    @Synchronized
    fun installRecovered(
        ownerUserId: String,
        kekAlias: String,
        restored: AccountRecoveryCrypto.Restored,
    ) {
        repositoryFor(ownerUserId).installRecovered(kekAlias, restored)
    }

    private fun ownerDir(ownerUuid: String): File = File(identityRoot, ownerUuid)

    private fun stagedDir(bundleUuid: String): File = File(stagingRoot, bundleUuid)

    private fun stagedRepository(bundleUuid: String): IdentityBundleRepository =
        IdentityBundleRepository(stagedDir(bundleUuid), wrapper)

    private fun flowFile(flowKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(flowKey.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ".json"
        return File(File(stagingRoot, FLOWS_DIR_NAME), name)
    }

    private fun boundBundle(flowKey: String): String? {
        val file = flowFile(flowKey)
        if (!file.isFile) return null
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText(Charsets.UTF_8))
            if (root !is JsonObject) return null
            val bundle = root["bundleId"]?.jsonPrimitive?.content ?: return null
            canonicalUuid(bundle)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeFlowBinding(flowKey: String, bundleUuid: String) {
        val file = flowFile(flowKey)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(
            buildJsonObject { put("bundleId", bundleUuid) }.toString().toByteArray(Charsets.UTF_8),
        )
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw java.security.GeneralSecurityException("could not persist flow binding")
        }
    }

    private fun listOwnerDirs(): List<File> =
        try {
            identityRoot.listFiles { file -> file.isDirectory }?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    private fun verifyDirBundle(directory: File, bundleUuid: String): Boolean {
        return try {
            val exports = IdentityBundleRepository(directory, wrapper).loadPublicExports()
            exports is IdentityBundleRepository.PublicExportsResult.Available &&
                deriveKeyBundleId(exports.encryptionPublicKeyset) == bundleUuid
        } catch (_: Exception) {
            false
        }
    }

    private fun deriveLoadedBundle(loaded: IdentityBundleRepository.LoadResult.Available): String {
        val bytes = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            loaded.encryptionHandle.publicKeysetHandle,
        )
        try {
            return deriveKeyBundleId(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun toPrepared(
        exports: IdentityBundleRepository.PublicExportsResult.Available,
    ): PreparedIdentity = PreparedIdentity(
        keyBundleId = deriveKeyBundleId(exports.encryptionPublicKeyset),
        encryptionPublicKeysetB64Url = encodeBase64Url(exports.encryptionPublicKeyset),
        signingPublicKeysetB64Url = encodeBase64Url(exports.signingPublicKeyset),
    )

    private fun writeClaim(directory: File, claim: OwnerClaim): Boolean {
        return try {
            val existing = readClaim(directory)
            if (existing != null && existing != claim) return false
            if (existing == claim) return true
            directory.mkdirs()
            val file = File(directory, OWNER_FILE_NAME)
            val temporary = File(directory, "$OWNER_FILE_NAME.tmp")
            temporary.writeBytes(
                buildJsonObject {
                    put("userId", claim.ownerUserId)
                    put("keyBundleId", claim.keyBundleId)
                }.toString().toByteArray(Charsets.UTF_8),
            )
            if (!temporary.renameTo(file)) {
                temporary.delete()
                return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val OWNER_FILE_NAME = "owner.json"
        const val FLOWS_DIR_NAME = "flows"

        /** Canonical lowercase UUID form; null (never a path) when malformed. */
        fun canonicalUuid(raw: String): String? {
            if (raw.isBlank()) return null
            return try {
                java.util.UUID.fromString(raw.trim()).toString()
            } catch (_: Exception) {
                null
            }
        }

        /** Deterministic client-generated UUID bound to exact identity bytes. */
        fun deriveKeyBundleId(encryptionPublicKeyset: ByteArray): String =
            java.util.UUID.nameUUIDFromBytes(encryptionPublicKeyset).toString()

        fun encodeBase64Url(value: ByteArray): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
