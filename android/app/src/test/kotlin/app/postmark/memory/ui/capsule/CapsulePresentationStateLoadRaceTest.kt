package app.postmark.memory.ui.capsule

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-REVIEW3-01 regression: a page load that is still in flight when the
 * presentation closes (expiry/revocation) can neither return its plaintext
 * to the caller nor re-enter the page cache - the rejected bytes are zeroed.
 * Deterministic: suspension gates plus UNDISPATCHED starts, no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapsulePresentationStateLoadRaceTest {

    @Test
    fun hungLoaderCompletingAfterCloseReturnsNothingCachesNothingAndZeroesBytes() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val produced = "plaintext-jpeg-bytes".toByteArray()
        val loader = CapsulePhotoLoader { ordinal ->
            started.complete(Unit)
            release.await()
            DecryptedPhoto(ordinal, produced)
        }
        val state = CapsulePresentationState(loader) { null }
        state.open(3)

        // Enter the loader and hang there; the outcome is captured INSIDE
        // the coroutine so nothing can escape into structured concurrency.
        val outcome = CompletableDeferred<Result<DecryptedPhoto>>()
        launch { outcome.complete(runCatching { state.pageAt(1) }) }
        started.await()

        // The presentation closes while the load is still in flight...
        state.close()
        // ...and only then does the loader deliver its plaintext.
        release.complete(Unit)

        val result = outcome.await()
        assertTrue("stale result must not be returned", result.isFailure)
        assertFalse(state.isOpen)
        assertTrue("no decrypted byte may enter the cache", state.loadedPages.isEmpty())
        assertTrue(
            "rejected plaintext bytes must be zeroed",
            produced.all { it == 0.toByte() },
        )
    }

    @Test
    fun concurrentLoadsForTheSamePageShareOneDecryptAndSurviveOnlyWhileOpen() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val invocations = AtomicInteger(0)
        val loader = CapsulePhotoLoader { ordinal ->
            invocations.incrementAndGet()
            started.complete(Unit)
            release.await()
            DecryptedPhoto(ordinal, "jpeg-$ordinal".toByteArray())
        }
        val state = CapsulePresentationState(loader) { null }
        state.open(3)

        val outcomes = CompletableDeferred<Pair<DecryptedPhoto, DecryptedPhoto>>()
        launch {
            val a = state.pageAt(2)
            val b = state.pageAt(2) // second request hits the same page path
            outcomes.complete(a to b)
        }
        started.await()

        // Complete while open; one decrypt serves both sequential callers.
        release.complete(Unit)
        val (a, b) = outcomes.await()
        assertEquals(1, invocations.get())
        assertSame(a, b)
        assertEquals(1, state.loadedPages.size)

        // And after close nothing of that page survives anywhere.
        state.close()
        assertTrue(state.loadedPages.isEmpty())
    }

    @Test
    fun closeDuringFlightAlsoRejectsLoadsThatArriveAfterwards() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val invocations = AtomicInteger(0)
        val loader = CapsulePhotoLoader { ordinal ->
            invocations.incrementAndGet()
            started.complete(Unit)
            release.await()
            DecryptedPhoto(ordinal, "late".toByteArray())
        }
        val state = CapsulePresentationState(loader) { null }
        state.open(3)

        val inFlightOutcome = CompletableDeferred<Result<DecryptedPhoto>>()
        launch { inFlightOutcome.complete(runCatching { state.pageAt(0) }) }
        started.await()
        state.close()
        release.complete(Unit)
        assertTrue(inFlightOutcome.await().isFailure)
        assertTrue(state.loadedPages.isEmpty())

        // A fresh attempt on the closed presentation fails immediately and
        // never reaches the loader again.
        val lateOutcome = runCatching { state.pageAt(0) }
        assertTrue(lateOutcome.isFailure)
        assertEquals(1, invocations.get())
        assertTrue(state.loadedPages.isEmpty())
    }
}
