package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.PendingFeedItem
import dev.hryshyn.remanence.core.data.network.PendingFeedResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M13: feed sync — one fenced page per call, durable cursor on
 * success only, owner isolation, failure keeps position, lease death
 * fails closed without a live-looking result.
 */
class PendingFeedSyncTest {

    private val owner = "33333333-4444-5555-6666-777777777777"

    private fun item(id: String) = PendingFeedItem(
        capsuleId = "11111111-2222-3333-4444-555555555555",
        deliveryId = id,
        deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        claimId = "55555555-6666-7777-8888-999999999999",
        availableAt = 1798765432L,
        envelopeRef = "/v2/pending-deliveries/$id/claim",
        manifestRef = "/v2/pending-deliveries/$id/manifest",
        blobRefs = emptyList(),
        completion = null,
    )

    private inner class SwitchingGate(var gateOwner: String = owner) : PendingRetirementSessionGate {
        override fun capture(): SessionRequestLease =
            SessionRequestLease(UserId.parseRest(gateOwner), 1L)

        override fun accessTokenFor(lease: SessionRequestLease): String? = "token"

        override fun isLive(lease: SessionRequestLease): Boolean =
            lease.ownerUserId == UserId.parseRest(gateOwner)
    }

    private fun storeDir(): File = Files.createTempDirectory("pending-feed-sync").toFile()

    private fun sync(
        dir: File,
        gate: SwitchingGate = SwitchingGate(),
        account: ProofAccount? = ProofAccount(owner, "bundle"),
        fetch: PendingFeedSync.FetchPort = PendingFeedSync.FetchPort { _, _, _ ->
            PendingFeedResult.Page(listOf(item("d1")), "cursor-1")
        },
    ): PendingFeedSync = PendingFeedSync(
        fetchPort = fetch,
        ingestPort = PendingFeedSync.IngestPort { _, _, _ -> },
        cursorStore = PendingFeedCursorStore(dir),
        sessionGate = gate,
        accountProvider = { account },
        scope = null,
    )

    @Test
    fun pageSyncPersistsCursor() = runTest {
        val dir = storeDir()
        try {
            val first = sync(dir)
            val synced = first.sync() as PendingFeedSync.State.Synced
            assertEquals(1, synced.items.size)
            assertEquals("d1", synced.items.single().deliveryId)
            assertEquals("cursor-1", PendingFeedCursorStore(dir).peek(owner))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun secondSyncResumesFromCursor() = runTest {
        val dir = storeDir()
        try {
            val seen = mutableListOf<String?>()
            val gate = SwitchingGate()
            val sync = PendingFeedSync(
                fetchPort = PendingFeedSync.FetchPort { cursor, _, _ ->
                    seen += cursor
                    if (cursor == null) {
                        PendingFeedResult.Page(listOf(item("d1")), "cursor-1")
                    } else {
                        PendingFeedResult.Page(listOf(item("d2")), "")
                    }
                },
                ingestPort = PendingFeedSync.IngestPort { _, _, _ -> },
                cursorStore = PendingFeedCursorStore(dir),
                sessionGate = gate,
                accountProvider = { ProofAccount(owner, "bundle") },
                scope = null,
            )
            val first = sync.sync() as PendingFeedSync.State.Synced
            assertEquals("d1", first.items.single().deliveryId)
            assertEquals(false, first.exhausted)
            val second = sync.sync() as PendingFeedSync.State.Synced
            assertEquals("d2", second.items.single().deliveryId)
            assertEquals(true, second.exhausted)
            assertEquals(listOf(null, "cursor-1"), seen)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failureKeepsOldCursor() = runTest {
        val dir = storeDir()
        try {
            var fail = false
            val sync = PendingFeedSync(
                fetchPort = PendingFeedSync.FetchPort { _, _, _ ->
                    if (!fail) {
                        PendingFeedResult.Page(listOf(item("d1")), "cursor-1")
                    } else {
                        PendingFeedResult.Failure(
                            dev.hryshyn.remanence.core.data.network.PendingFeedFailure.NETWORK,
                            retryable = true,
                        )
                    }
                },
                cursorStore = PendingFeedCursorStore(dir),
                ingestPort = PendingFeedSync.IngestPort { _, _, _ -> },
                sessionGate = SwitchingGate(),
                accountProvider = { ProofAccount(owner, "bundle") },
                scope = null,
            )
            sync.sync()
            assertEquals("cursor-1", PendingFeedCursorStore(dir).peek(owner))
            fail = true
            val failed = sync.sync()
            assertTrue(failed is PendingFeedSync.State.Failed)
            // Position preserved for the retry.
            assertEquals("cursor-1", PendingFeedCursorStore(dir).peek(owner))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun switchedOwnerFailsWithoutFetch() = runTest {
        val dir = storeDir()
        try {
            var fetched = false
            val sync = sync(
                dir,
                gate = SwitchingGate("99999999-2222-3333-4444-555555555555"),
                fetch = PendingFeedSync.FetchPort { _, _, _ ->
                    fetched = true
                    PendingFeedResult.Page(emptyList(), "")
                },
            )
            val result = sync.sync()
            assertEquals(
                PendingFeedSync.State.Failed(PendingFeedSync.Reason.SESSION_CHANGED),
                result,
            )
            assertEquals(false, fetched)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14b-ingest (T188 B2): failed ingestion keeps the old
     * cursor — items not persisted are never advanced past, so failure
     * and process loss cannot lose the page.
     */
    @Test
    fun failedIngestKeepsCursorUnchanged() = runTest {
        val dir = storeDir()
        try {
            val sync = PendingFeedSync(
                fetchPort = PendingFeedSync.FetchPort { _, _, _ ->
                    PendingFeedResult.Page(listOf(item("d1")), "cursor-1")
                },
                ingestPort = PendingFeedSync.IngestPort { _, _, _ ->
                    throw IllegalStateException("admission rejected")
                },
                cursorStore = PendingFeedCursorStore(dir),
                sessionGate = SwitchingGate(),
                accountProvider = { ProofAccount(owner, "bundle") },
                scope = null,
            )
            val failed = sync.sync()
            assertTrue(failed is PendingFeedSync.State.Failed)
            assertNull(PendingFeedCursorStore(dir).peek(owner))
        } finally {
            dir.deleteRecursively()
        }
    }
}
