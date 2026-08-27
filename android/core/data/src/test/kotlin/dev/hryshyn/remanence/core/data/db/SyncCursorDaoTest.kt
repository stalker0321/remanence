package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCursorDaoTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var dao: SyncCursorDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.syncCursorDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val userId = "0198f0a0-0000-7000-8000-00000000us01"

    private fun cursor(ts: Long, value: String?, stream: String = "incoming") = SyncCursorEntity(
        userId = userId,
        streamName = stream,
        serverCursor = value,
        lastSyncedAtEpochMs = ts,
    )

    private suspend fun advance(candidate: SyncCursorEntity): Boolean =
        dao.advance(candidate.userId, candidate)

    @Test
    fun missingCursorReadsNull() = runBlocking {
        assertNull(dao.get(userId, "incoming"))
    }

    @Test
    fun firstAdvanceInsertsPosition() = runBlocking {
        assertTrue(advance(cursor(1_755_000_000_000, "opaque-cursor-1")))
        assertEquals("opaque-cursor-1", dao.get(userId, "incoming")!!.serverCursor)
    }

    @Test
    fun authoritativeOwnerMismatchIsRejectedBeforeAdvance() = runBlocking {
        val candidate = cursor(1_000, "cursor-other").copy(userId = "0198f0a0-0000-7000-8000-00000000us02")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.advance(userId, candidate) }
        }
        assertNull(dao.get(userId, "incoming"))
        assertNull(dao.get(candidate.userId, "incoming"))
    }

    @Test
    fun newerTimestampMovesCursorForward() = runBlocking {
        advance(cursor(1_000, "cursor-old"))
        assertTrue(advance(cursor(2_000, "cursor-new")))
        val stored = dao.get(userId, "incoming")!!
        assertEquals("cursor-new", stored.serverCursor)
        assertEquals(2_000, stored.lastSyncedAtEpochMs)
    }

    @Test
    fun replayWithEqualOrOlderTimestampNeverRewinds() = runBlocking {
        advance(cursor(2_000, "cursor-new"))
        assertFalse(advance(cursor(2_000, "cursor-new")))
        assertFalse(advance(cursor(1_000, "cursor-old")))
        val stored = dao.get(userId, "incoming")!!
        assertEquals("cursor-new", stored.serverCursor)
        assertEquals(2_000, stored.lastSyncedAtEpochMs)
    }

    @Test
    fun streamsAndUsersAreIndependent() = runBlocking {
        val otherUser = "0198f0a0-0000-7000-8000-00000000us02"
        advance(cursor(1_000, "cursor-incoming", stream = "incoming"))
        advance(cursor(5_000, "cursor-outgoing", stream = "outgoing"))
        advance(cursor(9_000, "cursor-other", stream = "incoming").copy(userId = otherUser))

        assertEquals("cursor-incoming", dao.get(userId, "incoming")!!.serverCursor)
        assertEquals("cursor-outgoing", dao.get(userId, "outgoing")!!.serverCursor)
        assertEquals("cursor-other", dao.get(otherUser, "incoming")!!.serverCursor)
    }

    @Test
    fun clearForOwnerRemovesOnlyThatAccountCursors() = runBlocking {
        val otherUser = "0198f0a0-0000-7000-8000-00000000us02"
        advance(cursor(1_000, "cursor-a"))
        advance(cursor(1_000, "cursor-b").copy(userId = otherUser))
        dao.clearForOwner(userId)
        assertNull(dao.get(userId, "incoming"))
        assertEquals("cursor-b", dao.get(otherUser, "incoming")!!.serverCursor)
    }
}
