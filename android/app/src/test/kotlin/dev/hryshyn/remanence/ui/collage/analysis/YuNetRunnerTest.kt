package dev.hryshyn.remanence.ui.collage.analysis

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM orchestration tests for [YuNetRunner] using a fake [YuNetRuntime] and
 * [ModelAssetSource]; no OpenCV natives are loaded. The real FaceDetectorYN
 * path is exercised only by the A3C androidTest probe.
 */
class YuNetRunnerTest {

    private class FakeAsset(
        val bytes: ByteArray,
        override val sha256: String = sha256Hex(bytes),
        override val byteSize: Long = bytes.size.toLong(),
        override val maxBytes: Long = bytes.size.toLong() + 16,
    ) : ModelAssetSource {
        var opens = 0
        override fun open(): InputStream {
            opens += 1
            return ByteArrayInputStream(bytes)
        }
    }

    private class FakeRuntime : YuNetRuntime {
        var loads = 0
        var closed = false
        var closeCount = 0
        var maxConcurrent = 0
        var failure: Exception? = null
        var delayMs = 0L
        var config: YuNetConfig? = null
        val longEdges = mutableListOf<Int>()
        var pass: (Int) -> YuNetPass = { YuNetPass(640, 640, 15, emptyList()) }
        var detectEntered: CountDownLatch? = null
        var releaseDetect: CountDownLatch? = null
        private var active = 0

        override fun load(modelPath: String, config: YuNetConfig) {
            loads += 1
            this.config = config
        }

        override fun detect(image: YuNetImage, longEdge: Int): YuNetPass {
            active += 1
            maxConcurrent = maxOf(maxConcurrent, active)
            try {
                detectEntered?.countDown()
                releaseDetect?.await()
                if (delayMs > 0) Thread.sleep(delayMs)
                failure?.let { throw it }
                longEdges += longEdge
                return pass(longEdge)
            } finally {
                active -= 1
            }
        }

        override fun close() {
            closed = true
            closeCount += 1
        }
    }

    private fun image() = YuNetImage(8, 8, IntArray(64) { 0xFF336699.toInt() })

    private fun tempDir(): File = Files.createTempDirectory("yunet-runner").toFile()

    private fun assertUnavailable(result: YuNetResult): String {
        assertTrue("expected Unavailable, got $result", result is YuNetResult.Unavailable)
        return (result as YuNetResult.Unavailable).reason
    }

    @Test
    fun materializesLoadsOnceAndRunsTwoPassesInOrder() {
        val asset = FakeAsset(ByteArray(4096) { 1 })
        val runtime = FakeRuntime()
        val runner = YuNetRunner(asset, runtime, tempDir())
        try {
            runBlocking {
                assertTrue(runner.detect(image()) is YuNetResult.Faces)
                assertTrue(runner.detect(image()) is YuNetResult.Faces)
            }
            assertEquals(1, asset.opens)
            assertEquals(1, runtime.loads)
            assertEquals(listOf(640, 960, 640, 960), runtime.longEdges)
            assertNotNull(runtime.config)
            val config = runtime.config!!
            assertEquals(0.65f, config.scoreThreshold, 0f)
            assertEquals(0.3f, config.nmsThreshold, 0f)
            assertEquals(5000, config.topK)
            assertEquals(listOf(640, 960), config.longEdges)
        } finally {
            runner.close()
        }
    }

