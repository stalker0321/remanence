package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Query

/** Read-only lookups behind the one-postcard-per-recipient rule. */
@Dao
abstract class SentPostcardDao {
    /** Capsules this owner has created for [recipientUserId], in any state. */
    @Query("SELECT capsule_id FROM outbox_capsule WHERE owner_user_id = :ownerUserId AND recipient_user_id = :recipientUserId")
    abstract suspend fun capsuleIdsForRecipient(ownerUserId: String, recipientUserId: String): List<String>
}
