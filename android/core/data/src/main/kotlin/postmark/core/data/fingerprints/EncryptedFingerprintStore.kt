package postmark.core.data.fingerprints

import java.io.File
import java.util.UUID
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.data.db.RecognitionFingerprintDao
import postmark.core.data.db.RecognitionFingerprintEntity

/** Raised when the same (capsule, side, origin) baseline already exists. */
class DuplicateFingerprintException(capsuleId: String, side: FingerprintSide) :
    IllegalStateException("fingerprint baseline already captured for $capsuleId/$side")

/**
 * Persists postcard fingerprints only as sealed bytes in app-private files
 * plus a Room reference row (docs/architecture.md section 6, security.md
 * section 11). Plaintext never touches disk: sealing happens before the
 * atomic write, and any mid-way failure removes artifacts this call created.
 */
class EncryptedFingerprintStore(
    private val filesRoot: File,
    private val sealer: SecretSealer,
    private val dao: RecognitionFingerprintDao,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SealedFingerprintPersistence {

    init {
        if (!filesRoot.exists() && !filesRoot.mkdirs()) {
            throw IllegalStateException("cannot create fingerprint storage root")
        }
    }

    /**
     * Seals [plaintextBytes], writes them atomically, and records the strict
     * reference row. Returns the generated fingerprint ID.
     */
    override suspend fun persist(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
        profileId: String,
        plaintextBytes: ByteArray,
    ): String {
        val existing = dao.getByCapsuleIdAndOrigin(capsuleId, origin)
        if (existing.any { it.side == side }) {
            throw DuplicateFingerprintException(capsuleId, side)
        }
        require(plaintextBytes.isNotEmpty()) { "fingerprint bytes are empty" }

        val fingerprintId = newId()
        val target = fileFor(fingerprintId)
        // Domain-separated AAD binds these ciphertext bytes to their row.
        val aad = aadFor(fingerprintId)
        val sealed = sealer.seal(plaintextBytes, aad)
        try {
            atomicWrite(target, sealed)
            dao.insertAll(
                listOf(
                    RecognitionFingerprintEntity(
                        fingerprintId = fingerprintId,
                        capsuleId = capsuleId,
                        side = side,
                        origin = origin,
                        fingerprintProfileId = profileId,
                        encryptedPath = target.relativeTo(filesRoot).path,
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
    ): Boolean = dao.getByCapsuleIdAndOrigin(capsuleId, origin).any { it.side == side }

    override suspend fun setPreferredPair(capsuleId: String, origin: FingerprintOrigin) {
        dao.setPreferredPair(capsuleId, origin)
    }

    override suspend fun deleteBaseline(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
    ) {
        val entity = dao.getByCapsuleIdAndOrigin(capsuleId, origin)
            .firstOrNull { it.side == side } ?: return
        filesRoot.resolve(entity.encryptedPath).delete()
        dao.deleteByFingerprintId(entity.fingerprintId)
    }

    /** Loads the row, reads its file, and unseals; anything unexpected fails closed. */
    override suspend fun decrypt(fingerprintId: String): ByteArray {
        val entity = dao.getByFingerprintId(fingerprintId)
            ?: throw IllegalStateException("unknown fingerprint record")
        val file = filesRoot.resolve(entity.encryptedPath)
        if (!file.exists()) {
            throw IllegalStateException("encrypted fingerprint bytes are missing")
        }
        return sealer.unseal(file.readBytes(), aadFor(fingerprintId))
    }

    /** Removes the ciphertext file of one record; the Room row must be removed by callers. */
    suspend fun deleteFileOf(fingerprintId: String) {
        val entity = dao.getByFingerprintId(fingerprintId) ?: return
        filesRoot.resolve(entity.encryptedPath).delete()
    }

    private fun fileFor(fingerprintId: String): File =
        File(filesRoot, "$fingerprintId.fpw")

    private fun aadFor(fingerprintId: String): ByteArray =
        "postmark/local-fp/v1:$fingerprintId".toByteArray(Charsets.UTF_8)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(filesRoot, "${target.name}.tmp")
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
