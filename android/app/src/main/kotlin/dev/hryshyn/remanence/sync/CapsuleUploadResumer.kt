package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of one owner-scoped restart discovery pass. */
enum class CapsuleUploadResumeStatus {
    COMPLETED,
    ACCOUNT_MISMATCH,
    INVALID_CANDIDATE,
    OPERATIONAL_FAILURE,
}

/** Minimal, non-sensitive result for a restart discovery pass. */
data class CapsuleUploadResumeResult(
    val status: CapsuleUploadResumeStatus,
    val discoveredCount: Int,
    val enqueuedCount: Int,
    val invalidCount: Int = 0,
)

/**
 * Owner-scoped restart discovery for staged capsule uploads. The enqueue
 * boundary is injected so this layer remains independent of lifecycle and
 * WorkManager wiring; repeated calls rely on the worker's unique KEEP policy.
 */
class CapsuleUploadResumer(
    private val capsuleDao: OutboxCapsuleDao,
    private val currentAccountUserId: suspend () -> String?,
    private val enqueue: suspend (UserId, CapsuleId) -> Unit,
) {

    suspend fun resume(owner: UserId): CapsuleUploadResumeResult {
        val ownerText = owner.toRestString()
        when (liveAccountMatches(ownerText)) {
            AccountCheck.MATCH -> Unit
            AccountCheck.MISMATCH -> return result(CapsuleUploadResumeStatus.ACCOUNT_MISMATCH)
            AccountCheck.FAILURE -> return result(CapsuleUploadResumeStatus.OPERATIONAL_FAILURE)
        }

        val capsuleIds = try {
            capsuleDao.getCapsuleIdsNeedingUploadForOwner(ownerText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return result(CapsuleUploadResumeStatus.OPERATIONAL_FAILURE)
        }

        var enqueuedCount = 0
        var invalidCount = 0
        for (rawCapsuleId in capsuleIds) {
            when (liveAccountMatches(ownerText)) {
                AccountCheck.MATCH -> Unit
                AccountCheck.MISMATCH -> return CapsuleUploadResumeResult(
                    status = CapsuleUploadResumeStatus.ACCOUNT_MISMATCH,
                    discoveredCount = capsuleIds.size,
                    enqueuedCount = enqueuedCount,
                    invalidCount = invalidCount,
                )
                AccountCheck.FAILURE -> return CapsuleUploadResumeResult(
                    status = CapsuleUploadResumeStatus.OPERATIONAL_FAILURE,
                    discoveredCount = capsuleIds.size,
                    enqueuedCount = enqueuedCount,
                    invalidCount = invalidCount,
                )
            }

            val capsuleId = try {
                CapsuleId.parseRest(rawCapsuleId)
            } catch (_: IllegalArgumentException) {
                invalidCount += 1
                continue
            }
            try {
                enqueue(owner, capsuleId)
                enqueuedCount += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CapsuleUploadResumeResult(
                    status = CapsuleUploadResumeStatus.OPERATIONAL_FAILURE,
                    discoveredCount = capsuleIds.size,
                    enqueuedCount = enqueuedCount,
                    invalidCount = invalidCount,
                )
            }
        }

        return CapsuleUploadResumeResult(
            status = if (invalidCount == 0) {
                CapsuleUploadResumeStatus.COMPLETED
            } else {
                CapsuleUploadResumeStatus.INVALID_CANDIDATE
            },
            discoveredCount = capsuleIds.size,
            enqueuedCount = enqueuedCount,
            invalidCount = invalidCount,
        )
    }

    private suspend fun liveAccountMatches(ownerText: String): AccountCheck = try {
        if (currentAccountUserId() == ownerText) AccountCheck.MATCH else AccountCheck.MISMATCH
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AccountCheck.FAILURE
    }

    private fun result(status: CapsuleUploadResumeStatus): CapsuleUploadResumeResult =
        CapsuleUploadResumeResult(status, discoveredCount = 0, enqueuedCount = 0)

    private enum class AccountCheck {
        MATCH,
        MISMATCH,
        FAILURE,
    }
}
