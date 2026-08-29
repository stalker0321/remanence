package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/** Room-only representation; signed bytes and all sender/display metadata are excluded by SQL. */
internal data class IncomingAcceptanceCandidateRow(
    @ColumnInfo(name = "capsule_id") val capsuleId: String,
    @ColumnInfo(name = "ready_at_epoch_ms") val readyAtEpochMs: Long,
) {
    override fun toString(): String = "IncomingAcceptanceCandidateRow(<redacted>)"
}

/** Internal test seam around the one exact production DAO query. */
internal fun interface IncomingAcceptanceCandidateQuery {
    suspend fun select(
        ownerUserId: String,
        afterReadyAtEpochMs: Long?,
        afterCapsuleId: String?,
        limit: Int,
    ): List<IncomingAcceptanceCandidateRow>
}

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
class IncomingAcceptanceCandidateSelector private constructor(
    private val query: IncomingAcceptanceCandidateQuery,
    val maxPageSize: Int,
) {
    constructor(
        dao: IncomingCapsuleDao,
        maxPageSize: Int = HARD_MAX_PAGE_SIZE,
    ) : this(
        query = IncomingAcceptanceCandidateQuery(dao::selectAcceptanceCandidateRows),
        maxPageSize = maxPageSize,
    )

    internal constructor(
        query: IncomingAcceptanceCandidateQuery,
        maxPageSize: Int = HARD_MAX_PAGE_SIZE,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
    ) : this(query = query, maxPageSize = maxPageSize)

    init {
        require(maxPageSize in 1..HARD_MAX_PAGE_SIZE) {
            "candidate page maximum is invalid"
        }
    }

    suspend fun select(
        ownerUserId: UserId,
        after: IncomingAcceptanceCandidateKey? = null,
        limit: Int,
    ): IncomingAcceptanceCandidateSelection {
        if (!isValidLimit(limit)) return IncomingAcceptanceCandidateSelection.InvalidRequest

        return try {
            val rows = query.select(
                ownerUserId = ownerUserId.toRestString(),
                afterReadyAtEpochMs = after?.readyAtEpochMs,
                afterCapsuleId = after?.capsuleId?.toRestString(),
                limit = limit,
            )
            IncomingAcceptanceCandidateSelection.Page(
                rows.map { row ->
                    require(row.readyAtEpochMs >= 0L) {
                        "durable candidate ordering key is invalid"
                    }
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
        const val HARD_MAX_PAGE_SIZE: Int = 32
    }
}
