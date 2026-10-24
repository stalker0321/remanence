package dev.hryshyn.remanence.core.recognition

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Opt-in benchmark of [FrozenMatcherS3] over the dataset manifest used by
 * [DatasetMatchBenchmarkTest]; writes one CSV row per query for an
 * image-by-image comparison with the Python reference.
 *
 * REMANENCE_BENCH_DIR also holds s3_centers.f32 / s3_idf.f32 (frozen
 * vocabulary). REMANENCE_BENCH_CANDIDATES=N: true card + N-1 others (0 = all).
 */
class FrozenMatcherS3BenchmarkTest {
    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    @Test
    fun benchmarkS3() {
        val dir = System.getenv("REMANENCE_BENCH_DIR")
        assumeTrue("set REMANENCE_BENCH_DIR", dir != null)
        val root = File(dir!!)
        val candidateCount = System.getenv("REMANENCE_BENCH_CANDIDATES")?.toInt() ?: 0
        val threads = System.getenv("REMANENCE_BENCH_THREADS")?.toInt() ?: 3
        val tag = System.getenv("REMANENCE_BENCH_TAG") ?: "s3"
        val limit = System.getenv("REMANENCE_BENCH_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val senderMode = System.getenv("REMANENCE_S3_SENDER") ?: "grabcut"
        val manifest = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
        val gallery = manifest["gallery"]!!.jsonArray.map { it.jsonObject }
        val queries = manifest["queries"]!!.jsonArray.map { it.jsonObject }.take(limit)
        val vocabulary = FrozenMatcherS3.Vocabulary(floats(File(root, "s3_centers.f32")), floats(File(root, "s3_idf.f32")))
        val model = File(System.getenv("REMANENCE_S3_MODEL") ?: "../../app/src/main/assets/${FrozenLocalizer.MODEL_ASSET_PATH}").readBytes()
        val pool = Executors.newFixedThreadPool(threads)
        val sessions = ThreadLocal.withInitial { FrozenLocalizerSession(OrtLocalizerRuntime.open(model)) }

        val started = System.nanoTime()
        val galleryRows = StringBuilder("card,features,crop_w,crop_h\n")
        val references = gallery.map { g ->
            pool.submit<FrozenMatcherS3.Reference> {
                val image = decode(File(root, "img/${g.str("id")}.jpg"))
                val features = when (senderMode) {
                    // Frozen reference: GrabCut auto-crop, else the whole image.
                    "grabcut" -> FrozenMatcherS3.autoCrop(image).let { crop -> FrozenMatcherS3.gridFeatures(crop).also { crop.release() } }
                    // Localizer mask over the whole sender photo.
                    "onnx_full" -> onnxFeatures(image, sessions.get())
                    // Sender framed like the recipient: 2:3 frame, then the mask.
                    "onnx_frame" -> frameOf(image).let { frame -> onnxFeatures(frame, sessions.get()).also { frame.release() } }
                    else -> error("REMANENCE_S3_SENDER=$senderMode")
                }
                synchronized(galleryRows) { galleryRows.append("${g.str("card_id")},${features.count},${features.width},${features.height}\n") }
                image.release()
                FrozenMatcherS3.reference(g.str("card_id")!!, features, vocabulary)
            }
        }.map { it.get() }
        File(root, "s3-gallery-$tag.csv").writeText(galleryRows.toString())
        val byId = references.associateBy { it.id }
        val cards = references.map { it.id }.sorted()

        val rows = queries.withIndex().map { (i, q) ->
            pool.submit<String> {
                val image = decode(File(root, "img/${q.str("id")}.jpg"))
                val frame = frameOf(image)
                image.release()
                val (features, quality) = FrozenMatcherS3.recipientFeatures(frame, maskOf(frame, sessions.get()))
                frame.release()
                val expected = q.str("expected")
                val picked = if (candidateCount <= 0) cards else {
                    val random = Random(i * 7919L + 17)
                    val others = cards.filter { it != expected }.shuffled(random).take(if (expected != null) candidateCount - 1 else candidateCount)
                    listOfNotNull(expected) + others
                }
                val d = FrozenMatcherS3.match(features, picked.map { byId.getValue(it) }, vocabulary, quality.components)
                val b = d.best
                listOf(
                    q.str("id"), expected.orEmpty(), if (d.accepted) "matched" else "unknown", d.id.orEmpty(),
                    if (d.baselineAccepted) "matched" else "unknown", if (d.baselineAccepted) b?.id.orEmpty() else "",
                    b?.id.orEmpty(), "%.4f".format(b?.finalScore ?: 0.0), "${b?.inliers ?: 0}", "%.4f".format(b?.coverage ?: 0.0),
                    "%.4f".format(b?.inlierRatio ?: 0.0), "%.4f".format(d.separation), "${quality.components}", "${features.count}",
                    d.gateReject?.code.orEmpty(), "${b?.coversFrameCentre ?: false}", "%.4f".format(b?.centreDistance ?: 9.0),
                ).joinToString(",")
            }
        }.map { it.get() }
        pool.shutdown()
        File(root, "s3-$tag.csv").writeText(
            "id,expected,status,card_id,baseline_status,baseline_card_id,best,score,inliers,coverage,inlier_ratio,separation,components,kept,gate,centred,centre_distance\n" +
                rows.joinToString("\n") + "\n",
        )
        File(root, "s3-$tag.txt").writeText("candidates=$candidateCount seconds=%.0f\n".format((System.nanoTime() - started) / 1e9))
    }

    private fun frameOf(image: Mat): Mat {
        val bounds = FrameGeometry.bounds(image.cols(), image.rows())
        return Mat(image, Rect(bounds.x0, bounds.y0, bounds.x1 - bounds.x0, bounds.y1 - bounds.y0)).clone()
    }

    private fun maskOf(image: Mat, session: FrozenLocalizerSession): ByteArray? {
        val bgr = ByteArray(3 * image.cols() * image.rows())
        image.get(0, 0, bgr)
        return when (val result = session.localize(bgr, image.cols(), image.rows())) {
            is FrozenLocalizer.LocalizerResult.Proposal -> result.mask
            is FrozenLocalizer.LocalizerResult.Unknown -> null
        }
    }

    private fun onnxFeatures(image: Mat, session: FrozenLocalizerSession): FrozenMatcherS3.Features {
        val f = FrozenMatcherS3.recipientFeatures(image, maskOf(image, session)).first
        // A reference is described in its own crop coordinates.
        return FrozenMatcherS3.Features(f.xy, f.descriptors, f.width, f.height)
    }

    /** `vision.decode(max_side=960)`: upright BGR, longest side at most 960. */
    private fun decode(file: File): Mat {
        val full = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_COLOR)
        val scale = FrozenMatcherS3.DECODE_MAX_SIDE.toDouble() / max(full.cols(), full.rows())
        if (scale >= 1.0) return full
        val out = Mat()
        Imgproc.resize(full, out, Size((full.cols() * scale).roundToInt().toDouble(), (full.rows() * scale).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        full.release()
        return out
    }

    private fun floats(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
