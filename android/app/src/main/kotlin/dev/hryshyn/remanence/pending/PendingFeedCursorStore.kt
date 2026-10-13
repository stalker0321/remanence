package dev.hryshyn.remanence.pending

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * T186 M13: durable receiver-owned v2 feed cursor (opaque, per-owner).
 *
 * A single slot `{owner, cursor}` — the opaque base64 claim-time cursor
 * is server-meaningful only; the client never parses it. Survives
 * process restart and second-device restore flows (the server feed is
 * ordered by claim time, so a late claim can never fall behind a
 * publish-time cursor). Owner-fenced: a different account starts from
 * null (generic empty page server-side — no existence oracle). v1
 * cursors live elsewhere and are never touched here.
 */
class PendingFeedCursorStore(filesDir: File) {

    private val dir = File(filesDir, "pending-feed")
    private val file = File(dir, "cursor.json")
    private val mutex = Mutex()

    suspend fun save(ownerUserId: String, cursor: String) {
        if (ownerUserId.isBlank()) return
        mutex.withLock {
            try {
                dir.mkdirs()
                val text = storeJson.encodeToString(
                    CursorDto.serializer(),
                    CursorDto(ownerUserId, cursor),
                )
                val tmp = File(dir, "cursor.json.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                }
            } catch (_: Exception) {
                // Cursor loss only repeats a page (idempotent feed).
            }
        }
    }

    /** Returns the cursor only for the SAME owner; null otherwise. */
    fun peek(ownerUserId: String): String? {
        return try {
            if (ownerUserId.isBlank() || !file.isFile) return null
            val text = file.readText()
            if (text.isBlank()) return null
            val dto = storeJson.decodeFromString(CursorDto.serializer(), text)
            if (dto.owner.isBlank() || dto.owner != ownerUserId) {
                null
            } else {
                dto.cursor.ifEmpty { null }
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
    private data class CursorDto(
        val owner: String = "",
        val cursor: String = "",
    )

    companion object {
        private val storeJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** File name for tests/maintenance callers. */
        const val FILE_NAME: String = "cursor.json"
    }
}
