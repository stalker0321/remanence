package dev.hryshyn.remanence.wiring

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageAnalysis
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.NanoDetDecoder
import dev.hryshyn.remanence.ui.collage.NanoDetHead
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageImage
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageMaps
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRuntime
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetImage
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.YuNetConfig
import dev.hryshyn.remanence.ui.collage.analysis.YuNetImage
import dev.hryshyn.remanence.ui.collage.analysis.YuNetPass
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRuntime
import dev.hryshyn.remanence.ui.create.RetainedOriginalHolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production holder/decoder/asset/cache/source wiring with host-safe runtimes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductionCollageCropSourceFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheBase = File(context.cacheDir, "collage")

    @After
    fun cleanCache() {
        cacheBase.deleteRecursively()
    }

    @Test
    fun productionFactoryOpensBundledModelsAndBuildsV5Candidates() {
        cacheBase.deleteRecursively()
        val field = AvailableFieldRuntime()
        val nano = AvailableNanoRuntime()
        val yunet = AvailableYuNetRuntime()
        val holder = RetainedOriginalHolder()
        val originals = listOf(
            jpeg(0xFF315A8A.toInt()),
            jpeg(0xFF80502D.toInt()),
            jpeg(0xFF47733A.toInt()),
        )
        val photos = originals.mapIndexed { index, bytes ->
            val hash = sha256Hex(bytes)
            val contentId = CapsulePhotoIdentity.contentIdFor(hash)
            assertTrue(holder.retain(contentId, bytes, hash) is RetainedOriginalHolder.RetainResult.Retained)
            GeneratorExpression.PhotoRef(contentId, index, 400, 400, hash)
        }
        val source = requireNotNull(
            productionCollageCropSourceFactory(
                appContext = context,
                fieldRuntime = { field },
                nanoDetRuntime = { nano },
                yuNetRuntime = { yunet },
            ).create(holder),
        )
        try {
            val result = runBlocking {
                CollageCandidateProvider(
                    source,
                    CollageRenderer.TextMeasurer { error("no text in this fixture") },
                ).candidates(
                    GeneratorExpression.GeneratorInput("owner", 1L, photos, null, null),
                    note = null,
                    track = null,
                )
            }
            assertTrue("expected candidates, got $result", result is CollageProviderResult.Candidates)
            val candidates = (result as CollageProviderResult.Candidates).candidates
            // T151: the provider enumerates every grammar-5 branch per style;
            // the real solver decides how many survive, but all three styles are
            // offered and the analysis runners still run exactly once per photo.
            assertTrue("expected enumerated candidates, got ${candidates.size}", candidates.size > 3)
            assertEquals(3, candidates.map { it.styleId }.distinct().size)
            assertEquals(3, field.analyzes)
            assertEquals(3, nano.forwards)
            assertEquals(6, yunet.detects)
            assertEquals(1, nano.loads)
            assertEquals(1, yunet.loads)
            assertEquals(3_800_954L, nano.modelBytes)
            assertEquals(232_589L, yunet.modelBytes)
        } finally {
            source.close()
            source.close()
            holder.close()
        }
        assertEquals(1, field.closes)
        assertEquals(1, nano.closes)
        assertEquals(1, yunet.closes)
    }

    private class AvailableFieldRuntime : FieldStageRuntime {
        var analyzes = 0
        var closes = 0
        override fun analyze(image: FieldStageImage): FieldStageMaps {
            analyzes += 1
            val cells = CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE
            return FieldStageMaps(
                image.width,
                image.height,
                DoubleArray(cells) { 1.0 },
                DoubleArray(cells) { 1.0 },
                DoubleArray(cells) { 1.0 },
                DoubleArray(cells) { 1.0 },
                emptyList(),
            )
        }
        override fun close() {
            closes += 1
        }
    }

    private class AvailableNanoRuntime : NanoDetRuntime {
        var loads = 0
        var forwards = 0
        var closes = 0
        var modelBytes = 0L
        override fun load(modelPath: String) {
            loads += 1
            modelBytes = File(modelPath).length()
        }
        override fun forward(image: NanoDetImage): List<NanoDetHead> {
            forwards += 1
            return listOf(
                head("cls52", 52 * 52, NanoDetDecoder.CLASS_COUNT),
                head("reg52", 52 * 52, NanoDetDecoder.REG_CHANNELS),
                head("cls26", 26 * 26, NanoDetDecoder.CLASS_COUNT),
                head("reg26", 26 * 26, NanoDetDecoder.REG_CHANNELS),
                head("cls13", 13 * 13, NanoDetDecoder.CLASS_COUNT),
                head("reg13", 13 * 13, NanoDetDecoder.REG_CHANNELS),
            )
        }
        override fun close() {
            closes += 1
        }

        private fun head(name: String, spatial: Int, channels: Int) =
            NanoDetHead(name, intArrayOf(1, spatial, channels), FloatArray(spatial * channels))
    }

    private class AvailableYuNetRuntime : YuNetRuntime {
        var loads = 0
        var detects = 0
        var closes = 0
        var modelBytes = 0L
        override fun load(modelPath: String, config: YuNetConfig) {
            loads += 1
            modelBytes = File(modelPath).length()
        }
        override fun detect(image: YuNetImage, longEdge: Int): YuNetPass {
            detects += 1
            return YuNetPass(image.width, image.height, 15, emptyList())
        }
        override fun close() {
            closes += 1
        }
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
