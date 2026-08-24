package app.postmark.memory.session

import java.io.File

/** Locally persisted account summary recorded after login/registration. */
data class PersistedAccountSummary(
    val userId: String,
    val handle: String,
)

/**
 * Tiny atomic file store for the current account summary ("user_id|handle"),
 * kept deliberately dependency-free and separate from any token material.
 */
class AccountSummaryStore(private val file: File) {

    fun save(summary: PersistedAccountSummary) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText("${summary.userId}\n${summary.handle}")
            if (!temporary.renameTo(file)) {
                throw IllegalStateException("could not persist account summary")
            }
        } finally {
            temporary.delete()
        }
    }

    fun load(): PersistedAccountSummary? {
        if (!file.exists()) return null
        val parts = runCatching { file.readText().split('\n') }.getOrNull() ?: return null
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return PersistedAccountSummary(parts[0], parts[1])
    }

    fun clear() {
        file.delete()
    }
}
