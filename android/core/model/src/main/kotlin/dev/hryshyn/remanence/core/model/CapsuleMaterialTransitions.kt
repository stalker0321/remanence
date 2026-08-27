package dev.hryshyn.remanence.core.model

/**
 * Canonical recipient-local material lifecycle for a single capsule
 * (M2-P13, docs/security.md section 6.7). The local plane is the
 * recipient's own device state machine; it is intentionally separate
 * from [ServerMaterialState] so an index-only local state can never be
 * confused with a server-side ciphertext acknowledgement.
 *
 * Forward progression is strictly:
 * `DISCOVERED` -> `INDEX_CACHED` -> `MATERIAL_CACHED` -> `FINGERPRINT_ACCEPTED`.
 * No state is ever skipped, no state regresses, and the only recovery
 * from [CORRUPT] is back to [DISCOVERED] (after the recipient has
 * cleared the cached material).
 */
enum class LocalMaterialState {
    DISCOVERED,
    INDEX_CACHED,
    MATERIAL_CACHED,
    FINGERPRINT_ACCEPTED,
    CORRUPT,
}

/**
 * Canonical server delivery lifecycle for a single capsule (M2-P13).
 * The server plane is intentionally separate from
 * [LocalMaterialState]; the two state spaces share no values, and no
 * cross-mapping function exists in this module.
 *
 * Forward progression is strictly:
 * `AVAILABLE` -> `CIPHERTEXT_SYNCED`.
 * No state regresses.
 */
enum class ServerMaterialState {
    AVAILABLE,
    CIPHERTEXT_SYNCED,
}

/**
 * Result of evaluating a candidate local transition. Pure data: no
 * exceptions, no I/O, no clock. Suitable for later compare-and-set use
 * where a caller has read [LocalMaterialState] and wants to attempt
 * the next transition under the rules pinned by
 * [LocalMaterialTransitionEvaluator.evaluate].
 */
sealed interface LocalMaterialTransition {
    val from: LocalMaterialState
    val to: LocalMaterialState

    /**
     * Same-state replay is an idempotent no-op: the recipient's local
     * view did not change, the comparison value is unchanged, and the
     * caller's compare-and-set succeeds.
     */
    data class IdempotentReplay(override val from: LocalMaterialState) : LocalMaterialTransition {
        override val to: LocalMaterialState get() = from
    }

    /**
     * Forward progression by exactly one local step, or by exactly one
     * step into [LocalMaterialState.CORRUPT], or by exactly one step
     * out of [LocalMaterialState.CORRUPT] to [LocalMaterialState.DISCOVERED].
     */
    data class Accepted(override val from: LocalMaterialState, override val to: LocalMaterialState) :
        LocalMaterialTransition

    /**
     * Any other candidate transition is rejected closed. The
     * comparison value is unchanged, the caller's compare-and-set fails,
     * and the recipient must reconcile with the server / re-fetch.
     */
    data class Rejected(override val from: LocalMaterialState, override val to: LocalMaterialState) :
        LocalMaterialTransition
}

/**
 * Result of evaluating a candidate server transition. Pure data: no
 * exceptions, no I/O, no clock. Suitable for later compare-and-set use.
 */
sealed interface ServerMaterialTransition {
    val from: ServerMaterialState
    val to: ServerMaterialState

    data class IdempotentReplay(override val from: ServerMaterialState) : ServerMaterialTransition {
        override val to: ServerMaterialState get() = from
    }

    data class Accepted(override val from: ServerMaterialState, override val to: ServerMaterialState) :
        ServerMaterialTransition

    data class Rejected(override val from: ServerMaterialState, override val to: ServerMaterialState) :
        ServerMaterialTransition
}

/**
 * Pure transition validator for the recipient-local material state
 * machine. No I/O, no exceptions, no clock. The semantics pinned here
 * are the only legal local transitions; the exhaustive Cartesian
 * product test pins the result for every (from, to) pair.
 *
 * Rules:
 * - Same-state is an idempotent replay.
 * - Strictly forward by one step:
 *   `DISCOVERED` -> `INDEX_CACHED` ->
 *   `INDEX_CACHED` -> `MATERIAL_CACHED` ->
 *   `MATERIAL_CACHED` -> `FINGERPRINT_ACCEPTED`.
 * - Any non-CORRUPT state may enter CORRUPT (rejection of a material
 *   verification, etc.).
 * - CORRUPT may only recover to DISCOVERED; any direct CORRUPT ->
 *   later state is rejected.
 * - No skipping, no regression, no other transitions.
 */
object LocalMaterialTransitionEvaluator {

    fun evaluate(
        from: LocalMaterialState,
        to: LocalMaterialState,
    ): LocalMaterialTransition = when {
        from == to -> LocalMaterialTransition.IdempotentReplay(from)
        from == LocalMaterialState.DISCOVERED && to == LocalMaterialState.INDEX_CACHED ->
            LocalMaterialTransition.Accepted(from, to)
        from == LocalMaterialState.INDEX_CACHED && to == LocalMaterialState.MATERIAL_CACHED ->
            LocalMaterialTransition.Accepted(from, to)
        from == LocalMaterialState.MATERIAL_CACHED && to == LocalMaterialState.FINGERPRINT_ACCEPTED ->
            LocalMaterialTransition.Accepted(from, to)
        from != LocalMaterialState.CORRUPT && to == LocalMaterialState.CORRUPT ->
            LocalMaterialTransition.Accepted(from, to)
        from == LocalMaterialState.CORRUPT && to == LocalMaterialState.DISCOVERED ->
            LocalMaterialTransition.Accepted(from, to)
        else -> LocalMaterialTransition.Rejected(from, to)
    }
}

/**
 * Pure transition validator for the server delivery state machine. No
 * I/O, no exceptions, no clock. The semantics pinned here are the only
 * legal server transitions; the exhaustive Cartesian product test pins
 * the result for every (from, to) pair.
 *
 * Rules:
 * - Same-state is an idempotent replay.
 * - Strictly forward: `AVAILABLE` -> `CIPHERTEXT_SYNCED`.
 * - No regression: `CIPHERTEXT_SYNCED` -> `AVAILABLE` is rejected.
 */
object ServerMaterialTransitionEvaluator {

    fun evaluate(
        from: ServerMaterialState,
        to: ServerMaterialState,
    ): ServerMaterialTransition = when {
        from == to -> ServerMaterialTransition.IdempotentReplay(from)
        from == ServerMaterialState.AVAILABLE && to == ServerMaterialState.CIPHERTEXT_SYNCED ->
            ServerMaterialTransition.Accepted(from, to)
        else -> ServerMaterialTransition.Rejected(from, to)
    }
}
