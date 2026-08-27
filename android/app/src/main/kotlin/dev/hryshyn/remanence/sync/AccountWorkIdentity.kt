package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId

/**
 * M2-P05 (architecture.md section 11) account-scoped work identity. Every
 * WorkManager chain that touches account material carries an immutable,
 * canonical unique name and a tag set derived purely from the typed
 * [UserId] / [CapsuleId] so a logout or account switch can cancel work for
 * exactly one canonical account tag and never reach another.
 *
 * The factory is pure: no Android, no WorkManager, no I/O, no clock, no
 * randomness, no global state. The same inputs always produce the same
 * strings, so the value is safe to compare in tests and in logging.
 *
 * Canonical unique names (architecture.md section 11):
 *  - incoming sync  : `remanence.account.<user-uuid>.incoming-sync`
 *  - outbox         : `remanence.account.<user-uuid>.outbox.<capsule-uuid>`
 *
 * Required tag set, in this order:
 *  - `remanence`
 *  - `remanence.account.<user-uuid>`
 *  - for outbox work: `remanence.capsule.<capsule-uuid>`
 *
 * No handle, email, file path, token, key, envelope, or plaintext appears in
 * any name or tag; [UserId.toRestString] / [CapsuleId.toRestString] are the
 * only input used to compose them.
 */
data class AccountWorkIdentity(
    val uniqueName: String,
    val tags: List<String>,
) {

    init {
        require(uniqueName.isNotEmpty()) { "account work unique name must not be blank" }
        require(tags.isNotEmpty()) { "account work must carry at least the global remanence tag" }
    }

    /**
     * The single canonical account tag this work is scoped to. Always equals
     * the second tag, the one carrying the [UserId]. Used by the cancellation
     * adapter to remove every chain for one account only.
     */
    val accountTag: String
        get() = tags[1]

    companion object {

        private const val GLOBAL_TAG: String = "remanence"
        private const val ACCOUNT_PREFIX: String = "remanence.account."
        private const val CAPSULE_PREFIX: String = "remanence.capsule."
        private const val INCOMING_SUFFIX: String = "incoming-sync"
        private const val OUTBOX_MIDDLE: String = "outbox."

        /**
         * The unique name and tag set for the per-account incoming-sync chain
         * documented in architecture.md section 11. There is exactly one such
         * chain per authenticated account.
         */
        fun incomingSync(userId: UserId): AccountWorkIdentity {
            val accountTag = accountTag(userId)
            return AccountWorkIdentity(
                uniqueName = "$ACCOUNT_PREFIX${userId.toRestString()}.$INCOMING_SUFFIX",
                tags = listOf(GLOBAL_TAG, accountTag),
            )
        }

        /**
         * The unique name and tag set for one account/capsule-scoped outbox
         * chain. The capsule tag is mandatory so a later worker can match work
         * for a single capsule across all accounts without scanning names.
         */
        fun outbox(userId: UserId, capsuleId: CapsuleId): AccountWorkIdentity {
            val accountTag = accountTag(userId)
            val capsuleTag = capsuleTag(capsuleId)
            return AccountWorkIdentity(
                uniqueName = "$ACCOUNT_PREFIX${userId.toRestString()}.$OUTBOX_MIDDLE${capsuleId.toRestString()}",
                tags = listOf(GLOBAL_TAG, accountTag, capsuleTag),
            )
        }

        /** `remanence.account.<user-uuid>` — the canonical account scope tag. */
        fun accountTag(userId: UserId): String = "$ACCOUNT_PREFIX${userId.toRestString()}"

        /** `remanence.capsule.<capsule-uuid>` — the canonical capsule scope tag. */
        fun capsuleTag(capsuleId: CapsuleId): String = "$CAPSULE_PREFIX${capsuleId.toRestString()}"
    }
}
