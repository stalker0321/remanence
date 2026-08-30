package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingMaterialAckCandidateSelection
import dev.hryshyn.remanence.core.data.db.IncomingMaterialAckResult
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Redacted outcomes of one bounded material-synced acknowledgement drain. */
internal sealed interface IncomingMaterialAckDrainResult {

    data class Completed(
        val recordedCount: Int,
        val selectedCount: Int,
        val pageMayHaveMore: Boolean,
    ) : IncomingMaterialAckDrainResult {
        init {
            require(recordedCount >= 0)
            require(selectedCount >= recordedCount)
        }

        override fun toString(): String =
            "IncomingMaterialAckDrainResult.Completed(<redacted>)"
    }

    data class Retryable(val reason: IncomingMaterialAckDrainRetryReason) :
        IncomingMaterialAckDrainResult {
        override fun toString(): String =
            "IncomingMaterialAckDrainResult.Retryable(<redacted>)"
    }

    data object AccountStopped : IncomingMaterialAckDrainResult
    data object InvalidRequest : IncomingMaterialAckDrainResult
}

internal enum class IncomingMaterialAckDrainRetryReason {
    SESSION_UNAVAILABLE,
    SELECTOR_UNAVAILABLE,
    MATERIAL_SYNC_RETRYABLE,
    LOCAL_PROGRESS_UNAVAILABLE,
}

/**
 * One bounded, sequential recipient material acknowledgement drain. Durable
 * PENDING state is the restart boundary: a process stop after the HTTP 204
 * and before the local CAS simply causes the same idempotent POST to replay.
 * This class owns neither scheduling nor a durable cursor.
 *
 * The repository's Success is also the server's idempotent replay success;
 * the current wire contract has no separate AlreadySynced response.
 */
