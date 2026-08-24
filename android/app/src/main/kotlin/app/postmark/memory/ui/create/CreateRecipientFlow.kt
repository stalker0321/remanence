package app.postmark.memory.ui.create

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import postmark.core.data.network.ResolvedHandleSnapshot

/**
 * I04 create-flow recipient wiring: chains the handle picker, the explicit
 * immutable confirmation, and the session store. The confirmed snapshot is
 * immutable once bound - changing recipients ends the session instead of
 * silently rebinding keys (docs/security.md section 8).
 */
class CreateRecipientFlow(
    private val picker: RecipientPickerViewModel,
    private val store: CreateSessionStore,
) {

    enum class Step { LOOKUP, CONFIRM, BOUND }

    private val _step = MutableStateFlow(Step.LOOKUP)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** Snapshot awaiting explicit confirmation; null until a lookup resolved. */
    private var pending: ResolvedHandleSnapshot? = null

    fun onResolved(snapshot: ResolvedHandleSnapshot) {
        pending = snapshot
        _step.value = Step.CONFIRM
    }

    /**
     * Explicit user confirmation binds the immutable snapshot into the session
     * store. Requires a pending resolved snapshot; re-binding after bound
     * fails closed - start a new session to change recipients.
     */
    fun onConfirm() {
        check(_step.value == Step.CONFIRM) { "confirmation requires a resolved recipient" }
        val snapshot = requireNotNull(pending) { "no resolved recipient pending" }
        if (store.confirmedRecipient.value != null) {
            throw IllegalStateException("recipient already bound for this session")
        }
        store.confirmRecipient(snapshot)
        _step.value = Step.BOUND
    }

    /** Recapture/change intent: ends the current binding entirely. */
    fun restartLookup() {
        store.endSession()
        pending = null
        _step.value = Step.LOOKUP
    }

    val confirmed: StateFlow<ResolvedHandleSnapshot?> get() = store.confirmedRecipient
}
