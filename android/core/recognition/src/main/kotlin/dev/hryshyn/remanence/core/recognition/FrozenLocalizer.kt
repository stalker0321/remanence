package dev.hryshyn.remanence.core.recognition

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.floor
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Frozen S2 postcard localizer: ships the exact seed-0 ONNX mask model behind
 * a bounded fail-closed wrapper. Not wired into scanning; S3 owns features,
 * retrieval and gates.
 *
 * Line-for-line behavior port of the frozen reference
 * (`support_sources/benchmark.py::predict_mask`, `normalize_chw`,
 * `rasterize.py::letterbox`, `mask_geometry_src/adapter.py::mask_to_roi`
 * foreground bbox + `_centres_inside`, `PRODUCTION_FREEZE_SPEC.md` §§2–3):
 *
 * letterbox (INTER_AREA onto a zero 320×320 canvas, centred paste)
 * → ImageNet normalize to NCHW float32 → session logits → sigmoid →
 * unletterbox crop → bilinear resize to source size → strict `> 0.5` mask →
 * foreground bounding-box ROI (`roi_foreground`) → `centres` point filter.
 *
 * The input contract is BGR bytes exactly as OpenCV delivers them, matching
 * the frozen `decode` path: the frozen code normalizes the BGR canvas with
 * the RGB-ordered ImageNet means applied per channel index, and this port
 * applies the identical per-index arithmetic without asserting color meaning.
 */
object FrozenLocalizer {

    /** Frozen `INPUT_SIZE`: the model always sees a 320×320 canvas. */
    const val LOCALIZER_SIZE = 320

    /** Frozen `THRESHOLD`: strict `probability > 0.5` becomes mask foreground. */
    const val THRESHOLD = 0.5f

