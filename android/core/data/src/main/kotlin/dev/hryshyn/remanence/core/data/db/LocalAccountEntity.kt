package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single active account record. Stores routing identity only: no password,
 * no private key material, and no refresh-token plaintext may ever live here.
 *
 * T186: [handleNormalized] is nullable. A receive-only account on the same
 * immutable identity defers its unique immutable handle until first send;
 * no empty placeholder identity is ever minted (T184 contract). v1
 * handleful accounts always carry non-null here.
 */
@Entity(tableName = "local_account")
data class LocalAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "handle_normalized")
    val handleNormalized: String?,
    @ColumnInfo(name = "active_key_bundle_id")
    val activeKeyBundleId: String,
    @ColumnInfo(name = "registered_at_epoch_ms")
    val registeredAtEpochMs: Long,
    @ColumnInfo(name = "last_authenticated_at_epoch_ms")
    val lastAuthenticatedAtEpochMs: Long,
)
