package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/** Room-only representation; signed bytes and all sender/display metadata are excluded by SQL. */
internal data class IncomingAcceptanceCandidateRow(
    @ColumnInfo(name = "capsule_id") val capsuleId: String,
    @ColumnInfo(name = "ready_at_epoch_ms") val readyAtEpochMs: Long,
)

/** Minimal deterministic identity of one capsule eligible for incoming acceptance. */
data class IncomingAcceptanceCandidate(
    val capsuleId: CapsuleId,
    val readyAtEpochMs: Long,
) {
    override fun toString(): String = "IncomingAcceptanceCandidate(<redacted>)"
}

/** Invocation-local keyset position. It is deliberately never persisted in Room or WorkData. */
data class IncomingAcceptanceCandidateKey(
    val readyAtEpochMs: Long,
    val capsuleId: CapsuleId,
) {
    init {
        require(readyAtEpochMs >= 0L) { "acceptance cursor timestamp is invalid" }
    }

    override fun toString(): String = "IncomingAcceptanceCandidateKey(<redacted>)"
}

sealed interface IncomingAcceptanceCandidateSelection {
    data class Page(val candidates: List<IncomingAcceptanceCandidate>) :
        IncomingAcceptanceCandidateSelection {
        override fun toString(): String = "IncomingAcceptanceCandidateSelection.Page(<redacted>)"
    }

    data object InvalidRequest : IncomingAcceptanceCandidateSelection

    /** Database access or malformed durable identity is conservatively retryable. */
    data object Unavailable : IncomingAcceptanceCandidateSelection
}

/** Owner-scoped, bounded candidate selection; it performs no state mutation or acceptance work. */
class IncomingAcceptanceCandidateSelector(
    private val dao: IncomingCapsuleDao,
    val maxPageSize: Int = DEFAULT_MAX_PAGE_SIZE,
) {
    init {
        require(maxPageSize > 0) { "candidate page maximum must be positive" }
    }

    suspend fun select(
        ownerUserId: UserId,
        after: IncomingAcceptanceCandidateKey? = null,
        limit: Int,
    ): IncomingAcceptanceCandidateSelection {
        if (!isValidLimit(limit)) return IncomingAcceptanceCandidateSelection.InvalidRequest

        return try {
            val rows = dao.selectAcceptanceCandidateRows(
                ownerUserId = ownerUserId.toRestString(),
                afterReadyAtEpochMs = after?.readyAtEpochMs,
                afterCapsuleId = after?.capsuleId?.toRestString(),
                limit = limit,
            )
            IncomingAcceptanceCandidateSelection.Page(
                rows.map { row ->
                    IncomingAcceptanceCandidate(
                        capsuleId = CapsuleId.parseRest(row.capsuleId),
                        readyAtEpochMs = row.readyAtEpochMs,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IncomingAcceptanceCandidateSelection.Unavailable
        }
    }

    private fun isValidLimit(limit: Int): Boolean = limit in 1..maxPageSize

    override fun toString(): String = "IncomingAcceptanceCandidateSelector(<redacted>)"

    companion object {
        const val DEFAULT_MAX_PAGE_SIZE: Int = 32
    }
}
