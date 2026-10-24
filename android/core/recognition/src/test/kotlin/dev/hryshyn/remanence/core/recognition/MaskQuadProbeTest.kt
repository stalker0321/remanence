package dev.hryshyn.remanence.core.recognition

import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Opt-in probe: card outline from the localizer mask for every image in REMANENCE_QUAD_IN. */
class MaskQuadProbeTest {
    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    @Test
    fun probeQuads() {
        val input = System.getenv("REMANENCE_QUAD_IN")
        assumeTrue("set REMANENCE_QUAD_IN", input != null)
        val limit = System.getenv("REMANENCE_QUAD_LIMIT")?.toInt() ?: 60
        val model = File("../../app/src/main/assets/${FrozenLocalizer.MODEL_ASSET_PATH}").readBytes()
        val session = FrozenLocalizerSession(OrtLocalizerRuntime.open(model))
        val rows = StringBuilder("id,w,h,x0,y0,x1,y1,x2,y2,x3,y3\n")
        File(input!!).listFiles { f -> f.extension == "jpg" }!!.sorted().filterIndexed { i, _ -> i % 23 == 0 }.take(limit).forEach { file ->
            val full = Imgcodecs.imread(file.path)
            val scale = 960.0 / maxOf(full.cols(), full.rows())
            val image = Mat()
            Imgproc.resize(full, image, Size(Math.round(full.cols() * scale).toDouble(), Math.round(full.rows() * scale).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val b = FrameGeometry.bounds(image.cols(), image.rows())
            val frame = Mat(image, Rect(b.x0, b.y0, b.x1 - b.x0, b.y1 - b.y0)).clone()
            val bgr = ByteArray(3 * frame.cols() * frame.rows()); frame.get(0, 0, bgr)
            val mask = (session.localize(bgr, frame.cols(), frame.rows()) as? FrozenLocalizer.LocalizerResult.Proposal)?.mask
            val quad = mask?.let { MaskQuad.fromMask(it, frame.cols(), frame.rows()) }?.let { MaskQuad.inset(it, 0.015) }
            if (quad != null) {
                val inv = 1.0 / scale
                rows.append(file.nameWithoutExtension).append(",${full.cols()},${full.rows()},")
                    .append(quad.joinToString(",") { "%.1f,%.1f".format((it.x + b.x0) * inv, (it.y + b.y0) * inv) }).append("\n")
            }
            full.release(); image.release(); frame.release()
        }
        File(System.getenv("REMANENCE_QUAD_OUT") ?: "/tmp/quads.csv").writeText(rows.toString())
    }
}
