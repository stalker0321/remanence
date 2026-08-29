package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.data.db.IncomingAcceptanceCandidate
import dev.hryshyn.remanence.core.data.db.IncomingAcceptanceCandidateSelection
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleQuarantineResult
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.util.ArrayDeque
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingAcceptanceDrainTest {

    private companion object {
        val OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000aa01")
        val OTHER_OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000aa02")
    }

    @Test
    fun oneDeterministicPageIsBoundedAndKeepsSelectorOrder() = runBlocking {
        val ordered = listOf(candidate(2, 20), candidate(1, 10), candidate(3, 30))
        val selected = mutableListOf<SelectionCall>()
        val accepted = mutableListOf<CapsuleId>()
        val result = drain(
            rows = ordered,
            max = 2,
            selected = selected,
            attempts = ArrayDeque(ordered.map { committed() }),
            accepted = accepted,
        ).run()

        assertEquals(IncomingAcceptanceDrainResult.Completed(2, 2, true), result)
        assertEquals(listOf(ordered[0].capsuleId, ordered[1].capsuleId), accepted)
        assertEquals(listOf(SelectionCall(OWNER, 2)), selected)
    }

    @Test
    fun acceptedAndIdempotentResultsCountProgressAndCompletePage() = runBlocking {
        val rows = listOf(candidate(1, 10), candidate(2, 20))
        val result = drain(
            rows = rows,
            attempts = ArrayDeque(listOf(committed(), idempotent())),
        ).run()

        assertEquals(IncomingAcceptanceDrainResult.Completed(2, 2, false), result)
    }

    @Test
    fun retryStopsAtFirstRetryAndDoesNotCallLaterCandidates() = runBlocking {
        val rows = listOf(candidate(1, 10), candidate(2, 20), candidate(3, 30))
        val accepted = mutableListOf<CapsuleId>()
        val attempted = mutableListOf<CapsuleId>()
        val result = drain(
            rows = rows,
            attempts = ArrayDeque(
                listOf(
                    committed(),
                    IncomingAcceptanceDrainAttempt.Acceptance(
                        IncomingCapsuleAcceptanceResult.Retryable(
                            IncomingCapsuleAcceptanceRetryReason.DOWNLOAD,
                        ),
                    ),
                    committed(),
                ),
            ),
            accepted = accepted,
            attempted = attempted,
        ).run()

        assertEquals(
            IncomingAcceptanceDrainResult.Retryable(
                IncomingAcceptanceDrainRetryReason.ACCEPTANCE_RETRYABLE,
            ),
            result,
        )
        assertEquals(listOf(rows[0].capsuleId), accepted)
        assertEquals(listOf(rows[0].capsuleId, rows[1].capsuleId), attempted)
    }

    @Test
    fun unclassifiedPoisonDoesNotBlockLaterCandidate() = runBlocking {
        val rows = listOf(candidate(1, 10), candidate(2, 20))
        val accepted = mutableListOf<CapsuleId>()
        val quarantineCalls = mutableListOf<CapsuleId>()
        val result = drain(
            rows = rows,
            attempts = ArrayDeque(
                listOf(
                    rejected(IncomingCapsuleAcceptanceRejectionReason.CRYPTO_REJECTED),
                    committed(),
                ),
            ),
            accepted = accepted,
            quarantineCalls = quarantineCalls,
        ).run()

        assertEquals(IncomingAcceptanceDrainResult.Completed(1, 2, false), result)
        assertEquals(listOf(rows[1].capsuleId), accepted)
        assertTrue(quarantineCalls.isEmpty())
    }

    @Test
    fun allUnclassifiedCandidatesCompleteWithoutProgressOrLoop() = runBlocking {
        val rows = listOf(candidate(1, 10), candidate(2, 20))
        var selectionCalls = 0
        val drain = drain(
            rows = rows,
            attempts = ArrayDeque(
                listOf(
                    rejected(IncomingCapsuleAcceptanceRejectionReason.DOWNLOAD_REJECTED),
                    rejected(IncomingCapsuleAcceptanceRejectionReason.DURABLE_STATE_INVALID),
                ),
            ),
            onSelect = { selectionCalls += 1 },
        )

        assertEquals(IncomingAcceptanceDrainResult.Completed(0, 2, false), drain.run())
        assertEquals(1, selectionCalls)
    }

    @Test
    fun accountSwitchBeforeSelectionStopsWithoutSelectorCall() = runBlocking {
        var selectionCalls = 0
        val drain = drain(
            rows = listOf(candidate(1, 10)),
            owners = ArrayDeque(listOf(OWNER, OTHER_OWNER)),
            onSelect = { selectionCalls += 1 },
        )

        assertEquals(IncomingAcceptanceDrainResult.AccountStopped, drain.run())
        assertEquals(0, selectionCalls)
    }

    @Test
    fun accountSwitchBetweenCandidatesStopsBeforeSecondAcceptance() = runBlocking {
        val rows = listOf(candidate(1, 10), candidate(2, 20))
        val accepted = mutableListOf<CapsuleId>()
        val drain = drain(
            rows = rows,
            owners = ArrayDeque(listOf(OWNER, OWNER, OWNER, OTHER_OWNER)),
            attempts = ArrayDeque(listOf(committed(), committed())),
            accepted = accepted,
        )

        assertEquals(IncomingAcceptanceDrainResult.AccountStopped, drain.run())
        assertEquals(listOf(rows[0].capsuleId), accepted)
    }

    @Test
    fun cancellationIdentityPropagatesFromAcceptance() {
        val expected = CancellationException("drain cancellation")
        val drain = drain(
            rows = listOf(candidate(1, 10)),
            attempts = ArrayDeque(),
            attemptOverride = { throw expected },
        )

        val actual = try {
            runBlocking { drain.run() }
            error("expected cancellation")
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertSame(expected, actual)
    }

    @Test
    fun selectorFailureIsRetryableAndDoesNoAcceptanceWork() = runBlocking {
        var attempts = 0
        val drain = drain(
            rows = emptyList(),
            selection = IncomingAcceptanceCandidateSelection.Unavailable,
            attemptOverride = {
                attempts += 1
                committed()
            },
        )

        assertEquals(
            IncomingAcceptanceDrainResult.Retryable(
                IncomingAcceptanceDrainRetryReason.SELECTOR_UNAVAILABLE,
            ),
            drain.run(),
        )
        assertEquals(0, attempts)
    }

    @Test
    fun acceptanceDependencyFailureIsRetryableAndStopsThePage() = runBlocking {
        val result = drain(
            rows = listOf(candidate(1, 10), candidate(2, 20)),
            attempts = ArrayDeque(listOf(committed())),
            attemptOverride = { throw IllegalStateException("provider failure") },
        ).run()

        assertEquals(
            IncomingAcceptanceDrainResult.Retryable(
                IncomingAcceptanceDrainRetryReason.ACCEPTANCE_UNAVAILABLE,
            ),
            result,
        )
    }

    @Test
    fun currentAggregateFailureCannotFabricateQuarantineProof() = runBlocking {
        val quarantineCalls = mutableListOf<CapsuleId>()
        val result = drain(
            rows = listOf(candidate(1, 10)),
            attempts = ArrayDeque(
                listOf(rejected(IncomingCapsuleAcceptanceRejectionReason.CRYPTO_REJECTED)),
            ),
            quarantineCalls = quarantineCalls,
        ).run()

        assertEquals(IncomingAcceptanceDrainResult.Completed(0, 1, false), result)
        assertTrue(quarantineCalls.isEmpty())
    }

    @Test
    fun typedProofMapsCasOutcomesAndRechecksProofBinding() = runBlocking {
        val candidate = candidate(1, 10)
        val proof = IncomingImmutableCapsuleInvalidityProof(OWNER, candidate.capsuleId)
        val freshResult = drain(
            rows = listOf(candidate),
            attempts = ArrayDeque(
                listOf(IncomingAcceptanceDrainAttempt.ProvenImmutableInvalidity(proof)),
            ),
            quarantineResult = IncomingCapsuleQuarantineResult.Quarantined,
        ).run()
        assertEquals(IncomingAcceptanceDrainResult.Completed(1, 1, false), freshResult)

        val quarantineCalls = mutableListOf<CapsuleId>()
        val result = drain(
            rows = listOf(candidate),
            attempts = ArrayDeque(
                listOf(IncomingAcceptanceDrainAttempt.ProvenImmutableInvalidity(proof)),
            ),
            quarantineResult = IncomingCapsuleQuarantineResult.AlreadyCorrupt,
            quarantineCalls = quarantineCalls,
        ).run()

        assertEquals(IncomingAcceptanceDrainResult.Completed(1, 1, false), result)
        assertEquals(listOf(candidate.capsuleId), quarantineCalls)

        val mismatchedProof = IncomingImmutableCapsuleInvalidityProof(OTHER_OWNER, candidate.capsuleId)
        val mismatchCalls = mutableListOf<CapsuleId>()
        val mismatch = drain(
            rows = listOf(candidate),
            attempts = ArrayDeque(
                listOf(IncomingAcceptanceDrainAttempt.ProvenImmutableInvalidity(mismatchedProof)),
            ),
            quarantineCalls = mismatchCalls,
        ).run()
        assertEquals(IncomingAcceptanceDrainResult.Completed(0, 1, false), mismatch)
        assertTrue(mismatchCalls.isEmpty())
    }

    @Test
    fun missingAndConcurrentCasAreNoMutationSkipsButDatabaseUnavailableRetries() = runBlocking {
        val rows = listOf(candidate(1, 10), candidate(2, 20))
        for (casResult in listOf(
            IncomingCapsuleQuarantineResult.MissingOrForeignOwner,
            IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged,
        )) {
            val accepted = mutableListOf<CapsuleId>()
            val result = drain(
                rows = rows,
                attempts = ArrayDeque(
                    listOf(
                        IncomingAcceptanceDrainAttempt.ProvenImmutableInvalidity(
                            IncomingImmutableCapsuleInvalidityProof(OWNER, rows[0].capsuleId),
                        ),
                        committed(),
                    ),
                ),
                quarantineResult = casResult,
                accepted = accepted,
            ).run()
            assertEquals(IncomingAcceptanceDrainResult.Completed(1, 2, false), result)
            assertEquals(listOf(rows[1].capsuleId), accepted)
        }

        val laterCalls = mutableListOf<CapsuleId>()
        val retry = drain(
            rows = rows,
            attempts = ArrayDeque(
                listOf(
                    IncomingAcceptanceDrainAttempt.ProvenImmutableInvalidity(
                        IncomingImmutableCapsuleInvalidityProof(OWNER, rows[0].capsuleId),
                    ),
                    committed(),
                ),
            ),
            quarantineResult = IncomingCapsuleQuarantineResult.DatabaseUnavailable,
            accepted = laterCalls,
        ).run()
        assertEquals(
            IncomingAcceptanceDrainResult.Retryable(
                IncomingAcceptanceDrainRetryReason.QUARANTINE_UNAVAILABLE,
            ),
            retry,
        )
        assertTrue(laterCalls.isEmpty())
    }

    @Test
    fun runOutcomesAreRedacted() {
        val outputs = listOf<Any>(
            IncomingAcceptanceDrainResult.Completed(0, 0, false),
            IncomingAcceptanceDrainResult.Retryable(
                IncomingAcceptanceDrainRetryReason.SELECTOR_UNAVAILABLE,
            ),
            IncomingAcceptanceDrainResult.AccountStopped,
        )

        outputs.forEach { output ->
            assertFalse(output.toString().contains(OWNER.toRestString()))
            assertFalse(output.toString().contains("c001"))
        }
    }

    @Test
    fun repeatedInvocationsHaveNoProcessLocalCursorOrDedupState() = runBlocking {
        val rows = listOf(candidate(1, 10))
        var callCount = 0
        val drain = drain(
            rows = rows,
            attempts = ArrayDeque(listOf(committed(), committed())),
            onSelect = { callCount += 1 },
        )

        assertEquals(IncomingAcceptanceDrainResult.Completed(1, 1, false), drain.run())
        assertEquals(IncomingAcceptanceDrainResult.Completed(1, 1, false), drain.run())
        assertEquals(2, callCount)
    }

    private fun drain(
        rows: List<IncomingAcceptanceCandidate>,
        max: Int = IncomingAcceptanceDrain.MAX_CANDIDATES_PER_RUN,
        owners: ArrayDeque<UserId?> = ArrayDeque(listOf(OWNER)),
        attempts: ArrayDeque<IncomingAcceptanceDrainAttempt> = ArrayDeque(
            rows.map { committed() },
        ),
        accepted: MutableList<CapsuleId> = mutableListOf(),
        attempted: MutableList<CapsuleId> = mutableListOf(),
        selected: MutableList<SelectionCall> = mutableListOf(),
        quarantineCalls: MutableList<CapsuleId> = mutableListOf(),
        quarantineResult: IncomingCapsuleQuarantineResult =
            IncomingCapsuleQuarantineResult.Quarantined,
        selection: IncomingAcceptanceCandidateSelection =
            IncomingAcceptanceCandidateSelection.Page(rows),
        onSelect: () -> Unit = {},
        attemptOverride: (suspend () -> IncomingAcceptanceDrainAttempt)? = null,
    ): IncomingAcceptanceDrain {
        val source = IncomingAcceptanceCandidateSource { owner, limit ->
            onSelect()
            selected += SelectionCall(owner, limit)
            selection
        }
        return IncomingAcceptanceDrain(
            candidates = source,
            currentOwner = {
                if (owners.size > 1) owners.removeFirst() else owners.first()
            },
            attempt = IncomingAcceptanceDrainAttemptPort {
                attempted += it.capsuleId
                val next = attemptOverride?.invoke() ?: attempts.removeFirst()
                if (next is IncomingAcceptanceDrainAttempt.Acceptance &&
                    next.result == IncomingCapsuleAcceptanceResult.Committed
                ) {
                    accepted += it.capsuleId
                }
                next
            },
            quarantine = IncomingAcceptanceDrainQuarantinePort { _, capsuleId, _ ->
                quarantineCalls += capsuleId
                quarantineResult
            },
            maxCandidatesPerRun = max,
        )
    }

    private fun candidate(number: Int, readyAt: Long) = IncomingAcceptanceCandidate(
        capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000c${number.toString().padStart(3, '0')}"),
        readyAtEpochMs = readyAt,
    )

    private fun committed() = IncomingAcceptanceDrainAttempt.Acceptance(
        IncomingCapsuleAcceptanceResult.Committed,
    )

    private fun idempotent() = IncomingAcceptanceDrainAttempt.Acceptance(
        IncomingCapsuleAcceptanceResult.IdempotentReplay,
    )

    private fun rejected(reason: IncomingCapsuleAcceptanceRejectionReason) =
        IncomingAcceptanceDrainAttempt.Acceptance(
            IncomingCapsuleAcceptanceResult.Rejected(reason),
        )

    private data class SelectionCall(val owner: UserId, val limit: Int)
}
