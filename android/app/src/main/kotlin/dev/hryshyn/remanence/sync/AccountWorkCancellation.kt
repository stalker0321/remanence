package dev.hryshyn.remanence.sync

import androidx.work.WorkManager
import androidx.work.await
import dev.hryshyn.remanence.core.model.UserId

/**
 * M2-P05 (architecture.md section 11) narrow boundary that cancels every
 * WorkManager chain scoped to a single canonical account tag, and WAITS for
 * WorkManager to acknowledge that cancellation before returning. It is the
 * only place outside [AccountWorkIdentity] that knows the relationship
 * between a [UserId] and the WorkManager tag set, so the `LogoutUseCase`
 * (and any future account switch) can ask it to "cancel A" and have
 * exactly that happen - atomically with respect to teardown ordering,
 * because the caller cannot proceed past this suspension until the stop
 * request has been applied by WorkManager itself.
 *
 * The adapter intentionally accepts a [WorkManager] (not a global
 * singleton) and a [UserId] (not a raw tag string) so the call site
 * cannot pass a global tag, the literal `remanence` global, or any
 * other over-broad selector. A cancel request for account A is
 * guaranteed by construction to only ever remove chains whose tag set
 * contains the canonical `remanence.account.<user-A-uuid>` tag.
 */
class AccountWorkCancellation(
    private val workManager: WorkManager,
) {

    /**
     * Cancel every work chain whose tag set contains the canonical
     * `remanence.account.<userId>` tag and SUSPEND until WorkManager
     * reports the cancellation operation complete through the supported
     * KTX [await][androidx.work.await] extension. Returns the canonical
     * account tag that was used, so callers and tests can assert what was
     * removed without re-deriving the contract. An upstream failure of the
     * operation propagates from this call.
     */
    suspend fun cancelForAccount(userId: UserId): String {
        val accountTag = AccountWorkIdentity.accountTag(userId)
        workManager.cancelAllWorkByTag(accountTag).await()
        return accountTag
    }
}