    /** Frozen ImageNet means, applied per channel index (see class KDoc). */
    val NORMALIZE_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)

    /** Frozen ImageNet stds, applied per channel index. */
    val NORMALIZE_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Bundled model location inside the APK assets (app module). */
    const val MODEL_ASSET_PATH = "frozen-matcher/v1/model_fold0_seed0.onnx"

    /** Frozen `models/model_fold0_seed0.onnx` digest (asserted by test). */
    const val MODEL_SHA256 = "cdf50b1486abd8ba49da31521ed450cda2ec14beedd13b32fb35361fd421f82d"

    /** Frozen model size in bytes (asserted by test). */
    const val MODEL_SIZE_BYTES = 12867153

    /** Letterbox plan: frozen `rasterize.letterbox` outputs. */
    data class LetterboxPlan(
        val scale: Double,
        val newWidth: Int,
        val newHeight: Int,
        val padX: Int,
        val padY: Int,
        val canvasSize: Int = LOCALIZER_SIZE,
    )

    /** Axis-aligned ROI window, exclusive end (adapter slice semantic). */
    data class ImageWindow(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** One 2D point in source-image pixels (S3 keypoint centres feed this). */
    data class PointF(val x: Float, val y: Float)

    /** Raw model logits with their spatial shape. */
    data class MaskLogits(val data: FloatArray, val height: Int, val width: Int)

    /** Bounded wrapper outcome: a proposal carries no pixels beyond mask/ROI. */
    sealed interface LocalizerResult {
        data class Proposal(val window: ImageWindow, val mask: ByteArray, val maskWidth: Int, val maskHeight: Int) : LocalizerResult
        data class Unknown(val reason: String) : LocalizerResult
    }

    /** Fail-closed reasons; none carries image bytes, URLs or embeddings. */
    object Reasons {
        const val BAD_DIMS = "bad_dims"
        const val BAD_BYTES = "bad_bytes"
        const val RUNTIME = "runtime"
        const val BAD_LOGITS = "bad_logits"
        const val EMPTY_MASK = "empty_mask"
        const val CLOSED = "closed"
    }

    /**
     * Injectable inference seam. Production uses [OrtLocalizerRuntime]; tests
     * use a fake. `inference` receives the 320×320 NCHW tensor.
     */
    interface LocalizerRuntime : AutoCloseable {
        fun infer(chw320: FloatArray): MaskLogits
    }

    /**
     * Frozen `rasterize.letterbox`: `scale = min(size/w, size/h)`,
     * `new = max(1, round(dim*scale))` (Python round), centred paste with
     * integer-division pads. Pure plan math; the resize itself runs in
     * [FrozenLocalizerSession] on OpenCV (INTER_AREA, frozen match).
     */
    fun planLetterbox(imageWidth: Int, imageHeight: Int, canvasSize: Int = LOCALIZER_SIZE): LetterboxPlan {
        require(imageWidth > 0) { "imageWidth must be positive" }
        require(imageHeight > 0) { "imageHeight must be positive" }
        require(canvasSize > 0) { "canvasSize must be positive" }
        val scale = minOf(canvasSize.toDouble() / imageWidth, canvasSize.toDouble() / imageHeight)
        val newWidth = maxOf(1, pyRound(imageWidth * scale))
        val newHeight = maxOf(1, pyRound(imageHeight * scale))
        return LetterboxPlan(
            scale = scale,
            newWidth = newWidth,
            newHeight = newHeight,
            padX = (canvasSize - newWidth) / 2,
            padY = (canvasSize - newHeight) / 2,
            canvasSize = canvasSize,
        )
    }

    /**
     * Frozen `normalize_chw`: `(byte/255 - mean[i]) / std[i]` per channel
     * index into NCHW order (batch 1, channels 3, canvas rows, canvas cols).
     */
    fun normalizeBgrToChw(bgr: ByteArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0) { "bad dims" }
        require(bgr.size == 3 * width * height) { "bad bytes" }
        val out = FloatArray(3 * width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val src = (y * width + x) * 3
                for (c in 0..2) {
                    val unit = (bgr[src + c].toInt() and 0xFF) / 255.0f
                    out[(c * height + y) * width + x] =
                        (unit - NORMALIZE_MEAN[c]) / NORMALIZE_STD[c]
                }
            }
        }
        return out
    }

    /**
     * Frozen mask postprocess minus the resize: sigmoid over every logit,
     * then the unletterbox window `prob[padY:padY+newH, padX:padX+newW]`
     * where `new = round(dim*scale)` is recomputed from the plan scale.
     * Returns row-major probabilities at the letterboxed size.
     */
    fun unletterboxedProbabilities(logits: MaskLogits, plan: LetterboxPlan, imageWidth: Int, imageHeight: Int): FloatArray {
        require(logits.height > 0 && logits.width > 0) { Reasons.BAD_LOGITS }
        require(logits.data.size == logits.height * logits.width) { Reasons.BAD_LOGITS }
        require(logits.data.all { it.isFinite() }) { Reasons.BAD_LOGITS }
        val newW = pyRound(imageWidth * plan.scale)
        val newH = pyRound(imageHeight * plan.scale)
        require(plan.padX + newW <= logits.width && plan.padY + newH <= logits.height) { Reasons.BAD_LOGITS }
        val sig = FloatArray(logits.data.size) { i -> sigmoid(logits.data[i]) }
        val out = FloatArray(newW * newH)
        for (y in 0 until newH) {
            System.arraycopy(sig, (plan.padY + y) * logits.width + plan.padX, out, y * newW, newW)
        }
        return out
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    /** Frozen strict threshold: `mask = (probability > threshold)`. */
    fun thresholdMask(probabilities: FloatArray, threshold: Float = THRESHOLD): ByteArray =
        ByteArray(probabilities.size) { i -> if (probabilities[i] > threshold) 1 else 0 }

    /**
     * Foreground bbox (`rasterize.mask_to_bbox` + adapter slice semantic):
     * inclusive min/max of mask>0 converted to the exclusive-end window;
     * null when empty (`roi_foreground` yields no hypothesis).
     */
    fun foregroundWindow(mask: ByteArray, width: Int, height: Int): ImageWindow? {
        require(mask.size == width * height) { Reasons.BAD_DIMS }
        var xmin = width
        var ymin = height
        var xmax = -1
        var ymax = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x] > 0) {
                    if (x < xmin) xmin = x
                    if (y < ymin) ymin = y
                    if (x > xmax) xmax = x
                    if (y > ymax) ymax = y
                }
            }
        }
        if (xmax < 0) return null
        return ImageWindow(xmin, ymin, xmax + 1, ymax + 1)
    }

    /**
     * Frozen `_centres_inside`: keep points whose Python-rounded centre lands
     * on mask foreground. Non-finite points are dropped (fail-closed); the
     * bounds check precedes the mask read exactly as frozen.
     */
    fun centresInside(points: List<PointF>, mask: ByteArray, width: Int, height: Int): List<PointF> {
        require(mask.size == width * height) { Reasons.BAD_DIMS }
        return points.filter { p ->
            if (!p.x.isFinite() || !p.y.isFinite()) return@filter false
            val x = pyRound(p.x.toDouble())
            val y = pyRound(p.y.toDouble())
            x in 0 until width && y in 0 until height && mask[y * width + x] > 0
        }
    }

    /**
     * Python `round()` parity (banker's rounding, full range): halves round
     * to even, unlike Kotlin `round()` which rounds halves up.
     */
    internal fun pyRound(value: Double): Int {
        require(value.isFinite()) { "non-finite rounding input" }
        val sign = if (value < 0) -1 else 1
        val abs = kotlin.math.abs(value)
        val floored = floor(abs)
        val fraction = abs - floored
        val rounded = when {
            fraction < 0.5 -> floored
            fraction > 0.5 -> floored + 1.0
            floored.toLong() % 2L == 0L -> floored
            else -> floored + 1.0
        }
        return (sign * rounded).toInt()
    }
}

