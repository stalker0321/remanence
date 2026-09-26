package dev.hryshyn.remanence.probe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetImage
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetModelAssetSource
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetResult
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRunner
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvNanoDetRuntime
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN

/**
 * V5-A1 capability probe — DIAGNOSTIC ONLY, NOT DEVICE ACCEPTANCE EVIDENCE.
 *
 * Compiling and even running this on one emulator does NOT prove the V5 sender
 * CV gate. A PASS requires running on a real **arm64-v8a** device AND a second
 * ABI (armeabi-v7a device or x86_64 emulator), with the recorded timings/shapes
 * reviewed. This class only proves that the OpenCV APIs and the shipped natives
 * load and execute the frozen models without throwing.
 *
 * It records timings and tensor/box shapes only: never file paths, never image
 * bytes, never private content. It loads each model by a bounded copy from the
 * packaged assets into cacheDir, then releases every Mat and deletes every temp
 * file in `finally`. OpenCV's Java `Net` and `FaceDetectorYN` expose no public
 * release API (native lifetime is finalizer/GC owned), so only their references
 * are dropped; Mats are released explicitly.
 *
 * No production detector/crop/provider code is implemented here.
 */
@RunWith(AndroidJUnit4::class)
class CollageCvCapabilityProbeTest {

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        assertTrue("OpenCV native runtime failed to initialise", OpenCVLoader.initDebug())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = File(context.cacheDir, "collage_cv_gate").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    /** Bounded asset → cache file copy; throws on any failure (explicit). */
    private fun materialize(asset: String): File {
        val target = File(cacheDir, asset)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.assets.open("collage/$asset").use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= MAX_ASSET_BYTES) { "asset exceeds $MAX_ASSET_BYTES bytes" }
                    output.write(buffer, 0, read)
                }
            }
        }
        assertTrue("materialised asset is empty", target.length() > 0L)
        return target
    }

    @Test
    fun nanoDetLoadsAndForwards416Diagnostics() {
        val model = materialize("nanodet.onnx")
        val mats = ArrayList<Mat>()
        var net: Net? = null
        try {
            net = Dnn.readNet(model.absolutePath)
            assertFalse("NanoDet Net is empty", net.empty())

            val input = Mat(416, 416, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).also { mats += it }
            Imgproc.rectangle(
                input, Point(120.0, 80.0), Point(300.0, 340.0), Scalar(200.0, 180.0, 160.0), -1,
            )
            val blob = Dnn.blobFromImage(input, 1.0 / 255.0, Size(416.0, 416.0), Scalar(0.0), true, false)
                .also { mats += it }
            net.setInput(blob)

            val outputs = ArrayList<Mat>()
            val start = System.nanoTime()
            net.forward(outputs, net.getUnconnectedOutLayersNames())
            val millis = (System.nanoTime() - start) / 1_000_000
            outputs.forEach { mats += it }

            assertTrue("NanoDet forward produced no outputs", outputs.isNotEmpty())
            outputs.forEachIndexed { index, mat ->
                Log.i(
                    TAG,
                    "nanodet out[$index] dims=${mat.dims()} rows=${mat.rows()} cols=${mat.cols()} " +
                        "channels=${mat.channels()} type=${mat.type()}",
                )
            }
            Log.i(TAG, "nanodet readNet+forward ${millis}ms outputs=${outputs.size}")
        } finally {
            mats.asReversed().forEach { runCatching { it.release() } }
            net = null
        }
    }

    @Test
    fun yuNetCreatesAndDetectsOnDeterministicImage() {
        val model = materialize("yunet.onnx")
        val mats = ArrayList<Mat>()
        var detector: FaceDetectorYN? = null
        try {
            detector = FaceDetectorYN.create(
                model.absolutePath, "", Size(640.0, 640.0), SCORE_THRESHOLD, NMS_THRESHOLD, TOP_K,
            )
            detector.setInputSize(Size(640.0, 640.0))

            // Fixed deterministic synthetic "face" (no user content, no bytes recorded).
            val image = Mat(640, 640, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0)).also { mats += it }
            Imgproc.ellipse(
                image, Point(320.0, 320.0), Size(90.0, 120.0), 0.0, 0.0, 360.0,
                Scalar(200.0, 180.0, 160.0), -1,
            )
            Imgproc.circle(image, Point(290.0, 300.0), 8, Scalar(30.0, 30.0, 30.0), -1)
            Imgproc.circle(image, Point(350.0, 300.0), 8, Scalar(30.0, 30.0, 30.0), -1)
            Imgproc.line(image, Point(300.0, 370.0), Point(340.0, 370.0), Scalar(30.0, 30.0, 30.0), 4)

            val faces = Mat().also { mats += it }
            val start = System.nanoTime()
            val count = detector.detect(image, faces)
            val millis = (System.nanoTime() - start) / 1_000_000

            assertTrue("YuNet detect count is negative", count >= 0)
            Log.i(
                TAG,
                "yunet detect count=$count rows=${faces.rows()} cols=${faces.cols()} " +
                    "type=${faces.type()} ${millis}ms",
            )
        } finally {
            mats.asReversed().forEach { runCatching { it.release() } }
            detector = null
        }
    }

    /**
     * V5-A3B: exercise the real [NanoDetRunner] wrapper end to end. Still
     * diagnostic only; reports timings/head shapes/detection count, never
     * paths or bytes. Any unavailable/invalid result is an explicit failure.
     */
    @Test
    fun nanoDetRunnerDecodesRealModelDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = object : NanoDetModelAssetSource {
            override val sha256: String = NANODET_SHA256
            override val byteSize: Long = NANODET_BYTES
            override val maxBytes: Long = MAX_ASSET_BYTES
            override fun open(): InputStream = context.assets.open("collage/nanodet.onnx")
        }
        val runner = NanoDetRunner(
            assetSource = source,
            runtime = OpenCvNanoDetRuntime(),
            cacheDir = File(context.cacheDir, "collage_cv_gate"),
        )
        try {
            val image = NanoDetImage(416, 416, deterministicArgb(416, 416))
            when (val result = runBlocking { runner.detect(image) }) {
                is NanoDetResult.Detections -> Log.i(
                    TAG,
                    "runner heads=${result.headShapes} detections=${result.objects.size} " +
                        "${result.elapsedMs}ms",
                )
                is NanoDetResult.Unavailable -> fail("runner unavailable: ${result.reason}")
                is NanoDetResult.Invalid -> fail("runner invalid: ${result.reason}")
            }
        } finally {
            runner.close()
        }
    }

    private fun deterministicArgb(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height) { 0xFF202020.toInt() }
        for (y in height / 4 until height / 2) {
            for (x in width / 4 until width / 2) {
                pixels[y * width + x] = 0xFFC08050.toInt()
            }
        }
        return pixels
    }

    private companion object {
        const val TAG = "CollageCvProbe"
        const val MAX_ASSET_BYTES = 8L * 1024 * 1024
        const val SCORE_THRESHOLD = 0.65f
        const val NMS_THRESHOLD = 0.3f
        const val TOP_K = 5000
        const val NANODET_BYTES = 3_800_954L
        const val NANODET_SHA256 =
            "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
    }
}
