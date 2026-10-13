package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * T186: non-blocking durable locator-retirement outbox.
 * Sources: T184 owner override + T185 `api-contract.md` §0/R15–R16
 * (STAGE 1 scaffold: routes NOT mounted; the container port stays
 * fail-closed until M2/M3).
 *
 * - Created ONLY after genuine supported presentation (caller gates on the
 *   live grant + verified Ready content; see [PendingPresentationGate]).
 * - Content display NEVER waits for retirement: [enqueue] persists
 *   synchronously and returns; [drainDetailed] runs in background.
 * - Queue holds ONLY opaque IDs (owner + capsule + operation UUID +
 *   optional publication hash). Never plaintext, keys, destination, or OTP
 *   — enforced by the type: no such fields exist.
 * - Persistence is pure Kotlin (`kotlinx.serialization.json`) — no Android
 *   `org.json` stub on this path, so plain-JVM unit tests exercise the real
 *   encode/decode. The on-disk shape keeps the original keys, so files
 *   written by earlier builds still read.
 * - Account/session fenced: drains execute items solely for
 *   [currentOwnerUserId], and re-check a live session lease around EVERY
 *   send (capture before, `isLive` + owner binding after the await). A dead
 *   lease keeps the item queued and aborts the drain; a switched owner never
 *   executes another owner's items.
 * - Corrupt queue files are NEVER swallowed into success: the unreadable
 *   file is quarantined aside (`outbox.corrupt.<millis>.json`, bytes
 *   preserved for forensics) and the drain reports [DrainOutcome.fault],
 *   which rearms the worker via backoff retry. Honest limitation: intents
 *   trapped inside irrecoverably corrupt bytes are preserved aside but NOT
 *   replayed — lossless recovery is never claimed for them. A failed
 *   [enqueue] intent itself is never lost: quarantine-then-write keeps the
 *   new intent queued. No UI thread is ever blocked by any of this.
 * - Durable across process death: atomic tmp-write + fsync + rename per
 *   owner under `filesDir/accounts/<owner>/pending-retirement/outbox.json`.
 *   A failed rename throws (never a silent non-atomic overwrite), and the
 *   scheduler treats persistence failure as non-fatal to presentation.
 * - In-process concurrency: all read-modify-write sections share one
 *   process-wide [Mutex], so a worker and an enqueue racing on separate
 *   outbox instances cannot lose updates.
 * - Backend failure never affects content display or entitlement; failures
 *   stay queued with backoff and retry later.
 */