/**
 * Production ORT runtime over one session. Owns the environment and session;
 * [close] releases both and is idempotent.
 */
class OrtLocalizerRuntime private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
) : FrozenLocalizer.LocalizerRuntime {

    override fun infer(chw320: FloatArray): FrozenLocalizer.MaskLogits {
        require(chw320.size == 3 * FrozenLocalizer.LOCALIZER_SIZE * FrozenLocalizer.LOCALIZER_SIZE) {
            FrozenLocalizer.Reasons.BAD_BYTES
        }
        val buffer = FloatBuffer.wrap(chw320)
        ai.onnxruntime.OnnxTensor.createTensor(
            env, buffer,
            longArrayOf(1, 3, FrozenLocalizer.LOCALIZER_SIZE.toLong(), FrozenLocalizer.LOCALIZER_SIZE.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                val outer = outputs[0].value as? Array<*>
                    ?: throw IllegalStateException(FrozenLocalizer.Reasons.BAD_LOGITS)
                val middle = outer.getOrNull(0) as? Array<*>
                    ?: throw IllegalStateException(FrozenLocalizer.Reasons.BAD_LOGITS)
                @Suppress("UNCHECKED_CAST")
                val rows = middle as? Array<FloatArray>
                    ?: throw IllegalStateException(FrozenLocalizer.Reasons.BAD_LOGITS)
                require(rows.isNotEmpty() && rows[0].isNotEmpty()) {
                    FrozenLocalizer.Reasons.BAD_LOGITS
                }
                val flat = FloatArray(rows.size * rows[0].size)
                rows.forEachIndexed { y, row ->
                    require(row.size == rows[0].size) { FrozenLocalizer.Reasons.BAD_LOGITS }
                    System.arraycopy(row, 0, flat, y * rows[0].size, row.size)
                }
                return FrozenLocalizer.MaskLogits(flat, rows.size, rows[0].size)
            }
        }
    }

    override fun close() {
        try {
            session.close()
        } finally {
            env.close()
        }
    }

    companion object {
        /** Opens the frozen model from raw asset bytes; throws on corrupt input. */
        fun open(modelBytes: ByteArray): OrtLocalizerRuntime {
            require(modelBytes.isNotEmpty()) { FrozenLocalizer.Reasons.RUNTIME }
            val env = OrtEnvironment.getEnvironment()
            try {
                val options = OrtSession.SessionOptions()
                val session = env.createSession(modelBytes, options)
                val inputName = session.inputNames.first()
                return OrtLocalizerRuntime(env, session, inputName)
            } catch (e: Throwable) {
                try {
                    env.close()
                } catch (_: Throwable) {
                }
                throw e
            }
        }
    }
}

