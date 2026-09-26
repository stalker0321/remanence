package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorStaging
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM tests for the non-production [RetainedOriginalHolder]: binding
 * against the real [CapsulePhotoIdentity] rule, item/count/aggregate
 * boundaries, atomic rejection, defensive copies, idempotent duplicates,
 * absent/closed behavior, and zeroization on remove/clear/close observed
 * through the same-module live-reference hook (public API untouched).
 * Standalone: no Create wiring, no capability change.
 */
class RetainedOriginalHolderTest {

    private fun triple(seed: Int, size: Int = 64): Triple<String, ByteArray, String> {
        val bytes = ByteArray(size) { ((it * 31 + seed * 17) and 0xFF).toByte() }
        val hash = sha256Hex(bytes)
        return Triple(CapsulePhotoIdentity.contentIdFor(hash), bytes, hash)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertRetained(holder: RetainedOriginalHolder, id: String, bytes: ByteArray) {
        val served = holder.bytesFor(id)
        assertTrue("expected bytes for $id", served != null)
        assertTrue(served!!.contentEquals(bytes))
        assertTrue("must be a copy, not the caller input", served !== bytes)
    }

    /**
     * Bounded test-source reflection: captures the exact live retained array
     * for [contentId] BEFORE a lifecycle action, so zeroization of the
     * holder-owned array is proven without any main-source accessor,
     * callback, or visibility change. Touches only the private `entries` map
     * and the private entry's `bytes` field; nothing is modified.
     */
    private fun liveRetainedBytes(holder: RetainedOriginalHolder, contentId: String): ByteArray {
        val entriesField = RetainedOriginalHolder::class.java.getDeclaredField("entries")
        entriesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val entries = entriesField.get(holder) as LinkedHashMap<*, *>
        val entry = entries[contentId] ?: error("no retained entry for $contentId")
        val bytesField = entry.javaClass.getDeclaredField("bytes")
        bytesField.isAccessible = true
        return bytesField.get(entry) as ByteArray
    }

    @Test
    fun retainServesDefensiveCopyAndOwnsInternalCopy() {
        val holder = RetainedOriginalHolder()
        try {
            val (id, bytes, hash) = triple(1)
            assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
            assertRetained(holder, id, bytes)
            // Mutating the served copy cannot alter the holder.
            holder.bytesFor(id)!!.fill(9)
            assertRetained(holder, id, bytes)
            // Mutating the original input after retain cannot alter the holder.
            bytes.fill(0)
            val served = holder.bytesFor(id)!!
            assertTrue(served.any { it != 0.toByte() })
            assertEquals(sha256Hex(served), hash)
        } finally {
            holder.close()
        }
    }

    @Test
    fun sameRetainIsIdempotentNoOp() {
        val holder = RetainedOriginalHolder()
        try {
            val (id, bytes, hash) = triple(2)
            assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
            assertTrue(
                holder.retain(id, bytes.copyOf(), hash) is RetainedOriginalHolder.RetainResult.AlreadyRetained,
            )
            assertEquals(mapOf(id to bytes.size), holder.retainedSizesForTests())
            assertRetained(holder, id, bytes)
        } finally {
            holder.close()
        }
    }

    @Test
    fun conflictingIdentityIsRejectedWithoutMutatingExisting() {
        val holder = RetainedOriginalHolder()
        try {
            val (idA, bytesA, hashA) = triple(3)
            assertTrue(holder.retain(idA, bytesA, hashA) is RetainedOriginalHolder.RetainResult.Retained)
            // Same key, different bytes with their own valid binding: the key
            // cannot denote two originals, so the binding check rejects first.
            val (_, bytesB, hashB) = triple(4)
            val rejected = holder.retain(idA, bytesB, hashB)
            assertTrue("expected Rejected, got $rejected", rejected is RetainedOriginalHolder.RetainResult.Rejected)
            assertRetained(holder, idA, bytesA)
            assertEquals(mapOf(idA to bytesA.size), holder.retainedSizesForTests())
            // Same key, same hash, tampered bytes: hash check rejects.
            val tampered = bytesA.copyOf().also { it[0] = (it[0] + 1).toByte() }
            val tamperedResult = holder.retain(idA, tampered, hashA)
            assertTrue(tamperedResult is RetainedOriginalHolder.RetainResult.Rejected)
            assertRetained(holder, idA, bytesA)
        } finally {
            holder.close()
        }
    }

    @Test
    fun hashMismatchIsRejected() {
        val holder = RetainedOriginalHolder()
        try {
            val (id, bytes, _) = triple(5)
            val (_, _, otherHash) = triple(6)
            val result = holder.retain(id, bytes, otherHash)
            assertTrue("expected Rejected, got $result", result is RetainedOriginalHolder.RetainResult.Rejected)
            assertNull(holder.bytesFor(id))
            assertTrue(holder.retainedSizesForTests().isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun bindingMismatchIsRejected() {
        val holder = RetainedOriginalHolder()
        try {
            val (_, bytes, hash) = triple(7)
            val (otherId, _, _) = triple(8)
            val result = holder.retain(otherId, bytes, hash)
            assertTrue("expected Rejected, got $result", result is RetainedOriginalHolder.RetainResult.Rejected)
            assertNull(holder.bytesFor(otherId))
        } finally {
            holder.close()
        }
    }

    @Test
    fun malformedInputsAreRejected() {
        val holder = RetainedOriginalHolder()
        try {
            val (id, bytes, hash) = triple(9)
            assertTrue(holder.retain(id, bytes, "not-hex") is RetainedOriginalHolder.RetainResult.Rejected)
            assertTrue(holder.retain(id, bytes, "ab".repeat(16)) is RetainedOriginalHolder.RetainResult.Rejected)
            assertTrue(holder.retain("  ", bytes, hash) is RetainedOriginalHolder.RetainResult.Rejected)
            assertTrue(holder.retain(id, ByteArray(0), hash) is RetainedOriginalHolder.RetainResult.Rejected)
            assertTrue(holder.retainedSizesForTests().isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun itemSizeBoundaryUsesStagingConstant() {
        val holder = RetainedOriginalHolder()
        try {
            val exact = ByteArray(GeneratorStaging.RAW_SOURCE_MAX_BYTES) { 1 }
            val hash = sha256Hex(exact)
            val id = CapsulePhotoIdentity.contentIdFor(hash)
            assertTrue(holder.retain(id, exact, hash) is RetainedOriginalHolder.RetainResult.Retained)
            val over = ByteArray(GeneratorStaging.RAW_SOURCE_MAX_BYTES + 1) { 2 }
            val overHash = sha256Hex(over)
            val overId = CapsulePhotoIdentity.contentIdFor(overHash)
            val result = holder.retain(overId, over, overHash)
            assertTrue("expected Rejected, got $result", result is RetainedOriginalHolder.RetainResult.Rejected)
            assertNull(holder.bytesFor(overId))
            assertEquals(mapOf(id to exact.size), holder.retainedSizesForTests())
        } finally {
            holder.close()
        }
    }

    @Test
    fun countBoundaryRejectsSixthWithoutEvicting() {
        val holder = RetainedOriginalHolder()
        try {
            val triples = (0 until RetainedOriginalHolder.MAX_ITEMS).map { triple(100 + it) }
            for ((id, bytes, hash) in triples) {
                assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
            }
            val (extraId, extraBytes, extraHash) = triple(200)
            val result = holder.retain(extraId, extraBytes, extraHash)
            assertTrue("expected Rejected, got $result", result is RetainedOriginalHolder.RetainResult.Rejected)
            assertNull(holder.bytesFor(extraId))
            assertEquals(triples.size, holder.retainedSizesForTests().size)
            for ((id, bytes, _) in triples) assertRetained(holder, id, bytes)
        } finally {
            holder.close()
        }
    }

    @Test
    fun aggregateBoundaryIsInclusiveAndAtomic() {
        val holder = RetainedOriginalHolder()
        try {
            val chunk = 20L * 1024L * 1024L
            val triples = (0 until 3).map { i ->
                val bytes = ByteArray(chunk.toInt()) { ((it + i) and 0xFF).toByte() }
                val hash = sha256Hex(bytes)
                Triple(CapsulePhotoIdentity.contentIdFor(hash), bytes, hash)
            }
            for ((id, bytes, hash) in triples) {
                assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
            }
            // 60 MiB + 5 MiB exceeds 64 MiB: rejected, existing intact.
            val big = ByteArray((5L * 1024L * 1024L).toInt()) { 3 }
            val bigHash = sha256Hex(big)
            val bigId = CapsulePhotoIdentity.contentIdFor(bigHash)
            val rejected = holder.retain(bigId, big, bigHash)
            assertTrue("expected Rejected, got $rejected", rejected is RetainedOriginalHolder.RetainResult.Rejected)
            assertNull(holder.bytesFor(bigId))
            assertEquals(3, holder.retainedSizesForTests().size)
            // 60 MiB + 4 MiB hits exactly 64 MiB: inclusive boundary retains.
            val fit = ByteArray((4L * 1024L * 1024L).toInt()) { 4 }
            val fitHash = sha256Hex(fit)
            val fitId = CapsulePhotoIdentity.contentIdFor(fitHash)
            assertTrue(holder.retain(fitId, fit, fitHash) is RetainedOriginalHolder.RetainResult.Retained)
            assertEquals(4, holder.retainedSizesForTests().size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun absentBytesForIsNull() {
        val holder = RetainedOriginalHolder()
        try {
            assertNull(holder.bytesFor("c" + "0".repeat(64)))
            assertFalse(holder.remove("c" + "0".repeat(64)))
        } finally {
            holder.close()
        }
    }

    @Test
    fun closedHolderIsFailClosedAndIdempotent() {
        val holder = RetainedOriginalHolder()
        val (id, bytes, hash) = triple(11)
        assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
        holder.close()
        holder.close()
        assertNull(holder.bytesFor(id))
        val result = holder.retain(id, bytes, hash)
        assertTrue("expected Rejected, got $result", result is RetainedOriginalHolder.RetainResult.Rejected)
        assertFalse(holder.remove(id))
        assertTrue(holder.retainedSizesForTests().isEmpty())
    }

    @Test
    fun removeZeroizesLiveArrayAndIsIdempotent() {
        val holder = RetainedOriginalHolder()
        try {
            val (id, bytes, hash) = triple(12)
            assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
            val live = liveRetainedBytes(holder, id)
            assertTrue(live.contentEquals(bytes))
            assertTrue(holder.remove(id))
            assertTrue("removed array must be zeroized", live.all { it == 0.toByte() })
            assertNull(holder.bytesFor(id))
            assertFalse(holder.remove(id))
        } finally {
            holder.close()
        }
    }

    @Test
    fun clearZeroizesEveryLiveArray() {
        val holder = RetainedOriginalHolder()
        try {
            val first = triple(13)
            val second = triple(14)
            holder.retain(first.first, first.second, first.third)
            holder.retain(second.first, second.second, second.third)
            val liveFirst = liveRetainedBytes(holder, first.first)
            val liveSecond = liveRetainedBytes(holder, second.first)
            holder.clear()
            holder.clear()
            assertTrue(liveFirst.all { it == 0.toByte() })
            assertTrue(liveSecond.all { it == 0.toByte() })
            assertTrue(holder.retainedSizesForTests().isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun closeZeroizesLiveArray() {
        val holder = RetainedOriginalHolder()
        val (id, bytes, hash) = triple(15)
        assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
        val live = liveRetainedBytes(holder, id)
        holder.close()
        assertTrue("closed array must be zeroized", live.all { it == 0.toByte() })
        assertTrue(holder.retainedSizesForTests().isEmpty())
    }

    @Test
    fun adapterServesDefensiveCopiesAndNulls() {
        val holder = RetainedOriginalHolder()
        try {
            val (id, bytes, hash) = triple(16)
            assertTrue(holder.retain(id, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
            val source = holder.asUprightBytesSource()
            runBlocking {
                val first = source.bytesFor(id)!!
                assertTrue(first.contentEquals(bytes))
                first.fill(0)
                val second = source.bytesFor(id)!!
                assertTrue("caller zeroization cannot alter holder", second.contentEquals(bytes))
                assertNull(source.bytesFor("c" + "f".repeat(64)))
            }
            holder.close()
            runBlocking {
                assertNull(source.bytesFor(id))
            }
        } finally {
            holder.close()
        }
    }
}
