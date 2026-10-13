package dev.hryshyn.remanence.pending

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * T186 M14a: durable receiver-owned admitted v2 gift index (metadata
 * only — NO keysets, NO plaintext, NO private material).
 *
 * One JSON file per owner under `pending-admitted/`: rows keyed by
 * capsule id carrying the claim/delivery/target ids, availability,
 * the sealed envelope bytes (opaque ciphertext for offline reopen
 * with live keys — never key material), its SHA-256, the manifest
 * artifact list and optional R15 completion metadata. Rows are
 * written ONLY after strict trusted admission (sender pair + service
 * pin + D/KC + owner checks); the cursor advances only past admitted
 * rows, so an unadmitted entry can never be skipped over.
 *
 * Claimed entitlement survives claim expiry, code death, reinstall
 * and second-device restore (same envelope re-fetchable via R12);
 * disabled recipients keep already-delivered rows (no remote
 * revocation is ever issued here).
 */
class PendingAdmittedGiftStore(filesDir: File) {

    data class StoredArtifact(
        val blobId: String,
        val kind: Int,
        val ordinal: Int,
        val ciphertextSize: Long,
        val ciphertextSha256Hex: String,
    )

    data class AdmittedGift(
        val ownerUserId: String,
        val capsuleId: String,
        val deliveryId: String,
        val deliveryTargetId: String,
        val claimId: String,
        val availableAt: Long,
        /** Sealed opaque envelope bytes (no keys) for offline reopen. */
        val envelopeB64: String,
        val envelopeSha256Hex: String,
        val artifacts: List<StoredArtifact>,
        /** R15 completion instant, once the background completion lands. */
        val completedAt: Long?,
        val admittedAtMillis: Long,
    )

    private val root = File(filesDir, "pending-admitted")
    private val mutex = Mutex()

    private fun fileFor(ownerUserId: String): File? {
        if (ownerUserId.isBlank() || ownerUserId.contains('/') || ownerUserId.contains("..")) {
            return null
        }
        return File(File(root, ownerUserId), "index.json")
    }

    /** Inserts or replaces the row for its capsule (same owner only). */
    suspend fun upsert(gift: AdmittedGift): Boolean {
        if (gift.ownerUserId.isBlank() || gift.capsuleId.isBlank()) return false
        val file = fileFor(gift.ownerUserId) ?: return false
        return mutex.withLock {
            try {
                val current = readLocked(file, gift.ownerUserId).toMutableList()
                current.removeAll { it.capsuleId == gift.capsuleId }
                current.add(gift)
                writeLocked(file, gift.ownerUserId, current)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Marks R15 completion for one admitted row (no-op when absent). */
    suspend fun markCompleted(ownerUserId: String, capsuleId: String, completedAt: Long): Boolean {
        if (ownerUserId.isBlank() || capsuleId.isBlank() || completedAt < 0) return false
        val file = fileFor(ownerUserId) ?: return false
        return mutex.withLock {
            try {
                val current = readLocked(file, ownerUserId)
                val index = current.indexOfFirst { it.capsuleId == capsuleId }
                if (index < 0) return@withLock false
                val updated = current.toMutableList()
                val row = updated[index]
                updated[index] = row.copy(completedAt = completedAt)
                writeLocked(file, ownerUserId, updated)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** All admitted rows for the SAME owner, insertion-ordered. */
    fun list(ownerUserId: String): List<AdmittedGift> {
        val file = fileFor(ownerUserId) ?: return emptyList()
        return try {
            if (!file.isFile) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val dto = storeJson.decodeFromString(IndexDto.serializer(), text)
            if (dto.owner.isBlank() || dto.owner != ownerUserId) return emptyList()
            dto.gifts.mapNotNull { it.toGift(ownerUserId) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** One admitted row for the SAME owner, or null. */
    fun get(ownerUserId: String, capsuleId: String): AdmittedGift? {
        if (ownerUserId.isBlank() || capsuleId.isBlank()) return null
        return list(ownerUserId).firstOrNull { it.capsuleId == capsuleId }
    }

    private fun readLocked(file: File, ownerUserId: String): List<AdmittedGift> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val dto = storeJson.decodeFromString(IndexDto.serializer(), text)
        if (dto.owner.isBlank() || dto.owner != ownerUserId) return emptyList()
        return dto.gifts.mapNotNull { it.toGift(ownerUserId) }
    }

    private fun writeLocked(file: File, ownerUserId: String, gifts: List<AdmittedGift>) {
        file.parentFile?.mkdirs()
        val dto = IndexDto(
            ownerUserId,
            gifts.map {
                GiftDto(
                    it.capsuleId, it.deliveryId, it.deliveryTargetId, it.claimId,
                    it.availableAt, it.envelopeB64, it.envelopeSha256Hex,
                    it.artifacts.map { artifact ->
                        ArtifactDto(
                            artifact.blobId, artifact.kind, artifact.ordinal,
                            artifact.ciphertextSize, artifact.ciphertextSha256Hex,
                        )
                    },
                    it.completedAt, it.admittedAtMillis,
                )
            },
        )
        val text = storeJson.encodeToString(IndexDto.serializer(), dto)
        val tmp = File(file.parentFile, "index.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("pending admitted index not durable")
        }
    }

    @Serializable
    private data class ArtifactDto(
        val blobId: String = "",
        val kind: Int = 0,
        val ordinal: Int = 0,
        val size: Long = -1,
        val sha256Hex: String = "",
    )

    @Serializable
    private data class GiftDto(
        val capsuleId: String = "",
        val deliveryId: String = "",
        val deliveryTargetId: String = "",
        val claimId: String = "",
        val availableAt: Long = -1,
        val envelopeB64: String = "",
        val envelopeSha256Hex: String = "",
        val artifacts: List<ArtifactDto> = emptyList(),
        val completedAt: Long? = null,
        val admittedAt: Long = 0,
    ) {
        fun toGift(ownerUserId: String): AdmittedGift? {
            if (capsuleId.isBlank() || deliveryId.isBlank() || deliveryTargetId.isBlank() ||
                claimId.isBlank() || availableAt < 0 || envelopeB64.isBlank()
            ) {
                return null
            }
            return AdmittedGift(
                ownerUserId, capsuleId, deliveryId, deliveryTargetId, claimId,
                availableAt, envelopeB64, envelopeSha256Hex,
                artifacts.map {
                    StoredArtifact(it.blobId, it.kind, it.ordinal, it.size, it.sha256Hex)
                },
                completedAt, admittedAt,
            )
        }
    }

    @Serializable
    private data class IndexDto(
        val owner: String = "",
        val gifts: List<GiftDto> = emptyList(),
    )

    companion object {
        private val storeJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
