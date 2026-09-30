package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Bounded, privacy-safe in-memory cache for verified cover-art bytes.
 *
 * Decision (V5 artwork contract, invariants I1/I2 and the privacy note): cover
 * bytes are cached **in memory only**, keyed by the sealed SHA-256 digest, never
 * by URL or provider, never written to disk, and never logged. The cache is
 * bounded by entry count and total bytes; the least-recently-used entry is
 * evicted first. Only bytes already verified by [ArtworkFetcher] are stored, so
 * a cache hit is exactly as trustworthy as a fresh verified fetch. Callers
 * receive a defensive copy and own its lifetime.
 */
class ArtworkCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var bytesHeld = 0L

    /** A copy of the verified bytes for [sha256], or `null` on a miss. */
    fun get(sha256: ByteArray): ByteArray? = synchronized(lock) {
        entries[sha256.toHexKey()]?.copyOf()
    }

    /**
     * Stores verified [bytes] under [sha256]. Ignores a malformed digest, an
     * empty block, or a block larger than the whole cache budget; evicts LRU
     * entries until both bounds hold.
     */
    fun put(sha256: ByteArray, bytes: ByteArray) {
        if (sha256.size != GeneratorExpression.ARTWORK_SHA256_BYTES) return
        if (bytes.isEmpty() || bytes.size.toLong() > maxTotalBytes) return
        synchronized(lock) {
            val key = sha256.toHexKey()
            entries.remove(key)?.let { bytesHeld -= it.size }
            entries[key] = bytes.copyOf()
            bytesHeld += bytes.size
            val iterator = entries.entries.iterator()
            while ((entries.size > maxEntries || bytesHeld > maxTotalBytes) && iterator.hasNext()) {
                val victim = iterator.next()
                iterator.remove()
                bytesHeld -= victim.value.size
            }
        }
    }

    /** Drops every cached block. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            bytesHeld = 0L
        }
    }

    /** Number of cached blocks. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** Total bytes currently held. */
    val totalBytes: Long get() = synchronized(lock) { bytesHeld }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 8
        const val DEFAULT_MAX_TOTAL_BYTES = 8L * 1024 * 1024
    }
}

private fun ByteArray.toHexKey(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
