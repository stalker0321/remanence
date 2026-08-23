package postmark.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAccountDaoTest {

    private lateinit var database: PostmarkLocalDatabase
    private lateinit var dao: LocalAccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.localAccountDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun account(
        userId: String = "0198f0a0-0000-7000-8000-000000000001",
        handle: String = "mykola",
    ) = LocalAccountEntity(
        userId = userId,
        handleNormalized = handle,
        activeKeyBundleId = "0198f0a0-0000-7000-8000-00000000ba01",
        registeredAtEpochMs = 1_755_000_000_000,
        lastAuthenticatedAtEpochMs = 1_755_000_500_000,
    )

    @Test
    fun emptyDatabaseReturnsNullAccount() = runBlocking {
        assertNull(dao.getAccount())
    }

    @Test
    fun upsertThenReadReturnsSameRecord() = runBlocking {
        val record = account()
        dao.replaceAccount(record)
        assertEquals(record, dao.getAccount())
    }

    @Test
    fun replaceAccountKeepsSingleActiveAccountScope() = runBlocking {
        dao.replaceAccount(account(userId = "user-one", handle = "first"))
        dao.replaceAccount(account(userId = "user-two", handle = "second"))
        val loaded = dao.getAccount()!!
        assertEquals("user-two", loaded.userId)
        assertEquals(1, countRows())
    }

    @Test
    fun updateHandleChangesOnlyHandle() = runBlocking {
        val record = account(handle = "old_handle")
        dao.replaceAccount(record)
        dao.updateHandle(record.userId, "new_handle")
        val loaded = dao.getAccount()!!
        assertEquals("new_handle", loaded.handleNormalized)
        assertEquals(record.activeKeyBundleId, loaded.activeKeyBundleId)
        assertEquals(record.registeredAtEpochMs, loaded.registeredAtEpochMs)
    }

    @Test
    fun clearRemovesAccount() = runBlocking {
        dao.replaceAccount(account())
        dao.clear()
        assertNull(dao.getAccount())
    }

    private fun countRows(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM local_account").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
