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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        var closeCount = 0
        var failure: Exception? = null
        var heads: () -> List<dev.hryshyn.remanence.ui.collage.NanoDetHead> = ::zeroHeads

        override fun load(modelPath: String) = Unit

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
        var closeCount = 0
        var failure: Exception? = null
        var pass: () -> YuNetPass = { YuNetPass(640, 640, 15, listOf(faceRow())) }

        override fun load(modelPath: String, config: dev.hryshyn.remanence.ui.collage.analysis.YuNetConfig) = Unit

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
        override suspend fun bytesFor(contentId: String): ByteArray? {
            calls += contentId
            return store[contentId]
        }
    }

    private class FakeDecoder(var image: FieldStageImage? = uprightImage()) : UprightImageDecoder {
        val seen = mutableListOf<ByteArray>()
        override fun decode(jpeg: ByteArray): FieldStageImage? {
            seen += jpeg.copyOf()
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
    fun missingBytesReturnNull() {
        val seam = seam(mutableMapOf())
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
}
