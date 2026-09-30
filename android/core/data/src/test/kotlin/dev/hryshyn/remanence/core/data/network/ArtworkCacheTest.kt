package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bounded, content-keyed, in-memory-only verified-artwork cache. */
class ArtworkCacheTest {

    private fun digest(value: Int) = ByteArray(32) { value.toByte() }

    @Test
    fun putAndGetReturnADefensiveCopy() {
        val cache = ArtworkCache()
        val bytes = ByteArray(8) { it.toByte() }
        cache.put(digest(1), bytes)
        val got = requireNotNull(cache.get(digest(1)))
        assertContentEquals(bytes, got)
        got[0] = 99
        assertContentEquals(bytes, cache.get(digest(1)))
    }

    @Test
    fun lookupIsKeyedByDigestContent() {
        val cache = ArtworkCache()
        cache.put(digest(1), ByteArray(4) { 1 })
        assertNull(cache.get(digest(2)))
        assertContentEquals(ByteArray(4) { 1 }, cache.get(digest(1)))
    }

    @Test
    fun malformedDigestIsIgnored() {
        val cache = ArtworkCache()
        cache.put(ByteArray(31), ByteArray(4))
        assertEquals(0, cache.size)
    }

    @Test
    fun lruEvictsByEntryCount() {
        val cache = ArtworkCache(maxEntries = 2, maxTotalBytes = 1024)
        cache.put(digest(1), ByteArray(4) { 1 })
        cache.put(digest(2), ByteArray(4) { 2 })
        cache.get(digest(1))
        cache.put(digest(3), ByteArray(4) { 3 })
        assertNull(cache.get(digest(2)))
        assertTrue(cache.get(digest(1)) != null)
        assertTrue(cache.get(digest(3)) != null)
    }

    @Test
    fun evictsByByteBudget() {
        val cache = ArtworkCache(maxEntries = 8, maxTotalBytes = 100)
        cache.put(digest(1), ByteArray(60))
        cache.put(digest(2), ByteArray(60))
        assertEquals(1, cache.size)
        assertEquals(60L, cache.totalBytes)
        assertNull(cache.get(digest(1)))
        assertTrue(cache.get(digest(2)) != null)
    }

    @Test
    fun blockLargerThanTheWholeBudgetIsIgnored() {
        val cache = ArtworkCache(maxEntries = 4, maxTotalBytes = 10)
        cache.put(digest(1), ByteArray(11))
        assertEquals(0, cache.size)
    }

    @Test
    fun clearDropsEverything() {
        val cache = ArtworkCache()
        cache.put(digest(1), ByteArray(4))
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.totalBytes)
        assertNull(cache.get(digest(1)))
    }
}