internal class IncomingMaterialAckDrain internal constructor(
    private val incomingCapsuleDao: IncomingCapsuleDao,
    private val currentSession: suspend () -> IncomingSyncSession?,
    private val recipientMaterialSyncedRepository: RecipientMaterialSyncedRepository,
) {

    suspend fun run(limit: Int): IncomingMaterialAckDrainResult {
        if (limit !in 1..MAX_CANDIDATES_PER_RUN) {
            return IncomingMaterialAckDrainResult.InvalidRequest
        }

        coroutineContext.ensureActive()
        val initialSession = when (val session = readSession()) {
            is SessionStatus.Ready -> session.value
            SessionStatus.Stopped -> return IncomingMaterialAckDrainResult.AccountStopped
            SessionStatus.Unavailable -> return retry(
                IncomingMaterialAckDrainRetryReason.SESSION_UNAVAILABLE,
            )
        }

        val selection = try {
            incomingCapsuleDao.selectMaterialAckCandidatesForOwner(
                ownerUserId = initialSession.ownerUserId,
                limit = limit,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return retry(IncomingMaterialAckDrainRetryReason.SELECTOR_UNAVAILABLE)
        }
        val page = when (selection) {
            is IncomingMaterialAckCandidateSelection.Page -> selection
            IncomingMaterialAckCandidateSelection.InvalidRequest ->
                return IncomingMaterialAckDrainResult.InvalidRequest
            IncomingMaterialAckCandidateSelection.Unavailable ->
                return retry(IncomingMaterialAckDrainRetryReason.SELECTOR_UNAVAILABLE)
        }
        val pageMayHaveMore = page.candidates.size == limit
        var recordedCount = 0

        for (candidate in page.candidates) {
            coroutineContext.ensureActive()
            val requestSession = when (val session = verifySession(initialSession)) {
                is SessionStatus.Ready -> session.value
                SessionStatus.Stopped -> return IncomingMaterialAckDrainResult.AccountStopped
                SessionStatus.Unavailable -> return retry(
                    IncomingMaterialAckDrainRetryReason.SESSION_UNAVAILABLE,
                )
            }

            val remoteResult = try {
                recipientMaterialSyncedRepository.markMaterialSynced(
                    capsuleId = candidate.capsuleId,
                    accessToken = requestSession.accessToken,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return retry(IncomingMaterialAckDrainRetryReason.MATERIAL_SYNC_RETRYABLE)
            }

            val target = when (remoteResult) {
                is RecipientMaterialSyncedResult.Success -> MaterialAckTarget.ACKED
                is RecipientMaterialSyncedResult.Failure -> when (remoteResult.reason) {
                    RecipientMaterialSyncedFailure.AUTH_INVALID ->
                        return IncomingMaterialAckDrainResult.AccountStopped
                    RecipientMaterialSyncedFailure.VALIDATION_FAILED,
                    RecipientMaterialSyncedFailure.CAPSULE_NOT_FOUND,
                    -> if (remoteResult.retryable) {
                        return retry(IncomingMaterialAckDrainRetryReason.MATERIAL_SYNC_RETRYABLE)
                    } else {
                        MaterialAckTarget.TERMINAL
                    }
                    RecipientMaterialSyncedFailure.NETWORK,
                    RecipientMaterialSyncedFailure.RATE_LIMITED,
                    RecipientMaterialSyncedFailure.HTTP,
                    RecipientMaterialSyncedFailure.INVALID_RESPONSE,
                    RecipientMaterialSyncedFailure.INTERNAL_ERROR,
                    -> return retry(IncomingMaterialAckDrainRetryReason.MATERIAL_SYNC_RETRYABLE)
                }
            }

            when (val session = verifySession(initialSession)) {
                is SessionStatus.Ready -> Unit
                SessionStatus.Stopped -> return IncomingMaterialAckDrainResult.AccountStopped
                SessionStatus.Unavailable -> return retry(
                    IncomingMaterialAckDrainRetryReason.SESSION_UNAVAILABLE,
                )
            }

            val localResult = try {
                when (target) {
                    MaterialAckTarget.ACKED -> incomingCapsuleDao.markMaterialAckedForOwner(
                        ownerUserId = initialSession.ownerUserId,
                        capsuleId = candidate.capsuleId,
                    )
                    MaterialAckTarget.TERMINAL -> incomingCapsuleDao.markMaterialTerminalForOwner(
                        ownerUserId = initialSession.ownerUserId,
                        capsuleId = candidate.capsuleId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return retry(IncomingMaterialAckDrainRetryReason.LOCAL_PROGRESS_UNAVAILABLE)
            }

            when (localResult) {
                IncomingMaterialAckResult.Marked,
                IncomingMaterialAckResult.AlreadyRecorded,
                -> recordedCount++

                // A concurrent delete, owner replacement, or state change is
                // reconciled by the DAO without an unscoped probe or write.
                // It cannot authorize a mutation, but it need not poison the
                // remaining candidates already selected for this page.
                IncomingMaterialAckResult.MissingOrForeign,
                IncomingMaterialAckResult.StateChanged,
                -> Unit

                IncomingMaterialAckResult.Unavailable -> return retry(
                    IncomingMaterialAckDrainRetryReason.LOCAL_PROGRESS_UNAVAILABLE,
                )
            }
        }

        return IncomingMaterialAckDrainResult.Completed(
            recordedCount = recordedCount,
            selectedCount = page.candidates.size,
            pageMayHaveMore = pageMayHaveMore,
        )
    }

    private suspend fun readSession(): SessionStatus = try {
        when (val session = currentSession()) {
            null -> SessionStatus.Stopped
            else -> if (session.accessToken.isBlank()) {
                SessionStatus.Stopped
            } else {
                SessionStatus.Ready(session)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SessionStatus.Unavailable
    }

    private suspend fun verifySession(expected: IncomingSyncSession): SessionStatus = try {
        when (val observed = currentSession()) {
            null -> SessionStatus.Stopped
            else -> when {
                observed.accessToken.isBlank() -> SessionStatus.Stopped
                !expected.isSameSession(observed) -> SessionStatus.Stopped
                else -> SessionStatus.Ready(observed)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SessionStatus.Unavailable
    }

    private fun retry(reason: IncomingMaterialAckDrainRetryReason) =
        IncomingMaterialAckDrainResult.Retryable(reason)

    private enum class MaterialAckTarget {
        ACKED,
        TERMINAL,
    }

    private sealed interface SessionStatus {
        data class Ready(val value: IncomingSyncSession) : SessionStatus
        data object Stopped : SessionStatus
        data object Unavailable : SessionStatus
    }

    private companion object {
        const val MAX_CANDIDATES_PER_RUN: Int =
            IncomingCapsuleDao.MATERIAL_ACK_HARD_MAX_PAGE_SIZE
    }
}
