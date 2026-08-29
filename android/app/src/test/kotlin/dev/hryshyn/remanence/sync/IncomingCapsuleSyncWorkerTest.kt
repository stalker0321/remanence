package dev.hryshyn.remanence.sync

import androidx.work.ListenableWorker
import dev.hryshyn.remanence.core.data.db.IncomingSyncFailure
import dev.hryshyn.remanence.core.data.db.IncomingSyncResult
import dev.hryshyn.remanence.core.data.network.IncomingCapsule
import dev.hryshyn.remanence.core.data.network.IncomingEnvelope
import dev.hryshyn.remanence.core.data.network.IncomingCapsulePage
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
    }

    private fun loop(pages: FakePages, maxPages: Int = MAX_PAGES_PER_RUN) = IncomingSyncPageLoop(
        currentOwner = { OWNER },
        syncNextPage = pages::next,
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
