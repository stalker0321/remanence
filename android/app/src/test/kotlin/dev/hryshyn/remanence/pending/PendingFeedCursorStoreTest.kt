package dev.hryshyn.remanence.pending

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T186 M13: feed cursor durability — opaque owner-fenced slot,
 * same-owner restore, foreign-owner isolation, corrupt tolerance.
 */
class PendingFeedCursorStoreTest {

    private fun store(): Pair<PendingFeedCursorStore, File> {
        val dir = Files.createTempDirectory("pending-feed-cursor").toFile()
        return PendingFeedCursorStore(dir) to dir
    }

    @Test
    fun roundTripSameOwner() = runTest {
        val (store, dir) = store()
        try {
            store.save("owner-a", "opaque-cursor-1")
            assertEquals("opaque-cursor-1", store.peek("owner-a"))
            // Fresh instance, same files: durable across restarts.
            assertEquals("opaque-cursor-1", PendingFeedCursorStore(dir).peek("owner-a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun foreignOwnerSeesNothing() = runTest {
        val (store, dir) = store()
        try {
            store.save("owner-a", "opaque-cursor-1")
            assertNull(store.peek("owner-b"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blankOwnerNeverMatches() = runTest {
        val (store, dir) = store()
        try {
            store.save("", "opaque-cursor-1")
            assertNull(store.peek(""))
            assertNull(store.peek("owner-a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptFileReadsNull() = runTest {
        val dir = Files.createTempDirectory("pending-feed-corrupt").toFile()
        try {
            val slot = File(File(dir, "pending-feed").apply { mkdirs() }, "cursor.json")
            slot.writeText("not-json{{{")
            assertNull(PendingFeedCursorStore(dir).peek("owner-a"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
