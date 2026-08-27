package dev.hryshyn.remanence.core.data.fingerprints

import java.io.File
import java.util.UUID
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide
import dev.hryshyn.remanence.core.data.db.RecognitionFingerprintDao
import dev.hryshyn.remanence.core.data.db.RecognitionFingerprintEntity
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.UserId

/** Raised when the same (capsule, side, origin) baseline already exists. */
class DuplicateFingerprintException(capsuleId: String, side: FingerprintSide) :
    IllegalStateException("fingerprint baseline already captured for $capsuleId/$side")

/**
 * Persists postcard fingerprints only as sealed bytes in app-private files
 * plus a Room reference row (docs/architecture.md section 6, security.md
 * section 11). Plaintext never touches disk: sealing happens before the
 * atomic write, and any mid-way failure removes artifacts this call created.
 *
 * M2-P04 account storage scoping: every operation snapshots the authenticated
 * local account EXACTLY ONCE through [ownerUserIdProvider], parses it into
 * the typed [UserId] (fail-closed against missing or non-canonical values),
 * and resolves THAT owner's [AccountScopedFileRoots.ChildRoot.FINGERPRINTS]
 * root for all file work. There is no shared `filesDir/fingerprints` root;
 * two accounts physically cannot collide because each resolves its own
 * directory. Stored [RecognitionFingerprintEntity.encryptedPath] values are
 * relative to the OWNER root of the account that wrote them, so reading,
 * deleting, or sweeping another account's ciphertext through a swapped-in
 * provider value would resolve outside this snapshot's own directory.
 */
class EncryptedFingerprintStore(
    private val roots: AccountScopedFileRoots,
    private val sealer: SecretSealer,
    private val dao: RecognitionFingerprintDao,
    private val ownerUserIdProvider: suspend () -> String,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SealedFingerprintPersistence {

    /**
     * The immutable owner snapshot one whole operation runs against: the
     * typed user id used by every DAO query plus its FINGERPRINTS root used
     * by every file access.
     */
    private data class OwnerSnapshot(val ownerId: UserId, val fingerprintsRoot: File)

    /**
     * Snapshots the authenticated owner exactly once per operation and
     * resolves its fingerprints root. Missing or non-canonical owners fail
     * closed here - nothing partial may depend on an unattributed call.
     */
    private suspend fun snapshotOwner(): OwnerSnapshot {
        val ownerId = UserId.parseRest(ownerUserIdProvider())
        return OwnerSnapshot(ownerId, roots.child(ownerId, AccountScopedFileRoots.ChildRoot.FINGERPRINTS))
    }

    private fun ensureOwnerRoot(root: File) {
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("cannot create account fingerprint storage root")
        }
    }

    /**
     * Seals [plaintextBytes], writes them atomically beneath the owner root,
     * and records the strict owner-scoped reference row. Returns the
     * generated fingerprint ID.
     */
    override suspend fun persist(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
        profileId: String,
        plaintextBytes: ByteArray,
    ): String {
        val owner = snapshotOwner()
        ensureOwnerRoot(owner.fingerprintsRoot)
        val existing =
            dao.getByCapsuleIdAndOriginAndOwner(capsuleId, origin, owner.ownerId.toRestString())
        if (existing.any { it.side == side }) {
            throw DuplicateFingerprintException(capsuleId, side)
        }
        require(plaintextBytes.isNotEmpty()) { "fingerprint bytes are empty" }

        val fingerprintId = newId()
        val target = owner.fileFor(fingerprintId)
        // Domain-separated AAD binds these ciphertext bytes to their row.
        val aad = aadFor(fingerprintId)
        val sealed = sealer.seal(plaintextBytes, aad)
        try {
            atomicWrite(target, sealed)
            dao.insertAll(
                listOf(
                    RecognitionFingerprintEntity(
                        fingerprintId = fingerprintId,
                        ownerUserId = owner.ownerId.toRestString(),
                        capsuleId = capsuleId,
                        side = side,
                        origin = origin,
                        fingerprintProfileId = profileId,
                        encryptedPath = target.relativeTo(owner.fingerprintsRoot).path,
                        createdAtEpochMs = nowEpochMs(),
                        preferred = false,
                    ),
                ),
            )
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
        return fingerprintId
    }

    override suspend fun hasBaseline(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
    ): Boolean {
        val owner = snapshotOwner()
        return dao.getByCapsuleIdAndOriginAndOwner(capsuleId, origin, owner.ownerId.toRestString())
            .any { it.side == side }
    }

    override suspend fun setPreferredPair(capsuleId: String, origin: FingerprintOrigin) {
        val owner = snapshotOwner()
        dao.setPreferredPairForOwner(capsuleId, origin, owner.ownerId.toRestString())
    }

    override suspend fun deleteBaseline(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
    ) {
        val owner = snapshotOwner()
        val entity = dao.getByCapsuleIdAndOriginAndOwner(capsuleId, origin, owner.ownerId.toRestString())
            .firstOrNull { it.side == side } ?: return
        owner.resolve(entity.encryptedPath).delete()
        check(dao.deleteByFingerprintIdAndOwner(entity.fingerprintId, owner.ownerId.toRestString()) == 1) {
            "owned baseline row vanished mid-delete"
        }
    }

    /** Loads the owned row, reads its file from the SAME owner root, and unseals; anything unexpected fails closed. */
    override suspend fun decrypt(fingerprintId: String): ByteArray {
        val owner = snapshotOwner()
        val entity = dao.getByFingerprintIdAndOwner(fingerprintId, owner.ownerId.toRestString())
            ?: throw IllegalStateException("unknown fingerprint record")
        val file = owner.resolve(entity.encryptedPath)
        if (!file.exists()) {
            throw IllegalStateException("encrypted fingerprint bytes are missing")
        }
        return sealer.unseal(file.readBytes(), aadFor(fingerprintId))
    }

    /** Removes the ciphertext file of one owned record; the Room row must be removed by callers. */
    suspend fun deleteFileOf(fingerprintId: String) {
        val owner = snapshotOwner()
        val entity = dao.getByFingerprintIdAndOwner(fingerprintId, owner.ownerId.toRestString()) ?: return
        owner.resolve(entity.encryptedPath).delete()
    }

    /** Resolves a stored RELATIVE path strictly within THIS snapshot's own fingerprints root. */
    private fun OwnerSnapshot.resolve(relativePath: String): File {
        val canonicalRoot = fingerprintsRoot.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        require(candidate.path.startsWith("$canonicalRoot${File.separator}")) {
            "stored fingerprint path escapes the account storage boundary"
        }
        return candidate
    }

    private fun OwnerSnapshot.fileFor(fingerprintId: String): File =
        File(fingerprintsRoot, "$fingerprintId.fpw")

    private fun aadFor(fingerprintId: String): ByteArray =
        "postmark/local-fp/v1:$fingerprintId".toByteArray(Charsets.UTF_8)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("could not persist ${target.name}")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
