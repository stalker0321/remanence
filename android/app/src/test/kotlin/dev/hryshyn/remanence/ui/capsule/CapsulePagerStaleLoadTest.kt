package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FIX-PAGING-01 regressions for the paging bitmap owner. These tests encode
 * the OWNERSHIP INVARIANT directly - the pager hands out bitmaps and must
 * never recycle them (minSdk 26 pixel memory is GC-managed; an eager recycle
 * raced the hardware render thread) - plus stale-load safety: an older page
 * result must never overwrite the bitmap/error of a newer requested ordinal,
 * and cancellation must propagate instead of becoming a fake page error.
 * Robolectric deliberately makes NO claim about GPU Canvas scheduling; the
 * invariant is checked on the bitmap objects themselves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsulePagerStaleLoadTest {

    /** Real bitmaps, distinct per ordinal, every instance tracked. */
    private class TrackedDecoder : CapsulePageDecoder {
        val produced = mutableListOf<Bitmap>()
        private val cache = mutableMapOf<Int, Bitmap>()
        val poisonedOrdinals = mutableSetOf<Int>()

        override fun decode(jpegBytes: ByteArray): Bitmap? {
            val ordinal = String(jpegBytes).removePrefix("jpeg-").toInt()
            if (ordinal in poisonedOrdinals) return null
            return cache.getOrPut(ordinal) {
                Bitmap.createBitmap(8 + ordinal, 6, Bitmap.Config.ARGB_8888).also {
                    produced += it
                }
            }
        }
    }

    /** Decrypt-side loader; optional per-ordinal gates simulate slow pages. */
    private class GatedLoader : CapsulePhotoLoader {
        val invocations = AtomicInteger(0)
        private val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        fun gateFor(ordinal: Int): CompletableDeferred<Unit> =
            gates.getOrPut(ordinal) { CompletableDeferred() }

        override suspend fun load(ordinal: Int): DecryptedPhoto {
            invocations.incrementAndGet()
            gates[ordinal]?.await()
            return DecryptedPhoto(ordinal, "jpeg-$ordinal".toByteArray())
        }
    }

    private fun openPresentation(count: Int = 3): Pair<CapsulePresentationState, GatedLoader> {
        val loader = GatedLoader()
        val presentation = CapsulePresentationState(loader).also { it.open(count, note = null) }
        return presentation to loader
    }

    /**
     * Decoder whose decode of [gateOrdinal] BLOCKS on a real thread until
     * released - mirroring a slow bounded decode that outlives its request.
     * The latch (not suspension) is required because [CapsulePageDecoder]
     * is deliberately non-suspending, like the production BitmapFactory call.
     */
    private class GatedDecoder(
        private val gateOrdinal: Int,
        private val staleResult: (ordinal: Int) -> Bitmap?,
    ) : CapsulePageDecoder {
        val gate = CountDownLatch(1)
        val enteredStaleDecode = AtomicBoolean(false)
        val produced = mutableListOf<Bitmap>()

        override fun decode(jpegBytes: ByteArray): Bitmap? {
            val ordinal = String(jpegBytes).removePrefix("jpeg-").toInt()
            if (ordinal == gateOrdinal) {
                enteredStaleDecode.set(true)
                gate.await()
            }
            val bitmap = staleResult(ordinal) ?: return null
            synchronized(produced) { produced += bitmap }
            return bitmap
        }
    }

    /**
     * Drives the scheduler once so [job] starts, then waits (bounded) until
     * the gated decode is parked on its real thread.
     */
    private fun kotlinx.coroutines.test.TestScope.awaitParkedInDecode(
        job: kotlinx.coroutines.Job,
        decoder: GatedDecoder,
    ) {
        runCurrent()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!decoder.enteredStaleDecode.get() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("stale decode must park inside the gate", decoder.enteredStaleDecode.get())
    }

    @Test
    fun staleDecodedPageNeverOverwritesNewerDisplayedPage() = runTest {
        val (presentation, _) = openPresentation()
        val decoder = GatedDecoder(gateOrdinal = 1, staleResult = { ordinal ->
            Bitmap.createBitmap(8 + ordinal, 6, Bitmap.Config.ARGB_8888)
        })
        // A REAL pool: the gated stale decode parks off-scheduler while the
        // newer page proceeds; two threads let both decodes run at once.
        val realDecodes = Executors.newFixedThreadPool(2)
        try {
            val pager = CapsulePager(
                presentation = presentation,
                decoder = decoder,
                decodeDispatcher = realDecodes.asCoroutineDispatcher(),
            )

            // Request page 1 and park it inside the bounded decode step
            // (the presentation mutex is RELEASED once decrypt finishes).
            val staleJob = launch { pager.show(1) }
            awaitParkedInDecode(staleJob, decoder)

            // Newer ordinal arrives while page 1 is still decoding.
            pager.show(2)
            val newest = synchronized(decoder.produced) {
                decoder.produced.single { it.width == 8 + 2 }
            }
            assertSame(newest, pager.displayedBitmap)

            // Only afterwards does the superseded decode finish: it must
            // land nothing - neither its bitmap nor any error. join()
            // guarantees the stale continuation ran before we assert.
            decoder.gate.countDown()
            staleJob.join()

            assertTrue(staleJob.isCompleted)
            assertSame(
                "a stale decoded page must never overwrite the newer ordinal",
                newest,
                pager.displayedBitmap,
            )
            assertNull(pager.pageError)
        } finally {
            realDecodes.shutdownNow()
        }
    }

    @Test
    fun staleFailedDecodeCannotPoisonNewerPageEither() = runTest {
        val (presentation, _) = openPresentation()
        val decoder = GatedDecoder(
            gateOrdinal = 1,
            staleResult = { ordinal ->
                if (ordinal == 1) null else Bitmap.createBitmap(
                    4 + ordinal,
                    4,
                    Bitmap.Config.ARGB_8888,
                )
            },
        )
        val realDecodes = Executors.newFixedThreadPool(2)
        try {
            val pager = CapsulePager(
                presentation = presentation,
                decoder = decoder,
                decodeDispatcher = realDecodes.asCoroutineDispatcher(),
            )

            val staleJob = launch { pager.show(1) }
            awaitParkedInDecode(staleJob, decoder)
            pager.show(2)
            val newest = pager.displayedBitmap

            // The stale page now turns out undecodable - far too late to
            // matter: no error, no eviction of the newer display state.
            decoder.gate.countDown()
            staleJob.join()

            assertTrue(staleJob.isCompleted)
            assertSame("stale failure must not replace the newer bitmap", newest, pager.displayedBitmap)
            assertNull(pager.pageError)
            assertTrue("decrypt cache entry is independent of UI staleness", presentation.loadedPages.containsKey(1))
        } finally {
            realDecodes.shutdownNow()
        }
    }

    @Test
    fun handedOutBitmapsAreNeverRecycledAcrossRapidPagingAndClose() = runTest {
        val (presentation, loader) = openPresentation()
        val decoder = TrackedDecoder()
        val pager = CapsulePager(
            presentation = presentation,
            decoder = decoder,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        )

        // Rapid Next/Previous bouncing through every reachable page.
        listOf(0, 1, 2, 1, 0, 1, 2).forEach { ordinal ->
            pager.show(ordinal)
        }
        advanceUntilIdle()
        assertEquals(3, decoder.produced.size)

        // THE ownership invariant: the UI layer never recycles - every
        // bitmap it ever received stays valid for any frame drawing it.
        decoder.produced.forEach { bitmap ->
            assertFalse("bitmap must never be recycled by the UI", bitmap.isRecycled)
        }

        // After close the pager must hold no page and touch no decryptor;
        // closing again stays fail-closed without recycling anything.
        val loadsBeforeClose = loader.invocations.get()
        presentation.close()
        pager.show(0)
        assertNull(pager.displayedBitmap)
        assertNull(pager.pageError)
        assertEquals(loadsBeforeClose, loader.invocations.get())
        decoder.produced.forEach { bitmap ->
            assertFalse("close must not recycle either", bitmap.isRecycled)
        }
    }

    @Test
    fun cancelledPageLoadPropagatesWithoutBecomingAFakePageError() = runTest {
        val (presentation, loader) = openPresentation()
        val decoder = TrackedDecoder()
        val pager = CapsulePager(
            presentation = presentation,
            decoder = decoder,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        )
        pager.show(0)

        // Register the gate FIRST so the load actually parks mid-decrypt.
        val gate1 = loader.gateFor(1)
        val hungJob = launch { pager.show(1) }
        runCurrent()
        assertEquals(
            "load must be parked inside the gated decrypt (page 0 + page 1)",
            2,
            loader.invocations.get(),
        )
        assertFalse("hung request must not complete while gated", hungJob.isCompleted)
        assertNull(pager.displayedBitmap) // cleared for the pending request

        hungJob.cancel()
        gate1.complete(Unit)
        advanceUntilIdle()

        assertTrue(hungJob.isCancelled)
        assertNull(
            "job cancellation must never surface as a page error",
            pager.pageError,
        )
        assertNull("a cancelled request must not land a bitmap", pager.displayedBitmap)
        assertFalse(presentation.loadedPages.containsKey(1))

        // The page-load mutex was released by the cancellation: the NEXT
        // request must proceed immediately (this would hang otherwise).
        pager.show(2)
        assertSame(decoder.produced.single { it.width == 8 + 2 }, pager.displayedBitmap)
        assertNull(pager.pageError)
    }

    @Test
    fun undecodableJpegShowsVisibleErrorEvictsPoisonedBytesAndRetryRecovers() = runTest {
        val (presentation, loader) = openPresentation()
        val decoder = TrackedDecoder()
        val pager = CapsulePager(
            presentation = presentation,
            decoder = decoder,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        )
        decoder.poisonedOrdinals += 0

        pager.show(0)

        assertEquals(
            "undecodable artifact must become a VISIBLE page error",
            "this page could not be decoded",
            pager.pageError,
        )
        assertNull(pager.displayedBitmap)
        assertFalse("poisoned plaintext must leave the cache", presentation.loadedPages.containsKey(0))

        // Retry (fresh FIX-STATE-07 epoch) recovers from fresh plaintext.
        decoder.poisonedOrdinals.clear()
        pager.show(0)
        assertNull(pager.pageError)
        assertEquals(2, loader.invocations.get())
        assertEquals(1, decoder.produced.count { it.width == 8 })
    }

    @Test
    fun decryptAadAndBoundsFailuresBecomeVisibleErrorsNotCrashes() = runTest {
        val failures = listOf(
            "aad tag mismatch" to CapsulePhotoLoader {
                throw AEADBadTagException("aad tag mismatch")
            },
            "photo artifact missing" to CapsulePhotoLoader { _ ->
                throw IllegalStateException("photo artifact missing")
            },
        )
        failures.forEach { (expectedMessage, failingLoader) ->
            val presentation = CapsulePresentationState(failingLoader).also { it.open(3, null) }
            val pager = CapsulePager(
                presentation = presentation,
                decoder = TrackedDecoder(),
                decodeDispatcher = StandardTestDispatcher(testScheduler),
            )

            pager.show(1)

            assertEquals(expectedMessage, pager.pageError)
            assertNull(pager.displayedBitmap)
            assertTrue("failed plaintext must never be cached", presentation.loadedPages.isEmpty())
            presentation.close()
        }

        // Out-of-bounds ordinal: buttons prevent it, the pager still fails
        // closed with a visible message instead of crashing.
        val (presentation, loader) = openPresentation(count = 3)
        val pager = CapsulePager(
            presentation = presentation,
            decoder = TrackedDecoder(),
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        )
        pager.show(9)
        assertEquals("ordinal out of bounds", pager.pageError)
        assertEquals(0, loader.invocations.get())
    }
}
