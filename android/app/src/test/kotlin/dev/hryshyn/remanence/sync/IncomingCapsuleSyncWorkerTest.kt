package dev.hryshyn.remanence.sync

import androidx.work.ListenableWorker
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import dev.hryshyn.remanence.core.data.db.IncomingSyncFailure
import dev.hryshyn.remanence.core.data.db.IncomingSyncResult
import dev.hryshyn.remanence.core.data.network.IncomingMaterialAckDrainResult
import dev.hryshyn.remanence.core.data.network.IncomingCapsule
import dev.hryshyn.remanence.core.data.network.IncomingEnvelope
import dev.hryshyn.remanence.core.data.network.IncomingCapsulePage
import dev.hryshyn.remanence.core.data.prefetch.IncomingPrefetchRetryReason
import dev.hryshyn.remanence.core.data.prefetch.IncomingPrefetchResult
import dev.hryshyn.remanence.core.data.prefetch.IncomingPrefetchTerminalReason
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import java.util.ArrayDeque
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCapsuleSyncWorkerTest {

    @Test
    fun emptyPageCompletesSuccessfully() = runTest {
        val pages = committedPages(page(nextCursor = null, hasMore = false))

        assertEquals(
            IncomingSyncRunOutcome.Succeeded(pagesProcessed = 1),
            loop(pages).run(OWNER),
        )
        assertEquals(listOf<String?>(null), pages.requestedCursors)
    }

    @Test
    fun terminalNonemptyPageWithHighWatermarkCompletes() = runTest {
        val pages = committedPages(
            page(
                nextCursor = "terminal-high-watermark",
                hasMore = false,
                items = listOf(sampleCapsule()),
            ),
        )

        assertEquals(
            IncomingSyncRunOutcome.Succeeded(pagesProcessed = 1),
            loop(pages).run(OWNER),
        )
        assertEquals(listOf<String?>(null), pages.requestedCursors)
    }

    @Test
    fun multiplePagesPreserveRepositoryOrder() = runTest {
        val pages = committedPages(
            page(nextCursor = "cursor-1", hasMore = true),
            page(nextCursor = "terminal", hasMore = false),
        )

        assertEquals(
            IncomingSyncRunOutcome.Succeeded(pagesProcessed = 2),
            loop(pages).run(OWNER),
        )
        assertEquals(listOf<String?>(null, "cursor-1"), pages.requestedCursors)
    }

    @Test
    fun pageCapRetriesAndNextInvocationResumesFromDurableCursor() = runTest {
        val pages = committedPages(
            page(nextCursor = "cursor-1", hasMore = true),
            page(nextCursor = "cursor-2", hasMore = true),
            page(nextCursor = "terminal", hasMore = false),
        )

        assertEquals(IncomingSyncRunOutcome.PageCapReached, loop(pages, maxPages = 1).run(OWNER))
        assertEquals(IncomingSyncRunOutcome.Succeeded(2), loop(pages).run(OWNER))
        assertEquals(listOf<String?>(null, "cursor-1", "cursor-2"), pages.requestedCursors)
    }

    @Test
    fun transientFailureRetriesWithoutAdvancingDurableCursor() = runTest {
        val pages = FakePages(
            results = listOf(
                IncomingSyncResult.Failure(IncomingSyncFailure.NETWORK, retryable = true),
                IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false)),
            ),
        )

        assertEquals(
            IncomingSyncRunOutcome.Retryable(IncomingSyncFailure.NETWORK),
            loop(pages).run(OWNER),
        )
        assertEquals(IncomingSyncRunOutcome.Succeeded(1), loop(pages).run(OWNER))
        assertEquals(listOf<String?>(null, null), pages.requestedCursors)
    }

    @Test
    fun malformedAndAuthFailuresAreTerminal() = runTest {
        assertEquals(
            IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.INVALID_RESPONSE),
            loop(FakePages(listOf(IncomingSyncResult.Failure(
                IncomingSyncFailure.INVALID_RESPONSE,
                retryable = false,
            )))).run(OWNER),
        )
        assertEquals(
            IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.AUTH_INVALID),
            loop(FakePages(listOf(IncomingSyncResult.Failure(
                IncomingSyncFailure.AUTH_INVALID,
                retryable = false,
            )))).run(OWNER),
        )
    }

    @Test
    fun logoutBeforeLoopFailsWithoutCallingRepository() = runTest {
        var calls = 0

        val outcome = IncomingSyncPageLoop(
            currentOwner = { null },
            syncNextPage = {
                calls += 1
                IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))
            },
        ).run(OWNER)

        assertEquals(IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.NO_ACTIVE_SESSION), outcome)
        assertEquals(0, calls)
    }

    @Test
    fun accountSwitchDuringLoopStopsBeforeASecondPageCommit() = runTest {
        var liveOwner: UserId? = OWNER
        val committedOwners = mutableListOf<UserId>()
        var calls = 0
        val outcome = IncomingSyncPageLoop(
            currentOwner = { liveOwner },
            syncNextPage = {
                calls += 1
                committedOwners += liveOwner!!
                liveOwner = OTHER_OWNER
                IncomingSyncResult.Committed(page(nextCursor = "must-not-fetch", hasMore = true))
            },
        ).run(OWNER)

        assertEquals(IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.ACCOUNT_CHANGED), outcome)
        assertEquals(1, calls)
        assertEquals(listOf(OWNER), committedOwners)
    }

    @Test
    fun cancellationPropagatesWithoutWorkerClassification() = runTest {
        val cancellation = CancellationException("test cancellation")
        var cancelled = false
        try {
            IncomingSyncPageLoop(
                currentOwner = { OWNER },
                syncNextPage = { throw cancellation },
            ).run(OWNER)
        } catch (thrown: CancellationException) {
            cancelled = true
            assertTrue(thrown === cancellation)
        }

        assertTrue(cancelled)
    }

    @Test
    fun workerMappingIsExplicitAndWorkDataContainsOnlyOwner() {
        assertEquals(
            ListenableWorker.Result.success(),
            IncomingCapsuleSyncWorker.mapOutcome(IncomingSyncRunOutcome.Succeeded(0)),
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            IncomingCapsuleSyncWorker.mapOutcome(IncomingSyncRunOutcome.PageCapReached),
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            IncomingCapsuleSyncWorker.mapOutcome(
                IncomingSyncRunOutcome.Retryable(IncomingSyncFailure.RATE_LIMITED),
            ),
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            IncomingCapsuleSyncWorker.mapOutcome(
                IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.ACCOUNT_CHANGED),
            ),
        )

        val request = IncomingCapsuleSyncWorker.request(OWNER)
        assertEquals(
            setOf(IncomingCapsuleSyncWorker.INPUT_OWNER_USER_ID),
            request.workSpec.input.keyValueMap.keys,
        )
        assertEquals(
            OWNER.toRestString(),
            request.workSpec.input.getString(IncomingCapsuleSyncWorker.INPUT_OWNER_USER_ID),
        )
        assertTrue(
            request.tags.containsAll(AccountWorkIdentity.incomingSync(OWNER).tags),
        )
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(INCOMING_SYNC_BACKOFF_INITIAL_MILLIS, request.workSpec.backoffDelayDuration)
    }

    @Test
    fun successfulPageRunsAcceptanceExactlyOnce() = runTest {
        val pages = FakePages(
            listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
        )
        var acceptanceCalls = 0

        val result = combinedRunner(
            pages = pages,
            runAcceptance = { expectedOwner ->
                assertEquals(OWNER, expectedOwner)
                acceptanceCalls += 1
                IncomingAcceptanceDrainResult.Completed(0, 0, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Succeeded, result)
        assertEquals(1, acceptanceCalls)
    }

    @Test
    fun successfulPageRunsAllSafeStagesOnceInOrder() = runTest {
        val pages = FakePages(
            listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
        )
        val events = mutableListOf<String>()

        val result = combinedRunner(
            pages = pages,
            syncNextPage = {
                events += "page"
                pages.next()
            },
            runAcceptance = {
                events += "acceptance"
                IncomingAcceptanceDrainResult.Completed(0, 0, false)
            },
            runPrefetch = {
                events += "prefetch"
                IncomingPrefetchResult.Completed(0, 0)
            },
            runMaterialAck = {
                events += "ack"
                IncomingMaterialAckDrainResult.Completed(0, 0, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Succeeded, result)
        assertEquals(listOf("page", "acceptance", "prefetch", "ack"), events)
    }

    @Test
    fun nonSuccessfulPageOutcomesBypassAcceptance() = runTest {
        val cases = listOf(
            listOf(IncomingSyncResult.Committed(page(nextCursor = "more", hasMore = true))) to
                MAX_PAGES_PER_RUN.coerceAtMost(1),
            listOf(IncomingSyncResult.Failure(IncomingSyncFailure.NETWORK, retryable = true)) to
                MAX_PAGES_PER_RUN,
            listOf(IncomingSyncResult.Failure(IncomingSyncFailure.INVALID_RESPONSE, retryable = false)) to
                MAX_PAGES_PER_RUN,
        )
        for ((results, maxPages) in cases) {
            var acceptanceCalls = 0
            var prefetchCalls = 0
            var ackCalls = 0
            val outcome = combinedRunner(
                pages = FakePages(results),
                maxPages = maxPages,
                runAcceptance = {
                    acceptanceCalls += 1
                    IncomingAcceptanceDrainResult.Completed(0, 0, false)
                },
                runPrefetch = {
                    prefetchCalls += 1
                    IncomingPrefetchResult.Completed(0, 0)
                },
                runMaterialAck = {
                    ackCalls += 1
                    IncomingMaterialAckDrainResult.Completed(0, 0, false)
                },
            ).run(OWNER)

            assertEquals(
                if (results.first() is IncomingSyncResult.Failure &&
                    !(results.first() as IncomingSyncResult.Failure).retryable
                ) {
                    IncomingSyncAndAcceptanceRunOutcome.Terminal
                } else {
                    IncomingSyncAndAcceptanceRunOutcome.Retryable
                },
                outcome,
            )
            assertEquals(0, acceptanceCalls)
            assertEquals(0, prefetchCalls)
            assertEquals(0, ackCalls)
        }
    }

    @Test
    fun drainOutcomesUseContinuationBitEvenWithoutProgress() = runTest {
        val cases = listOf(
            IncomingAcceptanceDrainResult.Completed(1, 8, true) to
                IncomingSyncAndAcceptanceRunOutcome.Retryable,
            IncomingAcceptanceDrainResult.Completed(0, 8, true) to
                IncomingSyncAndAcceptanceRunOutcome.Retryable,
            IncomingAcceptanceDrainResult.Completed(1, 8, false) to
                IncomingSyncAndAcceptanceRunOutcome.Succeeded,
            IncomingAcceptanceDrainResult.Completed(0, 8, false) to
                IncomingSyncAndAcceptanceRunOutcome.Succeeded,
            IncomingAcceptanceDrainResult.Retryable(
                IncomingAcceptanceDrainRetryReason.ACCEPTANCE_RETRYABLE,
            ) to IncomingSyncAndAcceptanceRunOutcome.Retryable,
            IncomingAcceptanceDrainResult.AccountStopped to
                IncomingSyncAndAcceptanceRunOutcome.Terminal,
        )
        for ((drainResult, expected) in cases) {
            val outcome = combinedRunner(
                pages = FakePages(
                    listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
                ),
                runAcceptance = { drainResult },
            ).run(OWNER)

            assertEquals(expected, outcome)
            assertEquals(
                when (expected) {
                    IncomingSyncAndAcceptanceRunOutcome.Succeeded -> ListenableWorker.Result.success()
                    IncomingSyncAndAcceptanceRunOutcome.Retryable -> ListenableWorker.Result.retry()
                    IncomingSyncAndAcceptanceRunOutcome.Terminal -> ListenableWorker.Result.failure()
                },
                IncomingCapsuleSyncWorker.mapCombinedOutcome(outcome),
            )
        }
    }

    @Test
    fun fullZeroProgressPagesRetryAfterLaterStagesRun() = runTest {
        val cases = listOf(
            Triple(
                IncomingAcceptanceDrainResult.Completed(0, 8, true),
                IncomingPrefetchResult.Completed(0, 0),
                IncomingMaterialAckDrainResult.Completed(0, 0, false),
            ),
            Triple(
                IncomingAcceptanceDrainResult.Completed(0, 0, false),
                IncomingPrefetchResult.Completed(0, 0, pageMayHaveMore = true),
                IncomingMaterialAckDrainResult.Completed(0, 0, false),
            ),
            Triple(
                IncomingAcceptanceDrainResult.Completed(0, 0, false),
                IncomingPrefetchResult.Completed(0, 0),
                IncomingMaterialAckDrainResult.Completed(0, 0, true),
            ),
        )

        for ((acceptance, prefetch, acknowledgement) in cases) {
            val events = mutableListOf<String>()
            val result = combinedRunner(
                pages = FakePages(
                    listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
                ),
                runAcceptance = {
                    events += "acceptance"
                    acceptance
                },
                runPrefetch = {
                    events += "prefetch"
                    prefetch
                },
                runMaterialAck = {
                    events += "ack"
                    acknowledgement
                },
            ).run(OWNER)

            assertEquals(IncomingSyncAndAcceptanceRunOutcome.Retryable, result)
            assertEquals(listOf("acceptance", "prefetch", "ack"), events)
        }
    }

    @Test
    fun nonFullZeroProgressPagesComplete() = runTest {
        val events = mutableListOf<String>()
        val result = combinedRunner(
            pages = FakePages(
                listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
            ),
            runAcceptance = {
                events += "acceptance"
                IncomingAcceptanceDrainResult.Completed(0, 7, false)
            },
            runPrefetch = {
                events += "prefetch"
                IncomingPrefetchResult.Completed(0, 0, pageMayHaveMore = false)
            },
            runMaterialAck = {
                events += "ack"
                IncomingMaterialAckDrainResult.Completed(0, 0, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Succeeded, result)
        assertEquals(listOf("acceptance", "prefetch", "ack"), events)
    }

    @Test
    fun fullPagesRetryOnlyAfterEverySafeStageHasOneBoundedTurn() = runTest {
        val events = mutableListOf<String>()
        val result = combinedRunner(
            pages = FakePages(
                listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
            ),
            runAcceptance = {
                events += "acceptance"
                IncomingAcceptanceDrainResult.Completed(1, 8, true)
            },
            runPrefetch = {
                events += "prefetch"
                IncomingPrefetchResult.Completed(
                    processedBlobCount = 1,
                    materialCachedCapsuleCount = 1,
                    pageMayHaveMore = true,
                )
            },
            runMaterialAck = {
                events += "ack"
                IncomingMaterialAckDrainResult.Completed(1, 8, true)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Retryable, result)
        assertEquals(listOf("acceptance", "prefetch", "ack"), events)
    }

    @Test
    fun retryablePrefetchStopsBeforeAcknowledgement() = runTest {
        var ackCalls = 0
        val result = combinedRunner(
            pages = FakePages(
                listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
            ),
            runAcceptance = { IncomingAcceptanceDrainResult.Completed(0, 0, false) },
            runPrefetch = {
                IncomingPrefetchResult.Retryable(
                    IncomingPrefetchRetryReason.DOWNLOAD,
                )
            },
            runMaterialAck = {
                ackCalls += 1
                IncomingMaterialAckDrainResult.Completed(0, 0, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Retryable, result)
        assertEquals(0, ackCalls)
    }

    @Test
    fun accountSwitchBetweenAcceptanceAndPrefetchStopsBothLaterStages() = runTest {
        var ownerReads = 0
        var prefetchCalls = 0
        var ackCalls = 0
        val result = combinedRunner(
            pages = FakePages(
                listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
            ),
            currentOwner = {
                ownerReads += 1
                if (ownerReads <= 2) OWNER else OTHER_OWNER
            },
            runAcceptance = { IncomingAcceptanceDrainResult.Completed(0, 0, false) },
            runPrefetch = {
                prefetchCalls += 1
                IncomingPrefetchResult.Completed(0, 0)
            },
            runMaterialAck = {
                ackCalls += 1
                IncomingMaterialAckDrainResult.Completed(0, 0, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Terminal, result)
        assertEquals(0, prefetchCalls)
        assertEquals(0, ackCalls)
    }

    @Test
    fun terminalPrefetchStopsBeforeAcknowledgement() = runTest {
        var ackCalls = 0
        val result = combinedRunner(
            pages = FakePages(
                listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
            ),
            runAcceptance = { IncomingAcceptanceDrainResult.Completed(0, 0, false) },
            runPrefetch = {
                IncomingPrefetchResult.Terminal(
                    IncomingPrefetchTerminalReason.INVALID_METADATA,
                )
            },
            runMaterialAck = {
                ackCalls += 1
                IncomingMaterialAckDrainResult.Completed(0, 0, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Terminal, result)
        assertEquals(0, ackCalls)
    }

    @Test
    fun accountSwitchBetweenPrefetchAndAcknowledgementStopsAcknowledgement() = runTest {
        var ownerReads = 0
        var prefetchCalls = 0
        var ackCalls = 0
        val result = combinedRunner(
            pages = FakePages(
                listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
            ),
            currentOwner = {
                ownerReads += 1
                if (ownerReads <= 3) OWNER else OTHER_OWNER
            },
            runAcceptance = { IncomingAcceptanceDrainResult.Completed(0, 0, false) },
            runPrefetch = {
                prefetchCalls += 1
                IncomingPrefetchResult.Completed(0, 0)
            },
            runMaterialAck = {
                ackCalls += 1
                IncomingMaterialAckDrainResult.Completed(0, 0, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Terminal, result)
        assertEquals(1, prefetchCalls)
        assertEquals(0, ackCalls)
    }

    @Test
    fun cancellationFromPrefetchPropagatesExactly() = runTest {
        val expected = CancellationException("prefetch cancellation")
        try {
            combinedRunner(
                pages = FakePages(
                    listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
                ),
                runAcceptance = { IncomingAcceptanceDrainResult.Completed(0, 0, false) },
                runPrefetch = { throw expected },
            ).run(OWNER)
            error("expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === expected)
        }
    }

    @Test
    fun accountSwitchAfterPageCompletionStopsBeforeAcceptance() = runTest {
        var ownerReads = 0
        var acceptanceCalls = 0
        val outcome = combinedRunner(
            pages = FakePages(
                listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
            ),
            currentOwner = {
                ownerReads += 1
                if (ownerReads == 1) OWNER else OTHER_OWNER
            },
            runAcceptance = {
                acceptanceCalls += 1
                IncomingAcceptanceDrainResult.Completed(1, 1, false)
            },
        ).run(OWNER)

        assertEquals(IncomingSyncAndAcceptanceRunOutcome.Terminal, outcome)
        assertEquals(0, acceptanceCalls)
    }

    @Test
    fun cancellationFromAcceptancePropagatesExactly() = runTest {
        val expected = CancellationException("acceptance cancellation")
        val pages = FakePages(
            listOf(IncomingSyncResult.Committed(page(nextCursor = null, hasMore = false))),
        )

        try {
            combinedRunner(
                pages = pages,
                runAcceptance = { throw expected },
            ).run(OWNER)
            error("expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === expected)
        }
    }

    private fun loop(pages: FakePages, maxPages: Int = MAX_PAGES_PER_RUN) = IncomingSyncPageLoop(
        currentOwner = { OWNER },
        syncNextPage = pages::next,
        maxPagesPerRun = maxPages,
    )

    private fun combinedRunner(
        pages: FakePages,
        syncNextPage: suspend () -> IncomingSyncResult = pages::next,
        currentOwner: suspend () -> UserId? = { OWNER },
        maxPages: Int = MAX_PAGES_PER_RUN,
        runAcceptance: suspend (UserId) -> IncomingAcceptanceDrainResult,
        runPrefetch: suspend (UserId) -> IncomingPrefetchResult = {
            IncomingPrefetchResult.Completed(0, 0)
        },
        runMaterialAck: suspend (UserId) -> IncomingMaterialAckDrainResult = {
            IncomingMaterialAckDrainResult.Completed(0, 0, false)
        },
    ) = IncomingSyncAndAcceptanceRunner(
        currentOwner = currentOwner,
        syncNextPage = syncNextPage,
        runAcceptance = runAcceptance,
        runPrefetch = runPrefetch,
        runMaterialAck = runMaterialAck,
        maxPagesPerRun = maxPages,
    )

    private fun page(
        nextCursor: String?,
        hasMore: Boolean,
        items: List<IncomingCapsule> = emptyList(),
    ) = IncomingCapsulePage(
        items = items,
        hasMore = hasMore,
        nextCursor = nextCursor,
    )

    private fun committedPages(vararg pages: IncomingCapsulePage) = FakePages(
        pages.map { page -> IncomingSyncResult.Committed(page) },
    )

    private fun sampleCapsule() = IncomingCapsule(
        capsuleId = dev.hryshyn.remanence.core.model.CapsuleId.parseRest(
            "0198f0a0-0000-7000-8000-00000000a011",
        ),
        senderUserId = OWNER,
        recipientUserId = OWNER,
        senderKeyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000a012"),
        recipientKeyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000a013"),
        protocolVersion = 1,
        readyAtEpochMs = 1L,
        signedStatementBytes = byteArrayOf(1),
        signedStatementSha256 = ByteArray(32),
        publishSignatureBytes = ByteArray(69),
        envelope = IncomingEnvelope(
            recipientKeyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000a013"),
            ciphertext = byteArrayOf(2),
            ciphertextSha256 = ByteArray(32),
        ),
        blobs = emptyList(),
    )

    private class FakePages(
        results: List<IncomingSyncResult>,
    ) {
        private val remaining = ArrayDeque(results)
        val requestedCursors = mutableListOf<String?>()
        private var durableCursor: String? = null

        suspend fun next(): IncomingSyncResult {
            requestedCursors += durableCursor
            val result = remaining.removeFirst()
            if (result is IncomingSyncResult.Committed) {
                durableCursor = result.page.nextCursor
            }
            return result
        }
    }

    private companion object {
        val OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
        val OTHER_OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")
    }
}
