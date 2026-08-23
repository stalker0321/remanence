package postmark.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Single-active-account DAO. Never exposes enumerable capsule data. */
@Dao
abstract class LocalAccountDao {

    @Query("SELECT * FROM local_account LIMIT 1")
    abstract suspend fun getAccount(): LocalAccountEntity?

    /** Atomically replaces any existing account so the table keeps exactly one active account. */
    @Transaction
    open suspend fun replaceAccount(account: LocalAccountEntity) {
        clear()
        upsertInternal(account)
    }

    @Upsert
    protected abstract suspend fun upsertInternal(account: LocalAccountEntity)

    @Query("UPDATE local_account SET handle_normalized = :handleNormalized WHERE user_id = :userId")
    abstract suspend fun updateHandle(userId: String, handleNormalized: String)

    @Query("DELETE FROM local_account")
    abstract suspend fun clear()
}
