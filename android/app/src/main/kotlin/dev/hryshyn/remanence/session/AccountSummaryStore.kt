package dev.hryshyn.remanence.session

/** Locally persisted account summary recorded after login/registration. */
data class PersistedAccountSummary(
    val userId: String,
    val handle: String,
)