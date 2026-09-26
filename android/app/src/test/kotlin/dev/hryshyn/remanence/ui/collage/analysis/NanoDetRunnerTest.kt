package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.NanoDetDecoder
import dev.hryshyn.remanence.ui.collage.NanoDetHead
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM orchestration tests for [NanoDetRunner] using a fake [NanoDetRuntime] and
 * [NanoDetModelAssetSource]; no OpenCV natives are loaded. The real
 * preprocessing/forward is exercised only by the androidTest probe.
 */
class NanoDetRunnerTest {

    private class FakeAsset(
        val bytes: ByteArray,
        override val sha256: String = sha256Hex(bytes),
        override val byteSize: Long = bytes.size.toLong(),
        override val maxBytes: Long = bytes.size.toLong() + 16,
    ) : NanoDetModelAssetSource {
        var opens = 0
        override fun open(): InputStream {
            opens += 1
            return ByteArrayInputStream(bytes)
        }
    }

    private class FakeRuntime : NanoDetRuntime {
        var loads = 0
        var closed = false
        var closeCount = 0
        var maxConcurrent = 0
        var failure: Exception? = null
        var delayMs = 0L
        var heads: List<NanoDetHead> = zeroHeads()
        var forwardEntered: CountDownLatch? = null
        var releaseForward: CountDownLatch? = null
        private var active = 0

        override fun load(modelPath: String) {
            loads += 1
        }

        override fun forward(image: NanoDetImage): List<NanoDetHead> {
            active += 1
            maxConcurrent = maxOf(maxConcurrent, active)
            try {
                forwardEntered?.countDown()
                releaseForward?.await()
                if (delayMs > 0) Thread.sleep(delayMs)
                failure?.let { throw it }
                return heads
            } finally {
                active -= 1
            }
        }

        override fun close() {
            closed = true
            closeCount += 1
        }
    }

    private fun image() = NanoDetImage(8, 8, IntArray(64) { 0xFF336699.toInt() })

    private fun tempDir(): File = Files.createTempDirectory("nanodet-runner").toFile()

    private fun assertUnavailable(result: NanoDetResult): String {
        assertTrue("expected Unavailable, got $result", result is NanoDetResult.Unavailable)
        return (result as NanoDetResult.Unavailable).reason
    }

    @Test
    fun materializesAndLoadsOnceAcrossDetects() {
        val asset = FakeAsset(ByteArray(4096) { 1 })
        val runtime = FakeRuntime()
        val runner = NanoDetRunner(asset, runtime, tempDir())
        try {
            runBlocking {
                val first = runner.detect(image())
                val second = runner.detect(image())
                assertTrue(first is NanoDetResult.Detections)
                assertTrue(second is NanoDetResult.Detections)
            }
            assertEquals(1, asset.opens)
            assertEquals(1, runtime.loads)
        } finally {
            runner.close()
        }
    }

