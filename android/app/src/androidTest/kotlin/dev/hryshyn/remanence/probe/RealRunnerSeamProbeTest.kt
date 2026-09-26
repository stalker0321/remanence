package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageCropRequest
import dev.hryshyn.remanence.ui.collage.UprightImageDecoder
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageMaps
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageResult
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRunner
import dev.hryshyn.remanence.ui.collage.analysis.FieldUprightDecoder
import dev.hryshyn.remanence.ui.collage.analysis.ModelAssetSource
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetModelAssetSource
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetResult
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRunner
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvFieldStage
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvNanoDetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvYuNetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.YuNetResult
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRunner
import dev.hryshyn.remanence.ui.create.RetainedOriginalHolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * V5-A4B5 real-runner seam probe — DIAGNOSTIC FUNCTIONAL PROBE ONLY.
 *
 * Positive test builds a deterministic encoded JPEG fixture in-test
 * (gradient plus high-contrast bars; no downloads, no real photo), retains
 * it through the real [RetainedOriginalHolder] under its verified
 * hash/contentId binding, feeds `holder.asUprightBytesSource()` into a real
 * [AndroidCollageCropSource] (real [FieldUprightDecoder], [FieldStageRunner],
 * [NanoDetRunner], [YuNetRunner] with the pinned bundled models), requests
 * one valid slot, and REQUIRES a non-null result keyed by the requested
 * content ID with a valid in-bounds crop window. Null, `Unavailable`, or
 * `Invalid` at any stage fails loudly — a missing crop is never logged
 * as a pass. Timings are logged, never asserted.
 *
 * A second, deterministic negative test requests an absent content ID and
 * REQUIRES fail-closed `null` without invoking or claiming any runner
 * success.
 *
 * This is NOT Python/cv2 parity and requires a physical arm64 run plus a
 * separate second-ABI run before any claim; a single emulator pass proves
 * only that the seam executes end to end. Logs carry dimensions, counts,
 * shapes, crop integers, timings, and ABIs only — never paths, bytes, or
 * exception text. Everything is released in `finally`: seam (covers all
 * three runners), holder, caller bytes (zeroized), bitmaps (recycled),
 * and temp/cache dirs (deleted).
 */
@RunWith(AndroidJUnit4::class)
class RealRunnerSeamProbeTest {

