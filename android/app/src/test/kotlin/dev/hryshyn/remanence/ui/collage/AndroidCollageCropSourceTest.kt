package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageImage
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageMaps
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageResult
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRunner
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRuntime
import dev.hryshyn.remanence.ui.collage.analysis.ModelAssetSource
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetImage
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetResult
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRunner
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.YuNetImage
import dev.hryshyn.remanence.ui.collage.analysis.YuNetPass
import dev.hryshyn.remanence.ui.collage.analysis.YuNetResult
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRunner
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRuntime
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Focused fake tests for the non-production [AndroidCollageCropSource] seam:
 * one cached analysis across three style slot calls with distinct per-slot
 * solves, per-content-ID analyses, failure mapping to `null` (provider
 * `Unavailable`), cancellation propagation without caching, delivered-bytes
 * zeroization, and idempotent close releasing all three owned runners. No
 * OpenCV natives are loaded; runner fakes mirror the existing runner tests.
 * Never mounted in production; no Create wiring, no capability change.
 */
class AndroidCollageCropSourceTest {

    private class FakeAsset(val bytes: ByteArray) : ModelAssetSource {
        override val sha256: String = sha256Hex(bytes)
        override val byteSize: Long = bytes.size.toLong()
        override val maxBytes: Long = bytes.size.toLong() + 16
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    private class FakeFieldRuntime : FieldStageRuntime {
        var analyzes = 0
        var closeCount = 0
        var failure: Exception? = null
        var maps: () -> FieldStageMaps = { solverFriendlyMaps() }

        override fun analyze(image: FieldStageImage): FieldStageMaps {
            failure?.let { throw it }
            analyzes += 1
            return maps()
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeNanoRuntime : NanoDetRuntime {
        var forwards = 0
        var loads = 0
        var closeCount = 0
        var failure: Exception? = null
        var heads: () -> List<dev.hryshyn.remanence.ui.collage.NanoDetHead> = ::zeroHeads

        override fun load(modelPath: String) {
            loads += 1
        }

        override fun forward(image: NanoDetImage): List<dev.hryshyn.remanence.ui.collage.NanoDetHead> {
            failure?.let { throw it }
            forwards += 1
            return heads()
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeYuNetRuntime : YuNetRuntime {
        var detects = 0
        var loads = 0
        var closeCount = 0
        var failure: Exception? = null
        var pass: () -> YuNetPass = { YuNetPass(640, 640, 15, listOf(faceRow())) }

        override fun load(modelPath: String, config: dev.hryshyn.remanence.ui.collage.analysis.YuNetConfig) {
            loads += 1
        }

        override fun detect(image: YuNetImage, longEdge: Int): YuNetPass {
            failure?.let { throw it }
            detects += 1
            return pass()
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeBytes(val store: MutableMap<String, ByteArray>) : UprightBytesSource {
        val calls = mutableListOf<String>()
        val threads = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun bytesFor(contentId: String): ByteArray? {
            calls += contentId
            threads += Thread.currentThread().name
            return store[contentId]
        }
    }

    private class FakeDecoder(var image: FieldStageImage? = uprightImage()) : UprightImageDecoder {
        val seen = mutableListOf<ByteArray>()
        val threads = Collections.synchronizedList(mutableListOf<String>())
        override fun decode(jpeg: ByteArray): FieldStageImage? {
            seen += jpeg.copyOf()
            threads += Thread.currentThread().name
            return image
        }
    }

    private class Seam(
        val bytes: FakeBytes,
        val decoder: FakeDecoder,
        val field: FakeFieldRuntime,
        val nano: FakeNanoRuntime,
        val yunet: FakeYuNetRuntime,
        val source: AndroidCollageCropSource,
        val nanoDir: File,
        val yunetDir: File,
    )

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun seam(
        store: MutableMap<String, ByteArray> = mutableMapOf("p0" to ByteArray(64) { 7 }),
        decoder: FakeDecoder = FakeDecoder(),
        field: FakeFieldRuntime = FakeFieldRuntime(),
        nano: FakeNanoRuntime = FakeNanoRuntime(),
        yunet: FakeYuNetRuntime = FakeYuNetRuntime(),
        workerDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    ): Seam {
        val bytes = FakeBytes(store)
        val nanoDir = tempDir("seam-nanodet")
        val yunetDir = tempDir("seam-yunet")
        val source = AndroidCollageCropSource(
            bytesSource = bytes,
            imageDecoder = decoder,
            fieldRunner = FieldStageRunner(field),
            nanoDetRunner = NanoDetRunner(FakeAsset(ByteArray(128) { 1 }), nano, nanoDir),
            yuNetRunner = YuNetRunner(FakeAsset(ByteArray(128) { 2 }), yunet, yunetDir),
            workerDispatcher = workerDispatcher ?: kotlinx.coroutines.Dispatchers.IO,
        )
        return Seam(bytes, decoder, field, nano, yunet, source, nanoDir, yunetDir)
    }

    private fun styleSlots(contentId: String) = listOf(
        CollageCropRequest(contentId, 340, 248),
        CollageCropRequest(contentId, 167, 248),
        CollageCropRequest(contentId, 340, 248, zooms = listOf(1.0)),
    )

    private fun assertValidWindow(window: GeneratorExpression.CropWindow) {
        assertTrue("x non-negative", window.x >= 0)
        assertTrue("y non-negative", window.y >= 0)
        assertTrue("positive size", window.width > 0 && window.height > 0)
        assertTrue("x + width in range", window.x.toLong() + window.width <= 10_000L)
        assertTrue("y + height in range", window.y.toLong() + window.height <= 10_000L)
    }

    @Test
    fun telemetryCacheReadNeverTriggersIoOrInferenceAndRequiresExactDimensions() {
        val keyed = seam()
        try {
            assertNull(keyed.source.cachedAnalysisFor("p0", 1000, 1000))
            assertTrue(keyed.bytes.calls.isEmpty())
            val loaded = runBlocking { keyed.source.analysisSource().analysisFor("p0") }!!
            val calls = keyed.bytes.calls.size
            val forwards = keyed.nano.forwards
            val detects = keyed.yunet.detects
            assertTrue(loaded === keyed.source.cachedAnalysisFor("p0", loaded.width, loaded.height))
            assertNull(keyed.source.cachedAnalysisFor("p0", loaded.width + 1, loaded.height))
            assertNull(keyed.source.cachedAnalysisFor("missing", loaded.width, loaded.height))
            assertEquals(calls, keyed.bytes.calls.size); assertEquals(forwards, keyed.nano.forwards); assertEquals(detects, keyed.yunet.detects)
            keyed.source.close()
            assertNull(keyed.source.cachedAnalysisFor("p0", loaded.width, loaded.height))
        } finally { keyed.source.close(); keyed.nanoDir.deleteRecursively(); keyed.yunetDir.deleteRecursively() }
    }

    @Test
    fun oneAnalysisPerContentIdAcrossThreeStyleCallsWithDistinctSolves() {
        val store = mutableMapOf(
            "p0" to ByteArray(64) { 7 },
            "p1" to ByteArray(64) { 8 },
        )
        val keyed = seam(store)
        try {
            // Three style calls: same content IDs, different slot geometry
            // per style (mirrors CollageCandidateProvider's per-style requests).
            val first = runBlocking {
                keyed.source.cropsFor(
                    listOf(CollageCropRequest("p0", 340, 248), CollageCropRequest("p1", 340, 248)),
                )
            }
            val second = runBlocking {
                keyed.source.cropsFor(
                    listOf(CollageCropRequest("p0", 167, 248), CollageCropRequest("p1", 167, 248)),
                )
            }
            val third = runBlocking {
                keyed.source.cropsFor(
                    listOf(CollageCropRequest("p0", 200, 200), CollageCropRequest("p1", 200, 200)),
                )
            }
            for (crops in listOf(first, second, third)) {
                assertTrue("expected crops, got null", crops != null)
                assertEquals(setOf("p0", "p1"), crops!!.keys)
                crops.values.forEach(::assertValidWindow)
            }
            // Different slot geometry solves distinctly; repeating the same
            // slots solves identically from the cache.
            assertNotEquals(
                "landscape and portrait slots must solve distinctly",
                first!!.getValue("p0"),
                second!!.getValue("p0"),
            )
            val repeat = runBlocking {
                keyed.source.cropsFor(
                    listOf(CollageCropRequest("p0", 340, 248), CollageCropRequest("p1", 340, 248)),
                )
            }
            assertEquals(first.getValue("p0"), repeat!!.getValue("p0"))
            // Exactly one runner-level analysis per content ID across all
            // calls (YuNet internally runs its two long-edge passes per
            // analysis, hence four runtime detects for two IDs).
            assertEquals(2, keyed.field.analyzes)
            assertEquals(2, keyed.nano.forwards)
            assertEquals(4, keyed.yunet.detects)
            assertEquals(listOf("p0", "p1"), keyed.bytes.calls)
            // Delivered bytes are zeroized once decoded on every path, after
            // the decoder saw the live bytes.
            assertTrue(store.getValue("p0").all { it == 0.toByte() })
            assertTrue(store.getValue("p1").all { it == 0.toByte() })
            assertTrue(keyed.decoder.seen.all { seen -> seen.any { it != 0.toByte() } })
        } finally {
            keyed.source.close()
        }
    }

    @Test
    fun producerMatrixAndPlacementsShareOneAnalysisPerPhoto() {
        val store = mutableMapOf(
            "p0" to ByteArray(64) { 7 },
            "p1" to ByteArray(64) { 8 },
            "p2" to ByteArray(64) { 9 },
        )
        val keyed = seam(store)
        try {
            val descriptors = listOf(
                PhotoDescriptor("p0", 1000, 1000, true),
                PhotoDescriptor("p1", 1000, 1000, true),
                PhotoDescriptor("p2", 1000, 1000, true),
            )
            val loaded = runBlocking {
                AllFamilyCandidateProducer.loadPhotos(descriptors, keyed.source.analysisSource())
            }
            assertNotNull("shared analysis feeder must load every photo", loaded)
            val input = AllFamilyInput("cache", "Alice", "a short note", true, true, loaded!!)
            val outcome = runBlocking {
                AllFamilyCandidateProducer(AndroidCollageCropMatrixSource()).produce(input)
            }
            assertTrue("must return a candidate outcome", outcome is AllFamilyOutcome.Candidates)
            // The feasibility matrix and every placement solve reused the single
            // cached analysis per photo: exactly one detector run each.
            assertEquals(3, keyed.field.analyzes)
            assertEquals(3, keyed.nano.forwards)
            assertEquals(6, keyed.yunet.detects)
            assertEquals(listOf("p0", "p1", "p2"), keyed.bytes.calls)
        } finally {
            keyed.source.close()
        }
    }

    @Test
    fun missingBytesReturnNull() {        val seam = seam(mutableMapOf())
        try {
            assertNull(runBlocking { seam.source.cropsFor(styleSlots("p0")) })
            assertEquals(0, seam.field.analyzes)
        } finally {
            seam.source.close()
        }
    }

    @Test
    fun undecodableBytesReturnNull() {
        val seam = seam(decoder = FakeDecoder(null))
        try {
            assertNull(runBlocking { seam.source.cropsFor(styleSlots("p0")) })
            assertEquals(0, seam.field.analyzes)
        } finally {
            seam.source.close()
        }
    }

    @Test
    fun fieldFailureReturnsNullWithoutCaching() {
        val field = FakeFieldRuntime().apply { failure = IllegalStateException("boom") }
        val seam = seam(field = field)
        try {
            assertNull(runBlocking { seam.source.cropsFor(styleSlots("p0")) })
            field.failure = null
            val crops = runBlocking { seam.source.cropsFor(styleSlots("p0")) }
            assertTrue("expected crops after recovery, got null", crops != null)
            assertEquals(1, field.analyzes)
        } finally {
            seam.source.close()
        }
    }

    @Test
    fun nanoDetInvalidReturnsNull() {
        val nano = FakeNanoRuntime().apply {
            heads = {
                listOf(
                    dev.hryshyn.remanence.ui.collage.NanoDetHead("cls", intArrayOf(36, 64), FloatArray(36 * 64)),
                    dev.hryshyn.remanence.ui.collage.NanoDetHead("reg", intArrayOf(36, 32), FloatArray(36 * 32)),
                )
            }
        }
        val seam = seam(nano = nano)
        try {
            assertNull(runBlocking { seam.source.cropsFor(styleSlots("p0")) })
        } finally {
            seam.source.close()
        }
    }

    @Test
    fun yuNetInvalidReturnsNull() {
        val yunet = FakeYuNetRuntime().apply {
            pass = { YuNetPass(640, 640, 14, listOf(FloatArray(14))) }
        }
        val seam = seam(yunet = yunet)
        try {
            assertNull(runBlocking { seam.source.cropsFor(styleSlots("p0")) })
        } finally {
            seam.source.close()
        }
    }

    @Test
    fun cancellationPropagatesAndCachesNothing() {
        val field = FakeFieldRuntime().apply {
            failure = CancellationException("cancelled")
        }
        val seam = seam(field = field)
        var cancelled = false
        try {
            try {
                runBlocking { seam.source.cropsFor(styleSlots("p0")) }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue("CancellationException must propagate", cancelled)
            assertEquals(0, field.analyzes)
            field.failure = null
            val crops = runBlocking { seam.source.cropsFor(styleSlots("p0")) }
            assertTrue("expected crops after cancel, got null", crops != null)
            assertEquals(1, field.analyzes)
        } finally {
            seam.source.close()
        }
    }

    @Test
    fun rejectedSlotRecordsFrozenReasonAndStaysOmitted() {
        // A 100x100 source with the 340x248 slot fails only the frozen
        // resolution floor, so the slot is omitted (fail-closed) and the
        // dominant reason is preserved for diagnostics.
        val small = FieldStageImage(100, 100, IntArray(10_000) { 0xFF336699.toInt() })
        val yunet = FakeYuNetRuntime().apply { pass = { YuNetPass(640, 640, 15, emptyList()) } }
        val seam = seam(decoder = FakeDecoder(small), yunet = yunet)
        try {
            val crops = runBlocking {
                seam.source.cropsFor(listOf(CollageCropRequest("p0", 340, 248)))
            }
            assertTrue("rejected slot must be omitted", crops != null && crops.isEmpty())
            assertEquals(
                listOf(CollageSlotRejection("p0", 340, 248, "rect", CropRejectReason.RESOLUTION_TOO_LOW)),
                seam.source.lastCropRejections,
            )
        } finally {
            seam.source.close()
        }
        assertTrue("close must clear the diagnostic surface", seam.source.lastCropRejections.isEmpty())
    }

    @Test
    fun closeIsIdempotentAndReleasesAllRunners() {
        val seam = seam()
        runBlocking { seam.source.cropsFor(styleSlots("p0")) }
        seam.source.close()
        seam.source.close()
        assertEquals(1, seam.field.closeCount)
        assertEquals(1, seam.nano.closeCount)
        assertEquals(1, seam.yunet.closeCount)
        assertNull(runBlocking { seam.source.cropsFor(styleSlots("p0")) })
    }

    private companion object {
        fun uprightImage() = FieldStageImage(1000, 1000, IntArray(1_000_000) { 0xFF336699.toInt() })

        fun solverFriendlyMaps(): FieldStageMaps {
            val cells = CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE
            return FieldStageMaps(
                sourceWidth = 1000,
                sourceHeight = 1000,
                saliency = DoubleArray(cells) { 1.0 },
                edgeEnergy = DoubleArray(cells) { 1.0 },
                contrast = DoubleArray(cells) { 1.0 },
                tips = DoubleArray(cells) { 1.0 },
                tipBoxes = emptyList(),
            )
        }

        fun faceRow(): FloatArray {
            val values = FloatArray(15)
            values[0] = 64f
            values[1] = 64f
            values[2] = 128f
            values[3] = 128f
            values[14] = 0.9f
            return values
        }

        fun zeroHeads(): List<dev.hryshyn.remanence.ui.collage.NanoDetHead> {
            val n = 36
            return listOf(
                dev.hryshyn.remanence.ui.collage.NanoDetHead(
                    "cls",
                    intArrayOf(n, dev.hryshyn.remanence.ui.collage.NanoDetDecoder.CLASS_COUNT),
                    FloatArray(n * dev.hryshyn.remanence.ui.collage.NanoDetDecoder.CLASS_COUNT),
                ),
                dev.hryshyn.remanence.ui.collage.NanoDetHead(
                    "reg",
                    intArrayOf(n, dev.hryshyn.remanence.ui.collage.NanoDetDecoder.REG_CHANNELS),
                    FloatArray(n * dev.hryshyn.remanence.ui.collage.NanoDetDecoder.REG_CHANNELS),
                ),
            )
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun decodeAndAnalysisRunOffCallerWhileFetchAndCacheStayOnCaller() {
        // Lane proof: bytes fetch stays on the caller lane (holder map has
        // no lock), decode + first-run materialize/load + analysis run on
        // the injected worker, publication + result return to the caller.
        val callerThread = Thread.currentThread().name
        val exec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "analysis-worker").also { it.isDaemon = true }
        }
        try {
            val seam = seam(workerDispatcher = exec.asCoroutineDispatcher())
            try {
                val analysis = runBlocking {
                    seam.source.analysisSource().analysisFor("p0")
                }
                assertTrue("expected an analysis", analysis != null)
                assertTrue(
                    "bytes fetch stays on caller, saw ${seam.bytes.threads}",
                    seam.bytes.threads.isNotEmpty() &&
                        seam.bytes.threads.all { it.substringBefore(" ") == callerThread.substringBefore(" ") },
                )
                assertTrue(
                    "decode runs on worker, saw ${seam.decoder.threads}",
                    seam.decoder.threads.isNotEmpty() &&
                        seam.decoder.threads.all { it.startsWith("analysis-worker") },
                )
                assertTrue(
                    "no worker stage runs on caller",
                    seam.decoder.threads.none { it.substringBefore(" ") == callerThread },
                )
            } finally {
                seam.source.close()
            }
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun warmCacheAvoidsReloading() {
        val seam = seam()
        try {
            assertTrue(
                "expected first analysis",
                runBlocking { seam.source.analysisSource().analysisFor("p0") } != null,
            )
            assertEquals(1, seam.nano.loads)
            assertEquals(1, seam.yunet.loads)
            val bytesCalls = seam.bytes.calls.size
            val decodes = seam.decoder.seen.size
            // Same content: cache serves, no re-read, no re-decode...
            assertTrue(
                "expected cached analysis",
                runBlocking { seam.source.analysisSource().analysisFor("p0") } != null,
            )
            assertEquals(bytesCalls, seam.bytes.calls.size)
            assertEquals(decodes, seam.decoder.seen.size)
            // ...and a second photo reuses the loaded models.
            val store2 = seam.bytes.store
            store2["p1"] = ByteArray(64) { 8 }
            assertTrue(
                "expected second analysis",
                runBlocking { seam.source.analysisSource().analysisFor("p1") } != null,
            )
            assertEquals(1, seam.nano.loads)
            assertEquals(1, seam.yunet.loads)
        } finally {
            seam.source.close()
        }
    }

    @Test
    fun cancelMidAnalysisPropagatesWithoutCaching() {
        // Deterministic pause: a blocking decoder holds the worker while
        // the caller cancels; nothing may be cached and bytes are wiped.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var decodedBytes: ByteArray? = null
        val gatedDecoder = object : UprightImageDecoder {
            override fun decode(jpeg: ByteArray): FieldStageImage? {
                decodedBytes = jpeg
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return uprightImage()
            }
        }
        val workerExec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "analysis-cancel").also { it.isDaemon = true }
        }
        try {
            val bytes = FakeBytes(mutableMapOf("p0" to ByteArray(64) { 7 }))
            val nanoDir = tempDir("seam-nanodet-c")
            val yunetDir = tempDir("seam-yunet-c")
            val field = FakeFieldRuntime()
            val nano = FakeNanoRuntime()
            val yunet = FakeYuNetRuntime()
            val gatedSource = AndroidCollageCropSource(
                bytesSource = bytes,
                imageDecoder = gatedDecoder,
                fieldRunner = FieldStageRunner(field),
                nanoDetRunner = NanoDetRunner(FakeAsset(ByteArray(128) { 1 }), nano, nanoDir),
                yuNetRunner = YuNetRunner(FakeAsset(ByteArray(128) { 2 }), yunet, yunetDir),
                workerDispatcher = workerExec.asCoroutineDispatcher(),
            )
            try {
                runBlocking {
                    val job = async(kotlinx.coroutines.Dispatchers.Default) { gatedSource.analysisSource().analysisFor("p0") }
                    assertTrue("worker must reach decode", entered.await(10, TimeUnit.SECONDS))
                    job.cancel()
                    release.countDown()
                    try {
                        job.await()
                        fail("expected CancellationException")
                    } catch (_: CancellationException) {
                        // Expected: cancellation propagates, never null.
                    }
                }
                val captured = requireNotNull(decodedBytes) { "decoder must have run" }
                assertTrue("original must be zeroized on cancel", captured.all { it == 0.toByte() })
                val callsBefore = bytes.calls.size
                runBlocking { gatedSource.analysisSource().analysisFor("p0") }
                assertTrue(
                    "cancelled analysis must not cache (refetch expected)",
                    bytes.calls.size > callsBefore,
                )
            } finally {
                gatedSource.close()
            }
        } finally {
            workerExec.shutdownNow()
        }
    }

    @Test
    fun closeWhileWorkerSuspendedNeverPublishesNorReusesRuntime() {
        // Close lands while the worker is gated in decode: the closed
        // runners must fail the analysis closed (no reuse), nothing may
        // be cached, and post-close calls stay fail-closed without crash.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gatedDecoder = object : UprightImageDecoder {
            override fun decode(jpeg: ByteArray): FieldStageImage? {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return uprightImage()
            }
        }
        val workerExec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "analysis-close").also { it.isDaemon = true }
        }
        try {
            val bytes = FakeBytes(mutableMapOf("p0" to ByteArray(64) { 7 }))
            val nanoDir = tempDir("seam-nanodet-x")
            val yunetDir = tempDir("seam-yunet-x")
            val gatedSource = AndroidCollageCropSource(
                bytesSource = bytes,
                imageDecoder = gatedDecoder,
                fieldRunner = FieldStageRunner(FakeFieldRuntime()),
                nanoDetRunner = NanoDetRunner(
                    FakeAsset(ByteArray(128) { 1 }),
                    FakeNanoRuntime(),
                    nanoDir,
                ),
                yuNetRunner = YuNetRunner(
                    FakeAsset(ByteArray(128) { 2 }),
                    FakeYuNetRuntime(),
                    yunetDir,
                ),
                workerDispatcher = workerExec.asCoroutineDispatcher(),
            )
            runBlocking {
                val job = async(kotlinx.coroutines.Dispatchers.Default) { gatedSource.analysisSource().analysisFor("p0") }
                assertTrue("worker must reach decode", entered.await(10, TimeUnit.SECONDS))
                gatedSource.close()
                release.countDown()
                assertNull("closed source must not deliver analysis", job.await())
            }
            val callsBefore = bytes.calls.size
            assertNull(
                "post-close analysis stays fail-closed without reuse",
                runBlocking { gatedSource.analysisSource().analysisFor("p0") },
            )
            assertTrue(
                "closed source must not cache (refetch expected)",
                bytes.calls.size > callsBefore,
            )
        } finally {
            workerExec.shutdownNow()
        }
    }
}
