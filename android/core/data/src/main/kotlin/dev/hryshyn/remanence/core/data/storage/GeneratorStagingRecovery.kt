package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId

/**
 * G4 restart-recovery construction seam for G3 staging managers.
 *
 * Single production path that binds the existing
 * [GeneratorStagingFileStore.sweepOwner] recovery hook to unpublished
 * Manager construction: the store for [owner] is swept with an empty
 * live set — truthful only because the Manager does not exist yet, so
 * this process holds no live session — and only then is the Manager
 * constructed and returned for publication.
 *
 * Ownership conditions (all enforced by the single call site in
 * `RemanenceApplication.generatorStagingManager`):
 * - Exactly one production call site; never in the store constructor,
 *   `openFor`, `revoke`, or any failure catch.
 * - Never re-invoked with an empty set after the Manager is published
 *   (the per-process manager cache is never evicted, so the sweep
 *   cannot be re-armed in-process).
 * - Scope stays this owner's `gen-staging` namespace; sweep failure is
 *   best-effort (entry remains, later `put` still refuses overwrite).
 */
object GeneratorStagingRecovery {

    /**
     * Builds the owner-bound store, runs the one pre-publication orphan
     * sweep, then constructs the Manager that will own the namespace.
     */
    fun managerForOwner(
        roots: AccountScopedFileRoots,
        retention: AccountStorageRetention,
        owner: UserId,
        nowMillis: () -> Long,
    ): GeneratorStaging.Manager {
        val store = GeneratorStagingFileStore(roots, retention, owner)
        store.sweepOwner(emptySet())
        return GeneratorStaging.Manager(
            store = store,
            nowMillis = nowMillis,
        )
    }
}
