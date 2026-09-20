package dev.hryshyn.remanence.core.data.db

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipientTombstonePresentationBoundaryTest {

    @Test
    fun lookupAddCannotAttachToBucketRemovedByEmptyClose() {
        val boundary = RecipientTombstonePresentationBoundary()
        val lookedUp = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val firstLookup = AtomicBoolean(false)
        val firstCallback = AtomicInteger(0)
        val secondCallback = AtomicInteger(0)
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b001")
        val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000c001")

        val first = boundary.registerPrepared(owner, capsule) { firstCallback.incrementAndGet() }
        boundary.onAfterCallbackBucketLookup = {
            if (firstLookup.compareAndSet(false, true)) {
                lookedUp.countDown()
                assertTrue(releaseLookup.await(5, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val second = executor.submit<AutoCloseable> {
                boundary.registerPrepared(owner, capsule) { secondCallback.incrementAndGet() }
            }
            assertTrue(lookedUp.await(5, TimeUnit.SECONDS))

            // The registrar has the old bucket but has not entered its lock.
            // Closing the only existing callback removes that empty bucket.
            first.close()
            releaseLookup.countDown()
            val secondHandle = second.get(5, TimeUnit.SECONDS)
            boundary.onAfterCallbackBucketLookup = null

            boundary.invalidatePrepared(owner, listOf(capsule))

            assertEquals(0, firstCallback.get())
            assertEquals(1, secondCallback.get())
            secondHandle.close()
        } finally {
            boundary.onAfterCallbackBucketLookup = null
            releaseLookup.countDown()
            executor.shutdownNow()
        }
    }
}