/**
 * Bounded S2 session: letterbox (INTER_AREA) → normalize → infer → sigmoid →
 * unletterbox → bilinear resize to source size → strict threshold →
 * `roi_foreground` proposal. Every invalid/runtime branch returns
 * [LocalizerResult.Unknown] carrying only a reason constant; Java/Kotlin
 * cancellation is never swallowed. Owns nothing except the injected runtime;
 * call [close] to release it (idempotent; use after close is [Unknown]).
 */
class FrozenLocalizerSession(
    private val runtime: FrozenLocalizer.LocalizerRuntime,
    private val isCancelled: () -> Boolean = { false },
) : AutoCloseable {

    @Volatile
    private var closed = false

    fun localize(frameBgr: ByteArray, width: Int, height: Int): FrozenLocalizer.LocalizerResult {
        try {
            if (closed) return FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.CLOSED)
            if (isCancelled()) throw CancellationException("cancelled before inference")
            if (width <= 0 || height <= 0) {
                return FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.BAD_DIMS)
            }
            if (frameBgr.size != 3 * width * height) {
                return FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.BAD_BYTES)
            }
            val plan = FrozenLocalizer.planLetterbox(width, height)
            val owner = NativeMatOwner()
            try {
                val src = owner.allocate {
                    Mat(height, width, CvType.CV_8UC3).apply { put(0, 0, frameBgr) }
                }
                val small = owner.allocate { Mat() }
                Imgproc.resize(
                    src, small,
                    Size(plan.newWidth.toDouble(), plan.newHeight.toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA,
                )
                val canvas = owner.allocate {
                    Mat.zeros(
                        FrozenLocalizer.LOCALIZER_SIZE,
                        FrozenLocalizer.LOCALIZER_SIZE,
                        CvType.CV_8UC3,
                    )
                }
                small.copyTo(canvas.submat(plan.padY, plan.padY + plan.newHeight, plan.padX, plan.padX + plan.newWidth))
                val canvasBytes = ByteArray(3 * FrozenLocalizer.LOCALIZER_SIZE * FrozenLocalizer.LOCALIZER_SIZE)
                canvas.get(0, 0, canvasBytes)
                val chw = FrozenLocalizer.normalizeBgrToChw(
                    canvasBytes, FrozenLocalizer.LOCALIZER_SIZE, FrozenLocalizer.LOCALIZER_SIZE,
                )
                if (isCancelled()) throw CancellationException("cancelled before inference")
                val logits = runtime.infer(chw)
                val probLetterboxed = FrozenLocalizer.unletterboxedProbabilities(logits, plan, width, height)
                val probFull = owner.allocate { Mat() }
                val probMat = owner.allocate {
                    Mat(plan.newHeight, plan.newWidth, CvType.CV_32F).apply {
                        put(0, 0, probLetterboxed)
                    }
                }
                Imgproc.resize(
                    probMat, probFull,
                    Size(width.toDouble(), height.toDouble()),
                    0.0, 0.0, Imgproc.INTER_LINEAR,
                )
                val probResized = FloatArray(width * height)
                probFull.get(0, 0, probResized)
                val mask = FrozenLocalizer.thresholdMask(probResized)
                val window = FrozenLocalizer.foregroundWindow(mask, width, height)
                    ?: return FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.EMPTY_MASK)
                return FrozenLocalizer.LocalizerResult.Proposal(window, mask, width, height)
            } finally {
                owner.releaseAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val reason = (t as? IllegalArgumentException)?.message
                ?.takeIf { it == FrozenLocalizer.Reasons.BAD_LOGITS }
                ?: FrozenLocalizer.Reasons.RUNTIME
            return FrozenLocalizer.LocalizerResult.Unknown(reason)
        }
    }

    override fun close() {
        closed = true
        runtime.close()
    }
}
