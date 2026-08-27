package dev.hryshyn.remanence.sync

import androidx.work.WorkManager
import dev.hryshyn.remanence.core.model.UserId

/**
 * M2-P05 (architecture.md section 11) narrow boundary that cancels every
 * WorkManager chain scoped to a single canonical account tag. It is the
 * only place outside [AccountWorkIdentity] that knows the relationship
 * between a [UserId] and the WorkManager tag set, so the `LogoutUseCase`
 * (and any future account switch) can ask it to "cancel A" and have
 * exactly that happen.
 *
 * The adapter intentionally accepts a [WorkManager] (not a global
 * singleton) and a [UserId] (not a raw tag string) so the call site
 * cannot pass a global tag, the literal `remanence` global, or any
 * other over-broad selector. A cancel request for account A is
 * guaranteed by construction to only ever remove chains whose tag set
 * contains the canonical `remanence.account.<user-A-uuid>` tag.
 *
 * Wiring is deliberately deferred to the worker that owns
 * [dev.hryshyn.remanence.auth.LogoutUseCase] so this branch stays
 * free to land without coupling.
 */
class AccountWorkCancellation(
    private val workManager: WorkManager,
) {

    /**
     * Cancel every work chain whose tag set contains the canonical
     * `remanence.account.<userId>` tag. Returns the canonical account tag
     * that was actually used, so callers and tests can assert what was
     * removed without re-deriving the contract.
     */
    fun cancelForAccount(userId: UserId): String {
        val accountTag = AccountWorkIdentity.accountTag(userId)
        workManager.cancelAllWorkByTag(accountTag)
        return accountTag
    }
}
