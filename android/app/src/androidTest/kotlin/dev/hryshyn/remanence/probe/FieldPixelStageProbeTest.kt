package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.ui.collage.FieldAggregator
import dev.hryshyn.remanence.ui.collage.ImportFieldPrimitives
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageImage
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageResult
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRunner
import dev.hryshyn.remanence.ui.collage.analysis.FieldUprightDecoder
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvFieldStage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * V5-A4b.1 pixel field-stage probe — DIAGNOSTIC ONLY, NOT DEVICE ACCEPTANCE
 * EVIDENCE.
 *
 * It runs the real [OpenCvFieldStage] (frozen `import-field-3.3` lines 50-73:
 * spectral-residual saliency, local contrast, Sobel edges, Canny contour tips)
 * over a deterministic synthetic image and composes the resulting maps with
 * empty detector outputs into `ImportFieldPrimitives` for the existing
 * [FieldAggregator]. It records dimensions, per-map extrema, tip/region
 * counts, and timings only: never file paths, never image bytes, never
 * private content. Every Mat is released and the runner closed in `finally`.
 *
 * Explicitly NOT verified here (stays OPEN until the A4b.2 parity pass):
 * Python/cv2/device numeric parity (functional equivalence only, no
 * bit-exact claim), the two-ABI physical-device matrix (arm64-v8a plus one
 * more ABI), and any production wiring (no detectors, no
 * `AndroidCollageCropSource`, no Create, no capability change).
 */
@RunWith(AndroidJUnit4::class)
class FieldPixelStageProbeTest {

    @Before
    fun setUp() {
        assertTrue("OpenCV native runtime failed to initialise", OpenCVLoader.initDebug())
    }

    @After
    fun tearDown() {
        // No shared state: the runner is closed per test in `finally`.
    }

    @Test
    fun fieldStageRunsRealPipelineAndLogsAllowedDiagnostics() {
        val runner = FieldStageRunner(OpenCvFieldStage())
        try {
            val image = syntheticUpright(320, 240)
            val started = System.nanoTime()
            val result = runBlocking { runner.analyze(image) }
            assertTrue("expected Maps, got $result", result is FieldStageResult.Maps)
            val maps = (result as FieldStageResult.Maps).maps
            val cells = 48 * 48
            for ((name, field) in mapOf(
                "saliency" to maps.saliency,
                "edgeEnergy" to maps.edgeEnergy,
                "contrast" to maps.contrast,
                "tips" to maps.tips,
            )) {
                assertEquals("$name must be 48x48", cells, field.size)
                assertTrue("$name must be finite", field.all { it.isFinite() })
            }
            assertTrue("tip boxes must be finite", maps.tipBoxes.all {
                it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite()
            })
            Log.i(
                TAG,
                "field maps=${maps.sourceWidth}x${maps.sourceHeight} " +
                    "sal=[${extrema(maps.saliency)}] edge=[${extrema(maps.edgeEnergy)}] " +
                    "contrast=[${extrema(maps.contrast)}] tips=[${extrema(maps.tips)}] " +
                    "tipBoxes=${maps.tipBoxes.size} totalMs=${(System.nanoTime() - started) / 1_000_000}",
            )

            // Diagnostic-only composition into the frozen aggregator with
            // empty detector outputs (detectors stay unwired).
            val analysis = FieldAggregator.aggregate(
                ImportFieldPrimitives(
                    sourceWidth = maps.sourceWidth,
                    sourceHeight = maps.sourceHeight,
                    faces = emptyList(),
                    objects = emptyList(),
                    saliency = maps.saliency,
                    edgeEnergy = maps.edgeEnergy,
                    contrast = maps.contrast,
                    structureField = maps.tips,
                    tipBoxes = maps.tipBoxes,
                ),
            )
            assertEquals(maps.tipBoxes.size, analysis.protectedRegions.size)
            Log.i(
                TAG,
                "aggregated importance=[${extrema(analysis.importance)}] " +
                    "baseline=[${extrema(analysis.baselineField)}] " +
                    "objects=[${extrema(analysis.objectField)}] " +
                    "regions=${analysis.protectedRegions.size}",
            )
            Log.i(
                TAG,
                "parity OPEN: python/cv2/device numeric parity and the two-ABI " +
                    "physical-device matrix are not verified by this probe; " +
                    "functional equivalence only, no bit-exact claim",
            )
        } finally {
            runner.close()
        }
    }

    @Test
    fun uprightDecoderTransposesExifOrientation() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(syntheticUpright(64, 48).argb, 0, 64, 0, 0, 64, 48)
        val jpeg = ByteArrayOutputStream().use { stream ->
            assertTrue("synthetic JPEG encode failed", bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream))
            stream.toByteArray()
        }
        bitmap.recycle()
        val file = File.createTempFile("field-upright", ".jpg")
        try {
            file.writeBytes(jpeg)
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val upright = FieldUprightDecoder.decodeUpright(file.readBytes())
            assertEquals(48, upright.width)
            assertEquals(64, upright.height)
            assertEquals(48 * 64, upright.argb.size)
            Log.i(TAG, "upright dims=${upright.width}x${upright.height}")
        } finally {
            file.delete()
        }
    }

    /**
     * Fixed deterministic synthetic upright image (gradient plus
     * high-contrast bars to trigger edges and contour endpoints); no user
     * content, no bytes recorded.
     */
    private fun syntheticUpright(width: Int, height: Int): FieldStageImage {
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gradient = (x * 255 / width + y * 255 / height) / 2
                val bar = x in width / 4 until width / 4 + width / 16 ||
                    y in height / 2 until height / 2 + height / 16
                val v = if (bar) 255 - gradient else gradient
                argb[y * width + x] = (0xFF shl 24) or (v shl 16) or ((v / 2) shl 8) or (255 - v)
            }
        }
        return FieldStageImage(width, height, argb)
    }

    private fun extrema(field: DoubleArray): String {
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (v in field) {
            if (v < min) min = v
            if (v > max) max = v
        }
        return "%.4f..%.4f".format(min, max)
    }

    private companion object {
        const val TAG = "CollageFieldProbe"
    }
}
