package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.CollageAnalysis
import dev.hryshyn.remanence.ui.collage.NormBox
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
 * JVM orchestration tests for [FieldStageRunner] using a fake
 * [FieldStageRuntime]; no OpenCV natives are loaded. The real pixel pipeline
 * is exercised only by the A4b androidTest probe.
 */
class FieldStageRunnerTest {

    private class FakeRuntime : FieldStageRuntime {
        var analyzes = 0
        var closed = false
        var closeCount = 0
        var maxConcurrent = 0
        var failure: Exception? = null
        var delayMs = 0L
        var maps: () -> FieldStageMaps = { validMaps() }
        var analyzeEntered: CountDownLatch? = null
        var releaseAnalyze: CountDownLatch? = null
        private var active = 0

        override fun analyze(image: FieldStageImage): FieldStageMaps {
            active += 1
            maxConcurrent = maxOf(maxConcurrent, active)
            try {
                analyzeEntered?.countDown()
                releaseAnalyze?.await()
                if (delayMs > 0) Thread.sleep(delayMs)
                failure?.let { throw it }
                analyzes += 1
                return maps()
            } finally {
                active -= 1
            }
        }

        override fun close() {
            closed = true
            closeCount += 1
        }
    }

    private fun image() = FieldStageImage(8, 8, IntArray(64) { 0xFF336699.toInt() })

    private fun assertUnavailable(result: FieldStageResult): String {
        assertTrue("expected Unavailable, got $result", result is FieldStageResult.Unavailable)
        return (result as FieldStageResult.Unavailable).reason
    }

    @Test
    fun analyzesOnceAndReturnsMapsPassthrough() {
        val runtime = FakeRuntime()
        val runner = FieldStageRunner(runtime)
        try {
            val result = runBlocking { runner.analyze(image()) }
            assertTrue(result is FieldStageResult.Maps)
            val maps = (result as FieldStageResult.Maps)
            assertEquals(1, runtime.analyzes)
            assertEquals(8, maps.maps.sourceWidth)
            assertEquals(8, maps.maps.sourceHeight)
            val cells = CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE
            assertEquals(cells, maps.maps.saliency.size)
            assertEquals(cells, maps.maps.edgeEnergy.size)
            assertEquals(cells, maps.maps.contrast.size)
            assertEquals(cells, maps.maps.tips.size)
            assertTrue(maps.elapsedMs >= 0)
        } finally {
            runner.close()
        }
    }

    @Test
    fun malformedMapSizeReturnsInvalid() {
        val runtime = FakeRuntime().apply {
            maps = { validMaps().copy(saliency = DoubleArray(100)) }
        }
        val runner = FieldStageRunner(runtime)
        try {
            val result = runBlocking { runner.analyze(image()) }
            assertTrue("expected Invalid, got $result", result is FieldStageResult.Invalid)
        } finally {
            runner.close()
        }
    }

    @Test
    fun nonFiniteMapsReturnInvalid() {
        val runtime = FakeRuntime().apply {
            maps = { validMaps().copy(edgeEnergy = DoubleArray(48 * 48) { Double.NaN }) }
        }
        val runner = FieldStageRunner(runtime)
        try {
            val result = runBlocking { runner.analyze(image()) }
            assertTrue("expected Invalid, got $result", result is FieldStageResult.Invalid)
        } finally {
            runner.close()
        }
    }

    @Test
    fun serializeConcurrentAnalyzes() {
        val runtime = FakeRuntime().apply { delayMs = 60 }
        val runner = FieldStageRunner(runtime)
        try {
            runBlocking {
                val first = async { runner.analyze(image()) }
                val second = async { runner.analyze(image()) }
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
        val runner = FieldStageRunner(runtime)
        var cancelled = false
        try {
            runBlocking { runner.analyze(image()) }
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
        val runner = FieldStageRunner(runtime)
        try {
            val reason = assertUnavailable(runBlocking { runner.analyze(image()) })
            assertEquals("IllegalStateException", reason)
            assertFalse("reason must not leak the message", reason.contains("secret"))
        } finally {
            runner.close()
        }
    }

    @Test
    fun closeDuringGatedAnalyzeWaitsAndDoesNotInterrupt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = FakeRuntime().apply {
            analyzeEntered = entered
            releaseAnalyze = release
        }
        val runner = FieldStageRunner(runtime)

        var analyzed: FieldStageResult? = null
        val analyzeThread = thread { analyzed = runBlocking { runner.analyze(image()) } }
        assertTrue("analyze must be reached", entered.await(2, TimeUnit.SECONDS))

        val closeReturned = CountDownLatch(1)
        val closeThread = thread {
            runner.close()
            closeReturned.countDown()
        }
        assertFalse(
            "close must wait for the active analyze",
            closeReturned.await(300, TimeUnit.MILLISECONDS),
        )

        release.countDown()
        analyzeThread.join(2_000)
        closeThread.join(2_000)

        assertTrue("in-flight analyze must complete, not be interrupted", analyzed is FieldStageResult.Maps)
        assertTrue(runtime.closed)
        assertEquals(1, runtime.closeCount)

        assertEquals("runner is closed", assertUnavailable(runBlocking { runner.analyze(image()) }))
    }

    @Test
    fun closeIsIdempotentAndClosesRuntimeOnce() {
        val runtime = FakeRuntime()
        val runner = FieldStageRunner(runtime)
        runBlocking { runner.analyze(image()) }

        runner.close()
        runner.close()

        assertEquals(1, runtime.closeCount)
    }

    @Test
    fun analyzeAfterCloseIsUnavailable() {
        val runner = FieldStageRunner(FakeRuntime())
        runner.close()
        assertEquals("runner is closed", assertUnavailable(runBlocking { runner.analyze(image()) }))
    }

    private companion object {
        fun validMaps(): FieldStageMaps {
            val cells = CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE
            return FieldStageMaps(
                sourceWidth = 8,
                sourceHeight = 8,
                saliency = DoubleArray(cells) { 0.5 },
                edgeEnergy = DoubleArray(cells) { 0.25 },
                contrast = DoubleArray(cells) { 0.75 },
                tips = DoubleArray(cells) { 0.0 },
                tipBoxes = listOf(NormBox(0.2, 0.6, 0.03, 0.04)),
            )
        }
    }
}
