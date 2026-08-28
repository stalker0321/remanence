package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/**
 * M2-P09: sender retry material lifecycle boundary.
 *
 * Owns the only paths through which the on-disk wrapped retry keyset
 * (the `.pwks` file under `accounts/<owner>/retry-material/`) may be
 * deleted. The boundary is narrow: it never touches ciphertext
 * artifacts, never deletes another account's material, and never
 * infers terminality from a UI step or error string.
 *
 * **Authoritative state**: every public method loads the capsule row
 * internally through an owner-scoped DAO read using typed [UserId]
 * and [CapsuleId]. Callers cannot supply a stale or fabricated
 * [OutboxCapsuleEntity] to authorize deletion — the boundary always
 * sees the freshest persisted state.
 *
 * **State enforcement**:
 *  - [cleanupForTerminalState] requires the internally-loaded state
 *    to be exactly [OutboxCapsuleState.PUBLISHED] or
 *    [OutboxCapsuleState.TERMINAL_FAILURE].
 *  - [reconcileNonterminal] requires an explicit allowlist:
 *    [OutboxCapsuleState.PREPARING], [OutboxCapsuleState.ENCRYPTED],
 *    [OutboxCapsuleState.UPLOADING], [OutboxCapsuleState.FINALIZING],
 *    [OutboxCapsuleState.RETRYABLE_FAILURE]. Any other state (including
 *    future enum additions) fails closed with [Result.STATE_NOT_ELIGIBLE].
 *  - [cleanupForAbort] is state-independent: it is reserved for
 *    upstream-authorized abort transactions.
 *
 * **Retention contract**: retry material survives process death,
 * every nonterminal capsule state, app restart, logout, and ordinary
 * retry. A nonterminal capsule whose retry material file is missing
 * or corrupt is unrecoverable — the boundary returns
 * [Result.MATERIAL_MISSING] and retains the pointer so upload/retry
 * callers can observe the failure and decide on user-visible error
 * handling.
 *
 * **Deletion contract**: retry material file and pointer are deleted
 * together only through [cleanupForTerminalState] (authoritative
 * terminal state) or [cleanupForAbort] (upstream-authorized abort).
 * Crash-window replay (process death between file deletion and
 * pointer clear) is handled by re-invoking terminal cleanup or abort,
 * which confirms the file is already missing then clears the pointer.
 *
 * **Crash-safe ordering**: the file is deleted (or confirmed
 * missing) FIRST, then the DB pointer is cleared through an
 * owner-scoped conditional DAO update matching capsule + owner +
 * expected prior path. If deletion fails or the target still
 * exists, the pointer remains.
 *
 * **Pointer integrity**: the DB path is NEVER used as a deletion
 * target. The expected path is derived from typed [UserId] /
 * [CapsuleId] via [SenderRetryMaterialStore.expectedPath]. Any
 * non-null stored pointer that does not equal its canonical
 * expected path causes a fail-closed refusal; neither the file
 * nor the pointer is touched.
 *
 * **CAS outcome discipline**: after a successful file deletion (or
 * confirmed absence), the result of the pointer-clear CAS is
 * inspected. A CAS returning 1 is a success. A CAS returning 0 is
 * acceptable ONLY when an owner-scoped re-read proves the row is
 * absent or its pointer is already null. If the re-read reveals a
 * live row with a non-null pointer, [Result.POINTER_CAS_CONFLICT]
 * is returned instead of OK.
 *
 * **Idempotency**: every public method is safe to call multiple
 * times with the same arguments. Repeated terminal-state cleanup
 * or abort on an already-cleaned row returns [Result.OK].
 */
