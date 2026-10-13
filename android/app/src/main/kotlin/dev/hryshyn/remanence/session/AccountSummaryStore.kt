package dev.hryshyn.remanence.session

/** Locally persisted account summary recorded after login/registration. */
data class PersistedAccountSummary(
    val userId: String,
    /** Null for receive-only accounts that defer the handle until send. */
    val handle: String?,
    val activeKeyBundleId: String,
)
