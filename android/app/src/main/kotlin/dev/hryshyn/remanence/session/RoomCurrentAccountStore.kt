package dev.hryshyn.remanence.session

import dev.hryshyn.remanence.core.data.db.LocalAccountDao
import dev.hryshyn.remanence.core.data.db.LocalAccountEntity

/**
 * FIX-M1-007-07: the current account lives in the real `local_account` Room
 * table - the single durable record of routing identity (docs/architecture.md
 * section 6). No password, private key, or token material is stored here.
 */
class RoomCurrentAccountStore(private val dao: LocalAccountDao) {

    /** Atomically replaces the single active account row after auth success. */
    suspend fun record(userId: String, handle: String, activeKeyBundleId: String) {
        val existing = dao.getAccount()
        val now = System.currentTimeMillis()
        dao.replaceAccount(
            LocalAccountEntity(
                userId = userId,
                handleNormalized = handle,
                activeKeyBundleId = activeKeyBundleId,
                registeredAtEpochMs = existing?.registeredAtEpochMs ?: now,
                lastAuthenticatedAtEpochMs = now,
            ),
        )
    }

    suspend fun load(): PersistedAccountSummary? =
        dao.getAccount()?.let { PersistedAccountSummary(it.userId, it.handleNormalized) }

    suspend fun loadEntity(): LocalAccountEntity? = dao.getAccount()

    suspend fun clear() {
        dao.clear()
    }
}