    @Test
    fun checksumMismatchFailsClosedAndCleansPartials() {
        val dir = tempDir()
        val asset = FakeAsset(ByteArray(64) { 2 }, sha256 = "0".repeat(64))
        val runner = NanoDetRunner(asset, FakeRuntime(), dir)
        try {
            val reason = assertUnavailable(runBlocking { runner.detect(image()) })
            assertEquals("IllegalStateException", reason)
            assertFalse("cache must be cleaned", dir.resolve("nanodet.onnx").exists())
            assertFalse("partial must be cleaned", dir.resolve("nanodet.onnx.part").exists())
        } finally {
            runner.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizeStreamFailsClosedAndCleansPartials() {
        val dir = tempDir()
        val asset = FakeAsset(ByteArray(256) { 3 }, maxBytes = 100)
        val runner = NanoDetRunner(asset, FakeRuntime(), dir)
        try {
            assertEquals("IllegalStateException", assertUnavailable(runBlocking { runner.detect(image()) }))
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            runner.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun serializeConcurrentDetects() {
        val runtime = FakeRuntime().apply { delayMs = 60 }
        val runner = NanoDetRunner(FakeAsset(ByteArray(1024) { 4 }), runtime, tempDir())
        try {
            runBlocking {
                val first = async { runner.detect(image()) }
                val second = async { runner.detect(image()) }
                first.await()
                second.await()
            }
            assertEquals(1, runtime.maxConcurrent)
        } finally {
            runner.close()
        }
    }

    @Test
    fun malformedHeadsReturnInvalid() {
        val runtime = FakeRuntime().apply {
            heads = listOf(
                NanoDetHead("cls", intArrayOf(36, 64), FloatArray(36 * 64)),
                NanoDetHead("reg", intArrayOf(36, 32), FloatArray(36 * 32)),
            )
        }
        val runner = NanoDetRunner(FakeAsset(ByteArray(1024) { 5 }), runtime, tempDir())
        try {
            val result = runBlocking { runner.detect(image()) }
            assertTrue("expected Invalid, got $result", result is NanoDetResult.Invalid)
        } finally {
            runner.close()
        }
    }

    @Test
    fun cancellationRethrows() {
        val runtime = FakeRuntime().apply { failure = CancellationException("cancelled") }
        val runner = NanoDetRunner(FakeAsset(ByteArray(1024) { 6 }), runtime, tempDir())
        var cancelled = false
        try {
            runBlocking { runner.detect(image()) }
        } catch (_: CancellationException) {
            cancelled = true
        } finally {
            runner.close()
        }
        assertTrue("CancellationException must propagate", cancelled)
    }

    @Test
    fun failureIsRedacted() {
        val runtime = FakeRuntime().apply { failure = IllegalStateException("/secret/model/path") }
        val runner = NanoDetRunner(FakeAsset(ByteArray(1024) { 7 }), runtime, tempDir())
        try {
            val reason = assertUnavailable(runBlocking { runner.detect(image()) })
            assertEquals("IllegalStateException", reason)
            assertFalse("reason must not leak the message", reason.contains("secret"))
        } finally {
            runner.close()
        }
    }

    @Test
    fun closeDuringGatedForwardWaitsAndDoesNotInterrupt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = FakeRuntime().apply {
            forwardEntered = entered
            releaseForward = release
        }
        val dir = tempDir()
        val runner = NanoDetRunner(FakeAsset(ByteArray(1024) { 20 }), runtime, dir)

        var detected: NanoDetResult? = null
        val detectThread = thread { detected = runBlocking { runner.detect(image()) } }
        assertTrue("forward must be reached", entered.await(2, TimeUnit.SECONDS))

        val closeReturned = CountDownLatch(1)
        val closeThread = thread {
            runner.close()
            closeReturned.countDown()
        }
        assertFalse(
            "close must wait for the active forward",
            closeReturned.await(300, TimeUnit.MILLISECONDS),
        )

        release.countDown()
        detectThread.join(2_000)
        closeThread.join(2_000)

        assertTrue("in-flight detect must complete, not be interrupted", detected is NanoDetResult.Detections)
        assertTrue(runtime.closed)
        assertEquals(1, runtime.closeCount)
        assertFalse("cache must be deleted by close", dir.resolve("nanodet.onnx").exists())

        assertEquals("runner is closed", assertUnavailable(runBlocking { runner.detect(image()) }))
        dir.deleteRecursively()
    }

    @Test
    fun closeIsIdempotentAndClosesRuntimeCacheOnce() {
        val dir = tempDir()
        val runtime = FakeRuntime()
        val runner = NanoDetRunner(FakeAsset(ByteArray(1024) { 8 }), runtime, dir)
        runBlocking { runner.detect(image()) }
        assertTrue(dir.resolve("nanodet.onnx").exists())

        runner.close()
        runner.close()

        assertEquals(1, runtime.closeCount)
        assertFalse(dir.resolve("nanodet.onnx").exists())
        dir.deleteRecursively()
    }

    @Test
    fun detectAfterCloseIsUnavailable() {
        val runner = NanoDetRunner(FakeAsset(ByteArray(1024) { 9 }), FakeRuntime(), tempDir())
        runner.close()
        assertEquals("runner is closed", assertUnavailable(runBlocking { runner.detect(image()) }))
    }

    private companion object {
        fun zeroHeads(): List<NanoDetHead> {
            val n = 36
            return listOf(
                NanoDetHead("cls", intArrayOf(n, NanoDetDecoder.CLASS_COUNT), FloatArray(n * NanoDetDecoder.CLASS_COUNT)),
                NanoDetHead("reg", intArrayOf(n, NanoDetDecoder.REG_CHANNELS), FloatArray(n * NanoDetDecoder.REG_CHANNELS)),
            )
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