    @Test
    fun mergesFacesAcrossPassesAndDeduplicates() {
        val runtime = FakeRuntime().apply {
            pass = { edge ->
                val x = if (edge == 640) 64f else 80f
                YuNetPass(640, 640, 15, listOf(row(x, 0.9f)))
            }
        }
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 2 }), runtime, tempDir())
        try {
            val result = runBlocking { runner.detect(image()) }
            assertTrue(result is YuNetResult.Faces)
            val faces = (result as YuNetResult.Faces)
            assertEquals(1, faces.faces.size)
            assertEquals(2, faces.passShapes.size)
            assertEquals(listOf(640, 640, 15), faces.passShapes[0])
        } finally {
            runner.close()
        }
    }

    @Test
    fun malformedRowsReturnInvalid() {
        val runtime = FakeRuntime().apply {
            pass = { YuNetPass(640, 640, 14, listOf(FloatArray(14))) }
        }
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 3 }), runtime, tempDir())
        try {
            val result = runBlocking { runner.detect(image()) }
            assertTrue("expected Invalid, got $result", result is YuNetResult.Invalid)
        } finally {
            runner.close()
        }
    }

    @Test
    fun checksumMismatchFailsClosedAndCleansPartials() {
        val dir = tempDir()
        val asset = FakeAsset(ByteArray(64) { 4 }, sha256 = "0".repeat(64))
        val runner = YuNetRunner(asset, FakeRuntime(), dir)
        try {
            assertEquals("IllegalStateException", assertUnavailable(runBlocking { runner.detect(image()) }))
            assertFalse("cache must be cleaned", dir.resolve("yunet.onnx").exists())
            assertFalse("partial must be cleaned", dir.resolve("yunet.onnx.part").exists())
        } finally {
            runner.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizeStreamFailsClosedAndCleansPartials() {
        val dir = tempDir()
        val asset = FakeAsset(ByteArray(256) { 5 }, maxBytes = 100)
        val runner = YuNetRunner(asset, FakeRuntime(), dir)
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
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 6 }), runtime, tempDir())
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
    fun cancellationRethrows() {
        val runtime = FakeRuntime().apply { failure = CancellationException("cancelled") }
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 7 }), runtime, tempDir())
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
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 8 }), runtime, tempDir())
        try {
            val reason = assertUnavailable(runBlocking { runner.detect(image()) })
            assertEquals("IllegalStateException", reason)
            assertFalse("reason must not leak the message", reason.contains("secret"))
        } finally {
            runner.close()
        }
    }

    @Test
    fun closeDuringGatedDetectWaitsAndDoesNotInterrupt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = FakeRuntime().apply {
            detectEntered = entered
            releaseDetect = release
        }
        val dir = tempDir()
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 20 }), runtime, dir)

        var detected: YuNetResult? = null
        val detectThread = thread { detected = runBlocking { runner.detect(image()) } }
        assertTrue("detect must be reached", entered.await(2, TimeUnit.SECONDS))

        val closeReturned = CountDownLatch(1)
        val closeThread = thread {
            runner.close()
            closeReturned.countDown()
        }
        assertFalse(
            "close must wait for the active detect",
            closeReturned.await(300, TimeUnit.MILLISECONDS),
        )

        release.countDown()
        detectThread.join(2_000)
        closeThread.join(2_000)

        assertTrue("in-flight detect must complete, not be interrupted", detected is YuNetResult.Faces)
        assertTrue(runtime.closed)
        assertEquals(1, runtime.closeCount)
        assertFalse("cache must be deleted by close", dir.resolve("yunet.onnx").exists())

        assertEquals("runner is closed", assertUnavailable(runBlocking { runner.detect(image()) }))
        dir.deleteRecursively()
    }

    @Test
    fun closeIsIdempotentAndClosesRuntimeCacheOnce() {
        val dir = tempDir()
        val runtime = FakeRuntime()
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 9 }), runtime, dir)
        runBlocking { runner.detect(image()) }
        assertTrue(dir.resolve("yunet.onnx").exists())

        runner.close()
        runner.close()

        assertEquals(1, runtime.closeCount)
        assertFalse(dir.resolve("yunet.onnx").exists())
        dir.deleteRecursively()
    }

    @Test
    fun detectAfterCloseIsUnavailable() {
        val runner = YuNetRunner(FakeAsset(ByteArray(1024) { 10 }), FakeRuntime(), tempDir())
        runner.close()
        assertEquals("runner is closed", assertUnavailable(runBlocking { runner.detect(image()) }))
    }

    private companion object {
        fun row(x: Float, score: Float): FloatArray {
            val values = FloatArray(15)
            values[0] = x
            values[1] = x
            values[2] = 128f
            values[3] = 128f
            values[14] = score
            return values
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