    @Test
    fun realRunnerSeamResolvesValidCropOnSyntheticUpright() {
        assertTrue("OpenCV native runtime failed to initialise", OpenCVLoader.initDebug())
        Log.i(TAG, "abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probeDir = File(context.cacheDir, "a4b5-seam").apply { mkdirs() }

        var bitmap: Bitmap? = null
        var jpeg: ByteArray? = null
        var uprightPixels: IntArray? = null
        val holder = RetainedOriginalHolder()
        var seam: AndroidCollageCropSource? = null
        var fieldRunner: FieldStageRunner? = null
        var nanoRunner: NanoDetRunner? = null
        var yuNetRunner: YuNetRunner? = null
        try {
            // Deterministic encoded fixture: upright ARGB gradient plus bars
            // (edges/contours for the pixel stage), JPEG-encoded in-test.
            val argb = syntheticArgb(FIXTURE_WIDTH, FIXTURE_HEIGHT)
            bitmap = Bitmap.createBitmap(FIXTURE_WIDTH, FIXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(argb, 0, FIXTURE_WIDTH, 0, 0, FIXTURE_WIDTH, FIXTURE_HEIGHT)
            jpeg = ByteArrayOutputStream().use { stream ->
                assertTrue("synthetic JPEG encode failed", bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                stream.toByteArray()
            }
            val bytes = jpeg
            val sha = sha256Hex(bytes)
            val contentId = CapsulePhotoIdentity.contentIdFor(sha)
            val retained = holder.retain(contentId, bytes, sha)
            assertTrue("holder must retain the verified fixture, got $retained", retained is RetainedOriginalHolder.RetainResult.Retained)
            Log.i(TAG, "source jpegBytes=${bytes.size} contentId=$contentId")

            fieldRunner = FieldStageRunner(OpenCvFieldStage())
            nanoRunner = NanoDetRunner(nanoAsset(), OpenCvNanoDetRuntime(), File(probeDir, "nano"))
            yuNetRunner = YuNetRunner(yuNetAsset(), OpenCvYuNetRuntime(), File(probeDir, "yunet"))
            val field = requireNotNull(fieldRunner)
            val nano = requireNotNull(nanoRunner)
            val yuNet = requireNotNull(yuNetRunner)
            seam = AndroidCollageCropSource(
                bytesSource = holder.asUprightBytesSource(),
                imageDecoder = UprightImageDecoder { FieldUprightDecoder.decodeUpright(it) },
                fieldRunner = field,
                nanoDetRunner = nano,
                yuNetRunner = yuNet,
            )

            // Per-stage diagnostics on the same upright bytes (also proves the
            // upright path resolves dimensions before the seam runs).
            val upright = FieldUprightDecoder.decodeUpright(bytes)
            uprightPixels = upright.argb
            Log.i(TAG, "upright dims=${upright.width}x${upright.height}")
            val fieldMaps: FieldStageMaps = when (val fields = runBlocking {
                field.analyze(
                    dev.hryshyn.remanence.ui.collage.analysis.FieldStageImage(
                        upright.width, upright.height, upright.argb,
                    ),
                )
            }) {
                is FieldStageResult.Maps -> {
                    Log.i(TAG, "field tips=${fields.maps.tipBoxes.size} ${fields.elapsedMs}ms")
                    fields.maps
                }
                is FieldStageResult.Unavailable -> throw AssertionError("field stage unavailable: ${fields.reason}")
                is FieldStageResult.Invalid -> throw AssertionError("field stage invalid")
            }
            when (val detected = runBlocking {
                nano.detect(
                    dev.hryshyn.remanence.ui.collage.analysis.NanoDetImage(
                        upright.width, upright.height, upright.argb,
                    ),
                )
            }) {
                is NanoDetResult.Detections -> Log.i(
                    TAG,
                    "nanodet objects=${detected.objects.size} heads=${detected.headShapes} ${detected.elapsedMs}ms",
                )
                is NanoDetResult.Unavailable -> fail("nanodet unavailable: ${detected.reason}")
                is NanoDetResult.Invalid -> fail("nanodet invalid")
            }
            when (val detected = runBlocking {
                yuNet.detect(
                    dev.hryshyn.remanence.ui.collage.analysis.YuNetImage(
                        upright.width, upright.height, upright.argb,
                    ),
                )
            }) {
                is YuNetResult.Faces -> Log.i(
                    TAG,
                    "yunet faces=${detected.faces.size} passes=${detected.passShapes} ${detected.elapsedMs}ms",
                )
                is YuNetResult.Unavailable -> fail("yunet unavailable: ${detected.reason}")
                is YuNetResult.Invalid -> fail("yunet invalid")
            }
            assertTrue("field maps must be finite", fieldMaps.saliency.all { it.isFinite() })

            val start = System.nanoTime()
            val crops = runBlocking {
                requireNotNull(seam).cropsFor(listOf(CollageCropRequest(contentId, 340, 248)))
            }
            Log.i(TAG, "seam totalMs=${(System.nanoTime() - start) / 1_000_000}")
            assertNotNull("seam must resolve a crop for the synthetic upright", crops)
            assertTrue("seam must key the requested content", crops!!.containsKey(contentId))
            val window = crops.getValue(contentId)
            assertTrue("x non-negative", window.x >= 0)
            assertTrue("y non-negative", window.y >= 0)
            assertTrue("positive size", window.width > 0 && window.height > 0)
            assertTrue("x + width in range", window.x.toLong() + window.width <= 10_000L)
            assertTrue("y + height in range", window.y.toLong() + window.height <= 10_000L)
            Log.i(TAG, "crop x=${window.x} y=${window.y} w=${window.width} h=${window.height}")
        } finally {
            runCatching { seam?.close() }
            runCatching { fieldRunner?.close() }
            runCatching { nanoRunner?.close() }
            runCatching { yuNetRunner?.close() }
            runCatching { holder.close() }
            jpeg?.fill(0)
            uprightPixels?.fill(0)
            runCatching { bitmap?.recycle() }
            probeDir.deleteRecursively()
        }
    }

    /**
     * Deterministic negative: an absent content ID must fail closed to
     * `null`. No runner invocation or success is claimed — the bytes source
     * answers absent before any runner runs. Runners are still constructed
     * (real types, never driven) and released to prove the seam's fail-closed
     * path leaves nothing owned.
     */
    @Test
    fun absentContentIdFailsClosedNullWithoutRunnerSuccess() {
        assertTrue("OpenCV native runtime failed to initialise", OpenCVLoader.initDebug())
        Log.i(TAG, "abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probeDir = File(context.cacheDir, "a4b5-seam-absent").apply { mkdirs() }

        val holder = RetainedOriginalHolder()
        var seam: AndroidCollageCropSource? = null
        var fieldRunner: FieldStageRunner? = null
        var nanoRunner: NanoDetRunner? = null
        var yuNetRunner: YuNetRunner? = null
        try {
            fieldRunner = FieldStageRunner(OpenCvFieldStage())
            nanoRunner = NanoDetRunner(nanoAsset(), OpenCvNanoDetRuntime(), File(probeDir, "nano"))
            yuNetRunner = YuNetRunner(yuNetAsset(), OpenCvYuNetRuntime(), File(probeDir, "yunet"))
            seam = AndroidCollageCropSource(
                bytesSource = holder.asUprightBytesSource(),
                imageDecoder = UprightImageDecoder { FieldUprightDecoder.decodeUpright(it) },
                fieldRunner = requireNotNull(fieldRunner),
                nanoDetRunner = requireNotNull(nanoRunner),
                yuNetRunner = requireNotNull(yuNetRunner),
            )
            val crops = runBlocking {
                requireNotNull(seam).cropsFor(listOf(CollageCropRequest("c" + "0".repeat(64), 340, 248)))
            }
            assertNull("absent content must fail closed to null", crops)
            Log.i(TAG, "absent contentId fail-closed null; no runner claim")
        } finally {
            runCatching { seam?.close() }
            runCatching { fieldRunner?.close() }
            runCatching { nanoRunner?.close() }
            runCatching { yuNetRunner?.close() }
            runCatching { holder.close() }
            probeDir.deleteRecursively()
        }
    }

    private fun nanoAsset(): NanoDetModelAssetSource {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return object : NanoDetModelAssetSource {
            override val sha256: String = NANODET_SHA256
            override val byteSize: Long = NANODET_BYTES
            override val maxBytes: Long = MAX_ASSET_BYTES
            override fun open(): InputStream = context.assets.open("collage/nanodet.onnx")
        }
    }

    private fun yuNetAsset(): ModelAssetSource {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ModelAssetSource {
            override val sha256: String = YUNET_SHA256
            override val byteSize: Long = YUNET_BYTES
            override val maxBytes: Long = MAX_ASSET_BYTES
            override fun open(): InputStream = context.assets.open("collage/yunet.onnx")
        }
    }

    private fun syntheticArgb(width: Int, height: Int): IntArray {
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
        return argb
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CollageSeamProbe"
        const val FIXTURE_WIDTH = 640
        const val FIXTURE_HEIGHT = 480
        const val MAX_ASSET_BYTES = 8L * 1024 * 1024
        const val NANODET_BYTES = 3_800_954L
        const val NANODET_SHA256 =
            "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
        const val YUNET_BYTES = 232_589L
        const val YUNET_SHA256 =
            "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
    }
}
