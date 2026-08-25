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

    /**
     * FIX-M1-ONDEVICE-01: read-only observable view of the resolved-but-NOT-
     * yet-confirmed snapshot. The confirmation screen renders THIS state;
     * the session store is touched only by the explicit confirmation.
     */
    private val _pending = MutableStateFlow<ResolvedHandleSnapshot?>(null)
    val pendingRecipient: StateFlow<ResolvedHandleSnapshot?> = _pending.asStateFlow()

    fun onResolved(snapshot: ResolvedHandleSnapshot) {
        _pending.value = snapshot
        _step.value = Step.CONFIRM
    }

    /**
     * Explicit user confirmation binds the immutable snapshot into the session
     * store. Requires a pending resolved snapshot; re-binding after bound
     * fails closed - start a new session to change recipients.
     */
    fun onConfirm() {
        check(_step.value == Step.CONFIRM) { "confirmation requires a resolved recipient" }
        val snapshot = requireNotNull(_pending.value) { "no resolved recipient pending" }
        if (store.confirmedRecipient.value != null) {
            throw IllegalStateException("recipient already bound for this session")
        }
        // FIX-M1-ONDEVICE-01: this one user action MOVES the exact resolved
        // snapshot into the session store; the pending copy dies with it.
        store.confirmRecipient(snapshot)
        _pending.value = null
        _step.value = Step.BOUND
    }

    /** Recapture/change intent: ends the current binding entirely. */
    fun restartLookup() {
        store.endSession()
        _pending.value = null
        _step.value = Step.LOOKUP
    }

    /**
     * FIX-M1-ONDEVICE-01: drops pending AND confirmed material without touching
     * navigation steps - used by endSession/new-epoch teardown.
     */
    fun clearTransientMaterial() {
        store.endSession()
        _pending.value = null
    }

    val confirmed: StateFlow<ResolvedHandleSnapshot?> get() = store.confirmedRecipient
}
