package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * T186: non-blocking durable locator-retirement outbox.
 * Sources: T184 owner override + T185 `api-contract.md` §0/R15–R16
 * (STAGE 1 scaffold: routes NOT mounted; the container port stays
 * fail-closed until M2/M3).
 *
 * - Created ONLY after genuine supported presentation (caller gates on the
 *   live grant + verified Ready content; see [PendingPresentationGate]).
 * - Content display NEVER waits for retirement: [enqueue] persists
 *   synchronously and returns; [drain] runs in background.
 * - Queue holds ONLY opaque IDs (owner + capsule + operation UUID +
 *   optional publication hash). Never plaintext, keys, destination, or OTP
 *   — enforced by the type: no such fields exist.
 * - Account/session fenced: [drain] executes items solely for
 *   [currentOwnerUserId], and re-checks a live session lease around EVERY
 *   send (capture before, `isLive` after the await). A dead lease keeps the
 *   item queued and aborts the drain; a switched owner never executes
 *   another owner's items.
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
        val existing = readLocked(ownerUserId).toMutableList()
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
        // always go through [fileMutex].
        readLocked(filesOwnerId)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Background drain for the CURRENT owner only. Returns the number of
     * items removed. Items for any other owner are never executed. Every
     * send is wrapped in a session-lease capture/recheck via [sessionGate]:
     * a null gate, a missing lease, a blank token, a lease bound to a
     * different account, or a dead post-await lease keeps the item queued
     * and aborts the drain for later retry — always preserving the full
     * unprocessed tail (never dropping not-yet-iterated items).
     */
    suspend fun drain(
        currentOwnerUserId: String,
        sessionGate: PendingRetirementSessionGate? = null,
    ): Int = fileMutex.withLock {
        if (currentOwnerUserId.isBlank()) return@withLock 0
        val items = try {
            readLocked(currentOwnerUserId)
        } catch (_: Exception) {
            return@withLock 0
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
        }
        removed
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

    private fun readLocked(ownerUserId: String): List<Item> {
        val file = outboxFile(ownerUserId)
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o: JSONObject = arr.getJSONObject(i)
            Item(
                id = o.getString("id"),
                ownerUserId = o.getString("owner"),
                capsuleId = o.getString("capsule"),
                // Backward compatible: files written before operationId/
                // publicationHash gain stable defaults on read.
                operationId = o.optString("op", "").ifBlank { o.getString("id") },
                publicationHash = o.optString("pub", "").ifBlank { null },
                enqueuedAtMillis = o.optLong("at", 0L),
                attempts = o.optInt("attempts", 0),
            )
        }
    }

    private fun writeLocked(ownerUserId: String, items: List<Item>) {
        val file = outboxFile(ownerUserId)
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("owner", item.ownerUserId)
                    .put("capsule", item.capsuleId)
                    .put("op", item.operationId)
                    .put("pub", item.publicationHash ?: "")
                    .put("at", item.enqueuedAtMillis)
                    .put("attempts", item.attempts),
            )
        }
        // Atomic durable write: tmp + fsync + rename. A failed rename
        // throws — never a silent non-atomic in-place overwrite.
        val tmp = File(file.parentFile, "outbox.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
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
    }
}

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