class PendingRetirementOutbox(
    private val filesDir: File,
    private val port: PendingRetirementPort,
) {
    data class Item(
        val id: String,
        val ownerUserId: String,
        val capsuleId: String,
        /** Opaque idempotency UUID for R15 (generated at enqueue). */
        val operationId: String,
        /** Opaque publication hash for R15, when known. Null until wired. */
        val publicationHash: String?,
        val enqueuedAtMillis: Long,
        val attempts: Int,
    )

    /** Persists one retirement intent; never performs network I/O. */
    suspend fun enqueue(
        ownerUserId: String,
        capsuleId: String,
        publicationHash: String? = null,
        operationId: String = java.util.UUID.randomUUID().toString(),
        clockMillis: Long = System.currentTimeMillis(),
    ): Item = fileMutex.withLock {
        require(ownerUserId.isNotBlank() && capsuleId.isNotBlank())
        require(operationId.isNotBlank())
        val existing = try {
            readLocked(ownerUserId)
        } catch (_: Exception) {
            // Corrupt file: quarantine aside (bytes preserved), then keep
            // the new intent — it must not be lost with the old file.
            quarantineLocked(ownerUserId)
            emptyList()
        }.toMutableList()
        existing.firstOrNull { it.capsuleId == capsuleId }?.let { return@withLock it }
        val item = Item(
            id = java.util.UUID.randomUUID().toString(),
            ownerUserId = ownerUserId,
            capsuleId = capsuleId,
            operationId = operationId,
            publicationHash = publicationHash,
            enqueuedAtMillis = clockMillis,
            attempts = 0,
        )
        writeLocked(ownerUserId, existing + item)
        item
    }

    fun pendingCount(filesOwnerId: String): Int = readSnapshot(filesOwnerId).size

    fun readSnapshot(filesOwnerId: String): List<Item> = try {
        // Best-effort read for UI/scheduler bookkeeping; mutation paths
        // always go through [fileMutex]. Corrupt files surface as faults
        // through [drainDetailed], never silently here.
        readLocked(filesOwnerId)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Background drain for the CURRENT owner only. See [drainDetailed] for
     * fault semantics; returns the removed count.
     */
    suspend fun drain(
        currentOwnerUserId: String,
        sessionGate: PendingRetirementSessionGate? = null,
    ): Int = drainDetailed(currentOwnerUserId, sessionGate).removed

    /**
     * Background drain with explicit outcome. A corrupt queue file is
     * quarantined and reported as [DrainOutcome.fault] (worker retries via
     * backoff) — never mapped to empty-success, and the unprocessed tail is
     * always preserved on session aborts.
     */
    suspend fun drainDetailed(
        currentOwnerUserId: String,
        sessionGate: PendingRetirementSessionGate? = null,
    ): DrainOutcome = fileMutex.withLock {
        if (currentOwnerUserId.isBlank()) return@withLock DrainOutcome(0, 0, false)
        val items = try {
            readLocked(currentOwnerUserId)
        } catch (_: Exception) {
            quarantineLocked(currentOwnerUserId)
            return@withLock DrainOutcome(removed = 0, remaining = 0, fault = true)
        }
        var removed = 0
        val remaining = mutableListOf<Item>()
        var index = 0
        // Abort helper: keeps the current item (bumped) plus the ENTIRE
        // unprocessed tail, so a session invalidation never drops items.
        fun abortWithCurrent(item: Item) {
            remaining += item.copy(attempts = item.attempts + 1)
            remaining += items.subList(index + 1, items.size)
        }
        while (index < items.size) {
            val item = items[index]
            // Owner fence: belt-and-braces; file is already per-owner.
            if (item.ownerUserId != currentOwnerUserId) {
                remaining += item
                index += 1
                continue
            }
            val lease = try {
                sessionGate?.capture()
            } catch (_: Exception) {
                null
            }
            if (sessionGate != null && lease == null) {
                abortWithCurrent(item)
                index = items.size
                break
            }
            // Lease-to-item binding: the captured lease must belong to the
            // item's owner, not merely be live with a non-blank token.
            if (sessionGate != null && lease != null && !leaseOwnsItem(lease, item.ownerUserId)) {
                abortWithCurrent(item)
                index = items.size
                break
            }
            val token = if (sessionGate != null && lease != null) {
                try {
                    sessionGate.accessTokenFor(lease)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            if (sessionGate != null && token.isNullOrBlank()) {
                abortWithCurrent(item)
                index = items.size
                break
            }
            val outcome = try {
                port.retire(
                    item.ownerUserId,
                    item.capsuleId,
                    item.operationId,
                    item.publicationHash,
                    token ?: "",
                )
            } catch (_: Exception) {
                PendingRetireOutcome.RetryableFailure("transport")
            }
            // Real lease-epoch recheck AFTER the await: a rotation/logout
            // underneath the send invalidates this attempt even on success.
            val liveAfter = if (sessionGate != null && lease != null) {
                try {
                    sessionGate.isLive(lease) && leaseOwnsItem(lease, item.ownerUserId)
                } catch (_: Exception) {
                    false
                }
            } else {
                sessionGate == null
            }
            if (!liveAfter) {
                abortWithCurrent(item)
                index = items.size
                break
            }
            when (outcome) {
                is PendingRetireOutcome.Success,
                is PendingRetireOutcome.PermanentFailure,
                -> removed += 1
                is PendingRetireOutcome.RetryableFailure -> remaining +=
                    item.copy(attempts = item.attempts + 1)
            }
            index += 1
        }
        try {
            writeLocked(currentOwnerUserId, remaining)
        } catch (_: Exception) {
            // Durability failure: report what we removed in memory only.
            return@withLock DrainOutcome(removed, remaining.size, fault = true)
        }
        DrainOutcome(removed, remaining.size, fault = false)
    }

    private fun leaseOwnsItem(lease: SessionRequestLease, itemOwnerUserId: String): Boolean {
        val itemOwner = runCatching {
            dev.hryshyn.remanence.core.model.UserId.parseRest(itemOwnerUserId)
        }.getOrNull() ?: return false
        return lease.ownerUserId == itemOwner
    }

    // -- durability -------------------------------------------------------

    private fun outboxFile(ownerUserId: String): File {
        // Owner segment is validated upstream (canonical UUID string); reject
        // path separators defensively so one owner can never address another.
        require('/' !in ownerUserId && '\\' !in ownerUserId && ownerUserId != "..")
        return File(File(filesDir, "accounts/$ownerUserId/pending-retirement"), "outbox.json")
    }

    /** Moves an unreadable queue file aside, preserving bytes for forensics. */
    private fun quarantineLocked(ownerUserId: String) {
        try {
            val file = outboxFile(ownerUserId)
            if (file.isFile) {
                val aside = File(file.parentFile, "outbox.corrupt.${System.currentTimeMillis()}.json")
                file.renameTo(aside)
            }
        } catch (_: Exception) {
            // Best effort: the fault is still reported to the worker.
        }
    }

    private fun readLocked(ownerUserId: String): List<Item> {
        val file = outboxFile(ownerUserId)
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        // Strict shape: malformed JSON or wrong types throw (handled by the
        // caller as a fault), so corruption is never read as empty-success.
        // Keys match the original on-disk shape; absent newer keys take
        // stable defaults (pre-op files keep working).
        val dtos = queueJson.decodeFromString(ListSerializer(QueuedItemDto.serializer()), text)
        return dtos.map {
            require(it.id.isNotBlank() && it.owner.isNotBlank() && it.capsule.isNotBlank()) {
                "pending outbox entry missing identity"
            }
            Item(
                id = it.id,
                ownerUserId = it.owner,
                capsuleId = it.capsule,
                operationId = it.op.ifBlank { it.id },
                publicationHash = it.pub.ifBlank { null },
                enqueuedAtMillis = it.at,
                attempts = it.attempts,
            )
        }
    }

    private fun writeLocked(ownerUserId: String, items: List<Item>) {
        val file = outboxFile(ownerUserId)
        file.parentFile?.mkdirs()
        val dtos = items.map {
            QueuedItemDto(
                id = it.id,
                owner = it.ownerUserId,
                capsule = it.capsuleId,
                op = it.operationId,
                pub = it.publicationHash ?: "",
                at = it.enqueuedAtMillis,
                attempts = it.attempts,
            )
        }
        val text = queueJson.encodeToString(ListSerializer(QueuedItemDto.serializer()), dtos)
        // Atomic durable write: tmp + fsync + rename. A failed rename
        // throws — never a silent non-atomic in-place overwrite.
        val tmp = File(file.parentFile, "outbox.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("pending outbox rename failed")
        }
    }

    companion object {
        /** Process-wide lock: every outbox instance shares it. */
        private val fileMutex = Mutex()

        /** Plain-JVM JSON, no Android stub on this path. */
        private val queueJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

/** On-disk shape (keys stable across builds; newer keys default on read). */
@Serializable
private data class QueuedItemDto(
    val id: String,
    val owner: String,
    val capsule: String,
    val op: String = "",
    val pub: String = "",
    val at: Long = 0L,
    val attempts: Int = 0,
)

/** Explicit drain result: corrupt queues report [fault], never empty-success. */
data class DrainOutcome(
    val removed: Int,
    val remaining: Int,
    val fault: Boolean,
)

/** Network seam for the owner-only idempotent retire call (frozen T185 R15 DTO later). */
sealed interface PendingRetireOutcome {
    data object Success : PendingRetireOutcome
    data class RetryableFailure(val reason: String = "retryable") : PendingRetireOutcome
    data class PermanentFailure(val reason: String = "rejected") : PendingRetireOutcome
}

fun interface PendingRetirementPort {
    suspend fun retire(
        ownerUserId: String,
        capsuleId: String,
        operationId: String,
        publicationHash: String?,
        accessToken: String,
    ): PendingRetireOutcome
}

/**
 * Session-lease fence around every send. Production delegates to the real
 * `SessionRequestLease` coordinator via the application container; tests
 * supply fakes. A dead lease after the await invalidates the attempt.
 */
interface PendingRetirementSessionGate {
    fun capture(): SessionRequestLease?
    fun accessTokenFor(lease: SessionRequestLease): String?
    fun isLive(lease: SessionRequestLease): Boolean
}

/**
 * Default port before the frozen T185 routes mount (STAGE 1 scaffold):
 * always asks for a retry later. Never reports fake success.
 */
object UnconfiguredPendingRetirementPort : PendingRetirementPort {
    override suspend fun retire(
        ownerUserId: String,
        capsuleId: String,
        operationId: String,
        publicationHash: String?,
        accessToken: String,
    ) = PendingRetireOutcome.RetryableFailure("pending-contract-unavailable")
}
