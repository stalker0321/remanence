package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.ui.collage.UprightBytesSource
import java.security.MessageDigest

/**
 * Standalone, non-production holder for retained ORIGINAL encoded bytes
 * backing a future analysis seam. It retains only bytes whose supplied
 * verified hash and content-ID binding agrees with the existing
 * [CapsulePhotoIdentity] rules: the SHA-256 of the bytes must equal the
 * supplied verified hash (64 lowercase hex), and the content ID must equal
 * `contentIdFor(verifiedHash)` (`"c" + hash`). The holder owns an internal
 * copy taken at retain time; later mutation of the caller's array cannot
 * alter it.
 *
 * Limits (reject atomically, without evicting or mutating existing entries):
 * at most [MAX_ITEMS] entries, at most
 * [GeneratorStaging.RAW_SOURCE_MAX_BYTES] per item, at most
 * [MAX_TOTAL_BYTES] aggregate. Retaining the same content ID with the same
 * bytes/hash is idempotent; the same key with conflicting identity or
 * content is rejected (identity rules prohibit replacement, so there is no
 * replace API, and no eviction path that would need to zero old bytes).
 *
 * [bytesFor] returns a defensive copy owned by the caller (`null` when
 * absent or closed), matching the [UprightBytesSource] ownership the A4B3
 * seam expects (it zeroizes after decode). [remove], [clear], and [close]
 * zeroize the retained arrays and are idempotent.
 *
 * Single-coroutine-confined: all methods must be called from one confined
 * coroutine context (for example the analysis caller's `LaunchedEffect`
 * scope). There is deliberately no internal lock and no shared mutable
 * state beyond the instance. No URI, preview bytes, disk, network, G3
 * bridge mutation, or production lifecycle wiring. Never mounted in Create;
 * no capability or publish change.
 */
class RetainedOriginalHolder : AutoCloseable {

    sealed interface RetainResult {
        data object Retained : RetainResult
        data object AlreadyRetained : RetainResult
        data class Rejected(val reason: String) : RetainResult
    }

    private data class Entry(val verifiedHash: String, val bytes: ByteArray)

    private val entries = LinkedHashMap<String, Entry>()
    private var totalBytes = 0L
    private var closed = false

    /**
     * Retains an internal copy of [bytes] under [contentId] when the binding
     * holds and the limits allow; otherwise rejects without mutating
     * anything. Reasons are fixed strings carrying no hashes, IDs, or sizes.
     */
    fun retain(contentId: String, bytes: ByteArray, verifiedHash: String): RetainResult {
        if (closed) return RetainResult.Rejected("holder is closed")
        if (contentId.isBlank()) return RetainResult.Rejected("content id is blank")
        if (!HASH64.matches(verifiedHash)) return RetainResult.Rejected("hash is malformed")
        if (bytes.isEmpty()) return RetainResult.Rejected("bytes are empty")
        if (bytes.size > GeneratorStaging.RAW_SOURCE_MAX_BYTES) {
            return RetainResult.Rejected("bytes over bound")
        }
        if (sha256Hex(bytes) != verifiedHash) return RetainResult.Rejected("hash mismatch")
        if (contentId != CapsulePhotoIdentity.contentIdFor(verifiedHash)) {
            return RetainResult.Rejected("content binding mismatch")
        }
        val existing = entries[contentId]
        if (existing != null) {
            return if (existing.verifiedHash == verifiedHash && existing.bytes.contentEquals(bytes)) {
                RetainResult.AlreadyRetained
            } else {
                RetainResult.Rejected("conflicting entry")
            }
        }
        if (entries.size >= MAX_ITEMS) return RetainResult.Rejected("holder is full")
        if (totalBytes + bytes.size > MAX_TOTAL_BYTES) {
            return RetainResult.Rejected("aggregate bytes over bound")
        }
        entries[contentId] = Entry(verifiedHash, bytes.copyOf())
        totalBytes += bytes.size
        return RetainResult.Retained
    }

    /** Defensive caller-owned copy, or `null` when absent or closed. */
    fun bytesFor(contentId: String): ByteArray? {
        if (closed) return null
        return entries[contentId]?.bytes?.copyOf()
    }

    /**
     * T186 M12: insertion-ordered snapshot of caller-owned retained
     * copies (the pending v2 sender seals these authored originals).
     * Empty when closed or nothing retained. Pure read — no state
     * change, no zeroization.
     */
    fun orderedBytes(): List<ByteArray> {
        if (closed) return emptyList()
        return entries.values.map { it.bytes.copyOf() }
    }

    /**
     * Drops [contentId], zeroizing its retained array first. Returns whether
     * an entry was present; idempotent.
     */
    fun remove(contentId: String): Boolean {
        val entry = entries.remove(contentId) ?: return false
        totalBytes -= entry.bytes.size
        entry.bytes.fill(0)
        return true
    }

    /** Zeroizes every retained array and drops all entries; idempotent. */
    fun clear() {
        for (entry in entries.values) entry.bytes.fill(0)
        entries.clear()
        totalBytes = 0L
    }

    /** Idempotent teardown: closed, cleared, and zeroized. */
    override fun close() {
        closed = true
        clear()
    }

    /** A4B3 adapter: serves defensive caller-owned copies per content ID. */
    fun asUprightBytesSource(): UprightBytesSource = UprightBytesSource { bytesFor(it) }

    /** Test-only retained sizes, proving atomic add/remove/clear/close. */
    internal fun retainedSizesForTests(): Map<String, Int> =
        entries.mapValues { it.value.bytes.size }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Max retained originals (photos are 3..5; no restage margin here). */
        const val MAX_ITEMS = 5

        /** Max aggregate retained bytes (64 MiB). */
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L

        private val HASH64 = Regex("[0-9a-f]{64}")
    }
}
