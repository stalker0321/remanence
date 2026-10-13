package dev.hryshyn.remanence.pending

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T186 M10: durable gift-intent slot — opaque ref + code only, owner
 * fenced, rotation/process-death survival without session bypass.
 */
class PendingGiftIntentStoreTest {

    private fun store(): Pair<PendingGiftIntentStore, java.io.File> {
        val root = Files.createTempDirectory("pending-gift-intent").toFile()
        return PendingGiftIntentStore(root) to root
    }

    @Test
    fun saveAndPeekSameOwner() = runTest {
        val (store, _) = store()
        val saved = store.save("owner-1", "ref-1", "A3K9Q")!!
        assertEquals("owner-1", saved.ownerUserId)
        assertEquals("ref-1", saved.giftReference)
        assertEquals("A3K9Q", saved.code)
        val peeked = store.peek("owner-1")!!
        assertEquals("ref-1", peeked.giftReference)
        assertEquals("A3K9Q", peeked.code)
    }

    @Test
    fun otherOwnerSeesNothing() = runTest {
        val (store, _) = store()
        store.save("owner-1", "ref-1", "A3K9Q")
        assertNull(store.peek("owner-2"))
        // ...and can replace the slot with their own intent.
        store.save("owner-2", "ref-2", "B4K9Q")
        assertEquals("ref-2", store.peek("owner-2")!!.giftReference)
        assertNull(store.peek("owner-1"))
    }

    @Test
    fun corruptFileReadsNullNeverThrows() = runTest {
        val (store, root) = store()
        val dir = java.io.File(root, "pending-gift")
        dir.mkdirs()
        java.io.File(dir, "intent.json").writeText("not-json{{{")
        assertNull(store.peek("owner-1"))
    }

    @Test
    fun blankInputsRejected() = runTest {
        val (store, _) = store()
        assertNull(store.save("", "ref-1", "A3K9Q"))
        assertNull(store.save("owner-1", "", "A3K9Q"))
        assertNull(store.peek(""))
    }

    @Test
    fun clearDropsIntent() = runTest {
        val (store, _) = store()
        store.save("owner-1", "ref-1", "A3K9Q")
        store.clear()
        assertNull(store.peek("owner-1"))
    }
}