class SenderRetryMaterialLifecycle(
    private val retryStore: SenderRetryMaterialStore,
    private val capsuleDao: OutboxCapsuleDao,
) {

    /**
     * Terminal-state cleanup. Loads the capsule row internally and
     * requires the state to be exactly [OutboxCapsuleState.PUBLISHED]
     * or [OutboxCapsuleState.TERMINAL_FAILURE]. Deletes the retry
     * file (or confirms it is missing), then clears the DB pointer.
     *
     * Crash-window replay: if a prior terminal cleanup deleted the
     * file but the process died before the pointer was cleared, this
     * call confirms the file is already missing and clears the
     * pointer. Idempotent on repeated calls.
     */
    suspend fun cleanupForTerminalState(
        owner: UserId,
        capsule: CapsuleId,
    ): Result {
        val entity = capsuleDao.getByCapsuleIdAndOwner(
            capsule.toRestString(), owner.value.toString(),
        ) ?: return Result.OK
        val eligible = entity.state == OutboxCapsuleState.PUBLISHED ||
            entity.state == OutboxCapsuleState.TERMINAL_FAILURE
        if (!eligible) return Result.STATE_NOT_ELIGIBLE
        return cleanupCore(owner, capsule, entity)
    }

    /**
     * Explicit abort cleanup. Loads the capsule row internally. This
     * is the only state-independent path — it is reserved for
     * upstream-authorized abort transactions where the caller owns
     * the decision regardless of outbox state.
     */
    suspend fun cleanupForAbort(
        owner: UserId,
        capsule: CapsuleId,
    ): Result {
        val entity = capsuleDao.getByCapsuleIdAndOwner(
            capsule.toRestString(), owner.value.toString(),
        ) ?: return Result.OK
        return cleanupCore(owner, capsule, entity)
    }

    /**
     * Nonterminal-state reconciliation. Loads the capsule row
     * internally and requires the state to be in the explicit
     * allowlist: [OutboxCapsuleState.PREPARING],
     * [OutboxCapsuleState.ENCRYPTED], [OutboxCapsuleState.UPLOADING],
     * [OutboxCapsuleState.FINALIZING],
     * [OutboxCapsuleState.RETRYABLE_FAILURE]. Any other state
     * (including future enum additions) fails closed.
     *
     * When the pointer is canonical and the file exists, returns OK.
     * When the pointer is live but the file is missing or corrupt,
     * returns [Result.MATERIAL_MISSING] and retains the pointer —
     * a nonterminal capsule with missing retry material is
     * unrecoverable, not a completed terminal cleanup. Null pointer
     * is a no-op for legacy/no-material rows.
     */
    suspend fun reconcileNonterminal(
        owner: UserId,
        capsule: CapsuleId,
    ): Result {
        val entity = capsuleDao.getByCapsuleIdAndOwner(
            capsule.toRestString(), owner.value.toString(),
        ) ?: return Result.OK
        if (entity.state !in NONTERMINAL_ALLOWLIST) return Result.STATE_NOT_ELIGIBLE

        val storedPath = entity.senderRetryKeysetPath
        if (storedPath == null) return Result.OK

        val expectedPath = retryStore.expectedPath(owner, capsule).canonicalPath
        if (storedPath != expectedPath) return Result.POINTER_MISMATCH

        val filePresent = retryStore.read(owner, capsule) != null
        if (filePresent) return Result.OK

        return Result.MATERIAL_MISSING
    }

    private suspend fun cleanupCore(
        owner: UserId,
        capsule: CapsuleId,
        entity: dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity,
    ): Result {
        val storedPath = entity.senderRetryKeysetPath
        if (storedPath == null) return Result.OK

        val expectedPath = retryStore.expectedPath(owner, capsule).canonicalPath
        if (storedPath != expectedPath) return Result.POINTER_MISMATCH

        try {
            retryStore.delete(owner, capsule)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.DELETE_FAILED
        }

        val cleared = capsuleDao.clearSenderRetryKeysetPath(
            entity.capsuleId,
            entity.ownerUserId,
            storedPath,
        )
        return verifyCasOutcome(entity.capsuleId, entity.ownerUserId, storedPath, cleared)
    }

    private suspend fun verifyCasOutcome(
        capsuleId: String,
        ownerUserId: String,
        expectedPath: String,
        casResult: Int,
    ): Result {
        if (casResult == 1) return Result.OK
        val reRead = capsuleDao.getByCapsuleIdAndOwner(capsuleId, ownerUserId)
        if (reRead == null || reRead.senderRetryKeysetPath == null) {
            return Result.OK
        }
        return Result.POINTER_CAS_CONFLICT
    }

    /**
     * Result of a cleanup or reconciliation operation.
     */
    enum class Result {
        /** File deleted/confirmed missing; pointer cleared or already null. */
        OK,
        /** Entity not found or state does not qualify for this operation. */
        STATE_NOT_ELIGIBLE,
        /** Stored pointer does not equal canonical path; neither touched. */
        POINTER_MISMATCH,
        /** Nonterminal capsule whose retry material file is missing or corrupt. Pointer retained. */
        MATERIAL_MISSING,
        /** File deletion failed; pointer remains for retry. */
        DELETE_FAILED,
        /** Pointer-clear CAS returned 0 but row still carries a live pointer. */
        POINTER_CAS_CONFLICT,
    }

    private companion object {
        val NONTERMINAL_ALLOWLIST = setOf(
            OutboxCapsuleState.PREPARING,
            OutboxCapsuleState.ENCRYPTED,
            OutboxCapsuleState.UPLOADING,
            OutboxCapsuleState.FINALIZING,
            OutboxCapsuleState.RETRYABLE_FAILURE,
        )
    }
}
