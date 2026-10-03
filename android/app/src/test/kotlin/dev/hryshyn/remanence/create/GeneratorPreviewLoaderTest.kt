package dev.hryshyn.remanence.create

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Host-side tests for the bounded, fail-closed preview loader. */
class GeneratorPreviewLoaderTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val decoderOk = GeneratorSourceBinding.PhotoDecoderPort {
        GeneratorSourceBinding.UprightPhoto(1080, 1920)
    }
    private val normalizerOk = PhotoNormalizerPort {
        NormalizedPhotoDto(
            jpegBytes = byteArrayOf(9, 8, 7),
            width = 540,
            height = 960,
        )
    }

    private fun loader(
        bytes: ByteArray,
        decoder: GeneratorSourceBinding.PhotoDecoderPort = decoderOk,
        normalizer: PhotoNormalizerPort = normalizerOk,
        maxSourceBytes: Int = PhotoStagingPipeline.MAX_SOURCE_BYTES,
        workerDispatcher: CoroutineDispatcher? = null,
    ) = DefaultGeneratorPreviewLoader(
        openSource = { PhotoSource { ByteArrayInputStream(bytes) } },
        decoder = decoder,
        normalizer = normalizer,
        maxSourceBytes = maxSourceBytes,
        workerDispatcher = workerDispatcher ?: Dispatchers.IO,
    )

    @Test
    fun loadsUprightSourceWithOriginalHashAndPreviewBytes() = runTest {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val loaded = loader(original).load("uri-1")
        requireNotNull(loaded) { "expected a loaded source" }
        assertEquals(sha256Hex(original), loaded.originalHash)
        assertEquals(1080, loaded.uprightWidthPx)
        assertEquals(1920, loaded.uprightHeightPx)
        assertArrayEquals(byteArrayOf(9, 8, 7), loaded.previewJpegBytes)
        assertEquals(540, loaded.previewWidthPx)
        assertEquals(960, loaded.previewHeightPx)
    }

    @Test
    fun overBudgetSourceFailsClosed() = runTest {
        assertNull(loader(ByteArray(9) { 1 }, maxSourceBytes = 8).load("uri-1"))
    }

    @Test
    fun decoderOrNormalizerFailureFailsClosed() = runTest {
        val throwingDecoder = GeneratorSourceBinding.PhotoDecoderPort { throw IllegalStateException("bad") }
        assertNull(loader(byteArrayOf(1), decoder = throwingDecoder).load("uri-1"))

        val throwingNormalizer = PhotoNormalizerPort { throw IllegalStateException("bad") }
        assertNull(loader(byteArrayOf(1), normalizer = throwingNormalizer).load("uri-1"))

        val emptyNormalizer = PhotoNormalizerPort { NormalizedPhotoDto(ByteArray(0), 10, 10) }
        assertNull(loader(byteArrayOf(1), normalizer = emptyNormalizer).load("uri-1"))
    }

    @Test
    fun workerStagesRunOffCallerAndSinkStaysOnCaller() {
        // Hermetic thread placement: a real single worker thread proves
        // read/decode/normalize/hash leave the caller lane, while the
        // retain callback fires on the calling thread.
        val callerThread = Thread.currentThread().name
        val workerThreads = Collections.synchronizedList(mutableListOf<String>())
        var sinkThread: String? = null
        var sinkBytes: ByteArray? = null
        var sinkHash: String? = null
        val exec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "probe-worker").apply { isDaemon = true }
        }
        try {
            val recordingDecoder = GeneratorSourceBinding.PhotoDecoderPort {
                workerThreads += Thread.currentThread().name
                GeneratorSourceBinding.UprightPhoto(1080, 1920)
            }
            val recordingNormalizer = PhotoNormalizerPort {
                workerThreads += Thread.currentThread().name
                NormalizedPhotoDto(byteArrayOf(9, 8, 7), 540, 960)
            }
            val loaded = kotlinx.coroutines.runBlocking {
                loader(
                    byteArrayOf(1, 2, 3),
                    decoder = recordingDecoder,
                    normalizer = recordingNormalizer,
                    workerDispatcher = exec.asCoroutineDispatcher(),
                ).also { it.retainOriginal = { bytes, hash ->
                    sinkThread = Thread.currentThread().name
                    sinkBytes = bytes.copyOf()
                    sinkHash = hash
                } }.load("uri-1")
            }
            requireNotNull(loaded) { "expected a loaded source" }
            assertTrue(
                "worker stages must run on the worker thread, saw $workerThreads",
                workerThreads.isNotEmpty() && workerThreads.all { it.startsWith("probe-worker") },
            )
            assertTrue(
                "no worker stage may run on the caller ($callerThread)",
                workerThreads.none { it == callerThread },
            )
            assertEquals(
                "retain callback must run on the caller lane",
                callerThread.substringBefore(" "),
                (sinkThread ?: "?").substringBefore(" "),
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), sinkBytes)
            assertEquals(sha256Hex(byteArrayOf(1, 2, 3)), sinkHash)
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun sinkSeesLiveBytesAndLoaderZeroizesAfterReturn() = runTest {
        var retainedRef: ByteArray? = null
        val loaded = loader(byteArrayOf(4, 5, 6)).also { loader ->
            loader.retainOriginal = { bytes, _ -> retainedRef = bytes }
        }.load("uri-1")
        requireNotNull(loaded) { "expected a loaded source" }
        val ref = requireNotNull(retainedRef) { "sink must have run" }
        // The sink observed the live original; the loader zeroized its own
        // bytes before returning (no live original retained anywhere).
        assertTrue("original must be zeroized after return", ref.all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(9, 8, 7), loaded.previewJpegBytes)
    }

    @Test
    fun cancelDuringWorkerWorkSkipsSinkAndPropagates() = runTest {
        val gate = CompletableDeferred<Unit>()
        var sinkRan = false
        val blockingDecoder = GeneratorSourceBinding.PhotoDecoderPort {
            gate.await()
            GeneratorSourceBinding.UprightPhoto(8, 8)
        }
        val loading = loader(
            byteArrayOf(1),
            decoder = blockingDecoder,
            workerDispatcher = StandardTestDispatcher(testScheduler),
        ).also { it.retainOriginal = { _, _ -> sinkRan = true } }
        val job = async { loading.load("uri-1") }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue("cancelled load must not deliver", job.isCancelled)
        try {
            job.await()
            fail("expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: cancellation crosses the dispatch hop.
        }
        assertTrue("stale retain must never fire", !sinkRan)
    }

    @Test
    fun cancelAfterWorkerSuccessStillZeroizesAndSkipsSink() = runTest {
        // F1/F2: worker finishes and publishes, then the caller is
        // cancelled BEFORE its queued continuation resumes. The produced
        // original must still be wiped and the sink must never fire.
        // A manual queue dispatcher gives a deterministic pause point:
        // worker tasks run only when drained, while the caller resume
        // stays queued on the test scheduler.
        val workerQueue = ArrayDeque<Runnable>()
        val manualWorker = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                workerQueue += block
            }
        }
        var workerOriginal: ByteArray? = null
        var sinkRan = false
        val capturingDecoder = GeneratorSourceBinding.PhotoDecoderPort { bytes ->
            workerOriginal = bytes
            GeneratorSourceBinding.UprightPhoto(8, 8)
        }
        val loading = DefaultGeneratorPreviewLoader(
            openSource = { PhotoSource { ByteArrayInputStream(byteArrayOf(7, 7, 7)) } },
            decoder = capturingDecoder,
            normalizer = normalizerOk,
            workerDispatcher = manualWorker,
        ).also { it.retainOriginal = { _, _ -> sinkRan = true } }
        val job = async { loading.load("uri-1") }
        // Run the caller up to the worker hop, then drain the worker to
        // success; the caller resume stays queued on the test scheduler.
        testScheduler.runCurrent()
        while (workerQueue.isNotEmpty()) workerQueue.removeFirst().run()
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue("cancelled handoff must not deliver", job.isCancelled)
        try {
            job.await()
            fail("expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: cancellation across the dispatch handoff.
        }
        assertTrue("stale retain must never fire", !sinkRan)
        val original = requireNotNull(workerOriginal) { "worker must have decoded" }
        assertTrue(
            "produced original must be zeroized despite resume cancellation",
            original.all { it == 0.toByte() },
        )
    }

    @Test
    fun throwingSinkStillReturnsPreview() = runTest {
        val loaded = loader(byteArrayOf(1, 2)).also { loader ->
            loader.retainOriginal = { _, _ -> throw IllegalStateException("sink boom") }
        }.load("uri-1")
        requireNotNull(loaded) { "sink failure must never break preview" }
        assertArrayEquals(byteArrayOf(9, 8, 7), loaded.previewJpegBytes)
    }
}
