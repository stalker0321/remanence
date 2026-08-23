package app.postmark.memory.ui.create

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import postmark.core.data.network.ResolvedHandleSnapshot

/**
 * Holds the confirmed recipient snapshot for exactly one creation session.
 * Deliberately memory-only (no Room, no files): the snapshot never outlives
 * the create flow or the process, matching docs/security.md section 8 —
 * directory responses are not cached beyond the current create flow.
 */
class CreateSessionStore {

    private val _confirmed = MutableStateFlow<ResolvedHandleSnapshot?>(null)

    /** Null until the user explicitly confirms a resolved recipient. */
    val confirmedRecipient: StateFlow<ResolvedHandleSnapshot?> = _confirmed.asStateFlow()

    /** Binds the immutable IDs/key ID after explicit confirmation; replaces any previous binding. */
    fun confirmRecipient(snapshot: ResolvedHandleSnapshot) {
        require(snapshot.keyBundleStatus == "ACTIVE") { "only an active bundle may be bound" }
        _confirmed.value = snapshot
    }

    /** Ends the create session and drops the confirmed snapshot immediately. */
    fun endSession() {
        _confirmed.value = null
    }
}
