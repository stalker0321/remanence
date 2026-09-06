package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalExactDuplicateProtectionTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var context: Context
    private var now = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun identicalCommittedFrontIsBlockedAndStoresOnlyItsSha256Identity() = runBlocking {
        val digest = digest("front-A")
        val protection = protection()

        val winner = protection.reserve(OWNER_A, CAPSULE_A, digest)
        assertTrue(protection.commit(winner))

        try {
            protection.reserve(OWNER_A, CAPSULE_B, digest)
            throw AssertionError("expected exact duplicate block")
        } catch (expected: ExactDuplicateBlockedException) {
            assertEquals("exact duplicate blocked", expected.message)
        }

        val row = database.localSendDuplicateDao().getAllForOwner(OWNER_A).single()
        assertEquals(digest.toList(), row.frontSha256.toList())
        assertEquals(LocalExactDuplicateProtection.STATE_COMMITTED, row.state)
    }

    @Test
    fun retentionBoundaryExpiresAtTheConfiguredWindow() = runBlocking {
        val protection = protection(windowMs = 100L)
        val digest = digest("front-boundary")
        assertTrue(protection.commit(protection.reserve(OWNER_A, CAPSULE_A, digest)))

        now = 100L
        val replacement = protection.reserve(OWNER_A, CAPSULE_B, digest)
        assertTrue(protection.commit(replacement))
        assertEquals(1, database.localSendDuplicateDao().countCommittedForOwner(OWNER_A))
    }

    @Test
    fun committedHistoryIsDeterministicallyBoundedToMostRecentRows() = runBlocking {
        val protection = protection(windowMs = 10_000L, maxCount = 2)
        now = 1L
        assertTrue(protection.commit(protection.reserve(OWNER_A, CAPSULE_A, digest("one"))))
        now = 2L
        assertTrue(protection.commit(protection.reserve(OWNER_A, CAPSULE_B, digest("two"))))
        now = 3L
        assertTrue(protection.commit(protection.reserve(OWNER_A, CAPSULE_C, digest("three"))))

        assertEquals(2, database.localSendDuplicateDao().countCommittedForOwner(OWNER_A))
        val oldDigest = digest("one")
        val retry = protection.reserve(OWNER_A, CAPSULE_D, oldDigest)
        assertTrue(protection.release(retry))
    }

    @Test
    fun differentFrontContentPassesOffline() = runBlocking {
        val protection = protection()
        assertTrue(protection.commit(protection.reserve(OWNER_A, CAPSULE_A, digest("front-A"))))
        val other = protection.reserve(OWNER_A, CAPSULE_B, digest("front-B"))
        assertTrue(protection.commit(other))
        assertEquals(2, database.localSendDuplicateDao().countCommittedForOwner(OWNER_A))
    }

    @Test
    fun sameDigestIsIsolatedPerOwner() = runBlocking {
        val protection = protection()
        val digest = digest("same-front")
        assertTrue(protection.commit(protection.reserve(OWNER_A, CAPSULE_A, digest)))
        assertTrue(protection.commit(protection.reserve(OWNER_B, CAPSULE_B, digest)))
        assertEquals(2, database.localSendDuplicateDao().countAll())
    }

    @Test
    fun reservationReleaseAllowsAbortedAttemptToRetry() = runBlocking {
        val protection = protection()
        val digest = digest("aborted-front")
        val aborted = protection.reserve(OWNER_A, CAPSULE_A, digest)
        assertTrue(protection.release(aborted))

        val retry = protection.reserve(OWNER_A, CAPSULE_B, digest)
        assertTrue(protection.commit(retry))
        assertEquals(1, database.localSendDuplicateDao().countAll())
    }

    @Test
    fun expiredCrashReservationStopsBlockingAtLeaseBoundary() = runBlocking {
        val protection = protection(leaseMs = 50L)
        val digest = digest("crashed-front")
        protection.reserve(OWNER_A, CAPSULE_A, digest)

        now = 50L
        val recovered = protection.reserve(OWNER_A, CAPSULE_B, digest)
        assertTrue(protection.commit(recovered))
    }

    @Test
    fun staleReservedRowCanBeReopenedAndRetriedAfterProcessDeath() = runBlocking {
        val dbName = "local-duplicate-reopen-${UUID.randomUUID()}.db"
        database.close()
        database = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val first = protection(leaseMs = 100L)
        first.reserve(OWNER_A, CAPSULE_A, digest("reopen-after-crash"))

        database.close()
        now = 101L
        database = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val reopened = protection(leaseMs = 100L)
        val retry = reopened.reserve(OWNER_A, CAPSULE_B, digest("reopen-after-crash"))
        assertTrue(reopened.commit(retry))
        assertEquals(1, database.localSendDuplicateDao().countCommittedForOwner(OWNER_A))

        database.close()
        context.getDatabasePath(dbName).delete()
        File(context.getDatabasePath(dbName).path + "-wal").delete()
        File(context.getDatabasePath(dbName).path + "-shm").delete()
        Unit
    }

    @Test
    fun freshCompetingReservationRemainsBlockedBeforeLeaseExpiry() = runBlocking {
        val protection = protection(leaseMs = 100L)
        val digest = digest("fresh-competitor")
        protection.reserve(OWNER_A, CAPSULE_A, digest)
        now = 99L

        try {
            protection.reserve(OWNER_A, CAPSULE_B, digest)
            throw AssertionError("expected fresh reservation block")
        } catch (expected: ExactDuplicateBlockedException) {
            assertEquals("exact duplicate blocked", expected.message)
        }
    }

    @Test
    fun liveReservationRenewalFencesACompetingRetryPastOriginalLease() = runBlocking {
        val protection = protection(leaseMs = 100L)
        val digest = digest("renewed-live-staging")
        val live = protection.reserve(OWNER_A, CAPSULE_A, digest)
        now = 90L
        assertTrue(protection.renew(live))
        now = 101L

        try {
            protection.reserve(OWNER_A, CAPSULE_B, digest)
            throw AssertionError("expected renewed reservation block")
        } catch (expected: ExactDuplicateBlockedException) {
            assertEquals("exact duplicate blocked", expected.message)
        }
    }

    @Test
    fun concurrentSameOwnerSameDigestHasExactlyOneReservationWinner() = runBlocking {
        val protection = protection()
        val digest = digest("concurrent-front")
        val outcomes = coroutineScope {
            listOf(CAPSULE_A, CAPSULE_B).map { capsule ->
                async(Dispatchers.Default) {
                    runCatching { protection.reserve(OWNER_A, capsule, digest) }
                }
            }.map { it.await() }
        }

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is ExactDuplicateBlockedException)
        assertTrue(protection.commit(outcomes.single { it.isSuccess }.getOrThrow()))
    }

    private fun protection(
        windowMs: Long = 10_000L,
        maxCount: Int = 100,
        leaseMs: Long = LocalExactDuplicateProtection.DEFAULT_RESERVATION_LEASE_MS,
    ) = LocalExactDuplicateProtection(
        database = database,
        nowEpochMs = { now },
        retentionWindowMs = windowMs,
        maxRecentCount = maxCount,
        reservationLeaseMs = leaseMs,
    )

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val OWNER_A = "0198f0a0-0000-7000-8000-00000000ab01"
        const val OWNER_B = "0198f0a0-0000-7000-8000-00000000ab02"
        const val CAPSULE_A = "2b111111-2222-4333-8444-555555555555"
        const val CAPSULE_B = "2b222222-3333-4444-8555-666666666666"
        const val CAPSULE_C = "2b333333-4444-4555-8666-777777777777"
        const val CAPSULE_D = "2b444444-5555-4666-8777-888888888888"
    }
}
