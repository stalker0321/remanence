package dev.hryshyn.remanence.ui.create

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.pending.PendingSendRouteFlow

/**
 * Holds the confirmed recipient snapshot for exactly one creation session.
 * Deliberately memory-only (no Room, no files): the snapshot never outlives
 * the create flow or the process, matching docs/security.md section 8 —
 * directory responses are not cached beyond the current create flow.
 *
 * T186 M12d: the store additionally holds one frozen pending route
 * (`PendingSendRouteFlow.FrozenIdentity`, R1-frozen EMAIL/PHONE
 * destination). The two bindings are mutually exclusive alternatives
 * for the same send: freezing a pending route drops a confirmed handle
 * and confirming a handle drops a frozen route. Publication branches on
 * exactly one of them at the normal boundary.
 */
class CreateSessionStore {

    private val _confirmed = MutableStateFlow<ResolvedHandleSnapshot?>(null)

    /** Null until the user explicitly confirms a resolved recipient. */
    val confirmedRecipient: StateFlow<ResolvedHandleSnapshot?> = _confirmed.asStateFlow()

    private val _pendingRoute = MutableStateFlow<PendingSendRouteFlow.FrozenIdentity?>(null)

    /** Null until an EMAIL/PHONE destination freezes an R1 route. */
    val pendingRoute: StateFlow<PendingSendRouteFlow.FrozenIdentity?> = _pendingRoute.asStateFlow()

    /** Binds the immutable IDs/key ID after explicit confirmation; replaces any previous binding. */
    fun confirmRecipient(snapshot: ResolvedHandleSnapshot) {
        require(snapshot.keyBundleStatus == "ACTIVE") { "only an active bundle may be bound" }
        _confirmed.value = snapshot
        _pendingRoute.value = null
    }

    /** Binds a frozen R1 route after explicit destination choice; replaces any previous binding. */
    fun freezePendingRoute(identity: PendingSendRouteFlow.FrozenIdentity) {
        _pendingRoute.value = identity
        _confirmed.value = null
    }

    /** Ends the create session and drops every recipient binding immediately. */
    fun endSession() {
        _confirmed.value = null
        _pendingRoute.value = null
    }
}
