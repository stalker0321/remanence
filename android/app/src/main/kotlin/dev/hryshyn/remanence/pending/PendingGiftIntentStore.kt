package dev.hryshyn.remanence.pending

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * T186 M10: minimal opaque gift intent, durable outside the sheet.
 *
 * A single slot (no history, no inbox): `{owner, gift_reference, code,
 * saved_at}` — opaque reference + code only, never keys/plaintext. Survives
 * sheet close, rotation, and process restart; resumes only for the SAME
 * account (owner fence) with mandatory PRF readiness enforced by the
 * existing scan/auth gates (this store never bypasses them). A save from a
 * different owner replaces the slot; logout clears it. No UI thread is
 * ever blocked (callers use background scopes).
 */
class PendingGiftIntentStore(filesDir: File) {

    data class StoredIntent(
        val ownerUserId: String,
        val giftReference: String,
        val code: String,
        val savedAtMillis: Long,
    )

    private val dir = File(filesDir, "pending-gift")
    private val file = File(dir, "intent.json")
    private val mutex = Mutex()

    suspend fun save(ownerUserId: String, giftReference: String, code: String): StoredIntent? =
        mutex.withLock {
            if (ownerUserId.isBlank() || giftReference.isBlank()) return@withLock null
            val intent = StoredIntent(ownerUserId, giftReference, code, System.currentTimeMillis())
            try {
                dir.mkdirs()
                val text = storeJson.encodeToString(StoredIntentDto.serializer(), StoredIntentDto(
                    intent.ownerUserId, intent.giftReference, intent.code, intent.savedAtMillis,
                ))
                val tmp = File(dir, "intent.json.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return@withLock null
                }
                intent
            } catch (_: Exception) {
                null
            }
        }

    /** Returns the intent only for the SAME owner; null otherwise. */
    fun peek(ownerUserId: String): StoredIntent? {
        return try {
            if (ownerUserId.isBlank() || !file.isFile) return null
            val text = file.readText()
            if (text.isBlank()) return null
            val dto = storeJson.decodeFromString(StoredIntentDto.serializer(), text)
            if (dto.owner.isBlank() || dto.owner != ownerUserId || dto.reference.isBlank()) {
                null
            } else {
                StoredIntent(dto.owner, dto.reference, dto.code, dto.at)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clear(): Unit = mutex.withLock {
        try {
            file.delete()
        } catch (_: Exception) {
            Unit
        }
    }

    @Serializable
    private data class StoredIntentDto(
        val owner: String = "",
        val reference: String = "",
        val code: String = "",
        val at: Long = 0L,
    )

    companion object {
        private val storeJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** File name for tests/maintenance callers. */
        const val FILE_NAME: String = "intent.json"
    }
}
