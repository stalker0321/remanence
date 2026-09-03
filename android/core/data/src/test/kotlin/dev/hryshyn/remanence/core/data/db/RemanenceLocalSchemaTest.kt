package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemanenceLocalSchemaTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun reopenPersistsRowsAcrossClose(): Unit = runBlocking {
        val dbFile = context.getDatabasePath(REOPEN_DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        val first = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, REOPEN_DB_NAME)
            .allowMainThreadQueries()
            .build()
        first.localAccountDao().replaceAccount(
            LocalAccountEntity(
                userId = "0198f0a0-0000-7000-8000-00000000us01",
                handleNormalized = "mykola",
                activeKeyBundleId = "0198f0a0-0000-7000-8000-00000000ba01",
                registeredAtEpochMs = 42,
                lastAuthenticatedAtEpochMs = 43,
            ),
        )
        first.close()

        val second = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, REOPEN_DB_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .allowMainThreadQueries()
            .build()
        val loaded = second.localAccountDao().getAccount()
        second.close()
        assertEquals("mykola", loaded!!.handleNormalized)

        dbFile.delete()
    }

    private companion object {
        const val REOPEN_DB_NAME = "remanence-reopen-test.db"
    }
}
